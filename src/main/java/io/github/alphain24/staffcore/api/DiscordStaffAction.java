package io.github.alphain24.staffcore.api;

/**
 * One thing a staff member did, as {@code /staff audit} shows it — which is to say without the
 * address it was done from. That is behind its own admin permission in game and is never offered to
 * Discord at all.
 *
 * @param kind   {@code punishment}, {@code inventory}, {@code case} or {@code command}
 * @param caseId the case it was done on, or null
 */
public record DiscordStaffAction(long at, String kind, String detail, String caseId) {}
