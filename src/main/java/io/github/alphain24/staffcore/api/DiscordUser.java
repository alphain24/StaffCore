package io.github.alphain24.staffcore.api;

import java.util.Set;

/**
 * Somebody on Discord, as the companion saw them when they clicked or typed something.
 * <p>
 * {@code roleNodes} is what the companion's role mapping says their roles are worth — and that is
 * all it is. StaffCore never takes it as a grant on its own: whatever a user asks for is checked
 * against the smaller of this and what their linked Minecraft account holds in game, so a
 * companion that got the mapping wrong, or a role handed out by mistake, can only ever take
 * permissions away.
 *
 * @param id        the Discord user id (a snowflake, as text)
 * @param name      their Discord username, for attribution only; names are not identity
 * @param roleNodes the StaffCore nodes their roles in the configured guild map to
 */
public record DiscordUser(String id, String name, Set<String> roleNodes) {

	public DiscordUser {
		roleNodes = roleNodes == null ? Set.of() : Set.copyOf(roleNodes);
	}
}
