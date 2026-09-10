package io.github.alphain24.staffcore.modules.replay;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Link;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.security.ReplaySession;
import io.github.alphain24.staffcore.modules.security.ReplaySidebar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watching somebody's session back, from inside it.
 *
 * <h2>Why this is playback and not a drawn line</h2>
 * The x-ray viewer draws a shape and lets you fly around it, which is right for an excavation:
 * a tunnel is a thing, and the question is what it looks like. A session is not a thing, it is
 * a sequence — the question is what somebody did and in what order, and the answer is
 * unreadable as a static line. Two players who walked the same route are the same picture and
 * completely different events.
 * <p>
 * So the staff member is moved along the path in time. The world is real and untouched, the
 * viewer is a spectator, and the only thing being reconstructed is where the camera goes.
 *
 * <h2>Gaps are skipped, and said out loud</h2>
 * Position history records movement, so a player who stood still, went AFK or logged off for
 * two hours leaves a gap between two samples. Played at real speed that is two hours of a staff
 * member watching nothing. Played by simply cutting to the next sample it is a silent teleport
 * that reads as suspicious movement.
 * <p>
 * Neither is acceptable, so a gap longer than {@link #GAP_PAUSE_MS} is held briefly, announced
 * in chat with its real length, and then skipped. What is being hidden is time, and hiding time
 * without saying so is how a replay becomes misleading evidence.
 *
 * <h2>What this is not</h2>
 * Not an anti-cheat input, and deliberately not sampled finely enough to become one. Two
 * readings a second says where somebody went; it says nothing reliable about how they moved
 * between them, and the interpolation here invents the space in between. Anything about
 * movement mechanics read off this replay would be reading the interpolation.
 */
public final class SessionReplay {
	private SessionReplay() {}

	/** How long to hold on a gap before skipping it, so the viewer sees that one happened. */
	private static final long GAP_PAUSE_MS = 3_000;

	/** A gap shorter than this is just a slow walk and is played through normally. */
	private static final long GAP_THRESHOLD_MS = 3_000;

	/** Playback speeds the command will accept. Above this it is a blur, below it is a wait. */
	private static final double MIN_SPEED = 0.25;
	private static final double MAX_SPEED = 16.0;

	/** How often the sidebar is redrawn, in ticks. Once a second is enough to read. */
	private static final int SIDEBAR_EVERY = 20;

	/** Whether a replay could be started, and why not when it could not. */
	public record Entry(boolean started, String refusal) {

		static final Entry OK = new Entry(true, null);

		static Entry no(String why) {
			return new Entry(false, why);
		}
	}

	/** One viewer's place in one track. Server thread only. */
	static final class Playback {
		final String subject;
		final PositionLog.Track track;
		final String world;

		/** Where the clock is, in the track's own wall-clock time base. */
		long clock;
		/** The frame the clock is at or just past. */
		int index;
		double speed = 1.0;
		boolean paused;
		boolean finished;
		int ticks;

		Playback(String subject, PositionLog.Track track, String world) {
			this.subject = subject;
			this.track = track;
			this.world = world;
			this.clock = track.first().at();
		}

		double progress() {
			long span = track.span();
			return span <= 0 ? 1 : Math.min(1, (clock - track.first().at()) / (double) span);
		}
	}

	private static final Map<UUID, Playback> LIVE = new ConcurrentHashMap<>();

	// ---------------------------------------------------------------- entering

	/**
	 * Reconstructs a window and starts playing it.
	 * <p>
	 * The order is the same safety story the x-ray viewer follows: the way home goes on disk
	 * before anything about the player changes, and if that write fails they do not move.
	 *
	 * @param caseId the case this was opened from, or null for an ad-hoc look
	 */
	public static Entry enter(MinecraftServer server, ServerPlayer staff, UUID subjectId,
			String subjectName, String caseId, long windowMs) {

		if (server == null || staff == null) return Entry.no("No server.");
		if (ReplaySession.isReplaying(staff.getUUID())) {
			return Entry.no("You are already in a replay. /staff replay exit first.");
		}
		if (!StaffCore.storage().isReady()) {
			return Entry.no("Storage is not available, so there is nothing to replay.");
		}

		// Said before anything is looked up, because "nothing recorded" and "not recording"
		// are the same empty screen and need opposite fixes. This is the same failure the
		// grief log's three counters exist to separate.
		if (!io.github.alphain24.staffcore.config.StaffConfig.get().positionTracking) {
			return Entry.no("Position tracking is off, so nothing was recorded to replay. "
					+ "Set positionTracking to true in config/staffcore.json — it only starts "
					+ "recording from that point, so this window will stay empty.");
		}

		long now = System.currentTimeMillis();
		PositionLog.Track track = PositionLog.reconstruct(subjectId, subjectName,
				now - windowMs, now);

		if (track.isEmpty()) {
			int days = io.github.alphain24.staffcore.config.StaffConfig.get()
					.positionRetentionDays;
			return Entry.no(subjectName + " has no recorded movement in that window. They may "
					+ "not have been online, or it may be past the "
					+ (days == 0 ? "retention window" : days + "-day retention window") + ".");
		}

		PositionLog.Frame start = track.first();
		ServerLevel level = ReplayStage.levelOf(server, start.world());
		if (level == null) {
			return Entry.no("That session was in " + start.world() + ", which this server no "
					+ "longer has.");
		}

		// Written before the player is touched at all. If this fails, they do not move.
		if (!ReplaySession.remember(staff, caseId, subjectName)) {
			return Entry.no("Could not record where you are standing, so you are staying "
					+ "there. Nothing has changed. Check the server log.");
		}

		Playback playback = new Playback(subjectName, track, start.world());
		LIVE.put(staff.getUUID(), playback);

		ReplayStage.begin(staff, level, start.x(), start.y(), start.z(),
				start.yaw(), start.pitch(), () -> LIVE.remove(staff.getUUID()));

		describe(staff, playback);
		drawSidebar(staff, playback);
		return Entry.OK;
	}

	private static void describe(ServerPlayer staff, Playback playback) {
		PositionLog.Track track = playback.track;

		staff.sendSystemMessage(Theme.prefix()
				.append(Icon.text("Replaying ", Theme.MUTED))
				.append(Icon.text(playback.subject, Theme.ACCENT))
				.append(Icon.text(" — " + io.github.alphain24.staffcore.util.TimeFormat
						.length(track.span()) + " of movement in " + playback.world,
						Theme.MUTED)));

		if (track.runs() > 1) {
			// Said plainly rather than left to be noticed. More than one run means the path
			// has holes in it, and a viewer who does not know that reads a skip as a teleport.
			staff.sendSystemMessage(Icon.text("  " + track.runs() + " separate stretches — they "
					+ "stopped, relogged or changed world in between. Gaps are announced as "
					+ "they come.", Theme.MUTED));
		}
		if (track.truncated()) {
			staff.sendSystemMessage(Theme.warn("  The window held more than "
					+ PositionLog.MAX_FRAMES + " samples and was cut short. Ask for a shorter "
					+ "one to see the end of it."));
		}

		staff.sendSystemMessage(Icon.text("  ", Theme.MUTED)
				.append(Link.run("[pause]", "/staff replay pause", Theme.ACCENT, "Hold here"))
				.append(Icon.text(" ", Theme.MUTED))
				.append(Link.suggest("[speed]", "/staff replay speed ", Theme.ACCENT,
						"0.25 to 16"))
				.append(Icon.text(" ", Theme.MUTED))
				.append(Link.run("[exit]", "/staff replay exit", Theme.ACCENT,
						"Puts you back where you were")));
	}

	// ----------------------------------------------------------------- driving

	/**
	 * Advances every running playback by one tick.
	 * <p>
	 * Called from the server tick loop. Returns immediately when nobody is watching anything,
	 * which is nearly always — this is a map emptiness check per tick and nothing else.
	 */
	public static void tick(MinecraftServer server) {
		if (LIVE.isEmpty() || server == null) return;

		for (Map.Entry<UUID, Playback> entry : Map.copyOf(LIVE).entrySet()) {
			ServerPlayer staff = server.getPlayerList().getPlayer(entry.getKey());
			if (staff == null) continue;

			Playback playback = entry.getValue();
			if (!playback.paused && !playback.finished) advance(staff, playback);

			if (++playback.ticks % SIDEBAR_EVERY == 0) drawSidebar(staff, playback);
		}
	}

	/**
	 * What one tick of playback decided to do.
	 * <p>
	 * Separated from carrying it out so that the decision can be tested without a world. The
	 * arithmetic here is where a replay becomes misleading rather than broken: an interpolation
	 * that runs past the end of a gap shows a player gliding smoothly across a distance they
	 * covered by logging off, and nothing about the result looks wrong.
	 */
	sealed interface Step {

		/** Where the camera should be this tick. */
		record Move(String world, double x, double y, double z, float yaw, float pitch)
				implements Step {}

		/** A stretch with no recorded movement, being jumped over. */
		record Skip(long gap, String fromWorld, String toWorld, double x, double y, double z,
				float yaw, float pitch) implements Step {

			boolean changesWorld() {
				return !fromWorld.equals(toWorld);
			}
		}

		/** The end of the recorded window. */
		record End() implements Step {}
	}

	/**
	 * Advances the clock by one tick and works out what that means.
	 * <p>
	 * Mutates the playback's clock and index, and touches nothing else. Fifty milliseconds of
	 * wall clock per tick scaled by the speed, not the tick count — a server running behind
	 * should replay a minute as a minute rather than as however long its ticks happened to take.
	 */
	static Step step(Playback playback) {
		List<PositionLog.Frame> frames = playback.track.frames();
		playback.clock += Math.round(50 * playback.speed);

		while (playback.index < frames.size() - 1
				&& frames.get(playback.index + 1).at() <= playback.clock) {
			playback.index++;
		}

		if (playback.index >= frames.size() - 1) return new Step.End();

		PositionLog.Frame from = frames.get(playback.index);
		PositionLog.Frame to = frames.get(playback.index + 1);
		long gap = to.at() - from.at();

		if (gap > GAP_THRESHOLD_MS && playback.clock - from.at() >= GAP_PAUSE_MS) {
			playback.clock = to.at();
			playback.index++;
			return new Step.Skip(gap, from.world(), to.world(), to.x(), to.y(), to.z(),
					to.yaw(), to.pitch());
		}

		double t = gap <= 0 ? 1 : Mth.clamp((playback.clock - from.at()) / (double) gap, 0, 1);
		return new Step.Move(from.world(),
				from.x() + (to.x() - from.x()) * t,
				from.y() + (to.y() - from.y()) * t,
				from.z() + (to.z() - from.z()) * t,
				lerpAngle(from.yaw(), to.yaw(), t),
				lerpAngle(from.pitch(), to.pitch(), t));
	}

	/** Carries out one tick of playback. */
	private static void advance(ServerPlayer staff, Playback playback) {
		switch (step(playback)) {
			case Step.End ignored -> finish(staff, playback);
			case Step.Move move -> {
				if (staff.level() instanceof ServerLevel level) {
					staff.teleportTo(level, move.x(), move.y(), move.z(), Set.of(),
							move.yaw(), move.pitch(), false);
				}
			}
			case Step.Skip skip -> skipGap(staff, playback, skip);
		}
	}

	/**
	 * Jumps over a stretch with no recorded movement, having said how long it was.
	 * <p>
	 * The announcement is the point. A replay that silently removes forty minutes shows a
	 * player in two places with nothing in between, and whoever is watching has no way to tell
	 * that from the recording being complete.
	 */
	private static void skipGap(ServerPlayer staff, Playback playback, Step.Skip skip) {
		staff.sendSystemMessage(Icon.text("  skipped "
				+ io.github.alphain24.staffcore.util.TimeFormat.length(skip.gap()) + " \u2014 "
				+ (skip.changesWorld()
						? "they went to " + skip.toWorld()
						: "no movement recorded"), Theme.MUTED));

		if (!skip.changesWorld()) return;

		// A world change inside the track means the camera has to move worlds too, and the
		// stage has to be told or it will end the replay for leaving the dimension.
		MinecraftServer server = staff.level().getServer();
		ServerLevel next = server == null ? null : ReplayStage.levelOf(server, skip.toWorld());
		if (next == null) {
			staff.sendSystemMessage(Theme.warn("  " + skip.toWorld() + " no longer exists on "
					+ "this server, so the replay stops here."));
			finish(staff, playback);
			return;
		}
		ReplayStage.begin(staff, next, skip.x(), skip.y(), skip.z(), skip.yaw(), skip.pitch(),
				() -> LIVE.remove(staff.getUUID()));
	}

	private static void finish(ServerPlayer staff, Playback playback) {
		if (playback.finished) return;
		playback.finished = true;

		staff.sendSystemMessage(Theme.prefix()
				.append(Icon.text("End of the recorded window.", Theme.MUTED)));
		staff.sendSystemMessage(Icon.text("  ", Theme.MUTED)
				.append(Link.run("[restart]", "/staff replay restart", Theme.ACCENT,
						"Play it again from the beginning"))
				.append(Icon.text(" ", Theme.MUTED))
				.append(Link.run("[exit]", "/staff replay exit", Theme.ACCENT,
						"Puts you back where you were")));
	}

	/** Interpolates an angle the short way round, so 350° to 10° is 20° and not 340°. */
	static float lerpAngle(float from, float to, double t) {
		float delta = ((to - from + 540) % 360) - 180;
		return from + (float) (delta * t);
	}

	// ---------------------------------------------------------------- controls

	public static boolean isWatching(UUID staff) {
		return LIVE.containsKey(staff);
	}

	/** @return the message to show, or null when this staff member is not watching anything */
	public static String pause(UUID staff, boolean paused) {
		Playback playback = LIVE.get(staff);
		if (playback == null) return null;

		playback.paused = paused;
		return paused ? "Held. /staff replay resume to carry on." : "Playing.";
	}

	/** @return the message to show, or null when this staff member is not watching anything */
	public static String speed(UUID staff, double speed) {
		Playback playback = LIVE.get(staff);
		if (playback == null) return null;

		playback.speed = Mth.clamp(speed, MIN_SPEED, MAX_SPEED);
		return "Playing at " + trim(playback.speed) + "×.";
	}

	/** @return the message to show, or null when this staff member is not watching anything */
	public static String restart(UUID staff) {
		Playback playback = LIVE.get(staff);
		if (playback == null) return null;

		playback.clock = playback.track.first().at();
		playback.index = 0;
		playback.finished = false;
		playback.paused = false;
		return "Back to the start.";
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	// ----------------------------------------------------------------- sidebar

	/**
	 * The four things worth having on screen while flying along somebody's path.
	 * <p>
	 * When, where, how fast it is playing, and how far through. Not who — that is in the chat
	 * line at the start and takes a whole row to repeat.
	 */
	static List<String> sidebarLines(Playback playback) {
		List<String> out = new ArrayList<>();
		List<PositionLog.Frame> frames = playback.track.frames();
		PositionLog.Frame at = frames.get(Math.min(playback.index, frames.size() - 1));

		out.add(io.github.alphain24.staffcore.util.TimeFormat.clock(playback.clock));
		out.add("%.0f, %.0f, %.0f".formatted(at.x(), at.y(), at.z()));
		out.add((playback.finished ? "ended"
				: playback.paused ? "held" : trim(playback.speed) + "× play"));
		out.add(Math.round(playback.progress() * 100) + "% of "
				+ io.github.alphain24.staffcore.util.TimeFormat.length(playback.track.span()));
		return out;
	}

	private static void drawSidebar(ServerPlayer staff, Playback playback) {
		ReplaySidebar.show(staff, sidebarLines(playback));
	}

	// -------------------------------------------------------------- lifecycle

	/** Only for tests and a deliberate reset. */
	public static void forgetAll() {
		LIVE.clear();
	}

	/** How many playbacks are running. For diagnostics. */
	public static int running() {
		return LIVE.size();
	}

	static Playback of(UUID staff) {
		return LIVE.get(staff);
	}

	static void put(UUID staff, Playback playback) {
		LIVE.put(staff, playback);
	}
}
