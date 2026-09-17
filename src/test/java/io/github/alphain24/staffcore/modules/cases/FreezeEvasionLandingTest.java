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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaving while frozen goes on top of the case the player already has, whatever kind it is, and staff are
 * told out loud either way.
 */
class FreezeEvasionLandingTest {

	@TempDir
	Path world;

	private Storage storage;
	private CaseStore cases;

	private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

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

	private static Signal evasion() {
		return Signal.of(Signal.Type.FREEZE_EVASION, SUBJECT, "Steve_", 90, "Steve_ left the server while frozen",
				"freeze");
	}

	@Test
	@DisplayName("it joins the newest open case of any kind, not a case of its own")
	void joinsTheCaseTheyHave() throws InterruptedException {
		String griefing = cases.openManually(SUBJECT, "Steve_", "Mod", "griefed spawn", 40, CaseCategory.GRIEFING);
		Thread.sleep(5);
		String cheating = cases.openManually(SUBJECT, "Steve_", "Mod", "x-ray", 40, CaseCategory.CHEATING);

		CaseStore.Landing landing = cases.ingest(evasion());
		assertEquals(cheating, landing.caseId(), "it did not join the newest open case");
		assertFalse(landing.openedCase());
		assertEquals(2, cases.openCasesFor(SUBJECT).size(), "a case of its own was opened as well");
		assertTrue(cases.signalsFor(griefing).isEmpty());

		CaseModule.Announcement said = CaseModule.decide(landing);
		assertTrue(said.loud(), "joining a case was said quietly, and staff need to act on this now");
		assertTrue(said.message().contains(cheating), said.message());
	}

	@Test
	@DisplayName("with nothing open, it opens a case, loudly")
	void opensOneWhenThereIsNone() {
		CaseStore.Landing landing = cases.ingest(evasion());
		assertTrue(landing.openedCase());
		assertEquals(CaseCategory.OTHER, cases.openCasesFor(SUBJECT).get(0).category());
		assertTrue(CaseModule.decide(landing).loud());
	}

	@Test
	@DisplayName("other kinds still join only their own kind of case")
	void othersAreUnchanged() {
		String griefing = cases.openManually(SUBJECT, "Steve_", "Mod", "griefed spawn", 40, CaseCategory.GRIEFING);
		CaseStore.Landing landing = cases.ingest(Signal.of(Signal.Type.XRAY, SUBJECT, "Steve_", 40, "{}", "test"));
		assertFalse(griefing.equals(landing.caseId()), "an x-ray signal joined a griefing case");
		assertFalse(Signal.Type.XRAY.alwaysLoud());
	}
}
