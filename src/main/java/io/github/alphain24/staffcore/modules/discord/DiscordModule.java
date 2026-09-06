package io.github.alphain24.staffcore.modules.discord;

import com.google.gson.JsonObject;
import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Module;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Outbound Discord webhook bridge — punishments, reports and alerts land in a channel.
 * <p>
 * Fire and forget by design: {@code sendAsync} means a slow or dead webhook can never
 * stall a tick, and a failure is logged once rather than retried. Staff tooling that
 * blocks the server thread on a third-party HTTP call is worse than no bridge at all.
 * <p>
 * Payloads are built with Gson rather than string concatenation, so a player name
 * containing a quote or a backslash cannot break the JSON.
 */
public class DiscordModule implements Module {

	@Override
	public String id() {
		return "discord";
	}

	@Override
	public String displayName() {
		return "Discord";
	}

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	public boolean isConfigured() {
		String url = StaffConfig.get().discordWebhookUrl;
		return url != null && !url.isBlank();
	}

	public void postAlert(String category, String message) {
		post("**[%s]** %s".formatted(category.toUpperCase(java.util.Locale.ROOT), message));
	}

	public void postPunishment(String staff, String target, String type, String reason) {
		post("🔨 **%s** %s **%s** — %s".formatted(staff, type, target, reason));
	}

	public void postReport(String reporter, String target, String reason) {
		post("📋 **%s** reported **%s** — %s".formatted(reporter, target, reason));
	}

	private void post(String content) {
		if (!isConfigured()) return;

		JsonObject body = new JsonObject();
		// Discord caps a webhook message at 2000 characters.
		body.addProperty("content", content.length() > 1900 ? content.substring(0, 1900) + "…" : content);
		// Never let a staff message ping @everyone, whatever it contains.
		body.add("allowed_mentions", parseNoMentions());

		HttpRequest req = HttpRequest.newBuilder(URI.create(StaffConfig.get().discordWebhookUrl))
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(10))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
				.exceptionally(err -> {
					StaffCore.LOGGER.warn("[Discord] webhook failed: {}", err.getMessage());
					return null;
				});
	}

	private static JsonObject parseNoMentions() {
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new com.google.gson.JsonArray());
		return mentions;
	}
}
