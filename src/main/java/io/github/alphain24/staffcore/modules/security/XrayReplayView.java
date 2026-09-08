package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standing inside somebody else's excavation and looking at what they did.
 *
 * <h2>Why a viewer rather than a longer report</h2>
 * The p-value says how unlikely a dig was. It cannot say whether the tunnel looks like a person
 * following a vein they could see or like a person walking to something they could not, and
 * that is the question a staff member is actually trying to answer before they act. A number
 * decides whether to look; this is the looking.
 *
 * <h2>Drawn with the same primitive as everything else</h2>
 * The path is painted by sending {@link ClientboundBlockUpdatePacket} to one player, which is
 * how the rollback preview draws proposed blocks and how a canary shows an ore that is not
 * there. Three features, one technique, no entities to leak and nothing to clean up if the
 * server stops mid-replay — the world was never touched, so there is nothing in it to repair.
 *
 * <h2>Leaving</h2>
 * Five ways out and all of them restore: the exit command, disconnecting, dying, changing
 * dimension, and the server restarting. The way home is on disk before anything about the
 * player changes — see {@link ReplaySession}.
 */
public final class XrayReplayView {
	private XrayReplayView() {}

	/** What is currently painted for one viewer, so it can be taken down exactly. */
	private static final Map<UUID, Map<BlockPos, BlockState>> PAINTED = new ConcurrentHashMap<>();

	/** The dimension each viewer is replaying in, to notice when they leave it. */
	private static final Map<UUID, String> WATCHING = new ConcurrentHashMap<>();

	/** How many blocks of path to draw. Beyond this the picture is noise, not information. */
	private static final int MAX_PAINTED = 3000;

	// ---------------------------------------------------------------------- entry

	/** Whether the replay could be started, and why not when it could not. */
	public record Entry(boolean started, String refusal) {

		static final Entry OK = new Entry(true, null);

		static Entry no(String why) {
			return new Entry(false, why);
		}
	}

	/**
	 * Puts a staff member in the middle of a dig, in spectator, with the path drawn.
	 * <p>
	 * The order is deliberate and is the whole safety story: the way home is written first,
	 * and if that write fails nothing else happens. A staff member left standing where they
	 * were is a command that did not work; a staff member in spectator at the bottom of a mine
	 * with no recorded way back is a support ticket.
	 *
	 * @param caseId the case this was opened from, or null for an ad-hoc look
	 */
	public static Entry enter(MinecraftServer server, ServerPlayer staff, String subject,
			String caseId, long windowMs) {

		if (server == null || staff == null) return Entry.no("No server.");
		if (ReplaySession.isReplaying(staff.getUUID())) {
			return Entry.no("You are already in a replay. /staff xray exit first.");
		}

		Map<String, List<Excavation.Dig>> digs = XraySweep.loadDigs(windowMs, subject);
		List<Excavation.Dig> theirs = digs.values().stream().findFirst().orElse(null);
		if (theirs == null || theirs.isEmpty()) {
			return Entry.no(subject + " has broken nothing in this window; there is nothing to "
					+ "stand in.");
		}

		// The biggest stretch, which is the one the finding was about. Replaying the whole
		// session would put somebody at the start of an hour of ordinary tunnelling.
		Excavation.Segment segment = Excavation.segment(theirs).stream()
				.max(java.util.Comparator.comparingInt(Excavation.Segment::drawn))
				.orElse(null);
		if (segment == null) return Entry.no("Nothing to replay.");

		ServerLevel level = levelOf(server, segment.world());
		if (level == null) {
			return Entry.no("The dig was in " + segment.world() + ", which this server no "
					+ "longer has.");
		}

		// Written before the player is touched at all. If this fails, they do not move.
		if (!ReplaySession.remember(staff, caseId, subject)) {
			return Entry.no("Could not record where you are standing, so you are staying "
					+ "there. Nothing has changed. Check the server log.");
		}

		BlockPos entrance = entranceOf(segment);
		staff.setGameMode(GameType.SPECTATOR);
		// Through the module's own toggle rather than its internals. Conditional because
		// somebody already hidden must not be un-hidden by being sent to look at a mine.
		if (!Mods.vanish().isVanished(staff)) Mods.vanish().toggle(staff);
		staff.teleportTo(level, entrance.getX() + 0.5, entrance.getY() + 0.5,
				entrance.getZ() + 0.5, java.util.Set.of(), staff.getYRot(), staff.getXRot(), false);

		WATCHING.put(staff.getUUID(), segment.world());
		paint(staff, level, segment);
		describe(staff, subject, segment, level);

		// The numbers stay on screen while they fly around. Reading the shape of a tunnel
		// with no idea whether it is unusual is half the evidence missing, and chat scrolls.
		XraySweep.forPlayer(server, subject, windowMs).stream()
				.min(java.util.Comparator.comparingDouble(XraySweep.Finding::pValue))
				.ifPresent(finding -> ReplaySidebar.show(staff, finding,
						Canaries.hitsFor(staff.getUUID())));
		return Entry.OK;
	}

	/**
	 * The first block of the dig in time order, which is where the person came in.
	 * <p>
	 * Not the centre of the excavation. Standing in the middle shows you a cavity; standing at
	 * the entrance and looking along it shows you the decision — whether the tunnel goes
	 * straight or bends towards things.
	 */
	private static BlockPos entranceOf(Excavation.Segment segment) {
		return segment.digs().stream()
				.min(java.util.Comparator.comparingLong(Excavation.Dig::at))
				.map(dig -> new BlockPos(dig.x(), dig.y(), dig.z()))
				.orElse(BlockPos.ZERO);
	}

	// -------------------------------------------------------------------- drawing

