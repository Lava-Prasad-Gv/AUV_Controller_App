package com.example.mathsya_v_01;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ConcurrentHashMap;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.slider.Slider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import io.socket.client.Socket;

public class ControlActivity extends AppCompatActivity {

    private static final String TAG = "ControlActivity";
    // It's recommended to make this configurable in a real app
    private static final String TARGET_IP = "10.42.0.1";
    private static final String SERVER_URL = "http://" + TARGET_IP + ":5000";

    private ControlState controlState;
    private static final int TEST_PORT = 80; // Port for the TCP ping fallback
    private Socket socket;
    private Handler handler = new Handler();
    private boolean systemOnline = false;
    private boolean systemArmed = false;
    private Runnable sendDataRunnable;
    private TextView statusLed, statusLabel, harmStatus, harmLabel, logboard, readmeter, throttlemeter;
    private ScrollView scrolllog;
    private Button reConnectBtn, armBtn;
    private Slider slider;
    private View joystickBase, joystickHandle;

    private float throttle;

    @SuppressLint({"ClickableViewAccessibility", "MissingInflatedId"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        controlState = new ControlState();
        sendDataRunnable = new Runnable() {
            @Override
            public void run() {
                sendDataToServer();
                handler.postDelayed(this, 2000); // repeat every 3 sec
            }
        };
        handler.post(sendDataRunnable);

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_control);

        // Standard boilerplate for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // --- Bind Views ---
        bindViews();

        // --- Initialize UI State ---
        updateStatus(false);
        updateArmStatus(false);

        // Start initial connection check
        logSetter("Pinging Vehicle to check connectivity...");
        testPingAndConnect();

        // Setup Listeners
        setupListeners();
    }

    private void setupSocket() {
        try {
            socket = SocketManager.getInstance().getSocket();
            setupSocketEventListeners();
            socket.connect();

            logSetter("Socket initialized. Attempting to connect to " + SERVER_URL);

        } catch (Exception e) {
            Log.e(TAG, "Socket initialization failed", e);
            logSetter("Error: Socket setup failed. " + e.getMessage());
        }
    }

    private void setupSocketEventListeners() {
        if (socket == null) return;

        socket.on(Socket.EVENT_CONNECT, args -> runOnUiThread(() -> {
            systemOnline = true;
            updateStatus(true);
            logSetter("Socket Connected to Vehicle.");
            Toast.makeText(ControlActivity.this, "Connected", Toast.LENGTH_SHORT).show();
        }));

        socket.on(Socket.EVENT_DISCONNECT, args -> runOnUiThread(() -> {
            systemOnline = false;
            // When disconnected, the system should be considered disarmed for safety
            systemArmed = false;
            updateStatus(false);
            updateArmStatus(false);
            logSetter("Socket Disconnected from Vehicle.");
            Toast.makeText(ControlActivity.this, "Disconnected", Toast.LENGTH_SHORT).show();
        }));

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> runOnUiThread(() -> {
            logSetter("Socket Connection Error: " + args[0]);
        }));

        socket.on("reconnecting", args -> runOnUiThread(() -> {
            logSetter("Attempting to reconnect... Attempt #" + args[0]);
        }));


        socket.on("telemetry", args -> runOnUiThread(() -> {
            JSONObject data = (JSONObject) args[0];
            int battery = data.optInt("battery", 0);
            // Update armed state based on server feedback for reliability
            systemArmed = data.optBoolean("armed", false);
            float depth = (float) data.optDouble("depth", 0.0);

            statusLabel.setText("Battery: " + battery + "%, Depth: " + depth);
            updateArmStatus(systemArmed); // Reflects the actual state from the vehicle
        }));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        reConnectBtn.setOnClickListener(v -> {
            logSetter("Reconnect button clicked.");
            if (socket != null && !socket.connected()) {
                logSetter("Attempting to reconnect socket...");
                socket.connect();
            } else if (socket == null) {
                logSetter("Socket not initialized. Re-initializing...");
                setupSocket();
            } else {
                logSetter("Socket is already connected.");
            }
            Toast.makeText(this, "Attempting to reconnect...", Toast.LENGTH_SHORT).show();
        });

