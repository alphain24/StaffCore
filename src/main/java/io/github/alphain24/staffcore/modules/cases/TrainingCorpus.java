package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The cases a human actually formed an opinion about, for validating a threshold against.
 *
 * <h2>What this is for</h2>
 * The tuning mistake this exists to prevent has a written history in this repository: the last
 * time the x-ray thresholds moved, the recorded reason was that the detector was too quiet.
 * That reason cannot be wrong. Any bar can be lowered until a feature speaks, and nothing said
 * who it would start speaking about.
 * <p>
 * A resolved case answers that. Every cleared case is a player the detector flagged and a staff
 * member then decided was fine; every actioned one is a player the detector flagged and a staff
 * member agreed about. Those are labels, and a threshold can be measured against them instead
 * of against its own volume.
 *
 * <h2>Why the composition is reported and not just the size</h2>
 * "Validated against 209 resolved cases" and "validated against 9 real clears and 200 timeouts"
 * describe the same query and completely different amounts of evidence. Only one of them is
 * worth acting on, and the first is what a bare count looks like.
 * <p>
 * So nothing here returns a number on its own. {@link Composition} carries what was counted and
 * what was left out, and every place that quotes the corpus quotes both.
 *
 * <h2>What is excluded, and why exclusion is not the same as ignoring</h2>
 * Only {@link Resolution#INVESTIGATED_INNOCENT} counts as a negative. A case that went stale,
 * was closed without being looked at, was a duplicate, or ended because the player left is
 * still a case and still readable — it is simply not a vote about whether the detector was
 * right, because nobody cast one.
 * <p>
 * Cases closed before the reason column existed are excluded too. Backfilling them as
 * investigated would invent a judgement nobody made, and each invented one would be a vote that
 * the detector was wrong.
 */
public final class TrainingCorpus {
	private TrainingCorpus() {}

	/** One labelled case: what the detector said, and what a human decided. */
	public record Labelled(String caseId, java.util.UUID subject, String subjectName,
			int severity, boolean cheating, long closedAt) {}

	/**
	 * What the corpus is made of.
	 *
	 * @param negatives  cleared after somebody investigated and found nothing
	 * @param positives  actioned — the detector was right
	 * @param excluded   resolved, but in a way that is not a judgement, by reason
	 * @param unlabelled closed before the reason column existed
	 */
	public record Composition(int negatives, int positives, Map<Resolution, Integer> excluded,
			int unlabelled) {

		/** Cases that actually say something about whether the detector was right. */
		public int usable() {
			return negatives + positives;
		}

		public int excludedTotal() {
			int total = unlabelled;
			for (int count : excluded.values()) total += count;
			return total;
		}

		/**
		 * Whether there is enough here to justify moving a threshold.
		 * <p>
		 * Thirty is not a statistical result, it is a floor below which the honest answer is
		 * "not yet". A threshold moved on the strength of four cleared cases is a threshold
		 * moved on an anecdote, and it will be defended afterwards with a number.
		 */
		public boolean isEnoughToTuneAgainst() {
			return usable() >= 30;
		}

		/** One line, and it always names both halves. */
		public String describe() {
			if (usable() == 0) {
				return "No usable cases yet — " + excludedTotal() + " resolved, none of them a "
						+ "recorded judgement about whether the detector was right.";
			}
			return usable() + " usable case(s): " + positives + " actioned, " + negatives
					+ " investigated and cleared. " + excludedTotal() + " excluded ("
					+ describeExclusions() + ").";
		}

		private String describeExclusions() {
			List<String> parts = new ArrayList<>();
			if (unlabelled > 0) parts.add(unlabelled + " closed before reasons were recorded");

			for (Map.Entry<Resolution, Integer> entry : excluded.entrySet()) {
				if (entry.getValue() > 0) {
					parts.add(entry.getValue() + " " + entry.getKey().stored());
				}
			}
			return parts.isEmpty() ? "none" : String.join(", ", parts);
		}
	}

	/**
	 * Everything resolved, sorted into what it can and cannot tell you.
	 * <p>
	 * One pass over the closed cases. This is read when somebody is choosing a threshold, which
	 * is rare and deliberate, so it is a plain query rather than anything incremental.
	 */
	public static Composition composition() {
		Map<Resolution, Integer> excluded = new EnumMap<>(Resolution.class);
		int negatives = 0;
		int positives = 0;
		int unlabelled = 0;

		if (!StaffCore.storage().isReady()) {
			return new Composition(0, 0, excluded, 0);
		}

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT status, resolution_reason FROM cases WHERE status IN ('cleared', 'actioned', 'stale')");
				ResultSet rs = ps.executeQuery()) {

			while (rs.next()) {
				Case.Status status = Case.Status.of(rs.getString("status"));
				Resolution reason = Resolution.of(rs.getString("resolution_reason"));

				// An actioned case is a positive whatever reason was given: somebody punished
				// a player over it, which is as clear a statement that the detector was right
				// as this system can produce.
				if (status == Case.Status.ACTIONED) {
					positives++;
					continue;
				}
				if (reason == null) {
					unlabelled++;
					continue;
				}
				if (reason.countsAsNegative()) negatives++;
				else excluded.merge(reason, 1, Integer::sum);
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Corpus] could not read the resolved cases", e);
		}
		return new Composition(negatives, positives, excluded, unlabelled);
	}

	/**
	 * The labelled cases themselves, for a validation run.
	 * <p>
	 * Only the usable ones. Everything the composition counts as excluded is absent here rather
	 * than present with a flag, because a caller that has to remember to filter is a caller
	 * that will eventually forget — and forgetting means two hundred timeouts arriving as two
	 * hundred players the detector was wrong about.
	 */
	public static List<Labelled> labelled() {
		List<Labelled> out = new ArrayList<>();
		if (!StaffCore.storage().isReady()) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT id, subject_uuid, subject_name, severity, status, resolution_reason,
				       closed_at
				FROM cases
				WHERE (status = 'actioned')
				   OR (status = 'cleared' AND resolution_reason = ?)
				ORDER BY closed_at
				""")) {
			ps.setString(1, Resolution.INVESTIGATED_INNOCENT.stored());

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					try {
						out.add(new Labelled(rs.getString("id"),
								java.util.UUID.fromString(rs.getString("subject_uuid")),
								rs.getString("subject_name"), rs.getInt("severity"),
								Case.Status.ACTIONED == Case.Status.of(rs.getString("status")),
								rs.getLong("closed_at")));
					} catch (IllegalArgumentException malformed) {
						// One unreadable row costs that row, not the validation run.
					}
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Corpus] could not read the labelled cases", e);
		}
		return out;
	}
}
