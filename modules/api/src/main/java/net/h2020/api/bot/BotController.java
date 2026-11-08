package net.h2020.api.bot;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/**
 * The bot's "keyboard and mouse". Everything here results in the exact packets a vanilla 1.8.9 client would send,
 * produced by a client-side physics simulation each tick (C03/C04/C05/C06, C0A swing, C02 use-entity, C08 place,
 * C07 dig, C09 held slot, C0E window click, C0D close, C0B entity action for sprint/sneak).
 * Inputs persist until changed. Call from the main thread only.
 */
public interface BotController {
    // ---- movement keys ----
    /** forward: -1 (S) .. 1 (W); strafe: -1 (D) .. 1 (A) (vanilla sign convention). */
    void setMovement(float forward, float strafe);
    void setJumping(boolean jump);
    void setSneaking(boolean sneak);
    void setSprinting(boolean sprint);

    // ---- mouse ----
    /** Smoothly move the crosshair toward a point using the human aim model (reaction delay, minimum-jerk, overshoot,
     *  micro-corrections, GCD-quantized deltas). Keeps tracking until another aim call or clearAim(). */
    void aimAt(Location point, AimIntent intent);
    /** Track an entity (aims at a skill-dependent point on its hitbox, leads moving targets imperfectly). */
    void aimAt(Entity entity, AimIntent intent);
    void aimRotation(float yaw, float pitch, AimIntent intent);
    void clearAim();
    /** True once the current aim target is within tolerance of the crosshair. */
    boolean aimSettled();
    float yaw();
    float pitch();
    /**
     * The aim point profile the fight models asked for: a vertical bias on the hitbox and a multiplier on how far
     * ahead of a moving target to aim. The human aim model still does the mouse work.
     */
    void setAimPolicy(double fracBias, double leadScale);
    /** Swings (arm animations) the client has sent this session - the denominator of the hit rate. */
    int swings();
    /** Ray-traced block/entity under the crosshair within reach (null if none) — as the client's objectMouseOver. */
    Entity targetedEntity();
    Block targetedBlock();
    BlockFace targetedFace();

    // ---- clicks ----
    /** Left click this tick: swings, and attacks targetedEntity() if any (client-side reach 3.0), else starts digging. */
    void leftClick();
    /** Hold left mouse (continuous digging of targetedBlock / auto-swing at given cps when fighting). */
    void setLeftHeld(boolean held, double cps);
    /** Right click this tick: place block against targetedBlock/face if holding a block, use item otherwise. */
    void rightClick();

    /** Swing the arm without attacking: the animation a fake-out is made of. */
    void swing();
    void setRightHeld(boolean held);
    void selectHotbar(int slot);
    int heldSlot();

    // ---- inventory GUI ----
    /** Right-click the container at block (must be targeted/in reach). */
    void openContainer(Block block);
    boolean containerOpen();
    /** C0E click with the given mode (0 = click, 1 = shift-click, 2 = number key). Human pacing is the caller's job. */
    void clickWindow(int rawSlot, int button, int mode);
    void closeWindow();
    void dropHeld(boolean wholeStack);

    // ---- misc ----
    void chat(String message);
    /** Run a command (/l, /play ...) like a client. */
    void command(String commandLine);

    // ---- client-side state (what the bot's client would know) ----
    boolean onGround();
    boolean collidedHorizontally();
    Vector velocity();
    Location position();
    /** Server sent a velocity (knockback) for us this tick. */
    boolean tookKnockback();
}
