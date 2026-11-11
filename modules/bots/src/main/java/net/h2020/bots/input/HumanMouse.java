package net.h2020.bots.input;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.BotSkill;
import net.minecraft.server.v1_8_R3.Entity;

import java.util.Random;

/**
 * Human mouse model producing the rotation of the virtual client.
 *
 * Every rendered frame (FRAMES_PER_TICK per tick) the model decides how far the hand moves the mouse, then converts
 * that into integer mouse counts exactly like the 1.8.9 client: f = sens*0.6+0.2, rotation += counts * f^3*8 * 0.15
 * (float maths as in EntityRenderer + Entity.setAngles), with sub-count remainders accumulated like a real mouse.
 * The resulting yaw/pitch deltas are therefore always integer multiples of the sensitivity GCD.
 *
 * Motion model:
 *  - reaction delay before responding to a new target (around BotSkill.reactionMs, shaped by intent);
 *  - aimed movements are submovements with a Fitts's-law duration and a minimum-jerk (10t^3-15t^4+6t^5) profile,
 *    curved slightly off the straight line, ending with an intent/skill dependent overshoot or undershoot;
 *  - corrective submovements after a short visual-feedback delay until within tolerance;
 *  - smooth pursuit of moving targets with gain < 1 (tracking lag) on top of a delayed percept of the target;
 *  - small micro-corrections when settled, and 8-12 Hz physiological tremor scaled with hand speed;
 *  - the aim point on an entity hitbox wanders around the chest/neck instead of the exact centre.
 */
public final class HumanMouse {
    /** Rendered mouse frames per client tick (6 = 120 fps). Changed live with /bot fps. */
    public static volatile int FRAMES_PER_TICK = 6;
    private static final int HIST = 16;

    /** Milliseconds per rendered frame; read live so /bot fps takes effect on the very next frame. */
    private static double frameMs() { return 50.0D / FRAMES_PER_TICK; }

    /** /bot fps: clamp to 1..20 frames per 50 ms tick. */
    public static void setFramesPerTick(int n) {
        FRAMES_PER_TICK = Math.max(1, Math.min(20, n));
    }

    public enum Mode { NONE, POINT, ENTITY, ROTATION }

    private final Random rnd;
    private final BotSkill skill;
    private final double aim;
    private final float mouseFactor;   // f*f*f*8
    public final double stepDeg;       // degrees per mouse count

    // ---- client rotation (authoritative, exactly what the client would hold) ----
    public float yaw, pitch;
    private double accX, accY;

    // ---- target ----
    public Mode mode = Mode.NONE;
    public AimIntent intent = AimIntent.NAVIGATE;
    public Entity target;
    private double px, py, pz;
    private float ry, rp;

    // aim point on hitbox (fraction of height, sideways offset in blocks)
    private double aimFrac = 0.75D, aimSide, aimFracGoal = 0.75D, aimSideGoal, nextAimPick;
    /** Bias from the aim policy: where on the hitbox to aim, and how much lead to take. */
    private double aimBiasFrac, leadBias = 1.0D;

    // percept history of the tracked entity
    private final double[] hx = new double[HIST], hy = new double[HIST], hz = new double[HIST], ht = new double[HIST];
    private int hHead, hCount;

    // ---- timing ----
    private double now;
    private double reactionUntil;
    private double nextCorrection;

    // ---- current submovement ----
    private boolean moving;
    private double mvStart, mvDur, mvYaw, mvPitch, mvCurv, mvPerpYaw, mvPerpPitch, mvPrevS, mvPrevC;

    // ---- pursuit ----
    private double velYaw, velPitch;
    private double lastDesYaw, lastDesPitch;
    private boolean haveLastDes;

    // ---- tremor ----
    private double trPhase1, trPhase2, trPhase3, trPhase4;
    private final double trFreq1, trFreq2;
    private double trLastX, trLastY;
    private double handSpeed; // deg/ms, smoothed

    // ---- diagnostics ----
    public double lastErrorDeg = 999.0D;
    public double lastToleranceDeg = 1.0D;
    public double lastDesiredYaw, lastDesiredPitch;

