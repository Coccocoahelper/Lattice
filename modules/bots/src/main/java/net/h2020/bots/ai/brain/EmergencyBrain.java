package net.h2020.bots.ai.brain;

/**
 * A brain that wants the combat stack's emergency layer even though it is not a game brain.
 *
 * <p>The fireball guard, the clutch and the calm heal run from {@code Combat.emergencyTick()}, which used to be gated
 * on {@code GameBrain}: a practice drill's brain is not one, so a block-clutch drill bot never once tried to save
 * itself despite holding sixty-four blocks. Implementing this marker is how a drill opts in.
 */
public interface EmergencyBrain { }
