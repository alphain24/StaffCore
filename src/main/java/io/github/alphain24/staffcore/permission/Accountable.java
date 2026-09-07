package io.github.alphain24.staffcore.permission;

/**
 * One rule: some actions require a person who can be held responsible for them.
 * <p>
 * The console, RCON, a command block, a scheduled function and an unlinked Discord user are
 * all the same case wearing different clothes. Each holds every permission or none, each is
 * attributable to no account, and if one of them does something wrong there is nobody to ask
 * about it afterwards. Treating them as separate special cases means the next way in — and
 * there is always a next way in — arrives without the check, because nobody remembered it was
 * a member of a set.
 * <p>
 * So the rule is stated once, here, in terms of the property rather than the mechanism:
 * <b>an action that exists to make somebody answerable cannot be taken by nobody.</b>
 *
 * <h2>Where it applies</h2>
 * Two-person approval, today. The point of a second signature is that a second <em>person</em>
 * read what was about to happen; a console approving is one identity-less thing agreeing with
 * another, which satisfies the letter of the check and none of its purpose. Phase 5's Discord
 * path gets the same rule for free, because an unlinked Discord user is already the same kind
 * of not-a-person.
 *
 * <h2>Where it does not</h2>
 * The console can still <em>stage</em> an action, and should be able to: an automated job that
 * proposes a mass rollback for a human to confirm is a perfectly good arrangement, and it is
 * the confirmation that needs a name behind it, not the proposal.
 */
public final class Accountable {
	private Accountable() {}

	/** Whether this action may proceed, and what to tell the caller if not. */
	public record Verdict(boolean allowed, String refusal) {

		public static final Verdict OK = new Verdict(true, null);

		public boolean refused() {
			return !allowed;
		}
	}

	/**
	 * Requires a named person behind an action.
	 * <p>
	 * The refusal explains rather than merely denying. Somebody typing this into a console at
	 * two in the morning during an incident needs to know that the answer is "get a second
	 * person", not "you lack a permission" — the second reading sends them looking for a
	 * config key that will not help, and they will find one and turn it off.
	 *
	 * @param what the action, named for the message: "approve a mass rollback"
	 */
	public static Verdict require(Actor actor, String what) {
		if (actor == null) {
			return new Verdict(false, "There is no identity behind this request, so it cannot "
					+ what + ".");
		}
		if (actor.isAccountable()) return Verdict.OK;

		return new Verdict(false, explain(actor, what));
	}

	private static String explain(Actor actor, String what) {
		String who = switch (actor.source()) {
			case CONSOLE -> "The server console";
			case RCON -> "A remote console";
			case SCHEDULED -> "An automated task";
			case DISCORD_UNLINKED -> "A Discord account with no linked Minecraft account";
			case SYSTEM -> "StaffCore itself";
			default -> actor.name();
		};

		return "%s cannot %s. This needs somebody who can be held responsible for it, and %s "
				.formatted(who, what, actor.source() == Actor.Source.DISCORD_UNLINKED
						? "an unlinked Discord account is not tied to a person here"
						: "that is nobody in particular — it holds every permission and belongs "
								+ "to no account")
				+ "— so agreeing to something on that basis satisfies the letter of the check "
				+ "and none of its purpose. Ask a staff member who is signed in.";
	}

	/**
	 * True when this actor could be the second signature on something.
	 * <p>
	 * Separate from {@link #require} so a screen can grey a button out rather than letting
	 * somebody click it and read a refusal.
	 */
	public static boolean canApprove(Actor actor) {
		return actor != null && actor.isAccountable();
	}
}
