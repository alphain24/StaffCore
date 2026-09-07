package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Being on duty survives the server going away and coming back.
 * <p>
 * Somebody will crash while clocked on. Their real inventory is in the stash at that moment,
 * and if duty state lived only in memory the restart would leave them holding the staff
 * toolset with their own items in a table nothing points at any more — recoverable by hand, on
 * a good day, by somebody who knows the schema.
 * <p>
 * A reconnect is not the same test as a restart, and this is the restart: the storage is
 * closed and reopened from the same directory, which is what actually happens.
 */
class StaffModePersistenceTest {

	@TempDir
	Path world;

	private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

	private Storage storage;

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private void restart() {
		if (storage != null) storage.close();
		storage = StaffCore.storage();
		storage.open(world);
	}

	@Test
	@DisplayName("clocked on before a restart is clocked on after it")
	void dutySurvivesARestart() {
		restart();
		StaffCore.state().setStaffMode(STAFF, true, "survival");

		restart();
		var stored = StaffCore.state().loadAll().get(STAFF);

		assertNotNull(stored, "the whole row was lost, so the stash it points at is orphaned");
		assertTrue(stored.staffMode(), "a staff member who crashed on duty came back off duty");
		assertEquals("survival", stored.priorGamemode(),
				"the gamemode to put them back into is the half that cannot be guessed");
	}

	@Test
	@DisplayName("clocking off before a restart stays off")
	void theSameGuaranteeInTheOtherDirection() {
		// The mirror case, and the one a naive fix breaks: a flag that is only ever written
		// when it turns on leaves everybody permanently on duty after their first shift.
		restart();
		StaffCore.state().setStaffMode(STAFF, true, "survival");
		StaffCore.state().setStaffMode(STAFF, false, null);

		restart();
		var stored = StaffCore.state().loadAll().get(STAFF);
		assertFalse(stored != null && stored.staffMode());
	}

	@Test
	@DisplayName("vanish survives too, and separately from duty")
	void vanishIsItsOwnFlag() {
		// Two flags in one row, and conflating them is how somebody comes back invisible
		// because they were on duty, or visible because they clocked off.
		restart();
		StaffCore.state().setVanished(STAFF, true);
		StaffCore.state().setStaffMode(STAFF, false, null);

		restart();
		var stored = StaffCore.state().loadAll().get(STAFF);
		assertNotNull(stored);
		assertTrue(stored.vanished(), "vanish was lost with the restart");
		assertFalse(stored.staffMode(), "duty was set by writing vanish");
	}
}
