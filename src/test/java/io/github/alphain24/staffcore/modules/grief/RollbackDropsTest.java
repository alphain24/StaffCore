package io.github.alphain24.staffcore.modules.grief;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a rollback may bill somebody for the block it puts back.
 */
class RollbackDropsTest {

	@Test
	@DisplayName("a blast with drop decay is not billed, whatever else the row says")
	void decayIsNotBilled() {
		// Explosion rows carry no gamemode, and a null gamemode reads as survival. This is the
		// case that used to bill a player for every block a crystal took.
		assertFalse(GriefModule.droppedAnything(0, null));
	}

	@Test
	@DisplayName("a blast that drops everything is billed, like a block broken by hand")
	void fullDropsAreBilled() {
		assertTrue(GriefModule.droppedAnything(1, null));
	}

	@Test
	@DisplayName("with nothing recorded, the gamemode decides as it always did")
	void olderRowsKeepTheGamemodeRule() {
		assertTrue(GriefModule.droppedAnything(null, "survival"));
		assertTrue(GriefModule.droppedAnything(null, null), "a row from before the column");
		assertFalse(GriefModule.droppedAnything(null, "creative"));
		assertFalse(GriefModule.droppedAnything(null, "spectator"));
	}
}
