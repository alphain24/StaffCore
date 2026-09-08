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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a signal lands, and — more importantly — where it does not.
 * <p>
 * These rules are entirely about noise. A tool that opens a case for every observation trains
 * staff to close cases without reading them, at which point the case model is worse than the
 * scrolling alert channel it replaced. The negative cases below are therefore the ones that
 * matter most: a weak signal must be <em>kept and silent</em>, which is a harder property than
 * either "alert" or "discard" and the one a later change is most likely to break by accident.
 */
class CaseAttachmentTest {

	@TempDir
	Path world;

	private Storage storage;
	private CaseStore cases;

	private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
		cases = new CaseStore();
		StaffConfig.get().caseAutoOpenSeverity = 70;
		StaffConfig.get().caseStaleDays = 14;
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private Signal signal(int confidence) {
		return signal(confidence, SUBJECT);
	}

	private Signal signal(int confidence, UUID subject) {
		return Signal.of(Signal.Type.XRAY, subject, "Steve_", confidence, "{}", "test");
	}

	private int rows(String table) throws SQLException {
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}

	// ------------------------------------------------------------ the quiet path

	@Test
	@DisplayName("a below-threshold signal opens nothing and is still kept")
	void weakSignalsAreKeptAndSilent() throws SQLException {
		CaseStore.Landing landing = cases.ingest(signal(40));

		assertTrue(landing.isUnattached(), "a 40 should not have opened a case");
		assertFalse(landing.openedCase());
		assertEquals(0, rows("cases"), "no case should exist");

		// The half that is easy to get wrong in the other direction. Discarding it would be
		// quieter still and would throw away the four weak things that turn out to be the
		// answer when somebody looks the player up.
		assertEquals(1, rows("signals"), "the signal itself must be kept");
		assertEquals(1, cases.unattachedFor(SUBJECT, 20).size(),
				"and must be findable against the player it was about");
	}

	@Test
	@DisplayName("weak signals stay silent however many arrive")
	void weakSignalsDoNotAccumulateIntoACase() throws SQLException {
		for (int i = 0; i < 10; i++) cases.ingest(signal(30));

		// Deliberate: ten weak signals do not add up to a strong one. Making them do so would
		// be a different design and would need its own threshold, decay and tests — and
		// getting it wrong means a case opened on ten pieces of nothing.
		assertEquals(0, rows("cases"), "weak signals should not accumulate into a case");
		assertEquals(10, cases.unattachedFor(SUBJECT, 50).size(), "all ten are still there");
	}

	// ------------------------------------------------------------- opening a case

	@Test
	@DisplayName("a signal at or above the threshold opens a case, attributed to the system")
	void strongSignalsOpenACase() {
		CaseStore.Landing landing = cases.ingest(signal(70));

		assertTrue(landing.openedCase(), "70 is the threshold, so 70 should open one");
		assertNotNull(landing.caseId());

		Case opened = cases.byId(landing.caseId()).orElseThrow();
		assertEquals(Case.Status.OPEN, opened.status());
		assertEquals(Case.SYSTEM, opened.openedBy(),
				"a case nobody opened has to say so, or it looks like a staff decision");
		assertEquals(70, opened.severity());
		assertEquals(SUBJECT, opened.subjectId());
	}

	@Test
	@DisplayName("the case id is one somebody could read aloud")
	void theIdIsUsable() {
		String id = cases.ingest(signal(90)).caseId();

		assertEquals(CaseId.LENGTH, id.length());
		assertEquals(id, CaseId.normalise(id));
		assertTrue(cases.byId(id.toLowerCase(java.util.Locale.ROOT)).isPresent(),
				"a lowercase id typed from a screenshot should still find the case");
	}

	// ------------------------------------------------------- joining an open case

	@Test
	@DisplayName("any signal joins a case that is already open, however weak")
	void weakSignalsJoinAnOpenCase() {
		String id = cases.ingest(signal(80)).caseId();

		CaseStore.Landing weak = cases.ingest(signal(10));
		assertEquals(id, weak.caseId(),
				"three weak things about one player is the shape of a real problem, and "
						+ "attaching them is the whole reason a case beats an alert channel");
		assertFalse(weak.openedCase(), "it joined rather than opened");
		assertEquals(2, cases.signalsFor(id).size());
	}

	@Test
	@DisplayName("a case takes the severity of the strongest thing in it")
	void severityRises() {
		String id = cases.ingest(signal(70)).caseId();
		cases.ingest(signal(95));
		cases.ingest(signal(20));

		assertEquals(95, cases.byId(id).orElseThrow().severity(),
				"the list sorts by severity, so a case that later collects stronger evidence "
						+ "has to rise rather than sink under whatever arrived first");
	}

	@Test
	@DisplayName("signals about different players do not share a case")
	void casesDoNotBleedBetweenSubjects() {
		String mine = cases.ingest(signal(80, SUBJECT)).caseId();
		String theirs = cases.ingest(signal(80, OTHER)).caseId();

		assertFalse(mine.equals(theirs), "two players, two cases");
		assertEquals(1, cases.signalsFor(mine).size());
	}

	@Test
	@DisplayName("a closed case does not collect new signals")
	void closedCasesDoNotReopenThemselves() {
		String id = cases.ingest(signal(80)).caseId();
		cases.setStatus(id, Case.Status.CLEARED, "Staff", "looked, nothing in it");

		// Somebody decided this was nothing. A later weak signal quietly reattaching to the
		// closed case would bury it where nobody is looking; a strong one opens a fresh case,
		// which is a new decision rather than a reversal of theirs.
		CaseStore.Landing later = cases.ingest(signal(30));
		assertTrue(later.isUnattached(), "a weak signal should not rejoin a cleared case");

		String fresh = cases.ingest(signal(90)).caseId();
		assertFalse(id.equals(fresh), "a strong signal opens a new case rather than reopening");
	}

