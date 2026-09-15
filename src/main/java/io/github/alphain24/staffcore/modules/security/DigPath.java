package io.github.alphain24.staffcore.modules.security;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;

/**
 * One player's tunnel, as straight runs between changes of direction.
 *
 * <h2>Why a tunnel's shape says anything</h2>
 * Somebody digging blind chooses a direction for reasons that have nothing to do with the ore
 * inside the rock: the way they were already going, the edge of a claim, a hunch. Somebody who
 * can see through the rock chooses the direction the ore is in. Each straight run — a leg — is
 * one such choice, and what a leg uncovers can be set beside what the directions they did not
 * take would have uncovered over the same distance. That comparison is made in
 * {@link OreSense}; this class only works out where the legs are.
 *
 * <h2>How a leg is read out of breaks</h2>
 * The heading is taken over the last {@value #WINDOW} rock breaks rather than from one to the
 * next, because a two-high tunnel alternates between the lower and upper block and the step
 * between two breaks is as often straight up as it is forwards. A heading has to hold for
 * {@value #CONFIRM} breaks before it counts, so mining around a vein — which jumps between
 * neighbours in every direction — does not read as a string of turns. Ore breaks are not fed in
 * at all, for the same reason. A break more than {@value #JUMP} blocks from the last one means
 * the player walked, and whatever leg they were on has ended.
 * <p>
 * Pure: positions in, legs out. No world, so the whole of it can be tested headlessly and the
 * simulation drives exactly this code.
 */
public final class DigPath {

	/** Rock breaks the heading is read over. */
	static final int WINDOW = 6;

	/** Consecutive breaks a new heading must hold before the old leg is closed. */
	static final int CONFIRM = 2;

	/** A break further than this from the previous one (on any axis) means the player walked. */
	static final int JUMP = 3;

	/** Shorter legs are mining around something, not choosing a direction. */
	public static final int MIN_LEG = 4;

	/**
	 * A straight run of tunnel.
	 *
	 * @param start    where it began — the corner the turn was made at
	 * @param heading  the way it went
	 * @param previous the way the leg before it went, or null; its tunnel is behind the corner,
	 *                 so it is never an alternative the player could have chosen
	 * @param length   blocks advanced along the heading
	 * @param faces    sealed rock faces the breaks on it opened — how much rock it looked into
	 * @param finds    hidden veins and decoys uncovered while digging it
	 */
	public record Leg(BlockPos start, Direction heading, Direction previous, int length, int faces,
			int finds) {}

	private final ArrayDeque<BlockPos> recent = new ArrayDeque<>();
	private BlockPos last;

	private Direction heading;
	private Direction previous;
	private BlockPos legStart;
	private int legLength;
	private int legFaces;
	private int legFinds;

	private Direction candidate;
	private int candidateRuns;

	/**
	 * One rock break.
	 *
	 * @return the leg this break ended, or null
	 */
	public Leg rock(BlockPos pos) {
		Leg ended = null;
		if (last != null && jumped(last, pos)) {
			ended = close();
			previous = null;
			recent.clear();
			candidate = null;
			candidateRuns = 0;
		}
		last = pos.immutable();
		recent.addLast(last);
		if (recent.size() > WINDOW) recent.removeFirst();
		if (recent.size() < WINDOW) return ended;

		Direction now = headingOf(recent.peekFirst(), last);
		if (now == null) {
			candidate = null;
			candidateRuns = 0;
			return ended;
		}
		if (now == heading) {
			legLength = Math.max(legLength, along(legStart, last, heading));
			candidate = null;
			candidateRuns = 0;
			return ended;
		}

		if (now == candidate) candidateRuns++;
		else {
			candidate = now;
			candidateRuns = 1;
		}
		if (candidateRuns < CONFIRM) return ended;

		Leg closed = close();
		heading = now;
		legStart = recent.peekFirst();
		legLength = along(legStart, last, heading);
		legFaces = 0;
		legFinds = 0;
		candidate = null;
		candidateRuns = 0;
		return closed != null ? closed : ended;
	}

	/** What the latest break opened and uncovered, counted to the leg being dug. */
	public void opened(int faces, int finds) {
		if (heading == null) return;
		legFaces += Math.max(0, faces);
		legFinds += Math.max(0, finds);
	}

	/** The leg in progress, closed as it stands — for a session that ends mid-tunnel. */
	public Leg finish() {
		Leg closed = close();
		recent.clear();
		last = null;
		previous = null;
		return closed;
	}

	private Leg close() {
		if (heading == null) return null;
		Leg leg = legLength >= MIN_LEG && legFaces > 0
				? new Leg(legStart, heading, previous, legLength, legFaces, legFinds)
				: null;
		previous = heading;
		heading = null;
		legFaces = 0;
		legFinds = 0;
		legLength = 0;
		return leg;
	}

	/**
	 * The direction from one break to another, or null when it is not clearly one.
	 * <p>
	 * Horizontal when it has gone at least two blocks along one horizontal axis and further
	 * along that than any other; a staircase counts as the way it is going across, not down.
	 * Straight down or up only when it has gone at least three that way and hardly anywhere else.
	 */
	static Direction headingOf(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		int dz = to.getZ() - from.getZ();
		int across = Math.max(Math.abs(dx), Math.abs(dz));

		if (across >= 2 && Math.abs(dx) != Math.abs(dz) && across >= Math.abs(dy)) {
			return Math.abs(dx) > Math.abs(dz)
					? (dx > 0 ? Direction.EAST : Direction.WEST)
					: (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		}
		if (Math.abs(dy) >= 3 && across <= 1) return dy > 0 ? Direction.UP : Direction.DOWN;
		return null;
	}

	private static int along(BlockPos from, BlockPos to, Direction heading) {
		return (to.getX() - from.getX()) * heading.getStepX()
				+ (to.getY() - from.getY()) * heading.getStepY()
				+ (to.getZ() - from.getZ()) * heading.getStepZ();
	}

	private static boolean jumped(BlockPos a, BlockPos b) {
		return Math.abs(a.getX() - b.getX()) > JUMP || Math.abs(a.getY() - b.getY()) > JUMP
				|| Math.abs(a.getZ() - b.getZ()) > JUMP;
	}
}
