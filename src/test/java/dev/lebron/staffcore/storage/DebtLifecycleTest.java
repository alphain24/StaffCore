package dev.lebron.staffcore.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When an owed item stops being owed.
 * <p>
 * A debit is not a punishment. It exists for one reason: a rollback puts a wall back up, so
 * whoever knocked it down must not still be holding the blocks, or the repair has printed
 * items. Everything about how long a debt lives follows from that, and none of it followed
 * from it before — a debt was raised once and then stood forever, announced on every login,
 * surviving even the undo of the rollback that caused it.
 * <p>
 * Two rules make it coherent, and both are behaviour rather than plumbing, so they are pinned
 * here rather than left to read correctly.
 */
class DebtLifecycleTest {

	@TempDir
	Path world;

	private Storage storage;

	/**
	 * Opens the real singleton, not a private instance.
	 * <p>
	 * {@link PendingActions} reads {@code StaffCore.storage()} rather than being handed a
	 * connection, so a test holding its own {@code Storage} watches every call return zero
	 * against a database nothing is looking at. Worth knowing before writing the next one.
	 */
	private Storage open() {
		storage = dev.lebron.staffcore.StaffCore.storage();
		storage.open(world);
		return storage;
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	/** Inserts a queued row directly, so the test does not need a server to raise one. */
	private void queue(String kind, String refKind, Long refId, int count, long createdAt)
			throws SQLException {

		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO pending_actions "
						+ "(uuid, owner_name, kind, item, count, ref_id, ref_kind, reason, queued_by, created_at) "
						+ "VALUES ('u','Alt',?,'minecraft:stone',?,?,?,'test','system',?)")) {
			ps.setString(1, kind);
			ps.setInt(2, count);
			if (refId == null) ps.setNull(3, java.sql.Types.INTEGER);
			else ps.setLong(3, refId);
			ps.setString(4, refKind);
			ps.setLong(5, createdAt);
			ps.executeUpdate();
		}
	}

	private int rows() throws SQLException {
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM pending_actions")) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}

	@Test
	@DisplayName("undoing a rollback cancels what it left owing")
	void undoCancelsItsOwnDebt() throws SQLException {
		open();
		long now = System.currentTimeMillis();

		queue("DEBIT", "ROLLBACK", 42L, 100, now);
		queue("DEBIT", "ROLLBACK", 99L, 50, now);   // a different rollback
		queue("DEBIT", null, null, 7, now);         // raised by something else

		int cancelled = dev.lebron.staffcore.StaffCore.pending().cancelFor("ROLLBACK", 42L);

		assertEquals(100, cancelled, "the whole of that rollback's debt");
		assertEquals(2, rows(), "and nothing belonging to anything else");
	}

	@Test
	@DisplayName("a debt older than the window is written off, a delivery never is")
	void debtsExpireButDeliveriesDoNot() throws SQLException {
		open();
		long now = System.currentTimeMillis();
		long old = now - 30L * 86_400_000L;

		queue("DEBIT", null, null, 500, old);   // long unpaid
		queue("DEBIT", null, null, 20, now);    // raised today
		queue("GIVE", null, null, 64, old);     // somebody's property, waiting

		int written = dev.lebron.staffcore.StaffCore.pending().expireOldDebts(7);

		assertEquals(500, written, "only the debt past the window");
		assertEquals(2, rows());

		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT COUNT(*) FROM pending_actions WHERE kind = 'GIVE'")) {
			assertTrue(rs.next() && rs.getInt(1) == 1,
					"an item waiting to be handed back must never expire — losing it would be "
							+ "theft rather than mercy");
		}
	}

	@Test
	@DisplayName("expiry is opt-out, not silent")
	void zeroKeepsEverything() throws SQLException {
		open();
		queue("DEBIT", null, null, 500, System.currentTimeMillis() - 365L * 86_400_000L);

		assertEquals(0, dev.lebron.staffcore.StaffCore.pending().expireOldDebts(0), "0 days means keep forever");
		assertEquals(1, rows());
	}
}