        armBtn.setOnClickListener(v -> {
            if (socket == null || !socket.connected()) {
                logSetter("Cannot arm/disarm: Not connected.");
                Toast.makeText(this, "Not connected to vehicle", Toast.LENGTH_SHORT).show();
                return;
            }

            // The desired new state is the opposite of the current state
            boolean newArmState = !systemArmed;
            socket.emit(newArmState ? "arm_system" : "disarm_system");
            logSetter("Command sent: " + (newArmState ? "Arm System" : "Disarm System"));

            // Optimistically update the UI. The telemetry event should confirm this shortly.
            updateArmStatus(newArmState);
            systemArmed = newArmState; // Update local state
            Toast.makeText(this, newArmState ? "System Armed" : "System Disarmed", Toast.LENGTH_SHORT).show();
        });

        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @SuppressLint("RestrictedApi")
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {}

            @SuppressLint("RestrictedApi")
            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                throttle = slider.getValue();
                throttlemeter.setText("Throttle: " + String.format("%.0f%%", throttle));
                sendThrottleData(throttle);
            }
        });

        joystickHandle.setOnTouchListener((v, event) -> {
            // Center of the joystick base
            float centerX = joystickBase.getX() + joystickBase.getWidth() / 2f;
            float centerY = joystickBase.getY() + joystickBase.getHeight() / 2f;
            float radius = joystickBase.getWidth() / 2f;

            switch (event.getAction()) {
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - centerX;
                    float dy = event.getRawY() - centerY;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);

                    // Clamp the handle position to the base radius
                    float clampedX, clampedY;
                    if (distance > radius) {
                        clampedX = dx * radius / distance;
                        clampedY = dy * radius / distance;
                    } else {
                        clampedX = dx;
                        clampedY = dy;
                    }

                    // Update handle visuals
                    joystickHandle.setX(centerX + clampedX - joystickHandle.getWidth() / 2f);
                    joystickHandle.setY(centerY + clampedY - joystickHandle.getHeight() / 2f);

                    // Normalize values between -1 and 1 for sending
                    float normalizedX = clampedX / radius;
                    // Invert Y-axis as screen coordinates are top-to-bottom
                    float normalizedY = clampedY / radius;

                    readmeter.setText(String.format("X: %.2f Y: %.2f", normalizedX, normalizedY));
                    // *** IMPROVEMENT: Actually send the joystick data ***
                    sendJoystickData(normalizedX, normalizedY);
                    break;

                case MotionEvent.ACTION_UP:
                    // Reset joystick handle to center
                    joystickHandle.animate().x(centerX - joystickHandle.getWidth() / 2f).setDuration(100).start();
                    joystickHandle.animate().y(centerY - joystickHandle.getHeight() / 2f).setDuration(100).start();

                    readmeter.setText("X: 0.00 Y: 0.00");
                    // *** IMPROVEMENT: Send a stop command when joystick is released ***
                    sendJoystickData(0f, 0f);
                    break;
            }
            return true;
        });
    }

    private void sendJoystickData(float normalizedX, float normalizedY) {
        // Safety Check: Only send data if connected and armed

        controlState.setPosition((double) normalizedX,(double) normalizedY);
    }

    private void sendThrottleData(float throttleValue) {
        // Safety Check: Only send data if connected and armed
        if (socket == null || !socket.connected()) {
            logSetter("Throttle Ignored: Not connected.");
            return;
        }
        if (!systemArmed) {
            controlState.setThrottle(0);
            logSetter("Throttle Ignored: System not armed.");
            Toast.makeText(this, "System must be armed to set throttle", Toast.LENGTH_SHORT).show();
        }else{
            controlState.setThrottle((int)throttleValue);
        }
    }

    private void testPingAndConnect() {
        new Thread(() -> {
            String pingMessage;
            boolean isReachable = false;
            logSetter("Trying ICMP Ping...");
            try {
                InetAddress inet = InetAddress.getByName(TARGET_IP);
                if (inet.isReachable(2000)) { // 2-second timeout
                    isReachable = true;
                    pingMessage = "ICMP Ping SUCCESS to " + TARGET_IP;
                } else {
                    logSetter("ICMP failed. Trying TCP port connection...");
                    try (java.net.Socket tcpSocket = new java.net.Socket()) {
                        tcpSocket.connect(new InetSocketAddress(TARGET_IP, TEST_PORT), 2000);
                        isReachable = true;
                        pingMessage = "TCP Connection SUCCESS to " + TARGET_IP + ":" + TEST_PORT;
                    } catch (IOException e) {
                        pingMessage = "Ping FAILED: Vehicle is not reachable on the network.";
                        Log.e(TAG, "TCP Fallback failed", e);
                    }
                }
            } catch (IOException e) {
                pingMessage = "Ping FAILED: An error occurred during ping.";
                Log.e(TAG, "ICMP Ping failed", e);
            }

            final String finalMessage = pingMessage;
            final boolean finalIsReachable = isReachable;

            runOnUiThread(() -> {
                logSetter(finalMessage);
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                if (finalIsReachable) {
                    logSetter("Vehicle is reachable. Initializing socket connection...");
                    setupSocket(); // Now that we know it's reachable, connect the main socket.
                } else {
                    logSetter("Vehicle unreachable. Check Wi-Fi and AUV power.");
                }
            });
        }).start();
    }

    private void updateStatus(boolean online) {
        systemOnline = online;
        if (online) {
            statusLed.setBackgroundResource(R.drawable.led_on);
            statusLabel.setText("Online");
        } else {
            statusLed.setBackgroundResource(R.drawable.led_off);
            statusLabel.setText("Offline");
        }
    }

    private void updateArmStatus(boolean armed) {
        systemArmed = armed;
        controlState.setArmed(armed);
        if (armed) {
            harmStatus.setBackgroundResource(R.drawable.led_on);
            harmLabel.setText("Armed");
            armBtn.setText("Disarm");
        } else {
            harmStatus.setBackgroundResource(R.drawable.led_off);
            harmLabel.setText("Disarmed");
            armBtn.setText("Arm");
        }
    }

    private void bindViews() {
        statusLed = findViewById(R.id.statusLed);
        statusLabel = findViewById(R.id.statusLabel);
        harmStatus = findViewById(R.id.harmStatus);
        harmLabel = findViewById(R.id.harmLabel);
        slider = findViewById(R.id.slider);
        reConnectBtn = findViewById(R.id.reConnectBtn);
        scrolllog = findViewById(R.id.scrollView2);
        armBtn = findViewById(R.id.armbtn);
        throttlemeter = findViewById(R.id.t_board);
        readmeter = findViewById(R.id.readingboard);
        logboard = findViewById(R.id.logmsg);
        joystickBase = findViewById(R.id.joystick_base);
        joystickHandle = findViewById(R.id.joystick_handle);
    }

    public void logSetter(final String log) {
        if (isFinishing() || isDestroyed()) {
            return; // Avoid crashing if activity is closing
        }
        runOnUiThread(() -> {
            final String formattedLog = log + "\n";
            if (logboard != null && scrolllog != null) {
                logboard.append(formattedLog);
                // Scroll to the bottom to show the latest log message
                scrolllog.post(() -> scrolllog.fullScroll(View.FOCUS_DOWN));
            }
        });
    }



    private void sendDataToServer() {
        if (socket != null && socket.connected()) {
            try {
                JSONObject json = controlState.toJSON();
                Log.d("SOCKET_EMIT", "Sending JSON: " + json.toString());
                socket.emit("client_data", json);
            } catch (Exception e) {
                Log.e("SOCKET_EMIT", "Failed to send", e);
            }
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socket != null) {
            logSetter("Closing socket connection.");
            socket.off(); // Remove all listeners
        }
        handler.removeCallbacks(sendDataRunnable);
    }
}