    public HumanMouse(Random rnd, BotSkill skill, float yaw, float pitch) {
        this.rnd = rnd;
        this.skill = skill;
        this.aim = clamp01(skill.aim);
        float f = skill.sensitivity * 0.6F + 0.2F;
        this.mouseFactor = f * f * f * 8.0F;
        this.stepDeg = (double) mouseFactor * 0.15D;
        this.yaw = yaw;
        this.pitch = pitch;
        this.trFreq1 = 8.0D + rnd.nextDouble() * 4.0D;
        this.trFreq2 = 8.0D + rnd.nextDouble() * 4.0D;
        this.trPhase1 = rnd.nextDouble() * Math.PI * 2;
        this.trPhase2 = rnd.nextDouble() * Math.PI * 2;
        this.trPhase3 = rnd.nextDouble() * Math.PI * 2;
        this.trPhase4 = rnd.nextDouble() * Math.PI * 2;
    }

    // ================================================================== targets
    public void aimAt(Entity e, AimIntent in) {
        if (e == null) { clear(); return; }
        if (mode != Mode.ENTITY || target != e) {
            mode = Mode.ENTITY;
            target = e;
            hCount = 0;
            pickAimPoint(true);
            react(in);
        } else if (in != intent && (in == AimIntent.FLICK)) {
            react(in);
        }
        intent = in;
    }

    public void aimAt(double x, double y, double z, AimIntent in, double eyeX, double eyeY, double eyeZ) {
        double newYaw = yawTo(eyeX, eyeZ, x, z);
        double newPitch = pitchTo(eyeX, eyeY, eyeZ, x, y, z);
        boolean jump = mode != Mode.POINT
                || angDist(newYaw, newPitch, yawTo(eyeX, eyeZ, px, pz), pitchTo(eyeX, eyeY, eyeZ, px, py, pz)) > 12.0D;
        mode = Mode.POINT;
        target = null;
        px = x; py = y; pz = z;
        if (jump) react(in);
        intent = in;
    }

    public void aimRotation(float yawTarget, float pitchTarget, AimIntent in) {
        boolean jump = mode != Mode.ROTATION || angDist(yawTarget, pitchTarget, ry, rp) > 12.0D;
        mode = Mode.ROTATION;
        target = null;
        ry = yawTarget;
        rp = clampPitch(pitchTarget);
        if (jump) react(in);
        intent = in;
    }

    /** Aim profile from the fight models: vertical bias on the hitbox and a lead multiplier. */
    public void setAimPolicy(double fracBias, double leadScale) {
        this.aimBiasFrac = Math.max(-0.6D, Math.min(0.25D, fracBias));
        this.leadBias = Math.max(0.2D, Math.min(2.5D, leadScale));
        pickAimPoint(false);
    }

    public void clear() {
        mode = Mode.NONE;
        target = null;
        haveLastDes = false;
    }

    /** Server teleport set our rotation: keep the plan relative, just adopt the new angles. */
    public void setRotation(float y, float p) {
        yaw = y;
        pitch = p;
        haveLastDes = false;
    }

    public boolean settled() {
        return mode != Mode.NONE && now >= reactionUntil && !moving && lastErrorDeg <= lastToleranceDeg;
    }

    public boolean reacting() { return now < reactionUntil; }
    public boolean moving() { return moving; }
    public double reactionRemainingMs() { return Math.max(0.0D, reactionUntil - now); }

    private void react(AimIntent in) {
        double base = skill.reactionMs;
        double mult;
        switch (in) {
            case FLICK: mult = 0.8D; break;
            case COMBAT: mult = 0.85D; break;
            case NAVIGATE: mult = 0.55D; break;
            case GLANCE: mult = 1.15D; break;
            default: mult = 0.95D; break;
        }
        double r = base * mult * (1.0D + rnd.nextGaussian() * 0.18D);
        r = Math.max(90.0D, Math.min(750.0D, r));
        reactionUntil = Math.max(reactionUntil, now + r);
        nextCorrection = reactionUntil;
        haveLastDes = false;
        velYaw = 0.0D;
        velPitch = 0.0D;
    }