	/**
	 * Paints the dig for this viewer alone.
	 * <p>
	 * Three colours, and the distinction is the point. The path is where they went; the ore is
	 * what they took; a canary is a block that was never there and they broke it anyway. A
	 * single colour would show the shape of the tunnel and lose the reason anybody is looking
	 * at it.
	 */
	private static void paint(ServerPlayer staff, ServerLevel level, Excavation.Segment segment) {
		Map<BlockPos, BlockState> painted = new LinkedHashMap<>();
		List<Excavation.Dig> ordered = new ArrayList<>(segment.digs());
		ordered.sort(java.util.Comparator.comparingLong(Excavation.Dig::at));

		for (Excavation.Dig dig : ordered) {
			if (painted.size() >= MAX_PAINTED) break;

			BlockPos pos = new BlockPos(dig.x(), dig.y(), dig.z());
			// Only paint air. A block that is there now is one somebody put back, and drawing
			// over it would show a tunnel through a wall that exists.
			if (!level.getBlockState(pos).isAir()) continue;

			// Tinted glass for the path and a sea lantern for ore: the first is dark and
			// see-through so the shape of a tunnel reads from outside it, the second is bright
			// enough to pick out at the end of one.
			painted.put(pos, dig.isTarget()
					? Blocks.SEA_LANTERN.defaultBlockState()
					: Blocks.TINTED_GLASS.defaultBlockState());
		}

		PAINTED.put(staff.getUUID(), painted);
		painted.forEach((pos, state) ->
				staff.connection.send(new ClientboundBlockUpdatePacket(pos, state)));
	}

	/** Takes the paint down, from the world rather than from memory of what was under it. */
	private static void unpaint(MinecraftServer server, ServerPlayer staff) {
		Map<BlockPos, BlockState> painted = PAINTED.remove(staff.getUUID());
		if (painted == null || staff.connection == null) return;

		ServerLevel level = staff.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		for (BlockPos pos : painted.keySet()) {
			// Sent from the world as it is now. A remembered "before" state would be wrong for
			// anything that changed while they were watching, and the point of taking this
			// down is that their client stops disagreeing with the server.
			if (level != null) {
				staff.connection.send(new ClientboundBlockUpdatePacket(level, pos));
			}
		}
	}

	/** The numbers, in chat, once. */
	private static void describe(ServerPlayer staff, String subject, Excavation.Segment segment,
			ServerLevel level) {

		staff.sendSystemMessage(Theme.prefix()
				.append(Icon.text("Replaying ", Theme.MUTED))
				.append(Icon.text(subject, Theme.ACCENT))
				.append(Icon.text(" — " + segment.describeBand() + " in " + segment.world(),
						Theme.MUTED)));

		staff.sendSystemMessage(Icon.text("  Grey glass is where they dug. Lit blocks are the "
				+ "ore they took.", Theme.MUTED));
		staff.sendSystemMessage(Icon.text("  Nothing here is real — only you can see it, and "
				+ "the world is untouched.", Theme.MUTED));
		staff.sendSystemMessage(Icon.text("  ", Theme.MUTED)
				.append(io.github.alphain24.staffcore.gui.Link.run("[exit the replay]",
						"/staff xray exit", Theme.ACCENT, "Puts you back where you were")));
	}

	// --------------------------------------------------------------------- exit

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

		unpaint(server, staff);
		// Taken down before anything else can fail. A sidebar left behind is a panel of
		// numbers about somebody else's mine following a staff member around their own game.
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

		staff.teleportTo(home, prior.x(), prior.y(), prior.z(), java.util.Set.of(),
				prior.yaw(), prior.pitch(), false);
		staff.setGameMode(prior.gameMode());

		// Restored to what it was, not switched off. Somebody who was already hidden before
		// they started watching should not reappear because they looked at a mine — that is
		// the tool undoing a decision it was never asked about.
		if (!prior.vanished() && Mods.vanish().isVanished(staff)) Mods.vanish().toggle(staff);

		// Cleared last, and only now. Everything above is done.
		ReplaySession.clear(staff.getUUID());

		staff.sendSystemMessage(Theme.good("Back where you were" + (why == null ? "." : " — " + why)));
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
	 * Spectators can fly through a portal, and a staff member who does is looking at a
	 * different world with a tunnel drawn over it. Called on a slow timer rather than hooked
	 * to a dimension-change event: this is a cheap map lookup per replaying player, and there
	 * is usually nobody replaying at all.
	 */
	public static void checkDimensions(MinecraftServer server) {
		if (WATCHING.isEmpty()) return;

		for (Map.Entry<UUID, String> entry : Map.copyOf(WATCHING).entrySet()) {
			ServerPlayer staff = server.getPlayerList().getPlayer(entry.getKey());
			if (staff == null) continue;

			if (!Mc.dimensionId(staff.level()).equals(entry.getValue())) {
				exit(server, staff, "you left the dimension the dig was in");
			}
		}
	}

	/** Brings back anybody the server went down on. Called once, after start. */
	public static void restoreAfterRestart(MinecraftServer server) {
		List<UUID> open = ReplaySession.open();
		if (open.isEmpty()) return;

		StaffCore.LOGGER.info("[Replay] {} replay session(s) were open when the server stopped; "
				+ "they will be restored as those staff members rejoin.", open.size());
	}

	private static ServerLevel levelOf(MinecraftServer server, String world) {
		for (ServerLevel level : server.getAllLevels()) {
			if (Mc.dimensionId(level).equals(world)) return level;
		}
		return null;
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
	}

	/** Only for tests and a deliberate reset. */
	public static void forgetAll() {
		PAINTED.clear();
		WATCHING.clear();
	}
}
