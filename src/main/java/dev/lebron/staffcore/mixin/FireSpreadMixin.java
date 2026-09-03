package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.modules.grief.GriefModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Records blocks that fire burns away.
 * <p>
 * Fire is the classic griefing tool and it was invisible to the log. Lighting the first block
 * is a block placement and gets recorded; everything the fire then eats is not, so a wooden
 * build could burn to nothing and the grief log would show a single flint-and-steel and no
 * damage at all. Staff could see the hole and had no record of what filled it.
 * <p>
 * <b>Why redirects rather than an inject.</b> {@code checkBurnOut} rolls dice: it is called
 * for every neighbour of every fire block on every fire tick, and most of those calls change
 * nothing. Injecting at the head would record blocks that never burned, which is worse than
 * recording none — a rollback would then put back blocks that never left, overwriting whatever
 * is genuinely there now.
 * <p>
 * There are two ways a block goes. It either becomes fire, or it becomes air. Both are the
 * moment it stopped being what it was, so both are redirected, and each records the state it
 * replaced rather than what it replaced it with.
 */
@Mixin(FireBlock.class)
public class FireSpreadMixin {

	/** The block burned and fire took its place. */
	@Redirect(
			method = "checkBurnOut",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
	private boolean staffcore$recordBurnToFire(Level level, BlockPos pos, BlockState replacement,
			int flags) {

		staffcore$record(level, pos);
		return level.setBlock(pos, replacement, flags);
	}

	/** The block burned and left nothing behind. */
	@Redirect(
			method = "checkBurnOut",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
	private boolean staffcore$recordBurnToAir(Level level, BlockPos pos, boolean moving) {
		staffcore$record(level, pos);
		return level.removeBlock(pos, moving);
	}

	/**
	 * Logs what was there, before it is not.
	 * <p>
	 * Read here rather than passed in: the redirect for the fire case is handed the state
	 * going <em>in</em>, and recording that would log every burned plank as a block of fire.
	 */
	private static void staffcore$record(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server)) return;

		GriefModule grief = StaffCore.modules().get("grief", GriefModule.class).orElse(null);
		if (grief == null) return;

		BlockState burned = level.getBlockState(pos);
		if (burned.isAir()) return;

		grief.logFire(server, pos, burned);
	}
}
