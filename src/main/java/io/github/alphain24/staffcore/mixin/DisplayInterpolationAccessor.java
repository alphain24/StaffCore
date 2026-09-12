package io.github.alphain24.staffcore.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the field that decides whether a {@link Display} entity glides or snaps.
 *
 * <h2>Why this is needed</h2>
 * The replay camera is an {@code ItemDisplay} the staff member spectates, so that the client
 * interpolates the motion across frames instead of being teleported twenty times a second.
 * <p>
 * Except a {@code Display} builds its {@code InterpolationHandler} with <b>zero steps</b> —
 * {@code iconst_0} in the 26.2 constructor — so out of the box it does not interpolate at all.
 * That is deliberate: vanilla displays are positioned by command and are supposed to snap unless
 * somebody asks otherwise, which is what the {@code teleport_duration} field is for.
 * <p>
 * Without setting it, the camera would have reproduced exactly the judder it exists to remove,
 * and it would have looked identical to the bug. That was caught by reading the constructor
 * rather than by assuming an entity is an entity.
 *
 * <h2>Why an accessor rather than something cleverer</h2>
 * {@code DATA_POS_ROT_INTERPOLATION_DURATION_ID} is private and static, and there is no setter.
 * An accessor is the smallest possible thing that reaches it: it adds no behaviour, injects into
 * no method, and cannot change what vanilla does. The alternative was reconstructing the
 * {@code EntityDataAccessor} from a guessed index, which would break silently the first time
 * Mojang added a field above it.
 * <p>
 * Non-fatal. If it stops applying, {@code ReplayCamera} falls back to moving the player directly
 * and the replay is choppy rather than broken.
 */
@Mixin(Display.class)
public interface DisplayInterpolationAccessor {

	@Accessor("DATA_POS_ROT_INTERPOLATION_DURATION_ID")
	static EntityDataAccessor<Integer> staffcore$posRotDuration() {
		throw new AssertionError("mixin accessor not applied");
	}
}
