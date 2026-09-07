package io.github.alphain24.staffcore.command;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.accountability.OperationId;
import io.github.alphain24.staffcore.permission.Actor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one staff member was in the middle of, for the length of a shift.
 * <p>
 * Three things that are all the same thing: the player they last looked at, the action they
 * last took that can be taken back, and the confirmation they are partway through. Each
 * removes a step from the commonest sequence in the job — look somebody up, decide, act, and
 * occasionally undo — and each is worthless if it is even slightly stale.
 *
 * <h2>In memory, and deliberately</h2>
 * None of this is persisted. It is not a record of anything: the punishment, the rollback and
 * the audit row are all in the database with references staff were handed, so a restart costs
 * a convenience rather than evidence. Persisting it would mean a staff member logging in after
 * a weekend and finding {@code /staff undo} pointed at something they no longer remember doing
 * — which is worse than being told there is nothing to undo.
 *
 * <h2>Why the confirmation has a clock</h2>
 * A confirmation prompt shows you what an action would do. Between seeing it and confirming
 * it, the world moves: the player logs off, another staff member handles it, the chest gets
 * emptied. Confirming an hour-old preview is confirming a description of a server that no
 * longer exists, and the gap between what the screen said and what actually happens is exactly
 * where an irreversible action goes wrong.
 */
public final class StaffSession {
	private StaffSession() {}

	/** One staff member's place in what they were doing. */
	private record Session(String lastLookedUp, OperationId.Ref lastReversible,
			String pendingKey, String pendingWhat, long pendingAt) {

		static final Session EMPTY = new Session(null, null, null, null, 0L);
	}

	private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

	private static Session of(Actor actor) {
		if (actor == null || actor.id() == null) return Session.EMPTY;
		return SESSIONS.getOrDefault(actor.id(), Session.EMPTY);
	}

	private static void put(Actor actor, Session session) {
		if (actor == null || actor.id() == null) return;
		SESSIONS.put(actor.id(), session);
	}

	// ------------------------------------------------------------- the last target

	/**
	 * Remembers who a staff member just looked at.
	 * <p>
	 * The sequence this exists for is: look somebody up, read their file, decide, act. Making
	 * them retype the name for the acting half is retyping a name they are looking at, which
	 * is both irritating and the moment a spelling goes wrong.
	 */
	public static void looked(Actor actor, String name) {
		if (name == null || name.isBlank()) return;
		Session current = of(actor);
		put(actor, new Session(name, current.lastReversible(), current.pendingKey(),
				current.pendingWhat(), current.pendingAt()));
	}

	/** Who they last looked at, or null. Never used silently — see {@code targetOrLast}. */
	public static String lastLookedUp(Actor actor) {
		return of(actor).lastLookedUp();
	}

	// ---------------------------------------------------------- the last undoable

	/**
	 * Records something that can be taken back, so {@code /staff undo} has a subject.
	 * <p>
	 * One deep rather than a stack. A stack invites walking backwards through a shift, and
	 * undoing three actions in a row is not a thing anybody does deliberately — it is a thing
	 * that happens when somebody presses the same key too many times.
	 */
	public static void didSomethingUndoable(Actor actor, OperationId.Ref ref) {
		Session current = of(actor);
		put(actor, new Session(current.lastLookedUp(), ref, current.pendingKey(),
				current.pendingWhat(), current.pendingAt()));
	}

	/** The last thing this staff member did that could be taken back, or null. */
	public static OperationId.Ref lastReversible(Actor actor) {
		return of(actor).lastReversible();
	}

	/** Forgets it, once it has been undone. Undoing an undo is a different command. */
	public static void forgetReversible(Actor actor) {
		Session current = of(actor);
		put(actor, new Session(current.lastLookedUp(), null, current.pendingKey(),
				current.pendingWhat(), current.pendingAt()));
	}

	// -------------------------------------------------------------- confirmations

	/**
	 * Notes that a staff member has been shown what an action would do.
	 *
	 * @param key  identifies the exact action, so confirming one thing cannot confirm another
	 * @param what a short description, for the message when it has gone stale
	 */
	public static void staged(Actor actor, String key, String what) {
		Session current = of(actor);
		put(actor, new Session(current.lastLookedUp(), current.lastReversible(), key, what,
				System.currentTimeMillis()));
	}

	/** Whether a confirmation may go ahead, and what to say when it may not. */
	public record Confirmation(boolean allowed, String refusal) {

		static final Confirmation OK = new Confirmation(true, null);

		static Confirmation no(String refusal) {
			return new Confirmation(false, refusal);
		}
	}

	/**
	 * Consumes a staged confirmation, or explains why it cannot be honoured.
	 * <p>
	 * Consumed rather than checked, so one preview confirms one action. Holding a valid
	 * confirmation open would let a second {@code confirm} typed by reflex run the whole thing
	 * again — and the second run of a purge is not the same as the first.
	 */
	public static Confirmation claim(Actor actor, String key) {
		Session current = of(actor);
		int seconds = StaffConfig.get().confirmExpirySeconds;

		if (current.pendingKey() == null || !current.pendingKey().equals(key)) {
			return Confirmation.no("Nothing to confirm. Run the command without \"confirm\" "
					+ "first, so you can see what it would do.");
		}

		put(actor, new Session(current.lastLookedUp(), current.lastReversible(), null, null, 0L));

		long age = (System.currentTimeMillis() - current.pendingAt()) / 1000;
		if (seconds > 0 && age > seconds) {
			return Confirmation.no("That preview is " + age + " seconds old and confirmations "
					+ "expire after " + seconds + ". Run it again — what it showed you was a "
					+ "description of the server as it was then, and irreversible things go "
					+ "wrong in the gap. (" + current.pendingWhat() + ")");
		}
		return Confirmation.OK;
	}

	/** Whether a screen opened this long ago is still safe to act on. */
	public static boolean stillFresh(long openedAt) {
		int seconds = StaffConfig.get().confirmExpirySeconds;
		return seconds <= 0 || System.currentTimeMillis() - openedAt <= seconds * 1000L;
	}

	/** Drops everything for one staff member. On disconnect, and for tests. */
	public static void forget(UUID staff) {
		if (staff != null) SESSIONS.remove(staff);
	}

	/** Drops everything. Only for tests and a deliberate reset. */
	public static void forgetAll() {
		SESSIONS.clear();
	}
}
