package io.github.alphain24.staffcore.api;

/**
 * One punishment, as a Discord user with the history permission may read it.
 *
 * @param expiresAt  null for permanent, or for a type with no duration
 * @param reversedBy who lifted it, or null while it stands or after it expired
 * @param caseId     the case it came from, or null
 */
public record DiscordPunishment(long id, String type, String reason, String staffName, long issuedAt,
		Long expiresAt, boolean active, String reversedBy, String caseId) {}
