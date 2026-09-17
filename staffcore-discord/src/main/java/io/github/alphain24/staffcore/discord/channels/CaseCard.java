package io.github.alphain24.staffcore.discord.channels;

import io.github.alphain24.staffcore.api.DiscordCase;
import io.github.alphain24.staffcore.discord.channels.Outbound.Button;
import io.github.alphain24.staffcore.discord.channels.Outbound.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A case's card in the cases channel: everything in the case at a glance, and the buttons to look
 * deeper or hold the player.
 * <p>
 * Drawn from the snapshot StaffCore sends whenever the case changes, and edited in place each time,
 * so the card is always the case as it is now; the thread under it is the case's history as it
 * happened.
 */
public final class CaseCard {
	private CaseCard() {}

	/** How many history lines the card shows; the Details button has more. */
	static final int LATEST = 5;

	/** What a case's card is remembered under. The case's thread is {@code case:<id>}. */
	public static String key(String caseId) {
		return "casecard:" + caseId;
	}

	public static Message message(DiscordCase c, String headUrl) {
		boolean closed = c.closedAt() != null;
		Embed.Builder embed = Embed.builder("Case " + c.id() + " · " + name(c))
				.color(colour(c))
				.thumbnail(headUrl)
				.description(c.summary() == null || c.summary().isBlank() ? null : Text.safe(c.summary(), 1000))
				.inline("Status", status(c.status()))
				.inline("Kind", Text.safe(c.category(), 64))
				.inline("Severity", c.severity() + "/100")
				.inline("Assignee", c.assignedTo() == null || c.assignedTo().isBlank() ? "Unassigned"
						: Text.safe(c.assignedTo(), 100))
				.inline("Opened", Text.when(c.openedAt()))
				.inline("Opened by", actor(c.openedBy()))
				.field("In this case", count(c.signals(), "signal") + " · " + count(c.evidence(), "piece")
						+ " of evidence · " + count(c.links(), "linked record"));
		if (closed) {
			embed.field("Closed", "By " + actor(c.closedBy()) + " " + Text.relative(c.closedAt())
					+ (c.resolution() == null || c.resolution().isBlank() ? "" : " — " + Text.safe(c.resolution(), 300)));
		}
		String latest = latest(c.events());
		if (!latest.isEmpty()) embed.field("Latest", latest);
		embed.footer("Case " + c.id()).timestamp(c.openedAt());
		return new Message(null, embed.build(), buttons(c.id(), c.subjectId(), closed));
	}

	/**
	 * Looking into the case on the first row; the player on the second. Holding the player makes no sense
	 * once the case is closed, so that row goes quiet then; looking still works.
	 */
	static List<List<Button>> buttons(String caseId, UUID player, boolean closed) {
		List<List<Button>> rows = new ArrayList<>();
		List<Button> look = new ArrayList<>(List.of(
				new Button("sc:case:" + caseId, "Details", Button.Style.PRIMARY, false),
				new Button("sc:caseev:" + caseId, "Evidence", Button.Style.SECONDARY, false)));
		if (player != null) {
			look.add(new Button("sc:notes:" + player, "Notes", Button.Style.SECONDARY, false));
			look.add(new Button("sc:history:" + player, "History", Button.Style.SECONDARY, false));
			look.add(new Button("sc:profile:" + player, "Profile", Button.Style.SECONDARY, false));
		}
		rows.add(look);

		List<Button> act = new ArrayList<>();
		if (player != null) {
			act.add(new Button("sc:freeze:" + player, "Freeze", Button.Style.DANGER, closed));
			act.add(new Button("sc:unfreeze:" + player, "Unfreeze", Button.Style.SUCCESS, closed));
		}
		act.add(new Button("sc:casenote:" + caseId, "Add Note", Button.Style.SECONDARY, closed));
		rows.add(act);
		return rows;
	}

	private static String name(DiscordCase c) {
		return c.subjectName() == null || c.subjectName().isBlank() ? "unknown player" : c.subjectName();
	}

	/** Red for the serious ones while open; once closed, what it came to. */
	static int colour(DiscordCase c) {
		String status = c.status() == null ? "" : c.status();
		return switch (status) {
			case "actioned" -> Router.BAN;
			case "cleared" -> Router.CLEARED;
			case "stale" -> Router.QUIET;
			default -> c.severity() >= 90 ? Router.SEVERE : c.severity() >= 70 ? Router.SERIOUS : Router.REPORT;
		};
	}

	static String status(String stored) {
		if (stored == null || stored.isBlank()) return "Open";
		return Character.toUpperCase(stored.charAt(0)) + stored.substring(1).toLowerCase(Locale.ROOT);
	}

	private static String actor(String who) {
		if (who == null || who.isBlank() || "system".equalsIgnoreCase(who)) return "StaffCore";
		return Text.safe(who, 100);
	}

	private static String count(int n, String what) {
		return n + " " + what + (n == 1 ? "" : "s");
	}

	/** The newest few history lines, newest first. */
	static String latest(List<DiscordCase.Event> events) {
		StringBuilder out = new StringBuilder();
		int shown = 0;
		for (DiscordCase.Event e : events) {
			if (shown++ >= LATEST) break;
			String body = e.body() == null || e.body().isBlank() ? "" : " — " + Text.safe(e.body(), 140);
			String line = Text.relative(e.at()) + " **" + actor(e.actor()) + "** " + Text.safe(e.kind(), 32) + body;
			if (out.length() + line.length() + 1 > 1000) break;
			if (!out.isEmpty()) out.append('\n');
			out.append(line);
		}
		return out.toString();
	}
}