    // ================================================================== per tick / per frame
    /** Called once per client tick before the frames: records the tracked entity position for the delayed percept. */
    public void tickStart() {
        if (mode == Mode.ENTITY && target != null) {
            if (target.dead || !target.valid) { clear(); return; }
            int idx;
            if (hCount < HIST) { idx = (hHead + hCount) % HIST; hCount++; }
            else { idx = hHead; hHead = (hHead + 1) % HIST; } // overwrite the oldest sample
            hx[idx] = target.locX; hy[idx] = target.locY; hz[idx] = target.locZ; ht[idx] = now;
        }
    }

    /** Simulates the frames of one tick. eye = client eye position. */
    public void frames(double eyeX, double eyeY, double eyeZ) {
        for (int i = 0; i < FRAMES_PER_TICK; i++) frameStep(eyeX, eyeY, eyeZ);
    }

    /** Simulates one rendered frame; the caller can raycast/click between steps. eye = client eye position. */
    public void frameStep(double ex, double ey, double ez) {
        now += frameMs();
        double dYaw = 0.0D, dPitch = 0.0D;

        double desYaw = yaw, desPitch = pitch;
        double width = 6.0D;
        boolean hasTarget = mode != Mode.NONE;
        if (hasTarget) {
            double tx, ty, tz;
            if (mode == Mode.ENTITY) {
                if (!perceive()) { clear(); hasTarget = false; tx = ty = tz = 0; }
                else {
                    driftAimPoint();
                    double h = (double) target.length;
                    tx = percX; ty = percY + h * aimFrac; tz = percZ;
                    double dx = tx - ex, dz = tz - ez;
                    double l = Math.sqrt(dx * dx + dz * dz);
                    if (l > 1.0E-3D) { tx += -dz / l * aimSide; tz += dx / l * aimSide; }
                    double dist = Math.max(0.5D, Math.sqrt(dx * dx + dz * dz + (ty - ey) * (ty - ey)));
                    width = Math.toDegrees(2.0D * Math.atan((double) target.width * 0.5D / dist));
                }
            } else if (mode == Mode.POINT) {
                tx = px; ty = py; tz = pz;
                double dist = Math.max(0.5D, Math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez)));
                width = intent == AimIntent.PRECISE ? Math.toDegrees(2.0D * Math.atan(0.3D / dist)) : 8.0D;
            } else {
                tx = ty = tz = 0;
            }
            if (hasTarget) {
                double rawYaw, rawPitch;
                if (mode == Mode.ROTATION) { rawYaw = ry; rawPitch = rp; width = intent == AimIntent.PRECISE ? 1.5D : 8.0D; }
                else { rawYaw = yawTo(ex, ez, tx, tz); rawPitch = pitchTo(ex, ey, ez, tx, ty, tz); }
                desYaw = yaw + wrap(rawYaw - yaw);
                desPitch = clampPitch((float) rawPitch);
            }
        }

        double errYaw = desYaw - yaw, errPitch = desPitch - pitch;
        double err = Math.sqrt(errYaw * errYaw + errPitch * errPitch);
        double tol = tolerance(width);
        lastErrorDeg = hasTarget ? err : 999.0D;
        lastToleranceDeg = tol;
        lastDesiredYaw = desYaw;
        lastDesiredPitch = desPitch;

        // target angular velocity estimate (deg/ms) for pursuit / lead
        if (hasTarget && haveLastDes) {
            double vy = wrap(desYaw - lastDesYaw) / frameMs(), vp = (desPitch - lastDesPitch) / frameMs();
            velYaw += (vy - velYaw) * 0.3D;
            velPitch += (vp - velPitch) * 0.3D;
        } else {
            velYaw *= 0.5D;
            velPitch *= 0.5D;
        }
        lastDesYaw = desYaw;
        lastDesPitch = desPitch;
        haveLastDes = hasTarget;

        // 1) ballistic submovement in flight
        if (moving) {
            double tau = Math.min(1.0D, (now - mvStart) / mvDur);
            double s = minJerk(tau);
            double c = Math.sin(Math.PI * tau);
            dYaw += mvYaw * (s - mvPrevS) + mvPerpYaw * mvCurv * (c - mvPrevC);
            dPitch += mvPitch * (s - mvPrevS) + mvPerpPitch * mvCurv * (c - mvPrevC);
            mvPrevS = s;
            mvPrevC = c;
            if (tau >= 1.0D) {
                moving = false;
                double fb = intent == AimIntent.COMBAT || intent == AimIntent.FLICK ? 35.0D : 60.0D;
                nextCorrection = now + fb + rnd.nextDouble() * (60.0D + 80.0D * (1.0D - aim));
            }
        }

        boolean responsive = hasTarget && now >= reactionUntil;

        // 2) smooth pursuit of a moving target (gain < 1 -> tracking lag)
        if (responsive && (mode == Mode.ENTITY || mode == Mode.POINT)) {
            double gain = pursuitGain();
            double vy = clampAbs(velYaw, 1.5D), vp = clampAbs(velPitch, 1.0D);
            dYaw += gain * vy * frameMs();
            dPitch += gain * vp * frameMs();
        }

        // 3) plan new / corrective submovements
        if (responsive && !moving && now >= nextCorrection) {
            if (err > tol) {
                plan(errYaw, errPitch, width, err > tol * 4.0D);
            } else if ((intent == AimIntent.COMBAT || intent == AimIntent.PRECISE) && err > tol * 0.35D
                    && rnd.nextDouble() < 0.06D) {
                // micro-correction while "on target"
                double k = 0.5D + rnd.nextDouble() * 0.4D;
                startMovement(errYaw * k, errPitch * k, 60.0D + rnd.nextDouble() * 70.0D, 0.0D);
            } else {
                nextCorrection = now + 40.0D + rnd.nextDouble() * 120.0D;
            }
        }

        // 4) tremor, amplitude grows with hand speed
        double inst = Math.sqrt(dYaw * dYaw + dPitch * dPitch) / frameMs();
        handSpeed += (inst - handSpeed) * 0.25D;
        double amp = (0.010D + 0.028D * (1.0D - aim)) * (0.25D + Math.min(1.5D, handSpeed / 0.12D));
        trPhase1 += 2.0D * Math.PI * trFreq1 * frameMs() / 1000.0D;
        trPhase2 += 2.0D * Math.PI * trFreq2 * frameMs() / 1000.0D;
        trPhase3 += 2.0D * Math.PI * (trFreq2 * 1.07D) * frameMs() / 1000.0D;
        trPhase4 += 2.0D * Math.PI * (trFreq1 * 0.93D) * frameMs() / 1000.0D;
        double tX = amp * (Math.sin(trPhase1) + 0.45D * Math.sin(trPhase2));
        double tY = amp * 0.8D * (Math.sin(trPhase3) + 0.45D * Math.sin(trPhase4));
        dYaw += tX - trLastX;
        dPitch += tY - trLastY;
        trLastX = tX;
        trLastY = tY;

        apply(dYaw, dPitch);
    }

    private void plan(double errYaw, double errPitch, double width, boolean primary) {
        double a = Math.sqrt(errYaw * errYaw + errPitch * errPitch);
        double w = Math.max(0.4D, width);
        double id = Math.log(a / w + 1.0D) / Math.log(2.0D);
        double fa = 55.0D + 45.0D * (1.0D - aim);
        double fb = 75.0D + 95.0D * (1.0D - aim);
        double t = (fa + fb * id) * intentSpeed() * Math.exp(rnd.nextGaussian() * 0.12D);
        if (!primary) t *= 0.8D;
        t = Math.max(55.0D, Math.min(1300.0D, t));
        // placements are not read-and-react: the face is already known, so the turn is a direct flick-and-settle.
        // Fitts's law on a 120-degree error against a 0.4-degree face wanted 1.3 s, which is longer than the
        // placer's whole patience. Cap the movement time so the crosshair can actually arrive.
        if (intent == AimIntent.PRECISE) t = Math.min(t, placementCapMs);
        else if (intent == AimIntent.FLICK) t = Math.min(t, flickCapMs);

        double mean, sigma = 0.03D + 0.06D * (1.0D - aim);
        switch (intent) {
            case COMBAT: mean = 0.06D; break;
            case FLICK: mean = 0.11D; break;
            case PRECISE: mean = -0.04D; break;
            case GLANCE: mean = -0.07D; break;
            default: mean = 0.02D; break;
        }
        if (!primary) { mean *= 0.3D; sigma *= 0.5D; }
        double scale = 1.0D + mean + rnd.nextGaussian() * sigma;
        scale = Math.max(0.72D, Math.min(1.35D, scale));

        // skilled players lead a moving target over the movement time (the aim policy scales how much)
        double lead = (mode == Mode.ENTITY) ? (0.2D + 0.6D * aim) * leadBias : 0.0D;
        double gy = errYaw * scale + velYaw * t * lead * (1.0D - pursuitGain());
        double gp = errPitch * scale + velPitch * t * lead * (1.0D - pursuitGain());

        double curv = a * rnd.nextGaussian() * (0.025D + 0.035D * (1.0D - aim));
        startMovement(gy, gp, t, curv);
    }

    private void startMovement(double dy, double dp, double durMs, double curv) {
        double len = Math.sqrt(dy * dy + dp * dp);
        moving = true;
        mvStart = now - frameMs() * 0.5D;
        mvDur = Math.max(frameMs(), durMs);
        mvYaw = dy;
        mvPitch = dp;
        mvCurv = curv;
        if (len > 1.0E-6D) { mvPerpYaw = -dp / len; mvPerpPitch = dy / len; }
        else { mvPerpYaw = 0.0D; mvPerpPitch = 0.0D; }
        mvPrevS = 0.0D;
        mvPrevC = 0.0D;
    }

    /** Converts a desired angular change into integer mouse counts and applies them with vanilla float maths. */
    private void apply(double dYawDeg, double dPitchDeg) {
        accX += dYawDeg / stepDeg;
        accY += -dPitchDeg / stepDeg;
        // a mouse reports a count only once a whole count of travel has accumulated (truncate, keep the remainder)
        int cx = (int) accX;
        int cy = (int) accY;
        accX -= cx;
        accY -= cy;
        if (cx == 0 && cy == 0) return;
        float f2 = (float) cx * mouseFactor;
        float f3 = (float) cy * mouseFactor;
        // Entity.setAngles(f2, f3)
        yaw = (float) ((double) yaw + (double) f2 * 0.15D);
        pitch = (float) ((double) pitch - (double) f3 * 0.15D);
        if (pitch < -90.0F) pitch = -90.0F;
        if (pitch > 90.0F) pitch = 90.0F;
    }

    // ================================================================== percept / aim point
    private double percX, percY, percZ;

    private boolean perceive() {
        if (target == null || target.dead) return false;
        if (hCount == 0) { percX = target.locX; percY = target.locY; percZ = target.locZ; return true; }
        double lag = 45.0D + 85.0D * (1.0D - aim);
        double t = now - lag;
        // newest sample first
        int newest = (hHead + hCount - 1) % HIST;
        if (t >= ht[newest] || hCount == 1) {
            // between newest sample and the live (render-interpolated) position
            double span = 50.0D;
            double k = Math.min(1.0D, Math.max(0.0D, (t - ht[newest]) / span));
            percX = hx[newest] + (target.locX - hx[newest]) * k;
            percY = hy[newest] + (target.locY - hy[newest]) * k;
            percZ = hz[newest] + (target.locZ - hz[newest]) * k;
            return true;
        }
        for (int i = hCount - 1; i > 0; i--) {
            int b = (hHead + i) % HIST, a = (hHead + i - 1) % HIST;
            if (t >= ht[a]) {
                double span = Math.max(1.0D, ht[b] - ht[a]);
                double k = (t - ht[a]) / span;
                percX = hx[a] + (hx[b] - hx[a]) * k;
                percY = hy[a] + (hy[b] - hy[a]) * k;
                percZ = hz[a] + (hz[b] - hz[a]) * k;
                return true;
            }
        }
        int oldest = hHead;
        percX = hx[oldest]; percY = hy[oldest]; percZ = hz[oldest];
        return true;
    }

    private void pickAimPoint(boolean immediate) {
        // chest / neck region by default, shifted by the learned aim profile; worse aimers spread more
        double spreadY = 0.06D + 0.08D * (1.0D - aim);
        aimFracGoal = Math.max(0.18D, Math.min(0.95D, 0.74D + aimBiasFrac + rnd.nextGaussian() * spreadY));
        aimSideGoal = Math.max(-0.24D, Math.min(0.24D, rnd.nextGaussian() * (0.05D + 0.08D * (1.0D - aim))));
        if (immediate) { aimFrac = aimFracGoal; aimSide = aimSideGoal; }
        nextAimPick = now + 400.0D + rnd.nextDouble() * 1100.0D;
    }

    private void driftAimPoint() {
        if (now >= nextAimPick) pickAimPoint(false);
        double k = frameMs() / 300.0D;
        aimFrac += (aimFracGoal - aimFrac) * k;
        aimSide += (aimSideGoal - aimSide) * k;
    }

    // ================================================================== tuning
    private double tolerance(double widthDeg) {
        switch (intent) {
            case GLANCE: return 7.0D;
            case NAVIGATE: return 4.0D;
            case FLICK: return Math.max(2.0D, widthDeg * 0.5D);
            case PRECISE: return Math.max(0.35D, Math.min(1.2D, widthDeg * 0.35D));
            default: return Math.max(0.6D, widthDeg * (0.32D + 0.18D * (1.0D - aim)));
        }
    }

    /** Longest a placement aim is allowed to take (ms). Raised/lowered from tuning; see HumanMouse.placementSpeed. */
    private double placementCapMs = 320.0D;
    private double flickCapMs = 220.0D;

    /** 1.0 = current limit, >1 = faster turns, <1 = slower. */
    public void setPlacementSpeed(double factor) {
        double f = Math.max(0.2D, Math.min(3.0D, factor));
        placementCapMs = 320.0D / f;
        flickCapMs = 220.0D / f;
    }

    private double intentSpeed() {
        switch (intent) {
            case FLICK: return 0.55D;
            case COMBAT: return 0.8D;
            case PRECISE: return 1.15D;
            case GLANCE: return 1.25D;
            default: return 1.0D;
        }
    }

    private double pursuitGain() {
        switch (intent) {
            case COMBAT: return 0.76D + 0.22D * aim; // tracking during fights (tuned to ~45-60% hit rate)
            case FLICK: return 0.75D;
            case PRECISE: return 0.55D + 0.25D * aim;
            case NAVIGATE: return 0.45D;
            default: return 0.0D;
        }
    }

    // ================================================================== maths
    private static double minJerk(double t) {
        return t * t * t * (10.0D - 15.0D * t + 6.0D * t * t);
    }

    public static double yawTo(double fromX, double fromZ, double x, double z) {
        return Math.toDegrees(Math.atan2(z - fromZ, x - fromX)) - 90.0D;
    }

    public static double pitchTo(double fromX, double fromY, double fromZ, double x, double y, double z) {
        double dx = x - fromX, dy = y - fromY, dz = z - fromZ;
        return -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    public static double wrap(double d) {
        d %= 360.0D;
        if (d >= 180.0D) d -= 360.0D;
        if (d < -180.0D) d += 360.0D;
        return d;
    }

    private static double angDist(double y1, double p1, double y2, double p2) {
        double dy = wrap(y1 - y2), dp = p1 - p2;
        return Math.sqrt(dy * dy + dp * dp);
    }

    private static float clampPitch(float p) {
        return p < -90.0F ? -90.0F : (p > 90.0F ? 90.0F : p);
    }

    private static double clampAbs(double v, double m) {
        return v > m ? m : (v < -m ? -m : v);
    }

    private static double clamp01(double v) {
        return v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
    }

    public String debug() {
        return String.format("mode=%s intent=%s yaw=%.2f pitch=%.2f des=(%.2f, %.2f) err=%.2f tol=%.2f moving=%s react=%.0fms gcd=%.4f aimFrac=%.2f",
                mode, intent, yaw, pitch, lastDesiredYaw, lastDesiredPitch, lastErrorDeg, lastToleranceDeg, moving,
                reactionRemainingMs(), stepDeg, aimFrac);
    }
}
