package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Records who picked an item up off the ground.
 * <p>
 * Recovering items by scanning for them fails in every way that matters on a live server:
 * chunks unload, drops despawn after five minutes, and anything already in somebody's pocket
 * is invisible to a scan by definition. Recording the pickup turns recovery into a query, so
 * distance and chunk state stop mattering.
 * <p>
 * <b>Why a redirect rather than an inject.</b> The amount picked up is not the amount
 * offered. {@code Inventory.add} mutates the stack in place, taking what fits and leaving the
 * rest on the floor, so the only way to know what actually changed hands is to measure the
 * stack either side of that one call. Injecting at the head of the method would record what
 * was on offer, and charging somebody for items a full inventory refused is how a repair
 * turns into a punishment.
 * <p>
 * Non-fatal like almost every hook here: if it stops applying, item recovery falls back to
 * scanning the ground and the startup check names it.
 */
@Mixin(ItemEntity.class)
public class ItemPickupMixin {

	@Redirect(
			method = "playerTouch",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
	private boolean staffcore$recordPickup(Inventory inventory, ItemStack stack, Player player) {
		// A copy, because add() empties the stack when everything fits — and an empty stack
		// reports its item as air, so reading the type afterwards would record every
		// successful pickup as a pickup of nothing.
		ItemStack picked = stack.copy();
		int before = stack.getCount();

		boolean added = inventory.add(stack);

		// What the inventory actually accepted. add() shrinks the stack by that amount, so
		// the difference is the true figure even when only part of it fitted.
		int taken = before - stack.getCount();

		if (taken > 0 && player instanceof ServerPlayer server) {
			GriefModule grief = StaffCore.modules().get("grief", GriefModule.class).orElse(null);
			if (grief != null) {
				ItemEntity self = (ItemEntity) (Object) this;
				grief.pickups().onPickup(server, picked, taken, self.blockPosition());
			}
		}
		return added;
	}
}
