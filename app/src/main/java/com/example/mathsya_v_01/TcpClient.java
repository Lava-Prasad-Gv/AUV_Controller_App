package com.example.mathsya_v_01;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpClient {

    public interface TcpCallback {
        void onMessageReceived(String message);
        void onStatusChanged(String status);
    }

    private static final String TAG = "TcpClient";

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    private String host;
    private int port;

    private TcpCallback callback;
    private boolean connected = false;
    private boolean manualClose = false;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public TcpClient(TcpCallback callback) {
        this.callback = callback;
    }

    // ---------------------------------------------------------
    // CONNECT
    // ---------------------------------------------------------
    public void connect(String host, int port) {
        this.host = host;
        this.port = port;
        manualClose = false;

        new Thread(() -> {
            try {
                callbackStatus("Connecting...");
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);

                out = new PrintWriter(socket.getOutputStream(), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                connected = true;
                callbackStatus("Connected");

                listenForMessages();

            } catch (Exception e) {
                connected = false;
                callbackStatus("Connection Failed: " + e.getMessage());
                attemptReconnect();
            }
        }).start();
    }

    // ---------------------------------------------------------
    // AUTO RECONNECT
    // ---------------------------------------------------------
    private void attemptReconnect() {
        if (manualClose) return;

        callbackStatus("Reconnecting in 3 sec...");
        mainHandler.postDelayed(() -> connect(host, port), 3000);
    }

    // ---------------------------------------------------------
    // MESSAGE RECEIVE LOOP
    // ---------------------------------------------------------
    private void listenForMessages() {
        new Thread(() -> {
            try {
                String line;
                while (!socket.isClosed() && (line = in.readLine()) != null) {
                    String finalLine = line;
                    mainHandler.post(() -> callback.onMessageReceived(finalLine));
                }
            } catch (IOException e) {
                if (!manualClose) {
                    callbackStatus("Disconnected");
                    connected = false;
                    attemptReconnect();
                }
            }
        }).start();
    }

    // ---------------------------------------------------------
    // SEND
    // ---------------------------------------------------------
    public void send(String msg) {
        if (!connected || out == null) return;

        new Thread(() -> {
            try {
                out.println(msg);
            } catch (Exception e) {
                callbackStatus("Send failed: " + e.getMessage());
                connected = false;
                attemptReconnect();
            }
        }).start();
    }

    // ---------------------------------------------------------
    // CLOSE
    // ---------------------------------------------------------
    public void close() {
        manualClose = true;
        connected = false;

        try {
            if (out != null) out.close();
            if (in  != null) in.close();
            if (socket != null) socket.close();
        } catch (IOException ignored) {}

        callbackStatus("Closed");
    }

    // ---------------------------------------------------------
    // HELPER
    // ---------------------------------------------------------
    private void callbackStatus(String s) {
        mainHandler.post(() -> callback.onStatusChanged(s));
        Log.d(TAG, s);
    }

    public boolean isConnected() {
        return connected;
    }
}
