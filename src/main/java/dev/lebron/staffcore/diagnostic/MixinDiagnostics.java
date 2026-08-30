package dev.lebron.staffcore.diagnostic;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.mixin.Mixins;

/**
 * Registers the mixin failure recorder before any mixin has a chance to fail.
 * <p>
 * Timing is the whole reason this class exists. {@link MixinFailureRecorder} can only be
 * told about a failure if Mixin already knows it is there, and mixins are applied as the
 * game's classes load — which is long before a mod's {@code main} entrypoint runs.
 * {@code preLaunch} is the one hook that comes early enough.
 * <p>
 * Registration is by class <em>name</em>, so nothing is loaded here and no game class is
 * touched — which is exactly the constraint {@code preLaunch} imposes.
 * <p>
 * An earlier attempt used {@code META-INF/services}, on the assumption that a documented
 * extension interface would be discovered the way service interfaces usually are. Mixin
 * does not look there: it reads its own registry, populated through
 * {@link Mixins#registerErrorHandlerClass}. The symptom was a diagnostic that reported
 * every mixin healthy while two of them were visibly failing in the log above it.
 */
public final class MixinDiagnostics implements PreLaunchEntrypoint {

	@Override
	public void onPreLaunch() {
		Mixins.registerErrorHandlerClass(MixinFailureRecorder.class.getName());
	}
}
