package io.github.alphain24.staffcore.api;

/**
 * What linking or unlinking did.
 *
 * @param done          whether anything changed
 * @param minecraftName the account involved, when there is one
 * @param message       what to show the Discord user, either way
 */
public record DiscordLinkResult(boolean done, String minecraftName, String message) {}
