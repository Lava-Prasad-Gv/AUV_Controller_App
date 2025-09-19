package com.example.mathsya_v_01;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class LoadingActivity extends AppCompatActivity {
    private static final String TAG = "LoadingActivity";
    private static final int REQUEST_LOCATION_PERMISSION = 1001;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private static final String WIFI_SSID = "Mathsya";
    private static final String WIFI_PASSWORD = "Mathsya123";

    public ScrollView scroll;
    public TextView logView;
    private WifiManager wifiManager;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> wifiList = new ArrayList<>();

    private void executeAfterDelay(Runnable action, long delayMillis) {
        mainHandler.postDelayed(action, delayMillis);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void scanWifi() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                List<ScanResult> results = wifiManager.getScanResults();
                wifiList.clear();
                for (ScanResult scanResult : results) {
                    wifiList.add(scanResult.SSID + " - " + scanResult.level + "dBm");
                }
                try {
                    unregisterReceiver(this);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Receiver already unregistered", e);
                }
            }
        };

        registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        wifiManager.startScan();

        Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show();
        logSetter("Scanning for Wi-Fi networks...");

        executeAfterDelay(() -> wifiList.forEach(this::logSetter), 3000);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);
        Log.d(TAG, "onCreate: Activity starting.");

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        if (connectivityManager == null || wifiManager == null) {
            Toast.makeText(this, "Error: Network services unavailable.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        scroll = findViewById(R.id.scrollView);
        logView = findViewById(R.id.logTextView);

        logSetter("Initializing Connection...");

        executeAfterDelay(() -> {
            scanWifi();
            Toast.makeText(this, "Connecting to Mathsya!", Toast.LENGTH_SHORT).show();
            logSetter("Establishing connection to Mathsya!");

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_LOCATION_PERMISSION
                );
            } else {
                if (!wifiManager.isWifiEnabled()) {
                    logSetter("Wi-Fi is off. Opening settings...");
                    Toast.makeText(this, "Please enable Wi-Fi manually.", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    executeAfterDelay(this::finishAffinity, 8000);

                }
                executeAfterDelay(this::tryConnectToWifi, 2000);
            }
        }, 2000);
    }

    private void tryConnectToWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            logSetter("Device supports WifiNetworkSpecifier. Attempting connection...");
            connectToWifi(WIFI_SSID, WIFI_PASSWORD);
        } else {
            Toast.makeText(this,
                    "Please connect manually to " + WIFI_SSID,
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void connectToWifi(String ssid, String password) {
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                boolean bound = connectivityManager.bindProcessToNetwork(network);
                logSetter("Connected to " + ssid + " (bound=" + bound + ")");
                mainHandler.post(() -> {
                    Toast.makeText(LoadingActivity.this,
                            "Connected to " + WIFI_SSID,
                            Toast.LENGTH_SHORT).show();
                    executeAfterDelay(()->{
                        gotoControlLayout();
                        finish();
                    },5000);

                });
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                logSetter("Connection to " + ssid + " lost.");
                connectivityManager.bindProcessToNetwork(null);
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                logSetter("Could not connect to " + ssid + ". Check details or accept prompt.");
                Toast.makeText(LoadingActivity.this,
                        "Failed to connect to " + WIFI_SSID,
                        Toast.LENGTH_LONG).show();
            }
        };

        try {
            connectivityManager.requestNetwork(request, networkCallback);
            logSetter("Requesting connection to " + ssid);
        } catch (Exception e) {
            logSetter("Error requesting network: " + e.getMessage());
            Log.e(TAG, "connectToWifi error", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering callback", e);
            }
            connectivityManager.bindProcessToNetwork(null);
            networkCallback = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logSetter("Location permission granted.");
                executeAfterDelay(this::tryConnectToWifi, 500);
            } else {
                logSetter("Location permission denied. Cannot connect to Wi-Fi.");
                Toast.makeText(this,
                        "Location permission is required.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    public void logSetter(final String log) {
        final String formattedLog = log + "\n";
        mainHandler.post(() -> {
            if (logView != null && scroll != null) {
                logView.append(formattedLog);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    public void gotoControlLayout() {
        Intent intent = new Intent(this, ControlActivity.class);
        startActivity(intent);
    }
}
