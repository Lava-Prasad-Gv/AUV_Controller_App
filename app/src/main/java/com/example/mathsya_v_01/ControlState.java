package com.example.mathsya_v_01;

import org.json.JSONObject;

/**
 * Simple container for control state — position (x,y) and throttle/arm.
 * Adjust fields as needed (add yaw, pitch, etc.).
 */
public class ControlState {
    private double x = 0.0;
    private double y = 0.0;
    private double throttle = 0.0;
    private boolean armed = false;

    public ControlState() {}

    public void setPosition(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public void setThrottle(double throttle) {
        this.throttle = throttle;
    }

    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    /**
     * Build JSON to send to server. Keep keys in a format server expects.
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        try {
            json.put("x", x);
            json.put("y", y);
            json.put("throttle", throttle);
            json.put("armed", armed);
            json.put("ts", System.currentTimeMillis());
        } catch (Exception e) {
            // should not happen
        }
        return json;
    }
}
