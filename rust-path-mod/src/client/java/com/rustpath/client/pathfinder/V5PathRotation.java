package com.rustpath.client.pathfinder;

/**
 * V5's path-walking rotation (V5-Client/V5, utils/pathfinder/PathWalker/PathRotations.js), every render frame, in
 * two stages:
 * <ol>
 *   <li>a "raw target" follows where the path says to look, a share of the way each frame (10%, 5% on a straight),
 *   and not at all while it's within a deadzone (1.2° yaw, 1.5× that on a straight; 1.8° pitch), so the look point's
 *   little swings as you walk never reach the view. Over the last 3 nodes it follows closer and closer. While in the
 *   air it won't take a turn of over 90°;</li>
 *   <li>the view is driven onto the raw target by a PD controller: a turning speed pushed by the error (0.05 × it,
 *   ×0.6-1.5 with its size) and held back by the speed (0.55, ×1.3 on a straight), the push at most 1.2 and the speed
 *   at most 8, bled 8% a frame. So a turn builds up, carries and eases in instead of starting at its fastest. For the
 *   first 10 frames of a walk, while over 35° off, it turns twice as hard.</li>
 * </ol>
 * Constants are per 120 fps frame; a frame scales them by its length (at most 2 of those). The view moves in whole
 * mouse steps.
 */
public final class V5PathRotation {
    private static final double BASE_KP = 0.05, KD = 0.55, MAX_VELOCITY = 8, ACCEL_LIMIT = 1.2, SETTLE = 0.15;
    private static final double YAW_DEADZONE = 1.2, PITCH_DEADZONE = 1.8, SMOOTH_FACTOR = 0.1, MAX_AIR_TURN = 90;
    // (V5: 10 — 80 ms at 120 fps; the first turn of a walk was slow and it walked into walls meanwhile)
    private static final double BOOST_TICKS = 30;

    private boolean active;
    /** Where the controller has the view (unwrapped), its raw target, and its turning speeds (degrees per frame). */
    private double yaw, pitch, rawYaw, rawPitch, yawVel, pitchVel, boostTicks;
    private float lastYaw, lastPitch;
    /** The path ahead: how much it bends (radians), and how many nodes are left. */
    private double curvature, remaining = Double.MAX_VALUE;

    public void setPathShape(double curvature, double remainingNodes) {
        this.curvature = curvature;
        this.remaining = remainingNodes;
    }

    /**
     * How soon something's in the way where you face (0: nothing near, 1: the body's up against it in a step):
     * the turn's taken that much harder, so it's round before it walks into it.
     */
    private double urgency;

    public void setUrgency(double u) {
        urgency = Math.max(0, Math.min(1, u));
    }

    /**
     * Where to look this frame from (playerYaw, playerPitch), turning toward (targetYaw, targetPitch); {@code ts} is
     * the frame's length in 120 fps frames (at most 2). {@code holdYaw}: the raw target stays put (V5, when the look
     * point is right below you in the air and its yaw would spin).
     */
    public float[] step(float playerYaw, float playerPitch, float targetYaw, float targetPitch, boolean onGround,
                        boolean holdYaw, double ts) {
        float g = MouseMath.mouseStep();
        if (!active) {
            yaw = rawYaw = playerYaw;
            pitch = rawPitch = playerPitch;
            yawVel = pitchVel = 0;
            boostTicks = BOOST_TICKS;
            active = true;
        } else if (Math.abs(MouseMath.wrap(playerYaw - lastYaw)) > g * 2 || Math.abs(playerPitch - lastPitch) > g * 2) {
            // something else turned the view: on from there
            rawYaw = playerYaw + MouseMath.wrap(rawYaw - playerYaw);
            yaw = playerYaw;
            pitch = playerPitch;
            yawVel = pitchVel = 0;
        }
        ts = Math.max(0, Math.min(2, ts));

        // the raw target, following the look target
        double finish = remaining < 3 ? Math.max(0.1, remaining / 3) : 1;
        boolean straight = curvature < 0.15;
        double yawDelta = MouseMath.wrap(targetYaw - rawYaw);
        double base = (straight ? SMOOTH_FACTOR * 0.5 : SMOOTH_FACTOR) / finish;
        // a big turn followed faster than a nudge (a quarter turn at a corner came round at a nudge's pace, and it
        // walked into the corner and had to jump), and harder with something in the way ahead
        double sharp = Math.min(3, Math.max(1, Math.abs(yawDelta) / 30));
        base *= sharp * (1 + urgency);
        double smooth = 1 - Math.pow(1 - Math.min(1, base * boost(yawDelta)), ts);
        double yawDeadzone = (straight ? YAW_DEADZONE * 1.5 : YAW_DEADZONE) * finish;
        boolean airTurn = !onGround && Math.abs(yawDelta) > MAX_AIR_TURN;
        if (!holdYaw && !airTurn && Math.abs(yawDelta) > yawDeadzone) rawYaw += yawDelta * Math.min(1, smooth);
        double pitchDelta = targetPitch - rawPitch;
        if (!holdYaw && Math.abs(pitchDelta) > PITCH_DEADZONE * finish) rawPitch += pitchDelta * Math.min(1, smooth);
        if (boostTicks > 0) {
            if (Math.abs(MouseMath.wrap(rawYaw - yaw)) <= 10) boostTicks = 0;
            else boostTicks -= ts;
        }

        // the view, driven onto it
        double yawErr = MouseMath.wrap(rawYaw - yaw), pitchErr = rawPitch - pitch;
        double b = boost(yawErr) * (1 + urgency);
        double kp = BASE_KP * Math.min(1.5, Math.max(0.6, Math.abs(yawErr) / 10)) * b;
        double kd = curvature < 0.2 && urgency == 0 ? KD * 1.3 : KD;
        double accel = ACCEL_LIMIT * b, maxVel = MAX_VELOCITY * b;
        if (Math.abs(yawErr) < SETTLE && Math.abs(yawVel) < 0.02) {
            yaw += yawErr;
            yawVel = 0;
        } else {
            yawVel += clamp((yawErr * kp - yawVel * kd) * ts, accel * ts);
            yawVel = clamp(yawVel * Math.pow(0.92, ts), maxVel);
            yaw += yawVel * ts;
        }
        if (Math.abs(pitchErr) < SETTLE && Math.abs(pitchVel) < 0.02) {
            pitch = rawPitch;
            pitchVel = 0;
        } else {
            pitchVel += clamp((pitchErr * kp - pitchVel * kd) * ts, accel * ts);
            pitchVel = clamp(pitchVel * Math.pow(0.92, ts), maxVel);
            pitch += pitchVel * ts;
        }
        pitch = Math.max(-90, Math.min(90, pitch));

        // in whole mouse steps from where the view is
        float newYaw = playerYaw + Math.round(MouseMath.wrap(yaw - playerYaw) / g) * g;
        float newPitch = playerPitch + Math.round((pitch - playerPitch) / g) * g;
        if (newPitch > 90) newPitch -= g;
        if (newPitch < -90) newPitch += g;
        lastYaw = newYaw;
        lastPitch = newPitch;
        return new float[]{newYaw, newPitch};
    }

    /** Forget the walk's turning: the next step starts from where you look, at rest. */
    public void reset() {
        active = false;
    }

    private double boost(double err) {
        return boostTicks > 0 && Math.abs(err) >= 35 ? 2 : 1;
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }
}
