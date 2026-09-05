package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.modules.grief.GriefModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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

	@Inject(method = "interactWithBlocks", at = @At("HEAD"))
	private void staffcore$recordExplosion(List<BlockPos> positions, CallbackInfo ci) {
		if (positions == null || positions.isEmpty()) return;

		GriefModule grief = StaffCore.modules().get("grief", GriefModule.class).orElse(null);
		if (grief == null) return;

		ServerExplosion self = (ServerExplosion) (Object) this;

		// A player wherever one is behind it: lighting TNT is griefing done with a tool, and
		// belongs on their record like any other damage they caused.
		String source = GriefModule.explosionSource(
				self.getDirectSourceEntity(), self.getIndirectSourceEntity());

		grief.logExplosion(level, positions, source);
	}
}
