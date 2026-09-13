package io.github.alphain24.staffcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Reads the loot a player's break is about to spawn.
 * <p>
 * A rollback has to take back what the break gave the player, and that is not the block: stone
 * gives cobblestone, grass gives dirt, glass gives nothing, and a pickaxe with fortune gives
 * more than one. The loot list is worked out here, once, and then spawned — so reading it here
 * is reading exactly what landed, with no second roll of the dice.
 * <p>
 * Read only: the list is passed on untouched. If this fails to apply, breaks fall back to
 * rollback's old rule of owing the block itself.
 */
@Mixin(Block.class)
public abstract class BlockDropsMixin {

	@WrapOperation(
			method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/Block;getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemInstance;)Ljava/util/List;"))
	private static List<ItemStack> staffcore$seeBreakDrops(BlockState state, ServerLevel level,
			BlockPos pos, BlockEntity blockEntity, Entity breaker, ItemInstance tool,
			Operation<List<ItemStack>> original) {

		List<ItemStack> drops = original.call(state, level, pos, blockEntity, breaker, tool);
		if (breaker instanceof ServerPlayer player) {
			StaffCore.modules().get("grief", GriefModule.class).ifPresent(grief ->
					grief.onHandDrops(player, level, pos, drops));
		}
		return drops;
	}
}
