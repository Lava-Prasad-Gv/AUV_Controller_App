package com.example.mathsya_v_01;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Robust WebSocket manager using OkHttp.
 *
 * Features:
 * - Safe connection state handling (connected only set in onOpen).
 * - Exponential backoff reconnect (stops when manually closed).
 * - Message queueing while disconnected (bounded by queue limit).
 * - Callbacks executed on main thread.
 * - Uses OkHttp pingInterval (no manual "Ping" text messages).
 */
public class SocketManager {
    private static final String TAG = "SocketManager";

    private final OkHttpClient client;
    private WebSocket webSocket;
    private final WebSocketCallback callback;
    private final Handler mainHandler;
    private final ConcurrentLinkedQueue<String> outgoingQueue = new ConcurrentLinkedQueue<>();

    // Connection bookkeeping
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean manualClose = new AtomicBoolean(false);
    private String url;

    // Reconnect/backoff
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private long reconnectDelayMs = 1000; // start 1s
    private final long RECONNECT_MAX_MS = 30_000; // max 30s

    // Queue limit to avoid unbounded memory growth
    private final int QUEUE_MAX = 200;

    public interface WebSocketCallback {
        void onMessageReceived(String message);
        void onStatusChanged(String status);
    }

    public SocketManager(WebSocketCallback callback) {
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());

        // OkHttpClient with pingInterval to keep connection alive; tune as needed
        this.client = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .readTimeout(0, TimeUnit.MILLISECONDS) // for WebSocket keep reading
                .build();
    }

    /**
     * Start/attempt connection to provided WS URL.
     */
    public void start(@NonNull String url) {
        this.url = url;
        manualClose.set(false);
        reconnectDelayMs = 1000;
        outgoingQueue.clear();
        connect();
    }

    private synchronized void connect() {
        if (connected.get()) {
            logStatus("Already connected.");
            return;
        }
        if (url == null) {
            logStatus("No URL set for WebSocket.");
            return;
        }

        logStatus("Connecting...");
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                Log.d(TAG, "onOpen");
                connected.set(true);
                reconnectDelayMs = 1000; // reset backoff
                postStatus("Connected");
                // flush queued messages
                flushQueue();
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                Log.d(TAG, "onMessage: " + text);
                postMessage(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull ByteString bytes) {
                Log.d(TAG, "onMessage (bytes)");
                postMessage(bytes.hex());
            }

            @Override
            public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "onClosing: " + reason);
                connected.set(false);
                postStatus("Closing: " + reason);
                ws.close(1000, null);
                attemptReconnectIfAllowed();
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "onClosed: " + reason);
                connected.set(false);
                postStatus("Closed: " + reason);
                attemptReconnectIfAllowed();
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable Response response) {
                Log.e(TAG, "onFailure", t);
                connected.set(false);
                String msg = t.getMessage() != null ? t.getMessage() : "Unknown failure";
                postStatus("Error: " + msg);
                attemptReconnectIfAllowed();
            }
        });
    }

    private void flushQueue() {
        int sent = 0;
        while (!outgoingQueue.isEmpty() && connected.get()) {
            String m = outgoingQueue.poll();
            if (m == null) break;
            boolean ok = webSocket.send(m);
            if (!ok) {
                // if send fails, re-enqueue but avoid infinite loop
                if (outgoingQueue.size() < QUEUE_MAX) outgoingQueue.offer(m);
                break;
            } else {
                sent++;
            }
        }
        Log.d(TAG, "Flushed queued messages: " + sent);
    }

    /**
     * Send a message. If not connected, the message will be queued (bounded).
     */
    public synchronized void sendMessage(@NonNull String message) {
        if (connected.get() && webSocket != null) {
            boolean ok = webSocket.send(message);
            if (!ok) {
                // fallback to queue if immediate send failed
                enqueueSafe(message);
                Log.w(TAG, "Immediate send failed — queued message");
            }
        } else {
            enqueueSafe(message);
            Log.d(TAG, "Not connected — queued message (queue size=" + outgoingQueue.size() + ")");
        }
    }

    private void enqueueSafe(String message) {
        if (outgoingQueue.size() >= QUEUE_MAX) {
            // drop oldest to keep queue bounded
            outgoingQueue.poll();
        }
        outgoingQueue.offer(message);
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Stop and prevent reconnects.
     */
    public synchronized void stop() {
        manualClose.set(true);
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Client closing");
            } catch (Exception e) {
                Log.w(TAG, "Error while closing websocket", e);
            }
            webSocket = null;
        }
        connected.set(false);
        postStatus("Stopped");
    }

    /**
     * Try to reconnect immediately (respects manualClose flag).
     * This is safe to call repeatedly.
     */
    public void reconnect() {
        if (manualClose.get()) {
            postStatus("Manual close: not reconnecting");
            return;
        }
        if (connected.get()) {
            postStatus("Already connected");
            return;
        }
        postStatus("Manual reconnect requested...");
        // schedule an immediate connect (but still allow backoff state to control later attempts)
        reconnectHandler.post(() -> {
            if (!connected.get()) connect();
        });
    }

    private void attemptReconnectIfAllowed() {
        if (manualClose.get()) {
            Log.d(TAG, "manualClose set, will not reconnect");
            return;
        }

        // schedule reconnect with exponential backoff
        long delay = reconnectDelayMs;
        postStatus("Reconnecting in " + (delay / 1000) + "s...");
        reconnectHandler.postDelayed(() -> {
            if (!connected.get() && !manualClose.get()) {
                Log.d(TAG, "Reconnecting now (delay=" + delay + ")");
                connect();
                // increase backoff
                reconnectDelayMs = Math.min(RECONNECT_MAX_MS, reconnectDelayMs * 2);
            }
        }, delay);
    }

    private void postMessage(final String message) {
        mainHandler.post(() -> {
            try {
                callback.onMessageReceived(message);
            } catch (Exception e) {
                Log.e(TAG, "Callback onMessageReceived threw", e);
            }
        });
    }

    private void postStatus(final String status) {
        mainHandler.post(() -> {
            try {
                callback.onStatusChanged(status);
            } catch (Exception e) {
                Log.e(TAG, "Callback onStatusChanged threw", e);
            }
        });
    }

    private void logStatus(String s) {
        Log.d(TAG, s);
        postStatus(s);
    }
}
