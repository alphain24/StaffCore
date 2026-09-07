package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Shared plumbing for the gametests.
 * <p>
 * These exist because the README says, plainly, that this mod is compile-verified rather than
 * runtime-verified: vanish is twenty-seven mixins covering sound, sleep counting, mob
 * targeting, pressure plates and the server-list sample, and every one of them had been
 * reasoned about rather than watched working. The JUnit suite cannot help — none of that
 * exists without a running server — and the boot check only proves the hooks attached, which
 * is a different claim from the feature behaving.
 * <p>
 * A gametest gets a real server, a real level and real players, so it can ask the questions
 * that actually matter: after vanishing, does the tab list still list them.
 */
final class Harness {
	private Harness() {}

	/**
	 * A player who exists properly enough to be vanished.
	 * <p>
	 * <b>They are in creative and cannot be moved out of it.</b> The mock overrides
	 * {@code gameMode()} to return CREATIVE outright, so changing the underlying
	 * {@code ServerPlayerGameMode} succeeds and changes nothing anybody can observe. The
	 * survival variant, {@code makeMockServerPlayer(GameType)}, has no connection, so
	 * anything that sends a packet — which includes vanishing — throws instead.
	 * <p>
	 * That is worth stating because it silently changes what some assertions mean.
	 * {@code Mob.asValidTarget} returns null for a creative player before looking at anything
	 * else, so "the zombie did not target them" is true whether or not vanish is working. Any
	 * test that would have passed for that reason is written here to assert something else
	 * instead, and says so.
	 * <p>
	 * These players are also not in the player list, which bounds what can be asserted about
	 * the tab list and player count. Those are covered through the mechanism each mixin
	 * actually hooks rather than through the list.
	 */
	static ServerPlayer mockPlayer(GameTestHelper helper) {
		return helper.makeMockServerPlayerInLevel();
	}

	static MinecraftServer server(GameTestHelper helper) {
		return helper.getLevel().getServer();
	}

	/** Fails the test with a message rather than an exception nobody can read. */
	static void check(GameTestHelper helper, boolean condition, String whatWentWrong) {
		if (!condition) throw helper.assertionException(whatWentWrong);
	}

	static void checkEquals(GameTestHelper helper, Object expected, Object actual, String what) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw helper.assertionException(
					what + " — expected " + expected + ", got " + actual);
		}
	}

	static String name(ServerPlayer player) {
		return Mc.name(player);
	}

	static GameType survival() {
		return GameType.SURVIVAL;
	}
}
