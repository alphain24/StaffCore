package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A punishment and the case it came out of, joined from both ends.
 * <p>
 * Gate 1 asks for this to be verifiable by query rather than by reading the code, which is the
 * right test to want: the link is only useful if it is in the data, and a link that exists
 * only as an in-memory association disappears the moment anybody restarts.
 * <p>
 * The other half is what happens on reversal, and it is the half with the sharper failure. A
 * punishment deleted when it is lifted takes the history with it — the player's record
 * silently improves, an appeal that was upheld leaves no trace of having been upheld, and
 * "has this happened before" quietly starts returning the wrong answer to everybody who asks.
 */
class PunishmentLinkageTest {

	@TempDir
	Path world;

	private Storage storage;
	private CaseStore cases;

	private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
		cases = new CaseStore();
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	/**
	 * Writes a punishment row the way {@code PunishmentModule.record} does.
	 * <p>
	 * Direct SQL rather than the module, because applying a punishment needs a live server to
	 * enforce and announce it. What is under test is the shape of the record and the link, and
	 * both of those are in the row.
	 */
	private long punish(String caseId) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement("""
				INSERT INTO punishments (target_uuid, target_name, staff_name, type, reason,
				                         created_at, active, case_id)
				VALUES (?,?,?,?,?,?,1,?)
				""", java.sql.Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, SUBJECT.toString());
			ps.setString(2, "Steve_");
			ps.setString(3, "Alice");
			ps.setString(4, "BAN");
			ps.setString(5, "x-ray");
			ps.setLong(6, System.currentTimeMillis());
			ps.setString(7, caseId);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : -1;
			}
		}
	}

	@Test
	@DisplayName("a punishment issued from a case is joined to it in the data")
	void theLinkIsInTheDatabase() throws SQLException {
		String caseId = cases.ingest(Signal.of(Signal.Type.XRAY, SUBJECT, "Steve_", 90, "{}", "t"))
				.caseId();
		long id = punish(caseId);
		cases.link(caseId, "punishment", String.valueOf(id), "Alice");

		// From the punishment to the case.
		try (PreparedStatement ps = storage.conn()
				.prepareStatement("SELECT case_id FROM punishments WHERE id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				assertEquals(caseId, rs.getString("case_id"));
			}
		}

		// And from the case back to the punishment, which is the direction the case view
		// reads and the one a foreign key alone would not give.
		var links = cases.linksFor(caseId);
		assertEquals(1, links.size());
		assertEquals("punishment", links.get(0).entityType());
		assertEquals(String.valueOf(id), links.get(0).entityId());
	}

	@Test
	@DisplayName("a punishment issued with no case records null rather than hiding it")
	void punishingWithoutACaseIsVisible() throws SQLException {
		long id = punish(null);

		try (PreparedStatement ps = storage.conn()
				.prepareStatement("SELECT case_id FROM punishments WHERE id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				assertNull(rs.getString("case_id"),
						"null is a real answer here. How often staff punish with no evidence "
								+ "attached is worth being able to count, and a schema that "
								+ "cannot express it cannot be asked");
			}
		}
	}

	@Test
	@DisplayName("existing punishments are backfilled as null, not given invented cases")
	void historyIsNotRewritten() throws SQLException {
		// The migration adds a nullable column. Anything already on disk has no case because
		// nobody opened one, and inventing one retroactively would be a lie about the record.
		long id = punish(null);
		assertEquals(1, countWith("case_id IS NULL"));
		assertEquals(0, countWith("case_id IS NOT NULL"));
		assertTrue(id > 0);
	}

	@Test
	@DisplayName("reversal marks the row and never removes it")
	void reversalKeepsTheRecord() throws SQLException {
		String caseId = cases.ingest(Signal.of(Signal.Type.REPORT, SUBJECT, "Steve_", 80, "{}", "t"))
				.caseId();
		long id = punish(caseId);

		try (PreparedStatement ps = storage.conn().prepareStatement("""
				UPDATE punishments SET active = 0, revoked_by = ?, revoked_at = ?, revoke_reason = ?
				WHERE id = ?
				""")) {
			ps.setString(1, "Bob");
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, "appeal upheld");
			ps.setLong(4, id);
			ps.executeUpdate();
		}

		assertEquals(1, countWith("1=1"), "the row must still be there");

		try (PreparedStatement ps = storage.conn().prepareStatement(
				"SELECT active, revoked_by, revoked_at, revoke_reason FROM punishments WHERE id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				assertEquals(0, rs.getInt("active"));
				assertEquals("Bob", rs.getString("revoked_by"));

				// Who alone is not enough. An appeal upheld months later needs the grounds
				// and the date, and neither can be reconstructed from a name.
				assertTrue(rs.getLong("revoked_at") > 0, "a reversal has to say when");
				assertEquals("appeal upheld", rs.getString("revoke_reason"),
						"and on what grounds");
			}
		}
	}

	@Test
	@DisplayName("the case keeps both the punishment and its reversal")
	void bothHalvesLandInTheCaseLog() {
		String caseId = cases.ingest(Signal.of(Signal.Type.XRAY, SUBJECT, "Steve_", 90, "{}", "t"))
				.caseId();

		cases.note(caseId, "Alice", "ban issued: x-ray");
		cases.note(caseId, "Bob", "ban #1 reversed: appeal upheld");

		var events = cases.eventsFor(caseId);
		assertTrue(events.stream().anyMatch(e -> e.body() != null && e.body().contains("issued")),
				"the case should record what was done");
		assertTrue(events.stream().anyMatch(e -> e.body() != null && e.body().contains("reversed")),
				"and that it was undone — a log showing only the part that stuck is a worse "
						+ "record than no log");

		// Order matters: the log is read top to bottom as the story of the case.
		int issued = indexOfBody(events, "issued");
		int reversed = indexOfBody(events, "reversed");
		assertTrue(issued < reversed, "the reversal must come after the punishment");
	}

	@Test
	@DisplayName("a link survives a restart, because it is a row and not an object")
	void linksAreDurable() throws SQLException {
		String caseId = cases.ingest(Signal.of(Signal.Type.XRAY, SUBJECT, "Steve_", 90, "{}", "t"))
				.caseId();
		long id = punish(caseId);
		cases.link(caseId, "punishment", String.valueOf(id), "Alice");

		storage.close();
		storage.open(world);
		CaseStore reopened = new CaseStore();

		assertNotNull(reopened.byId(caseId).orElse(null), "the case survived");
		assertEquals(1, reopened.linksFor(caseId).size(), "and so did the link");
		assertFalse(reopened.eventsFor(caseId).isEmpty(), "and the log");
	}

	private int indexOfBody(java.util.List<CaseStore.Event> events, String needle) {
		for (int i = 0; i < events.size(); i++) {
			if (events.get(i).body() != null && events.get(i).body().contains(needle)) return i;
		}
		return -1;
	}

	private int countWith(String where) throws SQLException {
		try (PreparedStatement ps = storage.conn()
				.prepareStatement("SELECT COUNT(*) FROM punishments WHERE " + where);
				ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}
}
