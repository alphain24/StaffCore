package io.github.alphain24.staffcore.modules.freeze;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which disconnects of a frozen player are reported, and what staff are told.
 */
class FreezeEndingTest {

	@Test
	@DisplayName("leaving and timing out are reported; kicks and shutdowns are not")
	void endings() {
		assertEquals(FreezeModule.Ending.LEFT, FreezeModule.ending(true, null));
		assertEquals(FreezeModule.Ending.LOST_CONNECTION,
				FreezeModule.ending(true, Component.translatable("disconnect.timeout")));
		assertEquals(FreezeModule.Ending.REMOVED_BY_SERVER,
				FreezeModule.ending(true, Component.literal("You are banned")));
		assertEquals(FreezeModule.Ending.REMOVED_BY_SERVER,
				FreezeModule.ending(true, Component.translatable("multiplayer.disconnect.kicked")));
		assertEquals(FreezeModule.Ending.SERVER_STOPPING,
				FreezeModule.ending(true, Component.translatable("multiplayer.disconnect.server_shutdown")));
		assertEquals(FreezeModule.Ending.SERVER_STOPPING, FreezeModule.ending(false, null));

		assertTrue(FreezeModule.Ending.LEFT.reported());
		assertTrue(FreezeModule.Ending.LOST_CONNECTION.reported());
		assertFalse(FreezeModule.Ending.REMOVED_BY_SERVER.reported());
		assertFalse(FreezeModule.Ending.SERVER_STOPPING.reported());
	}

	@Test
	@DisplayName("staff are told who froze them and how long it lasted")
	void description() {
		long now = 10_000_000L;
		FreezeModule.Hold hold = new FreezeModule.Hold(Vec3.ZERO, "overworld", "Alice", now - 3 * 60_000L);
		assertEquals("Steve left the server while frozen by Alice, after 3m frozen",
				FreezeModule.describe("Steve", hold, FreezeModule.Ending.LEFT, now));

		FreezeModule.Hold unknown = new FreezeModule.Hold(Vec3.ZERO, "overworld", null, now - 5_000L);
		assertEquals("Steve lost connection while frozen, moments after being frozen",
				FreezeModule.describe("Steve", unknown, FreezeModule.Ending.LOST_CONNECTION, now));
	}
}
