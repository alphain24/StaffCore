package io.github.alphain24.staffcore.security;

import io.github.alphain24.staffcore.modules.security.Excavation;
import io.github.alphain24.staffcore.modules.security.Hypergeometric;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores whole populations of miners at candidate thresholds, so a threshold can be chosen
 * from what it would do rather than from how often it speaks.
 * <p>
 * The last time these numbers moved, the reason recorded was that the detector was too quiet.
 * That reason cannot be wrong, which is the problem with it: any bar can be lowered until the
 * feature talks, and nothing in the repository could say who it would start talking about.
 * This answers that question directly — for each candidate pair, how many distinct players
 * get alerted on, and how many get a quiet notice.
 * <p>
 * Two sources of sessions. {@link #fromPatterns} scores the generated set in
 * {@link MiningPatterns}, which runs everywhere including CI and has known right answers.
 * {@link #fromDatabase} scores a real server's {@code block_log}, which is the only way to
 * see the shape of an actual player base — run it against a copy of a live database:
 *
 * <pre>
 * ./gradlew test --tests '*XrayThresholdTest' -Dstaffcore.replay.db=/path/to/staffcore.db
 * </pre>
 *
 * Test scope on purpose. It is a measuring instrument for whoever is choosing the numbers, not
 * a feature, and nothing about it belongs in a shipped jar.
 */
final class XrayReplay {
	private XrayReplay() {}

	/**
	 * One player's mining over the window being scored, and the ore density it happened in.
	 * <p>
	 * The density is the half a p-value needs and a weighted score did not: what the player
	 * took only means something against what there was to take. A generated session states
	 * the number it was built with; a real one has to be given an assumed density, and the
	 * grid below is run across several so a threshold is not chosen against one guess.
	 */
	record Session(String player, List<Excavation.Dig> breaks, double oreFraction) {}

	/**
	 * What one candidate pair would have done to a population.
	 *
	 * @param scored  players with enough blocks to be judged at all
	 * @param alerted players staff would have been alerted about
	 * @param noticed players who would have drawn a quiet notice instead
	 */
	record Cell(int minimumVolume, int alertConfidence, int noticeConfidence,
			int players, int scored, int alerted, int noticed) {

		double alertRate() {
			return players == 0 ? 0 : alerted / (double) players;
		}
	}

	/** Every candidate pair, scored against the same population. */
	static List<Cell> sweep(List<Session> sessions, int[] volumes, int[] alertLevels,
			int noticeConfidence) {

		List<Cell> grid = new ArrayList<>();

		for (int volume : volumes) {
			// Scoring is the expensive half and does not depend on the alert level, so it
			// happens once per volume gate and the levels are counted off the same numbers.
			List<Integer> confidences = new ArrayList<>();

			for (Session session : sessions) {
				int worst = 0;
				boolean scored = false;

				// Per segment, exactly as production does. A session is scored by its worst
				// stretch rather than its average: an hour of honest tunnelling with twenty
				// guided minutes in the middle averages out to nothing, and the twenty
				// minutes are the whole point.
				for (Excavation.Segment segment : Excavation.segment(session.breaks())) {
					if (segment.population() < volume) continue;
					scored = true;

					int ores = segment.found() + (int) Math.round(
							Math.max(0, segment.population() - segment.drawn())
									* session.oreFraction());
					double p = Hypergeometric.atLeast(segment.population(), ores,
							segment.drawn(), segment.found());
					worst = Math.max(worst, Hypergeometric.confidence(p));
				}
				if (scored) confidences.add(worst);
			}

			for (int alert : alertLevels) {
				int alerted = 0;
				int noticed = 0;
				for (int confidence : confidences) {
					if (confidence >= alert) alerted++;
					else if (noticeConfidence > 0 && confidence >= noticeConfidence) noticed++;
				}
				grid.add(new Cell(volume, alert, noticeConfidence,
						sessions.size(), confidences.size(), alerted, noticed));
			}
		}
		return grid;
	}

	/** The generated set, one session per pattern. */
	static List<Session> fromPatterns() {
		List<Session> out = new ArrayList<>();
		for (MiningPatterns.Pattern pattern : MiningPatterns.all()) {
			out.add(new Session(pattern.name(), pattern.breaks(), pattern.oreFraction()));
		}
		return out;
	}

	/**
	 * Real mining, read straight out of a server's database.
	 * <p>
	 * Deliberately a plain JDBC read rather than going through {@code Storage}: this opens
	 * somebody's production database, and it should not be able to migrate it, write to it, or
	 * take a lock on it. Read-only, and it never writes the file back.
	 */
	static List<Session> fromDatabase(String path, long windowMs, long now, double oreFraction)
			throws SQLException {

		Map<String, List<Excavation.Dig>> byPlayer = new LinkedHashMap<>();

		String url = "jdbc:sqlite:file:" + path.replace('\\', '/') + "?mode=ro";
		try (Connection conn = DriverManager.getConnection(url)) {
			// Same shape as the detector's own read, including the exclusion of ore the
			// player placed themselves — scoring on a different query than production uses
			// would measure something nobody is going to ship.
			String sql = """
					SELECT b.player_name, b.block, b.world, b.x, b.y, b.z, b.created_at
					FROM block_log b
					WHERE b.action = 'BREAK' AND b.created_at >= ?
					  AND b.player_name NOT LIKE '#%'
					  AND NOT EXISTS (
					      SELECT 1 FROM block_log p
					      WHERE p.action = 'PLACE' AND p.player_name = b.player_name
					        AND p.block = b.block AND p.world = b.world
					        AND p.x = b.x AND p.y = b.y AND p.z = b.z
					        AND p.created_at <= b.created_at AND p.created_at >= ?
					  )
					ORDER BY b.player_name ASC, b.created_at ASC
					""";
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setLong(1, now - windowMs);
				ps.setLong(2, now - windowMs);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						byPlayer.computeIfAbsent(rs.getString("player_name"), k -> new ArrayList<>())
								.add(new Excavation.Dig(rs.getString("block"),
										rs.getString("world"), rs.getInt("x"), rs.getInt("y"),
										rs.getInt("z"), rs.getLong("created_at")));
					}
				}
			}
		}

		List<Session> out = new ArrayList<>();
		byPlayer.forEach((player, breaks) -> out.add(new Session(player, breaks, oreFraction)));
		return out;
	}

	/** The grid as a table, for pasting into the decision record. */
	static String table(List<Cell> grid) {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("%-8s %-8s %-8s %-8s %-8s%n",
				"floor", "alert", "scored", "alerted", "noticed"));
		for (Cell cell : grid) {
			sb.append(String.format("%-8d %-8d %-8d %-8d %-8d%n",
					cell.minimumVolume(), cell.alertConfidence(),
					cell.scored(), cell.alerted(), cell.noticed()));
		}
		return sb.toString();
	}
}
