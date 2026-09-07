package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives when the server is killed halfway through a write.
 * <p>
 * The README claims multi-statement work is atomic and that a crash mid-restore leaves the
 * stash intact for another attempt. Both are claims about a failure that had never been
 * induced, and "we wrapped it in a transaction" is a description of the code rather than
 * evidence about the outcome — WAL mode, synchronous settings and the driver's own buffering
 * all sit between the two.
 * <p>
 * So this kills a real process. A forked JVM opens the database, writes the first half of a
 * change, and calls {@code Runtime.halt}, which stops the JVM where it stands with no shutdown
 * hooks, no finalizers and no flush. That is as close to SIGKILL as a Java process can get to
 * itself, and close enough that what is left on disk is what the write-ahead log actually
 * guaranteed rather than what a tidy shutdown papered over.
 * <p>
 * Then this process reopens the file and asks whether it can see half a change.
 */
class CrashConsistencyTest {

	@TempDir
	Path world;

	private Storage storage;

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	/**
	 * Runs {@link CrashVictim} in its own JVM and waits for it to die.
	 *
	 * @return true when the child ran; false when this environment cannot fork one
	 */
	private boolean runVictim(String scenario) throws IOException, InterruptedException {
		String java = ProcessHandle.current().info().command().orElse(null);
		if (java == null) return false;

		List<String> command = new ArrayList<>(List.of(
				java, "-cp", System.getProperty("java.class.path"),
				CrashVictim.class.getName(), scenario, world.toString()));

		Process victim = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();
		String output = new String(victim.getInputStream().readAllBytes());

		assertTrue(victim.waitFor(120, TimeUnit.SECONDS),
				"the victim process did not exit; it should have killed itself");

		// Checked, and not merely for tidiness. Without this a child that fell over before
		// writing anything looks exactly like a child that was killed mid-write, and every
		// assertion below would pass or fail against an empty database for reasons nothing
		// reported. That is the failure mode these tests exist to rule out, so it would be a
		// poor one to build them on.
		int expected = "seed".equals(scenario) ? 0 : KILLED;
		assertEquals(expected, victim.exitValue(),
				"the victim exited with " + victim.exitValue() + " rather than " + expected
						+ ", so it did not do what this test assumes. Output:\n" + output);
		return true;
	}

	/** The exit code {@link CrashVictim} halts with. Not a status anybody reads — a marker. */
	private static final int KILLED = 137;

	/** Reopens the database the way a server would on the next boot. */
	private Storage reboot() {
		storage = StaffCore.storage();
		storage.open(world);
		assertTrue(storage.isReady(),
				"the database would not reopen after a crash, which is the one outcome that "
						+ "is worse than losing the write");
		return storage;
	}

	private int count(String sql) throws SQLException {
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}

	/** Sets the stage in a child process, so the parent has never held this file open. */
	private boolean seed() throws IOException, InterruptedException {
		return runVictim("seed");
	}

	@Test
	@DisplayName("a snapshot delete killed halfway leaves the snapshot whole")
	void snapshotDeleteIsAllOrNothing() throws Exception {
		if (!seed()) return;
		if (!runVictim("snapshot")) return;

		reboot();

		// The items were deleted first and the snapshot row second. Without a transaction the
		// file would now hold a snapshot listing an inventory that no longer exists, and the
		// restore screen would offer to put back nothing at all.
		int items = count("SELECT COUNT(*) FROM snapshot_items WHERE snapshot_id = 1");
		int snapshots = count("SELECT COUNT(*) FROM snapshots WHERE id = 1");

		assertNotEquals(0, items,
				"the item rows were deleted and the snapshot survived: a crash mid-delete left "
						+ "a snapshot that restores an empty inventory");
		assertEquals(20, items, "the delete was partially applied");
		assertEquals(1, snapshots, "the snapshot itself should still be there");
	}

	@Test
	@DisplayName("a rollback restore point killed halfway leaves no half-written undo")
	void rollbackPointIsAllOrNothing() throws Exception {
		if (!seed()) return;
		if (!runVictim("rollback")) return;

		reboot();

		// Fifty changes were written and the transaction never committed. A partial restore
		// point is worse than none: undoing it would put back part of an area and report that
		// it had finished.
		assertEquals(0, count("SELECT COUNT(*) FROM rollback_change WHERE point_id = 1"),
				"a half-written restore point survived the crash, so its undo would restore "
						+ "part of the area and call that success");
	}

	@Test
	@DisplayName("a stash killed mid-handback is kept, so the failure is a duplicate not a loss")
	void theStashSurvivesACrashMidRestore() throws Exception {
		if (!seed()) return;
		if (!runVictim("stash")) return;

		reboot();

		// This one is deliberately not transactional, and could not be: the items go into a
		// player's inventory in memory, which no database transaction can roll back. What the
		// ordering buys is which way the failure falls. Clearing the stash first would make a
		// crash destroy somebody's whole inventory; clearing it last makes the worst case a
		// second hand-back of items they may already have.
		//
		// That is the right trade, and it is the one the README claims. Duplicating items is
		// a problem an admin can see and fix. Losing an inventory to a power cut is not.
		assertEquals(2, count("SELECT COUNT(*) FROM stash WHERE uuid = 'victim'"),
				"the stash was cleared before the hand-back completed, so a crash in the gap "
						+ "destroys the player's inventory instead of offering it again");
	}

	@Test
	@DisplayName("the database still opens cleanly after being killed mid-write")
	void theFileIsStillUsable() throws Exception {
		if (!seed()) return;
		if (!runVictim("rollback")) return;

		reboot();

		// Storage.open runs PRAGMA integrity_check and quarantines a file that fails it. If a
		// crash mid-transaction could produce that, every one of these tests would be
		// measuring a recovered empty database rather than a survived one.
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
			assertTrue(rs.next());
			assertEquals("ok", rs.getString(1),
					"the database was damaged by a crash mid-write");
		}
		assertEquals(1, count("SELECT COUNT(*) FROM snapshots WHERE id = 1"),
				"rows written before the crash should still be there — this is a crash, not "
						+ "a rollback of everything");
	}
}
