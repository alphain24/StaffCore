package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.storage.Storage;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One open case per player per kind, and the evidence filed against them.
 */
class CaseKindsAndEvidenceTest {

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
		StaffConfig.get().caseAutoOpenSeverity = 70;
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private static Signal signal(Signal.Type type, int confidence) {
		return Signal.of(type, SUBJECT, "Steve_", confidence, "detail", "test");
	}

	@Test
	@DisplayName("griefing and x-ray about one player are two cases, each of its kind")
	void kindsAreSeparateCases() {
		String grief = cases.ingest(signal(Signal.Type.MASS_GRIEF, 75)).caseId();
		String xray = cases.ingest(signal(Signal.Type.XRAY, 80)).caseId();

		assertNotEquals(grief, xray, "one case per kind");
		assertEquals(CaseCategory.GRIEFING, cases.byId(grief).orElseThrow().category());
		assertEquals(CaseCategory.CHEATING, cases.byId(xray).orElseThrow().category());
		assertEquals(2, cases.openCasesFor(SUBJECT).size());
	}

	@Test
	@DisplayName("a signal joins the open case of its own kind, never another kind's")
	void signalsJoinOnlyTheirOwnKind() {
		String grief = cases.ingest(signal(Signal.Type.MASS_GRIEF, 75)).caseId();

		CaseStore.Landing weakXray = cases.ingest(signal(Signal.Type.XRAY, 40));
		assertTrue(weakXray.isUnattached(),
				"a weak x-ray signal joined the griefing case, so clearing the griefing would "
						+ "close it and the corpus would count it as an x-ray false positive");

		CaseStore.Landing moreGrief = cases.ingest(signal(Signal.Type.MASS_GRIEF, 30));
		assertEquals(grief, moreGrief.caseId(), "a weak griefing signal still joins its case");
	}

	@Test
	@DisplayName("opening a case by hand for a kind the player already has returns that case")
	void manualOpenReusesTheOpenCase() {
		String grief = cases.ingest(signal(Signal.Type.MASS_GRIEF, 75)).caseId();

		String manual = cases.openManually(SUBJECT, "Steve_", "Mod", "saw it myself", 0,
				CaseCategory.GRIEFING);
		assertEquals(grief, manual);

		String chat = cases.openManually(SUBJECT, "Steve_", "Mod", "abusive in chat", 0,
				CaseCategory.CHAT);
		assertNotEquals(grief, chat);
		assertEquals(CaseCategory.CHAT, cases.byId(chat).orElseThrow().category());
	}

	@Test
	@DisplayName("a case can be moved to another kind, but not onto one already open")
	void recategorising() {
		String report = cases.ingest(Signal.of(Signal.Type.REPORT, SUBJECT, "Steve_", 70,
				"Alex reported: being annoying", "report")).caseId();
		assertEquals(CaseCategory.OTHER, cases.byId(report).orElseThrow().category());

		assertTrue(cases.setCategory(report, CaseCategory.CHAT, "Mod"));
		assertEquals(CaseCategory.CHAT, cases.byId(report).orElseThrow().category());
		assertTrue(cases.eventsFor(report).stream().anyMatch(e -> e.kind().equals("category")));

		cases.ingest(signal(Signal.Type.MASS_GRIEF, 75));
		assertFalse(cases.setCategory(report, CaseCategory.GRIEFING, "Mod"),
				"two live griefing cases for one player would split the evidence between them");
	}

	@Test
	@DisplayName("evidence is filed, read back, and retracted without being deleted")
	void evidenceLifecycle() throws Exception {
		String id = cases.ingest(signal(Signal.Type.MASS_GRIEF, 75)).caseId();
		CaseEvidence evidence = new CaseEvidence();

		long replay = evidence.add(id, CaseEvidence.Draft.replay(SUBJECT, "Steve_",
				"minecraft:overworld", new BlockPos(1, 64, 2), 1_000, 61_000, "the burst"), "Mod");
		long blocks = evidence.add(id, CaseEvidence.Draft.blocks(SUBJECT, "Steve_",
				"minecraft:overworld", new BlockPos(1, 64, 2), 32, 1_000, 300_000, "damage"), "Mod");

		List<CaseEvidence.Item> items = evidence.forCase(id);
		assertEquals(2, items.size());
		CaseEvidence.Item first = items.get(0);
		assertEquals(CaseEvidence.Kind.REPLAY, first.kind());
		assertEquals(new BlockPos(1, 64, 2), first.pos());
		assertEquals(61_000, first.to());
		assertEquals(32, items.get(1).radius());

		assertTrue(evidence.retract(replay, "Mod"));
		assertEquals(List.of(blocks), evidence.forCase(id).stream().map(CaseEvidence.Item::id).toList());

		try (var st = storage.conn().createStatement();
				var rs = st.executeQuery("SELECT COUNT(*) FROM case_evidence")) {
			assertTrue(rs.next());
			assertEquals(2, rs.getInt(1), "retracting deleted the row");
		}
		assertTrue(cases.eventsFor(id).stream().anyMatch(e -> e.kind().equals("evidence-retracted")));
	}

	@Test
	@DisplayName("a detector's evidence is filed when its signal lands in a case, and only then")
	void emittedEvidenceFollowsTheCase() {
		CaseModule module = new CaseModule();
		List<CaseEvidence.Draft> drafts = List.of(CaseEvidence.Draft.replay(SUBJECT, "Steve_",
				"minecraft:overworld", null, 0, 60_000, "window"));

		CaseStore.Landing weak = module.emit(null, signal(Signal.Type.MASS_GRIEF, 30), drafts);
		assertTrue(weak.isUnattached());

		CaseStore.Landing strong = module.emit(null, signal(Signal.Type.MASS_GRIEF, 75), drafts);
		assertEquals(1, module.evidence().forCase(strong.caseId()).size(),
				"the case opened without the evidence the detector handed over");
		assertEquals(Case.SYSTEM, module.evidence().forCase(strong.caseId()).get(0).addedBy());
	}
}
