package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.staffmode.StaffToolset;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Staff tools cannot be dropped.
 * <p>
 * Every leaked tool is a permanent item in circulation that the security scanner then has
 * to keep flagging, and the usual way one leaks is a staff member pressing Q by reflex
 * while holding a netherite axe. Refusing the drop is cheaper than cleaning up afterwards.
 * <p>
 * Optional by design: without it the tools are still marked and still flagged when found
 * outside staff mode, you just lose the prevention.
 */
@Mixin(Player.class)
public class PlayerDropMixin {

	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;",
			at = @At("HEAD"), cancellable = true)
	private void staffcore$keepStaffTools(ItemStack stack, boolean includeThrowerName,
			CallbackInfoReturnable<ItemEntity> cir) {

		if (!StaffToolset.isStaffTool(stack)) return;

		// Returning null is how vanilla reports "nothing was dropped", so the stack stays
		// exactly where it was rather than disappearing.
		cir.setReturnValue(null);

		if ((Object) this instanceof ServerPlayer player) {
			player.sendSystemMessage(Theme.warn("Staff tools stay with you. Clock off to drop your own items."));
			Sfx.deny(player);
			player.containerMenu.broadcastChanges();
		}
	}
}