	// -------------------------------------------------------------------- staleness

	@Test
	@DisplayName("a quiet case goes stale and is never deleted")
	void staleCasesSurvive() throws SQLException {
		String id = cases.ingest(signal(80)).caseId();
		ageEverything(id, 30);

		assertEquals(1, cases.markStale(14));

		Case stale = cases.byId(id).orElseThrow();
		assertEquals(Case.Status.STALE, stale.status());
		assertEquals(1, rows("cases"), "stale is a status, never a deletion");
		assertEquals(1, cases.signalsFor(id).size(), "and its evidence stays attached");

		assertTrue(cases.eventsFor(id).stream().anyMatch(e -> e.kind().equals("stale")),
				"a case cannot become stale without the log saying when");
	}

	@Test
	@DisplayName("recent staff activity keeps a case alive even with no new signals")
	void staffActivityCountsAsActivity() throws SQLException {
		String id = cases.ingest(signal(80)).caseId();
		ageEverything(id, 30);

		// A staff member working a case all week is activity. Ageing on opened_at instead of
		// the last event would have marked this one stale while somebody was reading it.
		cases.note(id, "Staff", "still looking into this");

		assertEquals(0, cases.markStale(14), "the note should have reset the clock");
		assertEquals(Case.Status.OPEN, cases.byId(id).orElseThrow().status());
	}

	@Test
	@DisplayName("0 days disables staleness, like every other retention knob")
	void zeroDisables() throws SQLException {
		String id = cases.ingest(signal(80)).caseId();
		ageEverything(id, 3650);

		assertEquals(0, cases.markStale(0));
		assertEquals(Case.Status.OPEN, cases.byId(id).orElseThrow().status());
	}

	// ------------------------------------------------------------------ the record

	@Test
	@DisplayName("the event log is append-only and records who did what")
	void everythingLeavesAnEvent() {
		String id = cases.ingest(signal(80)).caseId();
		cases.assign(id, "Alice", "Alice");
		cases.note(id, "Alice", "checked the tunnel");
		cases.setStatus(id, Case.Status.ACTIONED, "Alice", "banned");

		List<CaseStore.Event> events = cases.eventsFor(id);
		assertTrue(events.size() >= 4, "expected open, assign, note and status; got " + events.size());

		assertEquals("opened", events.get(0).kind());
		assertEquals(Case.SYSTEM, events.get(0).actor());
		assertTrue(events.stream().anyMatch(e -> e.kind().equals("assigned")));
		assertTrue(events.stream().anyMatch(e -> e.kind().equals("actioned")
				&& "Alice".equals(e.actor())));
	}

	@Test
	@DisplayName("links point at things by type and id, and cannot be duplicated")
	void linksAreIdempotent() {
		String id = cases.ingest(signal(80)).caseId();

		cases.link(id, "punishment", "42", "Alice");
		cases.link(id, "punishment", "42", "Alice");

		assertEquals(1, cases.linksFor(id).size(),
				"linking the same thing twice should not produce two links");
		assertEquals("punishment", cases.linksFor(id).get(0).entityType());
	}

	@Test
	@DisplayName("every case and signal records what was running when it was written")
	void versionsAreRecorded() {
		String id = cases.ingest(signal(80)).caseId();
		Case opened = cases.byId(id).orElseThrow();

		// Detection thresholds move and mixins stop applying across Minecraft updates. A row
		// written under a build where a hook was silently broken means something different
		// from one written under a healthy build, and only this says which.
		assertNotNull(opened.modVersion());
		assertNotNull(opened.serverVersion());
		assertFalse(opened.modVersion().isBlank());
	}

	@Test
	@DisplayName("an unknown case id is refused rather than guessed at")
	void unknownIdsAreEmpty() {
		assertTrue(cases.byId("ZZZZZZZZ").isEmpty());
		assertTrue(cases.byId("not an id").isEmpty());
		assertTrue(cases.byId(null).isEmpty());
	}

	@Test
	@DisplayName("nothing is written when storage is closed, and nothing throws")
	void storageBeingDownIsSurvivable() {
		storage.close();

		// A detector emitting a signal must never take down whatever it was watching. This is
		// the path that runs on a join, a block break and an anti-cheat callback.
		CaseStore.Landing landing = cases.ingest(signal(90));
		assertTrue(landing.isUnattached());
		assertNull(landing.caseId());
		assertTrue(cases.list(null, null, 0, 10).isEmpty());
	}

	/** Pushes a case and its whole history into the past. */
	private void ageEverything(String caseId, int days) throws SQLException {
		long at = System.currentTimeMillis() - days * 86_400_000L;
		try (PreparedStatement ps = storage.conn()
				.prepareStatement("UPDATE cases SET opened_at = ? WHERE id = ?")) {
			ps.setLong(1, at);
			ps.setString(2, caseId);
			ps.executeUpdate();
		}
		try (PreparedStatement ps = storage.conn()
				.prepareStatement("UPDATE case_events SET at = ? WHERE case_id = ?")) {
			ps.setLong(1, at);
			ps.setString(2, caseId);
			ps.executeUpdate();
		}
	}
}
