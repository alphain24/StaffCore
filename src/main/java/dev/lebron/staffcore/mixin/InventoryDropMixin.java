package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.modules.staffmode.StaffToolset;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Staff tools never hit the ground on death.
 * <p>
 * {@code Player#drop} only covers the manual Q-drop; dying goes through
 * {@code Inventory#dropAll}, which is why tools were still spilling onto the floor for
 * anyone to pick up. Clearing them at the head of that method means the drop loop finds
 * nothing to spill — and since the real inventory is safely in the stash, deleting the
 * tools costs nothing. They are handed out fresh on the next clock-on.
 */
@Mixin(Inventory.class)
public class InventoryDropMixin {

	@Inject(method = "dropAll", at = @At("HEAD"))
	private void staffcore$keepToolsOutOfTheWorld(CallbackInfo ci) {
		Inventory self = (Inventory) (Object) this;

		for (int slot = 0; slot < self.getContainerSize(); slot++) {
			ItemStack stack = self.getItem(slot);
			if (StaffToolset.isStaffTool(stack)) {
				self.setItem(slot, ItemStack.EMPTY);
			}
		}
	}
}
