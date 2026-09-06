package dev.lebron.staffcore.modules.security;

/**
 * The x-ray detector's tuning, in one place, with the measurement that chose it.
 * <p>
 * These were literals in {@code StaffConfig} field initialisers, and the numbers in the
 * README, the handbook and the config reference drifted apart from them and from each other:
 * the sample floor was documented as 500 in one section and 200 in another, while the code
 * used its own answer. Nobody could say what the detector would do without reading the source.
 * Naming them makes the config defaults the single source of truth, and lets
 * {@code ConfigDocsTest} fail when the documentation stops agreeing with them.
 * <p>
 * <b>How these were chosen.</b> The previous values were lowered on the grounds that the
 * detector was too quiet. That is a visibility argument, not an accuracy one — any bar can be
 * lowered until the feature speaks — and nothing measured what it cost. Measuring it showed
 * the cost was severe: at the old settings the worst honest mining pattern scored 66 against
 * an alert line of 55, so the detector was alerting on ordinary strip mining and on anybody
 * hunting diamond at y=-54.
 * <p>
 * These come from a grid over 200 generated honest sessions and 40 guided ones
 * ({@code XrayThresholdTest}, and {@code docs/decisions.md} for the run). They separate the
 * two populations completely: the worst honest session scores 50, the best-hidden guided one
 * 81, and everything here sits in that gap.
 */
public final class XrayTuning {
	private XrayTuning() {}

	/**
	 * Ore-plus-filler blocks a player must have broken before the sweep will score them.
	 * <p>
	 * This knob runs the opposite way to intuition and is the one worth being careful with.
	 * Raising it does not make the detector safer — it makes it blind, and specifically blind
	 * to the person it exists for. Guided mining is <em>efficient</em>: it breaks far less
	 * cover to reach far more ore, so those sessions are the small ones. In the measured set
	 * the guided sessions ran 328–450 blocks while every honest pattern ran 525–1020, and at a
	 * floor of 400 the detector missed all forty cheats while keeping all two hundred honest
	 * players in scope. That is worse than switching it off.
	 * <p>
	 * 200 leaves room below the smallest guided session and is still enough that a handful of
	 * lucky finds cannot carry a score on their own.
	 */
	public static final int SAMPLE_FLOOR = 200;

	/**
	 * Confidence at which staff are alerted automatically.
	 * <p>
	 * The midpoint of the measured gap between the two populations — 15 points above the worst
	 * honest session and 16 below the best-hidden guided one. Chosen from that separation
	 * rather than from how often it makes the feature talk.
	 */
	public static final int ALERT_CONFIDENCE = 65;

	/**
	 * Confidence at which staff get a quiet heads-up instead of an alert. 0 disables.
	 * <p>
	 * Set just above the worst honest score, so in the measured set an honest player does not
	 * draw even a notice. It exists because silence and absence look identical from outside: a
	 * server owner otherwise has no way to tell "nobody is cheating" from "this has never run".
	 */
	public static final int NOTICE_CONFIDENCE = 55;

	/**
	 * Ore-to-total fraction above which the blunt ratio signal starts scoring.
	 * <p>
	 * Raised from 0.04, which was the single largest source of false positives: honest
	 * deepslate mining at y=-54 genuinely returns 13% ore, and at 0.04 that alone scored the
	 * full 40 points. The ground being rich is not evidence about the player.
	 */
	public static final double RATIO_THRESHOLD = 0.12D;

	/** Mean filler blocks between veins below which mining starts to look guided. */
	public static final double DIRECTNESS_FLOOR = 12.0D;

	/** How often the background sweep scores active miners, in minutes. 0 disables. */
	public static final int SWEEP_MINUTES = 5;
}
