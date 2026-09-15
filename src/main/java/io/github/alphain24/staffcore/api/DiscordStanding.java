package io.github.alphain24.staffcore.api;

import java.util.Set;
import java.util.UUID;

/**
 * Who a Discord user is to StaffCore at this moment.
 *
 * @param linked        whether their Discord account is linked to a Minecraft account
 * @param minecraftId   the linked account, or null
 * @param minecraftName the linked account's name when it was linked, or null
 * @param nodes         what they may use from Discord right now — roles narrowed by the game
 * @param limitation    why that is less than their roles suggest, or null
 */
public record DiscordStanding(boolean linked, UUID minecraftId, String minecraftName,
		Set<String> nodes, String limitation) {

	public DiscordStanding {
		nodes = nodes == null ? Set.of() : Set.copyOf(nodes);
	}

	public boolean holds(String node) {
		return nodes.contains(node);
	}
}
