package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.mixin.DisplayInterpolationAccessor;
import io.github.alphain24.staffcore.modules.replay.ReplayCamera;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The camera exists, glides, and goes away again.
 *
 * <h2>What this is really checking</h2>
 * A replay used to move the viewer's own player twenty times a second. A position packet aimed
 * at the receiving player is authoritative — the client snaps to it and does no smoothing —
 * so twenty updates a second looked like twenty frames a second however fast the machine drew.
 * <p>
 * The camera is an {@code ItemDisplay} the staff member spectates instead, because entity
 * positions <em>are</em> interpolated across frames. Except a {@code Display} builds its
 * interpolation handler with <b>zero steps</b>, so it snaps too unless told otherwise. That is
 * set through a private static field reached by an accessor mixin, and an accessor that fails
 * to apply throws at the moment somebody opens a replay.
 * <p>
 * Whether the result <em>looks</em> smooth is a question about a client and is in
 * {@code docs/manual-checks/replay.md}. What can be checked here is every link in the chain
 * that has to hold for the answer to be yes.
 */
public class ReplayCameraTests {

	@GameTest
	public void theAccessorReachesTheInterpolationField(GameTestHelper helper) {
		// If the mixin stops applying, this throws rather than returning something wrong —
		// which is the good failure, and this is where it should surface rather than under a
		// staff member opening a replay.
		Harness.check(helper, DisplayInterpolationAccessor.staffcore$posRotDuration() != null,
				"the Display interpolation accessor did not apply, so the camera cannot be told "
						+ "to glide and a replay would be exactly as choppy as before");
		helper.succeed();
	}

	@GameTest
	public void aCameraIsCreatedAndGlides(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer staff = Harness.mockPlayer(helper);
		var where = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));

		Entity camera = ReplayCamera.open(staff, level, where.getX(), where.getY(), where.getZ(),
				0, 0);

		Harness.check(helper, camera != null, "no camera was created");
		Harness.check(helper, !camera.isRemoved(), "the camera was removed as soon as it existed");

		int duration = camera.getEntityData()
				.get(DisplayInterpolationAccessor.staffcore$posRotDuration());
		Harness.check(helper, duration > 0,
				"the camera's interpolation duration is " + duration + ", so the client snaps "
						+ "to each position instead of gliding between them — which is the "
						+ "original complaint, reproduced by the thing meant to fix it");

		Harness.check(helper, staff.getCamera() == camera,
				"the staff member is not looking through the camera, so moving it changes "
						+ "nothing they can see");

		ReplayCamera.close(staff, camera);
		helper.succeed();
	}

	@GameTest
	public void closingGivesTheirEyesBackAndRemovesIt(GameTestHelper helper) {
		// The failure this rules out is the worst one available: a staff member left
		// spectating an entity that no longer exists, which is a client with no camera at all.
		ServerLevel level = helper.getLevel();
		ServerPlayer staff = Harness.mockPlayer(helper);
		var where = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));

		Entity camera = ReplayCamera.open(staff, level, where.getX(), where.getY(), where.getZ(),
				0, 0);
		Harness.check(helper, camera != null, "no camera was created");

		ReplayCamera.close(staff, camera);

		Harness.check(helper, staff.getCamera() == staff,
				"closing the replay left the staff member spectating something else");
		Harness.check(helper, camera.isRemoved(),
				"the camera entity outlived the replay, so it is now an invisible passenger in "
						+ "the world nobody can see or remove");
		helper.succeed();
	}

	// There is deliberately no gametest for ReplayCamera.sweep.
	//
	// It removes every camera in every level, and gametests inside a batch run at the same
	// time — so a test calling it would discard the cameras the tests beside it are part way
	// through asserting on. That is precisely the hazard the shared-state audit found in
	// MaintenanceTests, arriving in new code a day later, which is the argument for the rule
	// rather than against it.
	//
	// It is not untested: a real boot logs "Removed N replay camera(s) left by a previous run"
	// when it finds one, and it did.
}
