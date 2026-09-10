package io.github.alphain24.staffcore.modules.replay;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.security.ReplaySession;
import io.github.alphain24.staffcore.modules.security.ReplaySidebar;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Being somewhere you did not walk to, and getting back afterwards.
 *
 * <h2>Why this is one class and not two</h2>
 * There are two replays in this mod. One stands a staff member inside an excavation and paints
 * the blocks somebody dug; the other moves them along the path a player actually walked. They
 * show completely different things, and everything about <em>leaving</em> is identical: the
 * gamemode to put back, the position to return to, the vanish flag not to disturb, the painted
 * blocks to take down, the sidebar to remove, and the five separate ways a session can end.
 * <p>
 * Those five ways are the reason this is shared rather than copied. Exit runs from the command,
 * a disconnect, a death, a dimension change and a server restart — and four of those fire for
 * every player on the server, so each one is a place where a second implementation could drift
 * out of step with the first and nobody would notice until somebody was stuck in spectator.
 *
 * <h2>Painted blocks are packets, never world edits</h2>
 * Everything drawn here is a {@link ClientboundBlockUpdatePacket} sent to one connection. The
 * world is not touched, so there is nothing to repair if the server stops mid-replay, nothing
 * for another player to see, and nothing to clean up beyond telling that one client the truth
 * again.
 * <p>
 * The corollary is the thing to remember: a block update is a delta against the chunk the
 * client is currently holding, not a fact about the world. Any chunk resend removes it. That is
 * why {@link #repaint} exists and why the canary system had to learn the same lesson the hard
 * way.
 */
public final class ReplayStage {
	private ReplayStage() {}

	/** What is currently painted for one viewer, so it can be taken down exactly. */
	private static final Map<UUID, Map<BlockPos, BlockState>> PAINTED = new ConcurrentHashMap<>();

	/** The dimension each viewer is replaying in, to notice when they leave it. */
	private static final Map<UUID, String> WATCHING = new ConcurrentHashMap<>();

	/**
	 * What else has to be torn down when a particular viewer leaves.
	 * <p>
	 * In memory rather than on disk, and that is correct rather than a compromise. It exists
	 * to stop a playback driver that is running right now, and a driver cannot survive a
	 * restart — so after one there is nothing here to run and nothing that needed running.
	 * The part that <em>must</em> outlive a restart is the way home, and that is in
	 * {@link ReplaySession}, on disk, written before the player is touched.
	 */
	private static final Map<UUID, Runnable> TEARDOWN = new ConcurrentHashMap<>();

	// ---------------------------------------------------------------- entering

	/**
	 * Moves a staff member into a replay, having already recorded where they were.
	 * <p>
	 * The caller must have called {@link ReplaySession#remember} and had it succeed. This does
	 * not check, because the check belongs where the refusal has to be worded — a caller that
	 * could not record the way home has to stop before it does anything at all, and returning
	 * a boolean from here would be a caller that already moved somebody.
	 *
	 * @param onExit run when this viewer leaves, whichever way they leave. May be null.
	 */
	public static void begin(ServerPlayer staff, ServerLevel level, double x, double y, double z,
			float yaw, float pitch, Runnable onExit) {

		staff.setGameMode(GameType.SPECTATOR);
		// Through the module's own toggle rather than its internals. Conditional because
		// somebody already hidden must not be un-hidden by being sent to look at something.
		if (!Mods.vanish().isVanished(staff)) Mods.vanish().toggle(staff);
		staff.teleportTo(level, x, y, z, Set.of(), yaw, pitch, false);

		WATCHING.put(staff.getUUID(), Mc.dimensionId(level));
		if (onExit != null) TEARDOWN.put(staff.getUUID(), onExit);
	}

	/** Whether this staff member is part way through a replay of any kind. */
	public static boolean isReplaying(UUID staff) {
		return ReplaySession.isReplaying(staff);
	}

	// ----------------------------------------------------------------- drawing

	/**
	 * Replaces what is painted for one viewer with a new set of blocks.
	 * <p>
	 * Anything previously painted and not in the new set is told the truth again, so a caller
	 * that redraws a moving picture does not leave a trail of blocks behind it.
	 */
	public static void paint(ServerPlayer staff, ServerLevel level,
			Map<BlockPos, BlockState> blocks) {

		if (staff == null || staff.connection == null) return;

		Map<BlockPos, BlockState> previous = PAINTED.get(staff.getUUID());
		if (previous != null) {
			for (BlockPos pos : previous.keySet()) {
				if (!blocks.containsKey(pos)) {
					staff.connection.send(new ClientboundBlockUpdatePacket(level, pos));
				}
			}
		}

		Map<BlockPos, BlockState> now = new LinkedHashMap<>(blocks);
		PAINTED.put(staff.getUUID(), now);
		now.forEach((pos, state) ->
				staff.connection.send(new ClientboundBlockUpdatePacket(pos, state)));
	}

	/**
	 * Sends every painted block again, unchanged.
	 * <p>
	 * A block update is a delta against the chunk the client currently holds, so a chunk
	 * resend — view distance, a dimension change, any reason the server has of its own —
	 * silently reverts the lot while the server goes on believing it drew them. Called on a
	 * slow timer for exactly that reason.
	 */
	public static void repaint(ServerPlayer staff) {
		if (staff == null || staff.connection == null) return;

		Map<BlockPos, BlockState> painted = PAINTED.get(staff.getUUID());
		if (painted == null || painted.isEmpty()) return;

		painted.forEach((pos, state) ->
				staff.connection.send(new ClientboundBlockUpdatePacket(pos, state)));
	}

	/** Takes the paint down, from the world as it is now rather than a remembered "before". */
	public static void unpaint(ServerPlayer staff) {
		Map<BlockPos, BlockState> painted = PAINTED.remove(staff.getUUID());
		if (painted == null || staff.connection == null) return;

		ServerLevel level = staff.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		if (level == null) return;

		for (BlockPos pos : painted.keySet()) {
			// From the world, because a remembered state would be wrong for anything that
			// changed while they were watching — and the point of taking this down is that
			// their client stops disagreeing with the server.
			staff.connection.send(new ClientboundBlockUpdatePacket(level, pos));
		}
	}

	/** How many blocks are currently drawn for this viewer. For tests and diagnostics. */
	public static int paintedFor(UUID staff) {
		Map<BlockPos, BlockState> painted = PAINTED.get(staff);
		return painted == null ? 0 : painted.size();
	}

	// -------------------------------------------------------------------- exit

	/**
	 * Puts a staff member back, whichever way they are leaving.
	 * <p>
	 * Idempotent and safe to call on somebody who is not replaying, because four of the five
	 * callers cannot know whether they are — a disconnect handler, a death, a dimension change
	 * and a join all fire for everybody.
	 *
	 * @return true when somebody was actually brought back
	 */
	public static boolean exit(MinecraftServer server, ServerPlayer staff, String why) {
		if (server == null || staff == null) return false;

		ReplaySession.Prior prior = ReplaySession.of(staff.getUUID());
		if (prior == null) return false;

		// Stopped first. A driver that is still running would go on teleporting somebody who
		// is being put back, and the two would fight for a tick or two before one of them won
		// — which reads to whoever is standing there as the exit command not working.
		Runnable teardown = TEARDOWN.remove(staff.getUUID());
		if (teardown != null) {
			try {
				teardown.run();
			} catch (RuntimeException e) {
				// A broken teardown must not strand somebody in spectator.
				StaffCore.LOGGER.error("[Replay] teardown for {} failed; continuing to restore "
						+ "them anyway", Mc.name(staff), e);
			}
		}

		unpaint(staff);
		// Taken down before anything else can fail. A sidebar left behind is a panel of
		// numbers about somebody else following a staff member around their own game.
		ReplaySidebar.hide(staff);
		WATCHING.remove(staff.getUUID());

		ServerLevel home = levelOf(server, prior.world());
		if (home == null) {
			// The dimension they came from has gone. Better to leave them where they are, in
			// their own gamemode, than to delete the record and strand them in spectator.
			staff.setGameMode(prior.gameMode());
			staff.sendSystemMessage(Theme.bad("The world you came from (" + prior.world()
					+ ") no longer exists, so you have been left here in "
					+ prior.gameMode().getName() + "."));
			ReplaySession.clear(staff.getUUID());
			return true;
		}

		staff.teleportTo(home, prior.x(), prior.y(), prior.z(), Set.of(),
				prior.yaw(), prior.pitch(), false);
		staff.setGameMode(prior.gameMode());

		// Restored to what it was, not switched off. Somebody who was already hidden before
		// they started watching should not reappear because they looked at something — that is
		// the tool undoing a decision it was never asked about.
		if (!prior.vanished() && Mods.vanish().isVanished(staff)) Mods.vanish().toggle(staff);

		// Cleared last, and only now. Everything above is done.
		ReplaySession.clear(staff.getUUID());

		staff.sendSystemMessage(Theme.good("Back where you were"
				+ (why == null ? "." : " — " + why)));
		StaffCore.LOGGER.info("[Replay] {} left a replay of {} ({})", Mc.name(staff),
				prior.subject(), why == null ? "command" : why);
		return true;
	}

	/**
	 * Puts somebody back who reconnected mid-replay.
	 * <p>
	 * The paint is gone by itself — a fresh client was sent the honest chunks — so this is
	 * only the gamemode, the position and the vanish flag. Called from the join handler for
	 * every player, and does nothing for anybody who was not replaying.
	 */
	public static void restoreOnJoin(MinecraftServer server, ServerPlayer staff) {
		if (!ReplaySession.isReplaying(staff.getUUID())) return;

		PAINTED.remove(staff.getUUID());
		exit(server, staff, "you reconnected");
	}

	/**
	 * Ends a replay for somebody who has left the dimension they were watching.
	 * <p>
	 * Spectators can fly through a portal, and one who does is looking at a different world
	 * with somebody else's tunnel drawn over it. Called on a slow timer rather than hooked to
	 * a dimension-change event: this is a cheap map lookup per replaying player, and there is
	 * usually nobody replaying at all.
	 */
	public static void checkDimensions(MinecraftServer server) {
		if (WATCHING.isEmpty()) return;

		for (Map.Entry<UUID, String> entry : Map.copyOf(WATCHING).entrySet()) {
			ServerPlayer staff = server.getPlayerList().getPlayer(entry.getKey());
			if (staff == null) continue;

			if (!Mc.dimensionId(staff.level()).equals(entry.getValue())) {
				exit(server, staff, "you left the dimension the replay was in");
			} else {
				// Free-riding on the timer that is already walking this map. The paint has to
				// be re-asserted periodically or a chunk resend takes it away silently — the
				// bug the canary system was found to have by somebody testing with an x-ray
				// pack, in a feature built on the same packet.
				repaint(staff);
			}
		}
	}

	/** Which dimension a viewer is watching, or null. */
	public static String watching(UUID staff) {
		return WATCHING.get(staff);
	}

	/**
	 * Drops what is drawn for one player, without touching their way home.
	 * <p>
	 * For a disconnect. The paint is a client-side lie and a reconnecting client is sent the
	 * honest chunks anyway; the row saying where they were standing has to survive, because
	 * putting them back is what happens when they return.
	 */
	public static void forget(UUID player) {
		if (player == null) return;
		PAINTED.remove(player);
		WATCHING.remove(player);

		Runnable teardown = TEARDOWN.remove(player);
		if (teardown != null) {
			try {
				teardown.run();
			} catch (RuntimeException ignored) {
				// Nothing left to protect: they are gone and the way home is on disk.
			}
		}
	}

	/** Only for tests and a deliberate reset. */
	public static void forgetAll() {
		PAINTED.clear();
		WATCHING.clear();
		TEARDOWN.clear();
	}

	public static ServerLevel levelOf(MinecraftServer server, String world) {
		for (ServerLevel level : server.getAllLevels()) {
			if (Mc.dimensionId(level).equals(world)) return level;
		}
		return null;
	}
}
