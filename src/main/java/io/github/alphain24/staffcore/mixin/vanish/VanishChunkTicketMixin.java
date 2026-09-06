package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optionally stops a vanished player holding chunks open around them.
 * <p>
 * {@code DistanceManager#addPlayer} does three separate things: it feeds the natural-spawn
 * counter, it feeds the player-ticket tracker, and it registers a simulation ticket. The
 * first is refused unconditionally by {@link VanishSpawnChunkMixin}, because a vanished
 * admin has no business making mob farms tick. The other two are what actually load the
 * world, and refusing them has a cost the person doing it has to have chosen: chunks nobody
 * else is holding open stop loading, so a vanished admin flying across their own server
 * flies into void.
 * <p>
 * That cost is why this used to be a documented limitation rather than an option. It is a
 * reasonable trade on a busy server where somebody is always nearby and a poor one on a
 * quiet server, and neither of those is a judgement this mod can make on an admin's behalf
 * — so it is a config key defaulting to the old behaviour, not a change.
 * <p>
 * Both redirects are keyed off the same predicate, so vanish can never end up half-applied:
 * either the player is invisible to the chunk system entirely or they are not.
 */
@Mixin(DistanceManager.class)
public class VanishChunkTicketMixin {

	/**
	 * The tracker that decides which chunks are kept loaded for a player.
	 * <p>
	 * Targeted by its own owner rather than the shared {@code ChunkTracker} supertype, so
	 * this cannot accidentally also swallow the natural-spawn call two instructions earlier
	 * — that one belongs to {@link VanishSpawnChunkMixin} and is refused on a different
	 * condition.
	 */
	@Redirect(
			method = "addPlayer",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/level/DistanceManager$PlayerTicketTracker;update(JIZ)V"))
	private void staffcore$skipTicketTracking(@Coerce ChunkTracker tracker,
			long chunkPos, int level, boolean decrease, SectionPos section, ServerPlayer player) {

		if (VanishHooks.blocksChunkLoading(player)) return;
		tracker.update(chunkPos, level, decrease);
	}

	/** The simulation ticket itself — what makes the chunk tick, not merely stay loaded. */
	@Redirect(
			method = "addPlayer",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/TicketStorage;addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V"))
	private void staffcore$skipSimulationTicket(TicketStorage storage, Ticket ticket, ChunkPos pos,
			SectionPos section, ServerPlayer player) {

		if (VanishHooks.blocksChunkLoading(player)) return;
		storage.addTicket(ticket, pos);
	}
}
