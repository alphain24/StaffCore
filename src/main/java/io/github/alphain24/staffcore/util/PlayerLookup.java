package io.github.alphain24.staffcore.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves names to profiles for players who are not online.
 * <p>
 * Punishments have to work on someone who already logged off — that is usually the whole
 * point — so every punishment path goes through the server's name cache rather than
 * requiring an online {@code ServerPlayer}. In 26.2 that cache lives behind
 * {@code server.services().nameToIdCache()} and deals in {@link NameAndId}.
 */
public final class PlayerLookup {
	private PlayerLookup() {}

	private static UserNameToIdResolver resolver(MinecraftServer server) {
		return server.services().nameToIdCache();
	}

	public static Optional<NameAndId> profile(MinecraftServer server, String name) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) {
			return Optional.of(online.nameAndId());
		}
		return resolver(server).get(name);
	}

	public static Optional<UUID> uuid(MinecraftServer server, String name) {
		return profile(server, name).map(NameAndId::id);
	}

	/** The online player behind a uuid, or empty when they are offline. */
	public static Optional<ServerPlayer> online(MinecraftServer server, UUID uuid) {
		return Optional.ofNullable(server.getPlayerList().getPlayer(uuid));
	}

	/**
	 * Turns a stored key back into something a person can read.
	 * <p>
	 * Records are keyed by UUID because names change and UUIDs do not, which is correct for
	 * storage and useless on a screen: a staff member reading
	 * {@code 069a79f4-44e9-4726-a5be-fca90e38aaf5 → moderator} learns nothing, and cannot
	 * even tell whether it is the account they meant. Anything that shows a stored key to a
	 * human goes through here first.
	 * <p>
	 * A key that is not a UUID is passed straight back — the permission file accepts names
	 * as well, and a name needs no resolving. A UUID nobody has ever seen is shortened rather
	 * than dropped, because the truncated form is still enough to match against a log line.
	 */
	public static String display(MinecraftServer server, String key) {
		if (server == null || key == null || key.isBlank()) return key;

		UUID uuid;
		try {
			uuid = UUID.fromString(key.trim());
		} catch (IllegalArgumentException notAUuid) {
			return key;
		}

		String name = nameOf(server, uuid, null);
		return name != null ? name : key.substring(0, 8) + "… (never seen)";
	}

	public static String nameOf(MinecraftServer server, UUID uuid, String fallback) {
		ServerPlayer p = server.getPlayerList().getPlayer(uuid);
		if (p != null) return p.nameAndId().name();
		return resolver(server).get(uuid).map(NameAndId::name).orElse(fallback);
	}
}
