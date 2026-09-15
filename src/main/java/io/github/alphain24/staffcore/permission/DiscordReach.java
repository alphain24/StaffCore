package io.github.alphain24.staffcore.permission;

/**
 * The actions that stay in the game: nobody runs them from Discord, whatever they hold.
 * <p>
 * An IP ban, a mass rollback and an edit to somebody's inventory are the three things this mod
 * treats as hardest to take back, which is why each needs a second person. They are also the
 * three a stolen Discord account would reach for first, and from Discord nobody can see the
 * world the action lands in — the player who shares the banned address, the builds under the
 * rollback, the items that are about to disappear. So the rule is about the channel rather than
 * the person: a linked admin is refused exactly as an unlinked stranger is.
 * <p>
 * Checked in the service each action already funnels through, never in the Discord companion.
 * The companion is one way in; a check that lived there would be missing from the next.
 */
public final class DiscordReach {
	private DiscordReach() {}

	/** Whether this instruction came from Discord, linked or not. */
	public static boolean isDiscord(Actor actor) {
		return actor != null && (actor.source() == Actor.Source.DISCORD_LINKED
				|| actor.source() == Actor.Source.DISCORD_UNLINKED);
	}

	/**
	 * Null when the actor may go ahead; otherwise what to tell them.
	 *
	 * @param what the action, as the start of a sentence: "IP bans"
	 */
	public static String refusal(Actor actor, String what) {
		if (!isDiscord(actor)) return null;
		return what + " cannot be run from Discord, by anybody. Do it in game, where you can see "
				+ "what it lands on.";
	}
}
