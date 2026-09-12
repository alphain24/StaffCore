package io.github.alphain24.staffcore.modules.replay;

import io.github.alphain24.staffcore.StaffCore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;

/**
 * An invisible thing to look through, so the replay is not a slideshow.
 *
 * <h2>Why the camera is an entity and not the player</h2>
 * The playback driver already sends twenty position updates a second, which is the server's
 * ceiling. It still looked like a low frame rate, and the reason is what was being moved rather
 * than how often.
 * <p>
 * A position packet aimed at the receiving player is <b>authoritative</b>: the client stops
 * predicting and snaps to it. There is no smoothing, by design — smoothing your own position
 * would be the client guessing where the server put you. So twenty teleports a second are
 * twenty discrete camera positions a second, and a machine drawing at 144 frames per second
 * shows each of them seven times.
 * <p>
 * Every <em>other</em> entity is interpolated. The client receives their positions at the same
 * twenty a second and lerps across the frames between, which is why a walking mob looks smooth
 * and a teleporting player does not. So the replay moves an entity and lets the staff member
 * spectate it, and the smoothness comes from the client for free.
 *
 * <h2>Why the player still has to follow it</h2>
 * {@code ServerPlayer.setCamera} teleports the player to the entity and updates their chunk
 * loading — <b>once</b>, when it is called. It does not keep them together afterwards.
 * <p>
 * That matters twice over: chunks are loaded around the player rather than the camera, and an
 * entity that leaves the player's tracking range stops being sent to them at all — which would
 * freeze the view completely. At sixteen times speed the camera covers sixty blocks a second,
 * so it can outrun its own viewer in well under a second.
 * <p>
 * So the player is teleported to the camera whenever they drift past {@link #LEASH} blocks of
 * it. That teleport is invisible: the view is the camera's, and the body it snaps is one nobody
 * is looking through.
 *
 * <h2>What it is made of</h2>
 * An {@code ItemDisplay} holding no item: no hitbox to walk into or hit, no gravity, no AI, no
 * physics, and it renders nothing at all. An invisible armour stand was the first choice and is
 * worse on every count \u2014 {@code setMarker} is private in 26.2, so it would keep a hitbox
 * somebody could bump into without being able to see it.
 */
public final class ReplayCamera {
	private ReplayCamera() {}

	/**
	 * How far the player may drift from the camera before being pulled to it.
	 * <p>
	 * Well inside entity tracking range, so the camera is never at risk of stopping being sent
	 * to the person looking through it, and far enough that the follow is occasional rather
	 * than another per-tick teleport.
	 */
	private static final double LEASH = 24.0;

	/** Marks these so a leftover one can be found and removed after a crash. */
	public static final String TAG = "staffcore_replay_camera";

	/**
	 * How far above the recorded position the camera sits.
	 *
	 * <h2>Why this is not zero, which is what it was</h2>
	 * The position log records {@code player.getY()}, which is the player's <b>feet</b>. That is
	 * the right thing to record — it is where the player was — and the old path teleported the
	 * viewer there, so their eyes ended up at feet plus eye height without anybody thinking
	 * about it.
	 * <p>
	 * A camera entity has no such courtesy. An {@code ItemDisplay} is dimensionless, so its view
	 * is at its position exactly, which put the camera at the subject's feet: a block and a half
	 * too low, and therefore <b>inside the floor</b> for anybody standing on ground. The symptom
	 * was a view that intersected the terrain, showing block interiors and unlit voids rather
	 * than the world.
	 * <p>
	 * 1.62 is a standing player's eye height. The log does not record pose, so a subject who was
	 * sneaking (1.27) or crawling (0.4) is reconstructed slightly high — which is the right
	 * direction to be wrong in, because too high looks over the ground and too low looks through
	 * it.
	 */
	private static final double EYE_HEIGHT = 1.62;

	/**
	 * How many ticks the client spends gliding to each new camera position.
	 * <p>
	 * The driver posts a position every tick, so two is the shortest value that always has
	 * somewhere to glide to. Higher is smoother and lags further behind the recorded path,
	 * which for evidence is the wrong trade: the camera should be where the player was, not
	 * where they were a moment ago.
	 */
	private static final int INTERPOLATION_TICKS = 2;

