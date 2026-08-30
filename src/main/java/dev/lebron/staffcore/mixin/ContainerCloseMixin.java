package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.modules.grief.GriefModule;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the container watch when a player shuts a chest.
 * <p>
 * There is no Fabric event for closing a container, and {@code doCloseContainer} is the one
 * method every route converges on — pressing Escape, walking away, being teleported, or
 * disconnecting. Diffing here rather than on every slot click means shift-clicking and
 * drag-distribution need no special handling at all.
 */
@Mixin(ServerPlayer.class)
public class ContainerCloseMixin {

	@Inject(method = "doCloseContainer", at = @At("HEAD"))
	private void staffcore$recordContainerChanges(CallbackInfo ci) {
		GriefModule grief = StaffCore.modules().get("grief", GriefModule.class).orElse(null);
		if (grief != null) {
			grief.onContainerClosed((ServerPlayer) (Object) this);
		}
	}
}
