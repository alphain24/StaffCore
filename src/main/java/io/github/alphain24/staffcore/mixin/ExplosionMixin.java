package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.grief.BlastAttribution;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Records what an explosion is about to destroy.
 * <p>
 * Only player block-breaking ever reached the grief log, which left the commonest damage on
 * most servers entirely invisible. A creeper takes out a wall and a chest, and staff have no
 * record of what was there and nothing to roll back — the log says the area was quiet,
 * because as far as it was concerned nobody did anything.
 * <p>
 * {@code interactWithBlocks} receives the full list of positions before any of them go, which
 * is the only moment the blocks and their contents can still be described. Reading them
 * afterwards returns air.
 * <p>
 * This does not change what the explosion does. It writes the same rows a player break writes
 * and returns, so the blast proceeds exactly as vanilla intended and rollback treats the
 * damage like any other.
 */
@Mixin(ServerExplosion.class)
public class ExplosionMixin {

	@Shadow @Final private ServerLevel level;

	/**
	 * The timestamp this blast's rows were logged under, so its drops can be filed under the
	 * same one. Per explosion, on the explosion — never shared between two.
	 */
	@Unique private long staffcore$loggedAt;

	/** Packed position to item id and count, filled as the blast hands its drops out. */
	@Unique private Map<Long, Map<String, Integer>> staffcore$drops;

	@Inject(method = "interactWithBlocks", at = @At("HEAD"))
	private void staffcore$recordExplosion(List<BlockPos> positions, CallbackInfo ci) {
		if (positions == null || positions.isEmpty()) return;

		GriefModule grief = StaffCore.modules().get("grief", GriefModule.class).orElse(null);
		if (grief == null) return;

		ServerExplosion self = (ServerExplosion) (Object) this;

		// A wind charge comes through here too, with a list of blocks it pushes against and
		// destroys none of. Recording those as broken wrote rows for damage that never
		// happened — a rollback "restored" blocks still standing and billed whoever fired it —
		// and counting them would open a mass-grief case over a breeze rod fight.
		if (self.getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK) return;

		// A player wherever one is behind it, including the ones vanilla does not name: TNT
		// they placed and lit with redstone, a crystal they hit, a bed they slept in in the
		// Nether. See BlastAttribution.
		BlastAttribution.Culprit who = grief.blame(self);
		String source = who != null
				? who.name()
				: GriefModule.explosionSource(self.getDirectSourceEntity(),
						self.getIndirectSourceEntity());

		// Both read the blocks, so both run here at HEAD, while the blocks are still there.
		grief.noteBlast(level, self, who, positions);
		long now = System.currentTimeMillis();
		int logged = grief.logExplosion(level, positions, source,
				self.getBlockInteraction() == Explosion.BlockInteraction.DESTROY, now);

		if (logged > 0) {
			staffcore$loggedAt = now;
			staffcore$drops = new HashMap<>();
		}
	}

	/**
	 * Sees each block's drops as the blast collects them.
	 * <p>
	 * Before vanilla merges them. A merged stack is filed under the position of the first
	 * block that contributed, so reading drops after the merge would put forty cobblestone
	 * under one block and none under the other thirty-nine — and a rollback of part of the
	 * crater would owe the wrong amount.
	 */
	@ModifyArg(method = "interactWithBlocks",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/state/BlockState;onExplosionHit(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Explosion;Ljava/util/function/BiConsumer;)V"),
			index = 3)
	private BiConsumer<ItemStack, BlockPos> staffcore$seeDrops(BiConsumer<ItemStack, BlockPos> collect) {
		Map<Long, Map<String, Integer>> drops = staffcore$drops;
		if (drops == null) return collect;

		return (stack, pos) -> {
			// Counted now: the collector grows the first stack it keeps as later ones merge
			// into it, so a reference kept here would read a different number later.
			if (stack != null && !stack.isEmpty()) {
				drops.computeIfAbsent(pos.asLong(), p -> new HashMap<>())
						.merge(Mc.itemId(stack.getItem()), stack.getCount(), Integer::sum);
			}
			collect.accept(stack, pos);
		};
	}

	/** Files the drops once the blast has handed all of them out. */
	@Inject(method = "interactWithBlocks", at = @At("TAIL"))
	private void staffcore$fileDrops(List<BlockPos> positions, CallbackInfo ci) {
		Map<Long, Map<String, Integer>> drops = staffcore$drops;
		staffcore$drops = null;
		if (drops == null) return;

		StaffCore.modules().get("grief", GriefModule.class).ifPresent(grief ->
				grief.logExplosionDrops(level, staffcore$loggedAt, drops));
	}
}
