package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A vanished player does not make chunks eligible for mob spawning.
 * <p>
 * {@code DistanceManager#addPlayer} does three things at once: it records the player in
 * {@code playersPerChunk}, it feeds the natural-spawn chunk counter, and it raises the
 * <em>chunk-loading ticket</em>. Cancelling the whole method — the obvious implementation
 * of "vanished players don't load chunks" — would take the third with it and leave the
 * staff member staring into unloaded void, unable to do the job vanish exists for.
 * <p>
 * So only the spawn counter is redirected. The observable effect people actually want is
 * that farms and mob rates behave as though nobody is there, and that is exactly what this
 * gives, while the admin keeps a world they can see and fly through.
 */
@Mixin(DistanceManager.class)
public class VanishSpawnChunkMixin {

	/**
	 * {@code @Coerce} because the receiver's real type is package-private.
	 * <p>
	 * Mixin validates a redirect's receiver against the exact owner in the constant pool,
	 * and {@code DistanceManager$FixedPlayerDistanceChunkTracker} cannot be named from
	 * outside its package — so declaring the supertype without {@code @Coerce} fails
	 * validation and the whole mixin is thrown away. {@code update} is declared public on
	 * {@link ChunkTracker}, so the coerced type is enough to call through.
	 */
	@Redirect(
			method = "addPlayer",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;update(JIZ)V"))
	private void staffcore$skipSpawnCounting(@Coerce ChunkTracker tracker,
			long chunkPos, int level, boolean decrease, SectionPos section, ServerPlayer player) {

		if (VanishHooks.ignoredByMobs(player)) return;
		tracker.update(chunkPos, level, decrease);
	}
}
