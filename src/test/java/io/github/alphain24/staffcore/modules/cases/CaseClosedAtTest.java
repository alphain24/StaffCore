package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A case is closed when its closing time says so, whoever it is assigned to.
 */
class CaseClosedAtTest {

	@TempDir
	Path world;

	private Storage storage;
	private CaseStore cases;

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

	@Test
	@DisplayName("an unassigned closed case reads as closed, and an assigned open one as open")
	void closedAtFollowsItsOwnColumn() {
		UUID subject = UUID.randomUUID();
		String unassigned = cases.openManually(subject, "Steve_", "Mod", "grief", 40, CaseCategory.GRIEFING);
		cases.setStatus(unassigned, Case.Status.CLEARED, "Mod", "nothing found");
		assertNotNull(cases.byId(unassigned).orElseThrow().closedAt(), "a closed case with nobody assigned read as open");

		String assigned = cases.openManually(subject, "Steve_", "Mod", "x-ray", 40, CaseCategory.CHEATING);
		cases.assign(assigned, "Mod", "Mod");
		assertNull(cases.byId(assigned).orElseThrow().closedAt(), "an open case somebody is assigned read as closed");
	}
}
