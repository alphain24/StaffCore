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
	 * <b>They are in the player list.</b> {@code makeMockServerPlayerInLevel} calls
	 * {@code PlayerList.placeNewPlayer} — confirmed from the 26.2 bytecode. This javadoc said
	 * the opposite for months and the claim was load-bearing: it was the reason
	 * {@code MaintenanceTests} was believed safe to toggle a server-wide flag that disconnects
	 * everybody, and it was the recorded explanation for why two deleted vanish tests were
	 * vacuous. The first was wrong and was actively kicking other tests' players; the second
	 * still stands, because it was established by probing rather than by that explanation.
	 * <p>
	 * What that means for a new test: a mock player is a real entry in the player list and can
	 * be disconnected, counted, iterated and broadcast to by anything that walks it. Assume the
	 * other tests running alongside yours can see it.
	 */
	static ServerPlayer mockPlayer(GameTestHelper helper) {
		return helper.makeMockServerPlayerInLevel();
	}

	/** Each named player's end of the connection, where what the server sent them collects. */
	private static final java.util.Map<java.util.UUID, io.netty.channel.embedded.EmbeddedChannel> CHANNELS =
			new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Everything the server has sent this named player since the last call, in order, as packets.
	 * <p>
	 * The connection has no encoder, so what the server writes arrives here as the packet objects
	 * themselves. Empty for a player {@link #namedPlayer} did not make.
	 */
	static java.util.List<Object> sent(ServerPlayer player) {
		java.util.List<Object> out = new java.util.ArrayList<>();
		io.netty.channel.embedded.EmbeddedChannel channel = CHANNELS.get(player.getUUID());
		if (channel == null) return out;
		for (Object packet = channel.readOutbound(); packet != null; packet = channel.readOutbound()) {
			out.add(packet);
		}
		return out;
	}

	/**
	 * A real survival player with a name of its own, placed the way the game places a mock one.
	 * <p>
	 * Every mock player is called test-mock-player, and anything that looks a player up by
	 * name — a rollback charging the griefer, a debt collected on login — finds whichever of
	 * them the server lists first, which is usually another test's. A test about charging the
	 * right player needs a player nobody else shares a name with. This is also not stuck in
	 * creative, so the game's own survival rules apply to it.
	 */
	static ServerPlayer namedPlayer(GameTestHelper helper) {
		String name = ("t" + java.util.UUID.randomUUID().toString().replace("-", "")).substring(0, 12);
		com.mojang.authlib.GameProfile profile =
				new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name);
		net.minecraft.server.network.CommonListenerCookie cookie =
				net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
		net.minecraft.server.level.ServerLevel level = helper.getLevel();
		ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(),
				cookie.clientInformation());
		net.minecraft.network.Connection connection =
				new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
		CHANNELS.put(profile.id(), new io.netty.channel.embedded.EmbeddedChannel(connection));
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setGameMode(GameType.SURVIVAL);
		player.snapTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(
				helper.absolutePos(new net.minecraft.core.BlockPos(0, 2, 0))));
		return player;
	}

	/**
	 * <b>Do not add {@code forgetAll()} calls to these tests.</b>
	 * <p>
	 * Gametests inside a batch run at the same time, in different parts of the world. Every
	 * {@code forgetAll} in this mod clears state for <em>every</em> player at once, so one
	 * test's tidy-up wipes another test's decoys part way through its assertions. That
	 * produced a failure roughly one run in eight, in whichever test happened to be unlucky —
	 * which reads as a flaky test rather than as what it is.
	 * <p>
	 * Isolation comes from identity instead, and it is already there for free: every mock
	 * player has its own UUID, all the state here is keyed by it, and every assertion that
	 * looks at shared collections filters by a position inside its own test area. Leftover
	 * state for a player nobody will ask about again costs a few bytes in a server that is
	 * about to exit.
	 * <p>
	 * If a test genuinely needs a clean global slate, it needs its own batch, not a reset.
	 */
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

	/**
	 * A staff identity for tests, built without a player.
	 * <p>
	 * The gateway takes an {@code Actor} rather than a name so the same call works from a
	 * command, a menu and — later — a Discord bridge with no player anywhere. That this is
	 * constructible here with nothing but a UUID is the property that makes it worth having.
	 */
	static io.github.alphain24.staffcore.permission.Actor staff() {
		return io.github.alphain24.staffcore.permission.Actor.of(
				java.util.UUID.nameUUIDFromBytes("TestStaff".getBytes()), "TestStaff",
				io.github.alphain24.staffcore.permission.Actor.Source.PLAYER,
				io.github.alphain24.staffcore.permission.Actor.all());
	}

	/**
	 * A second staff identity, for the cases where two different people touch one thing.
	 * <p>
	 * An invsee screen is opened by one staff member and can be closed by the server — or, once
	 * a Discord bridge exists, acted on by someone else entirely. Having two identities to hand
	 * is what lets a test tell "the same person finished what they started" apart from "somebody
	 * else did", which is the distinction the audit row exists to record.
	 */
	static io.github.alphain24.staffcore.permission.Actor otherStaff() {
		return io.github.alphain24.staffcore.permission.Actor.of(
				java.util.UUID.nameUUIDFromBytes("OtherStaff".getBytes()), "OtherStaff",
				io.github.alphain24.staffcore.permission.Actor.Source.PLAYER,
				io.github.alphain24.staffcore.permission.Actor.all());
	}

	static GameType survival() {
		return GameType.SURVIVAL;
	}
}
