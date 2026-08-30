package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.modules.staffmode.StaffToolset;
import dev.lebron.staffcore.modules.vanish.VanishModule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two rules about items on the ground.
 * <p>
 * A vanished staff member does not pick anything up — otherwise drops vanish off the floor
 * with nobody visibly there, and the player who dropped them is certain they have been
 * robbed.
 * <p>
 * And a staff tool that has somehow reached the world is destroyed rather than collected.
 * Every route that creates one is now blocked, but this is the backstop that matters: a
 * tool loose in the world is a permanently circulating item the scanner has to keep
 * flagging, and it only takes one escape for a player to be holding a staff netherite axe.
 */
@Mixin(ItemEntity.class)
public class ItemEntityMixin {

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void staffcore$guardPickup(Player player, CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;

		// A staff tool on the floor is a leak. Remove it rather than let anyone collect it.
		if (StaffToolset.isStaffTool(self.getItem())) {
			self.remove(Entity.RemovalReason.DISCARDED);
			ci.cancel();
			return;
		}

		if (!(player instanceof ServerPlayer serverPlayer)) return;

		VanishModule vanish = StaffCore.modules().get("vanish", VanishModule.class).orElse(null);
		if (vanish != null && vanish.blocksItemPickup(serverPlayer)) {
			ci.cancel();
		}
	}
}