	/**
	 * Creates the camera and points the staff member through it.
	 *
	 * @return the camera, or null if it could not be created — in which case the caller should
	 *         carry on moving the player directly rather than refusing to replay
	 */
	public static Entity open(ServerPlayer staff, ServerLevel level, double x, double y, double z,
			float yaw, float pitch) {

		if (staff == null || level == null) return null;

		try {
			// An ItemDisplay holding no item. It has no hitbox to walk into or hit, no
			// gravity, no AI and no physics, and it renders nothing at all — which is exactly
			// what a camera should be. An invisible armour stand was the first choice and is
			// worse on every count: setMarker is private in 26.2, so it keeps a hitbox.
			Display.ItemDisplay camera =
					new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);

			// Eye height, not the recorded position: the log stores feet, and a camera entity
			// has no eye height of its own to add.
			camera.snapTo(x, y + EYE_HEIGHT, z, yaw, pitch);
			camera.setInvulnerable(true);
			camera.setSilent(true);
			camera.addTag(TAG);

			// Without this the whole exercise is pointless. A Display builds its interpolation
			// handler with zero steps, so it snaps exactly like the player teleport this is
			// replacing. Two ticks is the shortest span that covers the gap between our
			// updates without the camera lagging visibly behind the path.
			camera.getEntityData().set(
					io.github.alphain24.staffcore.mixin.DisplayInterpolationAccessor
							.staffcore$posRotDuration(), INTERPOLATION_TICKS);

			if (!level.addFreshEntity(camera)) return null;

			// Teleports the player to it and moves their chunk loading with them. Once.
			staff.setCamera(camera);
			return camera;
		} catch (RuntimeException e) {
			StaffCore.LOGGER.error("[Replay] could not create a camera for {}; falling back to "
					+ "moving the player directly",
					io.github.alphain24.staffcore.compat.Mc.name(staff), e);
			return null;
		}
	}

	/**
	 * Puts the camera where the replay says to look, and drags the player along if they have
	 * fallen behind.
	 * <p>
	 * {@code moveTo} rather than {@code teleportTo}: the entity tracker turns an ordinary move
	 * into the packets the client interpolates, and a teleport into the one it does not.
	 */
	public static void moveTo(ServerPlayer staff, Entity camera, double x, double y, double z,
			float yaw, float pitch) {

		if (camera == null || camera.isRemoved()) return;

		// snapTo, which is moveTo in most versions and was renamed in 26.2. It sets the
		// position the tracker then turns into the ordinary movement packets a client
		// interpolates — as opposed to a teleport, which is the thing it does not.
		camera.snapTo(x, y + EYE_HEIGHT, z, yaw, pitch);
		camera.setYHeadRot(yaw);

		if (staff == null || staff.level() != camera.level()) return;

		// Only when they have drifted. Doing this every tick would put back exactly the
		// per-tick player teleport this class exists to remove.
		//
		// The full form, not teleportTo(x, y, z): that one builds its move with yaw and pitch
		// of zero, so every leash pull silently span the viewer's own body to face north. It
		// is invisible while they are looking through the camera and very visible the moment
		// they are not.
		// The player goes to the recorded position rather than to the camera's, because the
		// camera is an eye and the player is a body. Nobody sees the difference — they are
		// looking through the camera — but putting their feet where somebody's eyes were is
		// the kind of thing that is wrong for a year before anybody notices.
		if (staff.distanceToSqr(camera) > LEASH * LEASH
				&& staff.level() instanceof ServerLevel level) {
			staff.teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, false);
		}
	}

	/**
	 * Gives the staff member their own eyes back and removes the camera.
	 * <p>
	 * The camera is dropped before the view is restored would be the wrong order — a player
	 * spectating an entity that has just been removed is a client with no camera at all.
	 */
	public static void close(ServerPlayer staff, Entity camera) {
		if (staff != null) staff.setCamera(staff);
		if (camera != null && !camera.isRemoved()) camera.discard();
	}

	/**
	 * Removes cameras left behind by a server that stopped mid-replay.
	 * <p>
	 * They are invisible, have no hitbox and do nothing, so a stray one is harmless — but
	 * harmless invisible entities accumulating in a world is how somebody ends up debugging an
	 * entity count months later. Called once after start.
	 *
	 * @return how many were removed
	 */
	public static int sweep(net.minecraft.server.MinecraftServer server) {
		if (server == null) return 0;

		int removed = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity.entityTags().contains(TAG)) {
					entity.discard();
					removed++;
				}
			}
		}
		if (removed > 0) {
			StaffCore.LOGGER.info("[Replay] Removed {} replay camera(s) left by a previous run",
					removed);
		}
		return removed;
	}
}
