package io.github.alphain24.staffcore.modules.punish;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A warning ladder that counts, forgets, and suggests.
 * <p>
 * Warnings without arithmetic are a filing cabinet: staff either remember somebody has been
 * warned four times or they do not, and on a busy server they do not. Points make the fourth
 * warning visibly different from the first without anybody having to read a history.
 *
 * <h2>Points decay, and that is not softness</h2>
 * A player warned three times in a week is a different person from one warned three times
 * across two years, and a ladder that cannot tell them apart eventually bans somebody for
 * having been around a long time. Decay is what makes the total mean "recent conduct" rather
 * than "lifetime total", which is the only thing worth escalating on.
 *
 * <h2>Nothing here punishes anybody</h2>
 * Crossing a threshold produces a <b>suggestion</b> that a staff member confirms. That is the
 * hard rule, and it is not timidity: an automatic ladder is a rule somebody will learn to
 * game, it fires on a count rather than on a judgement, and the one case where it is most
 * likely to be wrong — a player being warned repeatedly by one staff member with a grudge —
 * is exactly the case where a human in the loop is the only safeguard there is.
 * <p>
 * So this computes and recommends. {@code PunishmentModule} is still the only thing that acts.
 */
public final class WarningPoints {
	private WarningPoints() {}

	/** What the ladder thinks, and what it wants a human to decide. */
	public record Standing(int points, int warnings, int threshold, PunishmentType suggested,
			String reason) {

		/** True when the total has crossed the line and something is being recommended. */
		public boolean escalates() {
			return suggested != null;
		}
	}

	/**
	 * Live points for a player: warnings inside the decay window, weighted.
	 * <p>
	 * Computed rather than stored. A running total in a column has to be adjusted every time
	 * a warning ages out, which means either a job that sweeps every player or a number that
	 * is quietly wrong between sweeps — and a wrong number here escalates somebody.
	 */
	public static Standing standingOf(UUID target) {
		StaffConfig cfg = StaffConfig.get();
		if (!StaffCore.storage().isReady()) {
			return new Standing(0, 0, cfg.warnEscalationPoints, null, null);
		}

		long since = cfg.warnDecayDays <= 0
				? 0
				: System.currentTimeMillis() - cfg.warnDecayDays * 86_400_000L;

		int points = 0;
		int warnings = 0;

		// Reversed warnings do not count. A warning somebody withdrew is one that should not
		// push the next one over a line, and leaving it in the total would make reversing it
		// meaningless.
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT COUNT(*) AS n, COALESCE(SUM(points), 0) AS total
				FROM punishments
				WHERE target_uuid = ? AND type = 'WARN' AND created_at >= ? AND active = 1
				""")) {
			ps.setString(1, target.toString());
			ps.setLong(2, since);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					warnings = rs.getInt("n");
					points = rs.getInt("total");
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] could not read warning points", e);
			return new Standing(0, 0, cfg.warnEscalationPoints, null, null);
		}

		// A warning written before points existed counts as the default rather than as zero,
		// so upgrading does not silently wipe everybody's standing.
		if (points == 0 && warnings > 0) points = warnings * cfg.warnPointsDefault;

		int threshold = cfg.warnEscalationPoints;
		if (threshold <= 0 || points < threshold) {
			return new Standing(points, warnings, threshold, null, null);
		}

		PunishmentType suggested = suggestionFor(points, threshold);
		return new Standing(points, warnings, threshold, suggested,
				"%d point(s) from %d warning(s) in the last %s"
						.formatted(points, warnings, window(cfg)));
	}

	/**
	 * What to suggest at this total.
	 * <p>
	 * Deliberately shallow: a mute at the threshold, a temporary ban at twice it. Anything
	 * more elaborate is a ladder pretending to a precision it does not have, and every rung
	 * is another place for the suggestion to be confidently wrong.
	 */
	private static PunishmentType suggestionFor(int points, int threshold) {
		return points >= threshold * 2 ? PunishmentType.TEMPBAN : PunishmentType.MUTE;
	}

	private static String window(StaffConfig cfg) {
		return cfg.warnDecayDays <= 0 ? "all time" : cfg.warnDecayDays + " days";
	}

	/** How many points a warning is worth, unless the caller says otherwise. */
	public static int defaultPoints() {
		return Math.max(0, StaffConfig.get().warnPointsDefault);
	}
}
