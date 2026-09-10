package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.illusion.BlockIllusions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts back the blocks a chunk send just erased.
 *
 * <h2>What this is for</h2>
 * StaffCore shows one player a block that is not there in three places: canary decoys, the
 * replay overlay and the rollback preview. All three do it with
 * {@code ClientboundBlockUpdatePacket}, which is a delta against the chunk the client already
 * holds — not stored, not re-applied, and invisible to the server once sent.
 * <p>
 * {@link PlayerChunkSender#sendChunk} builds {@code ClientboundLevelChunkWithLightPacket}
 * directly from the {@code LevelChunk}, which is the honest world. So the instant it runs, every
 * delta the client had for that chunk is gone and the server has no idea. This injects at its
 * return and re-asserts whatever belonged there, for that player, for that chunk.
 *
 * <h2>Why a mixin, and why this one is nothing like the one that was deleted</h2>
 * The Fabric API has no per-player chunk-send event in this version — checked against every
 * {@code *Events} class in every {@code fabric-api} jar on the classpath, not from memory.
 * {@code ServerChunkEvents} is server-side chunk lifecycle (load, generate, unload, status) and
 * {@code EntityTrackingEvents} is about entities. Neither fires when a chunk goes to a player.
 * <p>
 * A previous mixin on {@code ChunkMap$TrackedEntity} was removed for good reasons: it targeted a
 * package-private inner class by string and cancelled a vanilla method at HEAD, taking over
 * behaviour it then had to reimplement. This shares none of that. {@code PlayerChunkSender} is a
 * public class, the injection is at {@code RETURN}, it cancels nothing, changes no argument, and
 * touches no serialisation. It observes that a thing happened and sends packets of its own.
 *
 * <h2>Ordering is the point</h2>
 * At {@code RETURN} rather than {@code HEAD}, and that is not stylistic. The re-asserted block
 * updates have to arrive <em>after</em> the chunk that erased them or they are erased in turn. A
 * connection delivers in order, so injecting after the chunk packet has been handed to
 * {@code send} is what guarantees it.
 *
 * <h2>Cost</h2>
 * This runs for every chunk sent to every player for the life of the server. The illusion store
 * is indexed by chunk, so the ordinary case — no illusions anywhere near — is one map lookup
 * returning null, and the case with illusions elsewhere in the world is the same. Only a chunk
 * that actually holds something does any work.
 * <p>
 * Tiered IMPORTANT rather than OPTIONAL. If this stops applying, nothing errors and no feature
 * switches off: decoys are placed, counted and reported as live while quietly evaporating from
 * every client that reloads a chunk. That is a feature running on data that is silently wrong,
 * which is exactly the distinction the tier exists to mark.
 */
@Mixin(PlayerChunkSender.class)
public class ChunkSendMixin {

	@Inject(method = "sendChunk", at = @At("RETURN"))
	private static void staffcore$reassertIllusions(ServerGamePacketListenerImpl connection,
			ServerLevel level, LevelChunk chunk, CallbackInfo ci) {

		if (connection.player == null) return;
		BlockIllusions.onChunkSent(connection.player, chunk.getPos().pack());
	}
}
