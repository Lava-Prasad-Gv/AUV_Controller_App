package com.example.mathsya_v_01;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.slider.Slider;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DashboardActivity extends AppCompatActivity implements OnMapReadyCallback {
    private GoogleMap map;
    private TextView btnArm, btnDisarm, btnExamine, onlineBadge;
    private View joystickHandle, joystickBase;
    public Slider slider;

    // TCP client
    private TcpClient tcpClient;

    // Logging
    private String logMessages = "";
    private String statusMessages = "";

    private boolean armStatus = false;

    private ImageButton reConnect, logPopin;
    private PopupWindow logPopUpWindow;

    // Popup UI elements (from your XML)
    private TextView logTextBox;
    private ScrollView logScrollView;

    private ControlState controlState;
    private static final String TAG = "ControlActivity";

    private static final String TARGET_IP = "10.42.0.1";  // adjust if needed
    private static final int TARGET_PORT = 5000;

    private Handler handler = new Handler();
    private Runnable sendDataRunnable;

    private float throttle = 0f;

    private long lastDisconnectToastMs = 0L;
    private static final long TOAST_THROTTLE_MS = 3000L;

    // Log trimming
    private static final int MAX_LOG_LENGTH = 30_000; // characters

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        controlState = new ControlState();

        // Initialize tcp client with callbacks that run on main thread (TcpClient posts status on main handler)
        tcpClient = new TcpClient(new TcpClient.TcpCallback() {
            @Override
            public void onMessageReceived(String message) {
                // message from server
                appendLog("RECV", message);
                Log.d(TAG, "TCP MSG: " + message);
            }

            @Override
            public void onStatusChanged(String status) {
                appendLog("INFO", status);
                statusMessages += "\n" + status;
                Log.d(TAG, "TCP STATUS: " + status);
                updateOnlineBadge();
            }
        });

        // connect
        tcpClient.connect(TARGET_IP, TARGET_PORT);

        bind();
        setupListeners();

        // Map Fragment
        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        // Initialize sensor cards
        int[] cardIds = {R.id.card_ph, R.id.card_do, R.id.card_temp, R.id.card_turb, R.id.card_amm};
        String[] names = {"pH", "DO", "Temp", "Turbidity", "Ammonia"};
        String[] units = {"ideal", "mg/L", "°C", "NTU", "mg/L"};

        for (int i = 0; i < cardIds.length; i++) {
            TextView name = findViewById(cardIds[i]).findViewById(R.id.sensorName);
            TextView unit = findViewById(cardIds[i]).findViewById(R.id.sensorUnit);
            name.setText(names[i]);
            unit.setText(units[i]);
        }

        // Send control JSON every 2 seconds
        sendDataRunnable = new Runnable() {
            @Override
            public void run() {
                sendDataToServer();
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(sendDataRunnable, 2000);

        updateOnlineBadge();
    }

    private void bind() {
        joystickBase = findViewById(R.id.joystick_base);
        joystickHandle = findViewById(R.id.joystick_handle);

        slider = findViewById(R.id.slider);

        btnArm = findViewById(R.id.btnStatusArm);
        btnDisarm = findViewById(R.id.btnStatusDisarm);
        btnExamine = findViewById(R.id.btnExamine);

        reConnect = findViewById(R.id.btnReconnect);
        logPopin = findViewById(R.id.btnLog);

        onlineBadge = findViewById(R.id.statusBadge);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        // ARM
        btnArm.setOnClickListener(view -> {
            if (tcpClient.isConnected()) {
                if (!armStatus) {
                    armStatus = true;
                    controlState.setArmed(true);
                    btnArm.setBackgroundResource(R.drawable.bg_button_glow);
                    btnDisarm.setBackgroundResource(R.drawable.bg_button_glow_low);
                    sendDataToServer();
                    appendLog("INFO", "ARM command sent");
                } else {
                    Toast.makeText(this, "Already Armed", Toast.LENGTH_SHORT).show();
                }
            } else {
                maybeToastDisconnected();
            }
        });

        // DISARM
        btnDisarm.setOnClickListener(view -> {
            if (armStatus) {
                armStatus = false;
                controlState.setArmed(false);
                btnArm.setBackgroundResource(R.drawable.bg_button_glow_high);
                btnDisarm.setBackgroundResource(R.drawable.bg_button_glow);
                sendDataToServer();
                appendLog("INFO", "DISARM command sent");
            }
        });

        btnExamine.setOnClickListener(view ->
                Toast.makeText(this, "Examine pressed", Toast.LENGTH_SHORT).show()
        );

        // RECONNECT
        reConnect.setOnClickListener(view -> {
            appendLog("INFO", "Manual reconnect requested");
            tcpClient.connect(TARGET_IP, TARGET_PORT);
            updateOnlineBadge();
        });

        // SHOW LOG POPUP
        logPopin.setOnClickListener(v -> showLogPopup());

        // SLIDER
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {}

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                throttle = slider.getValue();
                controlState.setThrottle(throttle);
                sendDataToServer();
            }
        });

        // JOYSTICK
        joystickHandle.setOnTouchListener((v, event) -> {
            float centerX = joystickBase.getX() + joystickBase.getWidth() / 2f;
            float centerY = joystickBase.getY() + joystickBase.getHeight() / 2f;
            float radius = joystickBase.getWidth() / 2f;

            float rawX = event.getRawX();
            float rawY = event.getRawY();

            int[] baseLoc = new int[2];
            joystickBase.getLocationOnScreen(baseLoc);

            float localX = rawX - (baseLoc[0] + joystickBase.getWidth() / 2f);
            float localY = rawY - (baseLoc[1] + joystickBase.getHeight() / 2f);

            switch (event.getAction()) {
                case MotionEvent.ACTION_MOVE:
                    float dx = localX;
                    float dy = localY;

                    float dist = (float) Math.sqrt(dx * dx + dy * dy);

                    float cx, cy;
                    if (dist > radius) {
                        cx = dx * radius / dist;
                        cy = dy * radius / dist;
                    } else {
                        cx = dx;
                        cy = dy;
                    }

                    joystickHandle.setX(centerX + cx - joystickHandle.getWidth() / 2f);
                    joystickHandle.setY(centerY + cy - joystickHandle.getHeight() / 2f);

                    float nx = cx / radius;
                    float ny = cy / radius;

                    controlState.setPosition(nx, ny);
                    sendDataToServer();
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    joystickHandle.animate()
                            .x(centerX - joystickHandle.getWidth() / 2f)
                            .y(centerY - joystickHandle.getHeight() / 2f)
                            .setDuration(100)
                            .start();

                    controlState.setPosition(0, 0);
                    sendDataToServer();
                    break;
            }
            return true;
        });
    }

    // Show popup and wire its log views
    private void showLogPopup() {
        if (logPopUpWindow != null && logPopUpWindow.isShowing()) {
            // already open
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        View logView = inflater.inflate(R.layout.log_view, null);

        // find popup UI elements
        logTextBox = logView.findViewById(R.id.logTextBox);
        logScrollView = logView.findViewById(R.id.scrollView4);

        // set initial text
        logTextBox.setText(""); // clear (we append using appendLog history if needed)
        if (!logMessages.isEmpty()) {
            // replay recent messages (logMessages is plain text; we store timestamped lines there)
            logTextBox.setText(logMessages);
            scrollToBottom();
        }

        ImageButton back = logView.findViewById(R.id.imageButtonBack);
        if (back != null) {
            back.setOnClickListener(v -> {
                if (logPopUpWindow != null) logPopUpWindow.dismiss();
            });
        }

        logPopUpWindow = new PopupWindow(
                logView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );

        logPopUpWindow.setElevation(10);
        logPopUpWindow.setAnimationStyle(android.R.style.Animation_Dialog);
        logPopUpWindow.setOutsideTouchable(true);
        logPopUpWindow.setFocusable(true);

        View rootView = findViewById(android.R.id.content);
        logPopUpWindow.showAtLocation(rootView, Gravity.CENTER, 0, -290);
    }

    // Append log to UI safely; level is "INFO", "RECV", "ERROR"
    private void appendLog(String level, String message) {
        // Build timestamped line
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = String.format("[%s] %s: %s\n", ts, level, message);

        // Keep backup of text-only logs (for popup replay if recreated)
        logMessages += line;
        if (logMessages.length() > MAX_LOG_LENGTH) {
            // trim oldest
            logMessages = logMessages.substring(logMessages.length() - MAX_LOG_LENGTH);
        }

        // If popup view exists and logTextBox is available, append with color
        runOnUiThread(() -> {
            if (logTextBox == null) {
                // popup not created yet; nothing to update UI-wise
                return;
            }

            int color = Color.WHITE;
            if ("ERROR".equals(level)) color = Color.parseColor("#ff4d4d");
            else if ("RECV".equals(level)) color = Color.parseColor("#b3e5fc"); // light blue
            else if ("INFO".equals(level)) color = Color.parseColor("#c8e6c9"); // light green

            SpannableString spannable = new SpannableString(line);
            spannable.setSpan(new ForegroundColorSpan(color), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Append
            CharSequence prev = logTextBox.getText();
            logTextBox.setText(""); // replace with combined to avoid repeated setSpan shifting
            logTextBox.append(prev);
            logTextBox.append(spannable);

            // auto-scroll
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        if (logScrollView == null || logTextBox == null) return;
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void updateOnlineBadge() {
        if (onlineBadge == null) return;
        if (tcpClient != null && tcpClient.isConnected()) {
            onlineBadge.setBackgroundResource(R.drawable.bg_status_badge_online);
            onlineBadge.setTextColor(Color.parseColor("#22c55e"));
            onlineBadge.setText("Online");
        } else {
            onlineBadge.setBackgroundResource(R.drawable.bg_status_badge_offline);
            onlineBadge.setTextColor(Color.RED);
            onlineBadge.setText("Offline");
        }
    }

    private void maybeToastDisconnected() {
        long now = System.currentTimeMillis();
        if (now - lastDisconnectToastMs > TOAST_THROTTLE_MS) {
            Toast.makeText(this, "System offline", Toast.LENGTH_SHORT).show();
            lastDisconnectToastMs = now;
        }
    }

    private void sendDataToServer() {
        if (tcpClient == null) return;

        try {
            JSONObject json = controlState.toJSON();
            tcpClient.send(json.toString());
            appendLog("INFO", "Sent: " + json.toString());
            updateOnlineBadge();
        } catch (Exception e) {
            appendLog("ERROR", "Send failed: " + e.getMessage());
            Log.e(TAG, "SEND ERROR", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(sendDataRunnable);
        if (tcpClient != null) tcpClient.close();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        LatLng pos = new LatLng(12.9602, 80.0574);
        map.moveCamera(CameraUpdateFactory.newLatLng(pos));
        map.getUiSettings().setZoomGesturesEnabled(true);
        map.getUiSettings().setCompassEnabled(true);
    }
}
