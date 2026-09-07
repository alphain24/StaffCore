package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A process that opens the database, starts writing, and is killed mid-write.
 * <p>
 * Run in a forked JVM by {@link CrashConsistencyTest}. {@code Runtime.halt} is used rather
 * than {@code exit} deliberately: {@code exit} runs shutdown hooks and gives SQLite a chance
 * to tidy up, which is the opposite of what needs testing. {@code halt} stops the JVM where it
 * stands with no hooks, no finalizers and no flush — the closest a Java process can get to
 * being killed with SIGKILL, and close enough that what survives is what the write-ahead log
 * and the transaction actually guaranteed rather than what a clean shutdown papered over.
 * <p>
 * Each scenario writes the first half of a multi-statement change and then dies. The parent
 * process reopens the file and asks whether it can see half of one.
 */
public final class CrashVictim {
	private CrashVictim() {}

	/** How hard to die. Not a status anybody reads — the parent expects the process to vanish. */
	private static final int KILLED = 137;

	public static void main(String[] args) throws Exception {
		String scenario = args[0];
		Path world = Path.of(args[1]);

		Storage storage = StaffCore.storage();
		storage.open(world);
		if (!storage.isReady()) {
			System.err.println("victim: storage did not open");
			Runtime.getRuntime().halt(2);
		}

		switch (scenario) {
			case "snapshot" -> snapshotHalfDeleted(storage);
			case "rollback" -> rollbackHalfApplied(storage);
			case "stash" -> stashHalfHandedBack(storage);
			case "seed" -> {
				seed(storage);
				storage.close();
				return;   // a clean exit: this run is setting the stage, not testing it
			}
			default -> {
				System.err.println("victim: unknown scenario " + scenario);
				Runtime.getRuntime().halt(3);
			}
		}
		Runtime.getRuntime().halt(KILLED);
	}

	/** Puts the rows in place that each scenario then tries to change. */
	private static void seed(Storage storage) throws SQLException {
		try (Statement st = storage.conn().createStatement()) {
			st.executeUpdate("INSERT INTO snapshots (id, uuid, label, taken_by, stacks, taken_at, kind) "
					+ "VALUES (1, 'victim', 'before', 'staff', 20, 1000, 'EVIDENCE')");
			for (int i = 0; i < 20; i++) {
				st.executeUpdate("INSERT INTO snapshot_items (snapshot_id, slot, item) "
						+ "VALUES (1, " + i + ", '{id:\"minecraft:diamond\",count:1}')");
			}

			st.executeUpdate("INSERT INTO rollback_point (id, staff_name, world, x, y, z, radius, "
					+ "window_ms, created_at, changes) "
					+ "VALUES (1, 'staff', 'minecraft:overworld', 0, 64, 0, 8, 60000, 1000, 0)");

			st.executeUpdate("INSERT INTO stash (uuid, slot, item, stashed_at) "
					+ "VALUES ('victim', 0, '{id:\"minecraft:diamond\",count:64}', 1000)");
			st.executeUpdate("INSERT INTO stash (uuid, slot, item, stashed_at) "
					+ "VALUES ('victim', 1, '{id:\"minecraft:emerald\",count:32}', 1000)");
		}
	}

	/**
	 * Deletes a snapshot's items and dies before deleting the snapshot itself.
	 * <p>
	 * Inside a transaction, so the correct outcome is that neither delete survives. Without
	 * one, the item rows would be gone and the snapshot would still be listed — a restore
	 * offering to put back an inventory that no longer exists.
	 */
	private static void snapshotHalfDeleted(Storage storage) {
		storage.inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
					"DELETE FROM snapshot_items WHERE snapshot_id = 1")) {
				ps.executeUpdate();
			}
			// The second delete never happens: the process dies here, inside the transaction.
			Runtime.getRuntime().halt(KILLED);
		});
	}

	/**
	 * Writes half a rollback's restore point and dies.
	 * <p>
	 * A rollback that recorded some of what it changed is worse than one that recorded none:
	 * the undo would put back part of the area and report success.
	 */
	private static void rollbackHalfApplied(Storage storage) {
		storage.inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO rollback_change (point_id, world, x, y, z, prior_block, prior_state) "
							+ "VALUES (1, 'minecraft:overworld', ?, 64, 0, 'minecraft:stone', NULL)")) {
				for (int x = 0; x < 50; x++) {
					ps.setInt(1, x);
					ps.executeUpdate();
				}
			}
			Runtime.getRuntime().halt(KILLED);
		});
	}

	/**
	 * Dies between handing a stash back and clearing it.
	 * <p>
	 * Unlike the other two this is <em>not</em> one transaction, and it should not be: the
	 * items go into a player's inventory in memory, which no database transaction can roll
	 * back. What the ordering buys is that the surviving failure is a duplicate hand-back
	 * rather than a loss — the stash is cleared only after the items have gone in, so a crash
	 * in the gap leaves it intact for the next attempt.
	 */
	private static void stashHalfHandedBack(Storage storage) {
		// The items have notionally just been written into the player's inventory. The clear
		// is the next statement, and it never runs.
		Runtime.getRuntime().halt(KILLED);
	}
}
