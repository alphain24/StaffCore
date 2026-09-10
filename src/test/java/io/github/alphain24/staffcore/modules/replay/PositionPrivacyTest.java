package io.github.alphain24.staffcore.modules.replay;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Position history is treated as personal data, in both directions.
 *
 * <h2>What is being protected against</h2>
 * {@code /staff export} exists so a server owner can answer a data request, hand a spreadsheet
 * to somebody auditing them, or look at their own numbers. It writes every table it finds,
 * which is the right default and is exactly how addresses ended up in a CSV nobody meant to
 * produce — they were not chosen, they were simply in the same database.
 * <p>
 * Position history is the second thing with that property and it is worse: an address says
 * where somebody connected from, and a position log says everywhere they went. So it is
 * withheld from an ordinary export and released only when somebody asks for it by name.
 *
 * <h2>The retention half</h2>
 * A run is a chain of deltas that can only be read from its start, so retention deletes whole
 * runs rather than old rows. Deleting by row would leave chains that begin in the middle,
 * which reconstruct into a plausible path through the wrong part of the world — and samples
 * whose run row is gone are bytes nothing can ever read or delete again.
 */
class PositionPrivacyTest {

	@TempDir
	Path world;

	private Storage storage;
	private final UUID subject = UUID.nameUUIDFromBytes("privacy-subject".getBytes());

	private static final long T0 = 1_700_000_000_000L;

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
		PositionSampler.forgetAll();
		StaffConfig.get().positionTracking = true;
	}

	@AfterEach
	void close() {
		PositionSampler.forgetAll();
		StaffConfig.get().positionTracking = false;
		if (storage != null) storage.close();
	}

	/** Lays down a short walk starting at the given time. */
	private void walk(long startedAt, double fromX) {
		for (int i = 0; i <= 5; i++) {
			PositionSampler.record(subject, "Subject", "minecraft:overworld",
					(int) Math.round((fromX + i) * PositionLog.SCALE), 64 * PositionLog.SCALE,
					200 * PositionLog.SCALE, 0, 0, startedAt + i * 500L);
		}
		PositionSampler.write();
		PositionSampler.forgetAll();
	}

	/** Something in the database that is a record of conduct rather than of a person. */
	private void aPunishment() {
		try (Statement st = storage.conn().createStatement()) {
			st.executeUpdate("INSERT INTO punishments (target_uuid, target_name, type, reason, "
					+ "created_at, active) VALUES ('" + subject + "', 'Subject', 'WARN', "
					+ "'for the control', " + T0 + ", 1)");
		} catch (SQLException e) {
			throw new AssertionError("could not write the control row", e);
		}
	}

	private List<String> exported(Path folder, String table) throws IOException {
		Path file = folder.resolve(table + ".csv");
		assertTrue(Files.exists(file), table + ".csv was not written at all. A missing file "
				+ "and a withheld one look the same to whoever opens the folder.");
		return Files.readAllLines(file, StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("an ordinary export withholds the position rows but keeps the file")
	void positionsAreWithheldByDefault() throws IOException {
		walk(T0, 100);
		aPunishment();

		Path folder = storage.export(false);
		assertTrue(folder != null, "the export produced nothing");

		List<String> samples = exported(folder, "position_log");
		assertEquals(1, samples.size(),
				"an ordinary export wrote " + (samples.size() - 1) + " position sample(s). "
						+ "That is every route the tracked players walked, released to whoever "
						+ "opens the file, because it happened to share a database with the "
						+ "punishment history somebody actually asked for.");
		assertTrue(samples.getFirst().contains("run"),
				"the header line is missing, so the file cannot be told from an empty table");

		List<String> runs = exported(folder, "position_run");
		assertEquals(1, runs.size(), "position_run rows were exported — they carry the player's "
				+ "UUID, name and starting coordinates");
	}

	@Test
	@DisplayName("the same export still writes the tables it is actually for")
	void theControl() throws IOException {
		// Without this, an export that failed and wrote nothing but headers everywhere would
		// pass the test above while being completely broken. The point is that position
		// history is withheld, not that the export is empty.
		walk(T0, 100);
		aPunishment();

		Path folder = storage.export(false);
		List<String> punishments = exported(folder, "punishments");

		assertTrue(punishments.size() > 1,
				"the export wrote no punishment rows either, so the assertion that position "
						+ "rows were withheld cannot distinguish a privacy rule from an export "
						+ "that does not work");
	}

	@Test
	@DisplayName("asking for it by name releases it")
	void personalExportIncludesPositions() throws IOException {
		walk(T0, 100);

		Path folder = storage.export(true);
		List<String> samples = exported(folder, "position_log");

		assertTrue(samples.size() > 1,
				"/staff export personal withheld the position history too, which makes it "
						+ "impossible to answer a data request about somebody's own movement");
	}

	@Test
	@DisplayName("retention deletes whole runs, never half a chain")
	void purgeTakesWholeRuns() {
		long old = T0 - 30L * 86_400_000L;
		walk(old, 100);
		walk(T0, 500);

		int deleted = PositionLog.purge(T0 - 7L * 86_400_000L);
		assertEquals(1, deleted, "expected exactly the run outside the retention window to go");

		// Nothing left pointing at a run that no longer exists. Those rows are unreachable —
		// no query finds them and no later purge deletes them, because every purge works from
		// the run table.
		assertEquals(0, count("SELECT COUNT(*) FROM position_log WHERE run NOT IN "
						+ "(SELECT id FROM position_run)"),
				"samples were left behind with no run row. They can never be read and never "
						+ "be deleted again.");

		PositionLog.Track kept = PositionLog.reconstruct(subject, "Subject", T0 - 1000, T0 + 60_000);
		assertFalse(kept.isEmpty(), "the run inside the window was purged as well");
		assertEquals(500, kept.first().x(), 1.0 / PositionLog.SCALE,
				"the surviving run reconstructs to the wrong place");
	}

	@Test
	@DisplayName("a purge with nothing to delete deletes nothing")
	void theControlForPurge() {
		// The other direction, and the one that would be catastrophic rather than untidy.
		walk(T0, 100);

		assertEquals(0, PositionLog.purge(T0 - 30L * 86_400_000L),
				"a cutoff a month before the only run still deleted it");
		assertTrue(count("SELECT COUNT(*) FROM position_log") > 0,
				"the samples went even though the run survived");
	}

	private long count(String sql) {
		try (Statement st = storage.conn().createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : -1;
		} catch (SQLException e) {
			throw new AssertionError(sql, e);
		}
	}
}
