package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.modules.grief.GriefModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The PLACE half of the grief log.
 * <p>
 * Fabric API has a block-break event but no block-place event, so this reads the world
 * back at {@code RETURN} — after vanilla has actually put the block down — rather than
 * guessing from the placement context. If the placement failed there is nothing to read
 * and nothing is logged.
 * <p>
 * Optional by design: without it, rollback still restores broken blocks, it just cannot
 * clear placed ones.
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {

	@Inject(method = "place", at = @At("RETURN"))
	private void staffcore$logPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		Level level = context.getLevel();
		if (level.isClientSide()) return;
		if (!(context.getPlayer() instanceof ServerPlayer player)) return;

		GriefModule grief = StaffCore.modules().get("grief", GriefModule.class).orElse(null);
		if (grief == null) return;

		BlockPos pos = context.getClickedPos();
		BlockState placed = level.getBlockState(pos);
		if (placed.isAir()) return; // placement was refused

		grief.logPlace(player, pos, placed, level.dimension().identifier().toString());
	}
}
