package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cases outlive the server, which is the only reason they are worth having.
 * <p>
 * The whole argument for a case over an alert is that it still exists tomorrow. An
 * investigation that evaporates on restart is a scrolling channel with extra steps, and the
 * failure would be invisible in development — a test that opens a case and reads it back in
 * the same process passes whether or not anything was ever written to disk.
 * <p>
 * So this closes the connection and reopens it, which is what a restart is from the database's
 * point of view: it re-runs the schema, re-reads {@code user_version}, and hands back whatever
 * genuinely landed on the file.
 */
class CaseDurabilityTest {

	@TempDir
	Path world;

	private Storage storage;
	private CaseStore cases;

	private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
		cases = new CaseStore();
		StaffConfig.get().caseAutoOpenSeverity = 70;
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	/** What a restart looks like from here: same file, new connection, new store. */
	private CaseStore restart() {
		storage.close();
		storage = StaffCore.storage();
		storage.open(world);
		cases = new CaseStore();
		return cases;
	}

	private Signal signal(int confidence) {
		return Signal.of(Signal.Type.XRAY, SUBJECT, "Steve_", confidence, "{\"ore\":12}", "test");
	}

	@Test
	@DisplayName("a case opened by a signal is still there after a restart")
	void autoOpenedCasesSurvive() {
		String id = cases.ingest(signal(90)).caseId();

		CaseStore after = restart();
		Case reopened = after.byId(id).orElseThrow(
				() -> new AssertionError("the case did not survive a restart, so it was never "
						+ "an investigation — only a message"));

		assertEquals(Case.Status.OPEN, reopened.status());
		assertEquals(Case.SYSTEM, reopened.openedBy());
		assertEquals(90, reopened.severity());
		assertEquals(SUBJECT, reopened.subjectId());
		assertEquals(1, after.signalsFor(id).size(), "and its evidence came back with it");
	}

	@Test
	@DisplayName("a signal after a restart joins the case that was already open")
	void attachmentWorksAcrossARestart() {
		String id = cases.ingest(signal(90)).caseId();

		// The attachment rule reads the database rather than anything held in memory, so a
		// signal arriving after a restart has to find the same case. If it did not, every
		// restart would silently start a fresh investigation into the same player and the
		// evidence would be split across two.
		CaseStore after = restart();
		CaseStore.Landing later = after.ingest(signal(20));

		assertEquals(id, later.caseId(), "a weak signal should have joined the open case");
		assertFalse(later.openedCase());
		assertEquals(2, after.signalsFor(id).size());
	}

	@Test
	@DisplayName("a stale transition survives a restart")
	void staleTransitionsSurvive() throws SQLException {
		String id = cases.ingest(signal(90)).caseId();
		age(id, 30);

		assertEquals(1, cases.markStale(14));
		CaseStore after = restart();

		assertEquals(Case.Status.STALE, after.byId(id).orElseThrow().status());
		assertTrue(after.eventsFor(id).stream().anyMatch(e -> e.kind().equals("stale")),
				"the log should still say when and why it went quiet");
	}

	@Test
	@DisplayName("staleness is decided from stored history, not from a timer that restarted")
	void stalenessIsRecomputedFromTheData() throws SQLException {
		String id = cases.ingest(signal(90)).caseId();
		age(id, 30);

		// Nothing in memory knows how old this case is. A restart has to reach the same
		// conclusion from the rows alone, or a server that restarts nightly never ages
		// anything out.
		CaseStore after = restart();
		assertEquals(1, after.markStale(14),
				"a fresh process should still be able to tell this case has gone quiet");
		assertEquals(Case.Status.STALE, after.byId(id).orElseThrow().status());
	}

	@Test
	@DisplayName("a case is never deleted, whatever happens to it")
	void nothingIsEverRemoved() throws SQLException {
		String id = cases.ingest(signal(90)).caseId();
		cases.note(id, "Alice", "looked into it");
		cases.setStatus(id, Case.Status.CLEARED, "Alice", "nothing in it");
		age(id, 400);
		cases.markStale(14);

		CaseStore after = restart();

		// Cleared, ancient, and stale-swept: still one row, still readable, still carrying
		// its evidence. This is the property everything downstream leans on — appeals cite
		// cases, replay opens from them, and accountability audits actions taken on them.
		assertTrue(after.byId(id).isPresent(), "the case is gone");
		assertEquals(1, after.signalsFor(id).size(), "its evidence is gone");
		assertTrue(after.eventsFor(id).size() >= 3, "its history is gone");
	}

	@Test
	@DisplayName("ids stay unique across restarts")
	void idsDoNotCollideAcrossRestarts() {
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int round = 0; round < 5; round++) {
			for (int i = 0; i < 20; i++) {
				String id = cases.openManually(UUID.randomUUID(), "Someone", "Alice", "test", 10);
				assertTrue(seen.add(id), "id " + id + " was issued twice");
			}
			restart();
		}
		assertEquals(100, seen.size());
	}

	private void age(String caseId, int days) throws SQLException {
		long at = System.currentTimeMillis() - days * 86_400_000L;
		for (String sql : new String[] {
				"UPDATE cases SET opened_at = ? WHERE id = ?",
				"UPDATE case_events SET at = ? WHERE case_id = ?" }) {
			try (PreparedStatement ps = storage.conn().prepareStatement(sql)) {
				ps.setLong(1, at);
				ps.setString(2, caseId);
				ps.executeUpdate();
			}
		}
	}
}
