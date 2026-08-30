package dev.lebron.staffcore.modules.grief;

import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shows a rollback in the world before it happens, to one person.
 * <p>
 * A preview that lists "412 changes, 38 oak planks" tells staff the shape of the operation
 * but not where it lands. The question they actually have is "is this the right area" — and
 * that is a question about a place, which a chat message is a poor way to answer. Standing
 * in the build and watching the missing wall reappear answers it instantly.
 * <p>
 * Nothing is written to the world. Each block is sent as a
 * {@link ClientboundBlockUpdatePacket} to the previewing player alone, so their client draws
 * the proposed state while the server and everybody else still see the real one. Clearing
 * the preview re-sends the genuine block, which is also what happens if they walk away, open
 * another menu, or the preview times out.
 * <p>
 * Because the server's copy never changes, the worst case for a preview that somehow fails
 * to clear is a client-side ghost that a chunk reload fixes — not a modified world.
 */
public final class RollbackPreview {

	/**
	 * How many blocks a preview will draw.
	 * <p>
	 * One packet per block, to one player. A few thousand is fine; a hundred thousand would
	 * be a self-inflicted denial of service on somebody who only wanted to look.
	 */
	private static final int MAX_BLOCKS = 4000;

	/** How long a preview stays up before it clears itself, in ticks. */
	private static final int TIMEOUT_TICKS = 20 * 45;

	/** What one player is currently being shown, and what the world really holds there. */
	private record Showing(ServerLevel level, Map<BlockPos, BlockState> real, int expiresAtTick) {}

	private final Map<UUID, Showing> active = new LinkedHashMap<>();
	private int tick;

	/**
	 * Draws the proposed states for one player, replacing any preview they already had.
	 *
	 * @param proposed what the rollback would put at each position
	 * @return how many blocks were drawn
	 */
	public int show(ServerPlayer viewer, ServerLevel level, Map<BlockPos, BlockState> proposed) {
		clear(viewer);
		if (proposed.isEmpty()) return 0;

		Map<BlockPos, BlockState> real = new LinkedHashMap<>();
		int drawn = 0;

		for (Map.Entry<BlockPos, BlockState> entry : proposed.entrySet()) {
			if (drawn >= MAX_BLOCKS) break;

			BlockPos pos = entry.getKey();
			// Remembered so the preview can be taken down exactly, rather than by guessing
			// or by asking the client to reload a chunk.
			real.put(pos.immutable(), level.getBlockState(pos));
			viewer.connection.send(new ClientboundBlockUpdatePacket(pos, entry.getValue()));
			drawn++;
		}

		active.put(viewer.getUUID(), new Showing(level, real, tick + TIMEOUT_TICKS));
		return drawn;
	}

	/** Takes a preview down by re-sending what is genuinely there. */
	public void clear(ServerPlayer viewer) {
		Showing showing = active.remove(viewer.getUUID());
		if (showing == null || viewer.hasDisconnected()) return;

		showing.real().forEach((pos, state) ->
				viewer.connection.send(new ClientboundBlockUpdatePacket(pos, state)));
	}

	public boolean isShowing(ServerPlayer viewer) {
		return active.containsKey(viewer.getUUID());
	}

	/**
	 * Clears previews that have timed out.
	 * <p>
	 * A preview is a ghost overlaid on somebody's view of the world, and one left up forever
	 * because they wandered off mid-decision is a moderation tool lying to a staff member
	 * about what is there. Timing out is the safe default.
	 */
	public void tick(net.minecraft.server.MinecraftServer server) {
		if (active.isEmpty()) return;
		tick++;

		var expired = active.entrySet().stream()
				.filter(e -> tick >= e.getValue().expiresAtTick())
				.map(Map.Entry::getKey)
				.toList();

		for (UUID id : expired) {
			ServerPlayer viewer = server.getPlayerList().getPlayer(id);
			if (viewer == null) {
				active.remove(id);
				continue;
			}
			clear(viewer);
			viewer.sendSystemMessage(Theme.info("Rollback preview cleared — nothing was changed."));
			Sfx.click(viewer);
		}
	}

	/** Drops a player's preview without sending anything. Used when they disconnect. */
	public void forget(UUID player) {
		active.remove(player);
	}

	public void clearAll(net.minecraft.server.MinecraftServer server) {
		for (UUID id : Map.copyOf(active).keySet()) {
			ServerPlayer viewer = server == null ? null : server.getPlayerList().getPlayer(id);
			if (viewer != null) clear(viewer);
			else active.remove(id);
		}
	}
}
