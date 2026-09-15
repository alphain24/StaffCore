package io.github.alphain24.staffcore.permission;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a Discord user may do in StaffCore: the smaller of what their Discord roles say and what
 * their linked Minecraft account holds in game.
 *
 * <h2>Why the intersection, and not either side alone</h2>
 * Roles alone would make a Discord server the permission system. Anybody who can hand out a
 * role — a Discord admin, a bot with Manage Roles, a stolen moderator account — could then hand
 * out bans on the Minecraft server, and "a Discord role compromise must not become a Minecraft
 * admin compromise" would be false on day one.
 * <p>
 * The in-game account alone would make every linked staff member a full staff member on Discord
 * with no way for the owner to say "ban from in game, not from a phone". Mapping roles to nodes
 * is that choice, made explicitly.
 * <p>
 * Both together means each side can only take away. A compromised role grants nothing the
 * account did not already have; a demotion in game takes effect on Discord at the next action,
 * because nothing is cached; and an unlinked account holds nothing at all.
 * <p>
 * Pure on purpose — no server, no Discord — so the rule is tested as a rule.
 */
public final class DiscordAuthority {
	private DiscordAuthority() {}

	/**
	 * What somebody ends up holding, and when it is less than their roles suggest, why.
	 *
	 * @param limitation null when nothing got in the way; otherwise one sentence for the user
	 */
	public record Grant(Set<String> nodes, String limitation) {

		public boolean holds(String node) {
			return nodes.contains(node);
		}
	}

	/**
	 * @param linked    whether the Discord account is linked to a Minecraft account
	 * @param banned    whether that Minecraft account is banned
	 * @param inGame    what the account could run in game right now, from {@link Rank#inGame};
	 *                  null when unlinked
	 * @param roleNodes the nodes the user's Discord roles map to in the companion's config
	 * @param known     every node StaffCore defines; anything else in the role mapping is ignored
	 */
	public static Grant grant(boolean linked, boolean banned, Rank.Held inGame,
			Set<String> roleNodes, Set<String> known) {

		if (!linked || inGame == null) {
			return new Grant(Set.of(), "Your Discord account is not linked to a Minecraft account. "
					+ "Run /staff discord link in game, then /link here with the code.");
		}

		// A banned account cannot type anything in game, so it cannot do anything from here
		// either. Without this, banning a rogue staff member would leave their Discord session
		// as the one place they could still act.
		if (banned) {
			return new Grant(Set.of(), "Your linked Minecraft account is banned, so it cannot act "
					+ "from Discord.");
		}

		if (!inGame.complete()) {
			return new Grant(Set.of(), "Your permissions live in a permissions plugin, which can only "
					+ "be asked about you while you are online. Join the server and try again.");
		}

		Set<String> granted = new TreeSet<>();
		if (roleNodes != null) {
			for (String node : roleNodes) {
				if (known.contains(node) && inGame.nodes().contains(node)) granted.add(node);
			}
		}

		String limitation = null;
		if (granted.isEmpty()) {
			limitation = roleNodes == null || roleNodes.isEmpty()
					? "None of your Discord roles are mapped to StaffCore permissions."
					: "Your Discord roles are mapped to permissions your Minecraft account does not "
							+ "hold in game, and Discord can only narrow what you hold there.";
		}
		return new Grant(Collections.unmodifiableSet(new LinkedHashSet<>(granted)), limitation);
	}
}
