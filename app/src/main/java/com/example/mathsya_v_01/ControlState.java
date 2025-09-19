package com.example.mathsya_v_01;

import org.json.JSONException;
import org.json.JSONObject;

public class ControlState {

    private boolean armed;
    private int throttle;       // 0–100
    private double joystickX;
    private double joystickY;

    public ControlState() {
        this.armed = false;
        this.throttle = 0;
        this.joystickX = 0.0;
        this.joystickY = 0.0;
    }

    // ---- Setters ----
    public synchronized void setArmed(boolean armed) {
        this.armed = armed;
    }

    public synchronized void setThrottle(int throttle) {
        this.throttle = Math.max(0, Math.min(throttle, 100));
    }

    public synchronized void setPosition(double x, double y) {
        this.joystickX = safeDouble(x);
        this.joystickY = safeDouble(y);
    }

    // ---- JSON Export ----
    public synchronized JSONObject toJSON() {
        JSONObject json = new JSONObject();
        try {
            json.put("armed", armed);
            json.put("throttle", throttle);
            json.put("joystick_x", joystickX);
            json.put("joystick_y", joystickY);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    // ---- Safe double (avoid NaN/Infinity) ----
    private double safeDouble(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return 0.0;
        return val;
    }
}
