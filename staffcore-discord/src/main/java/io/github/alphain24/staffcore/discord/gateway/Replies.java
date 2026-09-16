package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordAnswer;
import io.github.alphain24.staffcore.api.DiscordEvidence;
import io.github.alphain24.staffcore.api.DiscordProfile;
import io.github.alphain24.staffcore.api.DiscordPunishment;
import io.github.alphain24.staffcore.api.DiscordStanding;
import io.github.alphain24.staffcore.discord.channels.Text;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * What the bot says back, as plain text, apart from Discord so it can be read in a test.
 */
public final class Replies {
	private Replies() {}

	/** The answer to {@code /whoami}. */
	public static String standing(DiscordStanding standing) {
		if (!standing.linked()) return standing.limitation();

		StringBuilder out = new StringBuilder("Linked to **").append(standing.minecraftName()).append("**.");
		if (standing.nodes().isEmpty()) {
			out.append("\nYou can use nothing from Discord");
			out.append(standing.limitation() == null ? "." : ": " + standing.limitation());
		} else {
			out.append("\nYou can use: ").append(String.join(", ", standing.nodes()));
		}
		return out.toString();
	}

	/** A player's standing, for the Profile button. */
	public static String profile(DiscordAnswer<DiscordProfile> answer) {
		if (!answer.answered()) return answer.refusal();
		DiscordProfile p = answer.value();
		StringBuilder out = new StringBuilder("**").append(Text.safe(p.name(), 64)).append("**")
				.append(p.online() ? " — online" : " — offline");
		out.append("\nPunishments: ").append(p.punishments())
				.append(" · Warning points: ").append(p.warningPoints())
				.append(" · Notes: ").append(p.notes())
				.append(" · Open appeals: ").append(p.openAppeals());
		out.append("\nBan: ").append(p.activeBan() == null ? "none" : inForce(p.activeBan()));
		out.append("\nMute: ").append(p.activeMute() == null ? "none" : inForce(p.activeMute()));
		out.append("\nOpen cases: ").append(p.openCases().isEmpty() ? "none"
				: String.join(", ", p.openCases().stream().map(id -> "`" + Text.clip(id, 16).replace('`', '\'') + "`")
						.toList()));
		return out.toString();
	}

	/** Their punishments, newest first, for the History button. */
	public static String history(DiscordAnswer<List<DiscordPunishment>> answer) {
		if (!answer.answered()) return answer.refusal();
		if (answer.value().isEmpty()) return "No punishments on record.";
		StringBuilder out = new StringBuilder();
		for (DiscordPunishment p : answer.value()) {
			String line = "#" + p.id() + " **" + Text.punishment(p.type()) + "** " + Text.relative(p.issuedAt())
					+ " by " + Text.safe(p.staffName(), 32) + " — " + Text.safe(p.reason(), 120)
					+ (p.reversedBy() != null ? " *(reversed by " + Text.safe(p.reversedBy(), 32) + ")*"
							: p.active() ? " *(in force)*" : "");
			if (out.length() + line.length() + 1 > 1900) {
				out.append("\n…");
				break;
			}
			if (out.length() > 0) out.append('\n');
			out.append(line);
		}
		return out.toString();
	}

	/** One punishment in full, for the Punishment button on an appeal. */
	public static String punishment(DiscordAnswer<DiscordPunishment> answer) {
		if (!answer.answered()) return answer.refusal();
		DiscordPunishment p = answer.value();
		return "**" + Text.punishment(p.type()) + " #" + p.id() + "**"
				+ (p.reversedBy() != null ? " — reversed by " + Text.safe(p.reversedBy(), 32)
						: p.active() ? " — in force" : " — no longer in force")
				+ "\nReason: " + Text.safe(p.reason(), 600)
				+ "\nIssued by " + Text.safe(p.staffName(), 32) + " " + Text.when(p.issuedAt())
				+ "\nEnds: " + (p.expiresAt() == null ? "never" : Text.when(p.expiresAt()))
				+ (p.caseId() == null ? "" : "\nCase: `" + Text.clip(p.caseId(), 16).replace('`', '\'') + "`");
	}

	/** What is filed on the punishment's case, for the Evidence button. */
	public static String evidence(DiscordAnswer<List<DiscordEvidence>> answer) {
		if (!answer.answered()) return answer.refusal();
		if (answer.value().isEmpty()) return "The case has no evidence filed.";
		StringBuilder out = new StringBuilder();
		for (DiscordEvidence item : answer.value()) {
			String line = "• " + Text.safe(item.description(), 200) + " — filed by " + Text.safe(item.addedBy(), 32)
					+ " " + Text.relative(item.addedAt());
			if (out.length() + line.length() + 1 > 1900) {
				out.append("\n…");
				break;
			}
			if (out.length() > 0) out.append('\n');
			out.append(line);
		}
		return out.toString();
	}

	/** One piece of evidence, in full. */
	public static String evidenceDetail(io.github.alphain24.staffcore.api.DiscordEvidenceDetail detail, int attached) {
		StringBuilder out = new StringBuilder();
		out.append("**Evidence #").append(detail.id()).append("** on case `").append(Text.safe(detail.caseId(), 16))
				.append("` — ").append(Text.safe(detail.description(), 300)).append("\nFiled by ")
				.append(Text.safe(detail.addedBy(), 32)).append(' ').append(Text.relative(detail.addedAt()));
		if (detail.authorName() != null) {
			out.append("\nMessage by ").append(Text.safe(detail.authorName(), 100));
			if (detail.postedAt() != null) out.append(", ").append(Text.when(detail.postedAt()));
		}
		if (detail.messageUrl() != null) out.append("\n").append(detail.messageUrl());
		if (detail.content() != null && !detail.content().isBlank()) {
			out.append("\n> ").append(Text.safe(detail.content(), 900).replace("\n", "\n> "));
		}
		for (var file : detail.files()) {
			out.append("\n• ").append(Text.safe(file.name(), 100)).append(" (").append(size(file.sizeBytes())).append(")");
			if (file.storedPath() == null) out.append(" — not kept: ").append(Text.safe(file.notKeptWhy(), 200));
			else out.append(" — SHA-256 `").append(file.sha256(), 0, 16).append("…`");
		}
		if (detail.files().size() > attached) {
			out.append("\n").append(detail.files().size() - attached).append(" file(s) not attached here: not kept, or too large to post.");
		}
		return out.length() > 1900 ? out.substring(0, 1899) + "…" : out.toString();
	}

	static String size(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
		return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}

	/** A player's notes, newest first. */
	public static String notes(DiscordAnswer<List<io.github.alphain24.staffcore.api.DiscordNote>> answer) {
		if (!answer.answered()) return answer.refusal();
		if (answer.value().isEmpty()) return "No notes on record.";
		StringBuilder out = new StringBuilder();
		for (var note : answer.value()) {
			String line = "• " + Text.relative(note.writtenAt()) + " **" + Text.safe(note.author(), 32) + "**: "
					+ Text.safe(note.text(), 300)
					+ (note.retractedBy() == null ? "" : " *(retracted by " + Text.safe(note.retractedBy(), 32) + ")*");
			if (!append(out, line)) break;
		}
		return out.toString();
	}

	/** What a staff member did, newest first. */
	public static String staffHistory(DiscordAnswer<List<io.github.alphain24.staffcore.api.DiscordStaffAction>> answer) {
		if (!answer.answered()) return answer.refusal();
		if (answer.value().isEmpty()) return "Nothing recorded in that time.";
		StringBuilder out = new StringBuilder();
		for (var action : answer.value()) {
			String line = "• " + Text.relative(action.at()) + " " + Text.safe(action.kind(), 16) + " — "
					+ Text.safe(action.detail(), 200) + (action.caseId() == null ? "" : " `" + code(action.caseId()) + "`");
			if (!append(out, line)) break;
		}
		return out.toString();
	}

	/** A case and the latest of its history. */
	public static String caseView(DiscordAnswer<io.github.alphain24.staffcore.api.DiscordCase> answer) {
		if (!answer.answered()) return answer.refusal();
		var c = answer.value();
		StringBuilder out = new StringBuilder("**Case `" + code(c.id()) + "` · " + Text.safe(c.subjectName(), 32) + "**")
				.append("\n").append(Text.safe(c.status(), 16)).append(" · ").append(Text.safe(c.category(), 32))
				.append(" · severity ").append(c.severity())
				.append("\n").append(Text.safe(c.summary(), 300))
				.append("\nOpened ").append(Text.relative(c.openedAt())).append(" by ").append(Text.safe(c.openedBy(), 32))
				.append(" · assigned to ").append(c.assignedTo() == null ? "nobody" : Text.safe(c.assignedTo(), 32));
		if (c.closedAt() != null) {
			out.append("\nClosed ").append(Text.relative(c.closedAt())).append(" by ").append(Text.safe(c.closedBy(), 32))
					.append(c.resolution() == null ? "" : ": " + Text.safe(c.resolution(), 200));
		}
		out.append("\n").append(c.signals()).append(" signal(s) · ").append(c.evidence()).append(" evidence · ")
				.append(c.links()).append(" linked record(s)");
		if (!c.events().isEmpty()) out.append("\n\n**Latest**");
		for (var e : c.events()) {
			if (!append(out, "• " + Text.relative(e.at()) + " **" + Text.safe(e.actor(), 32) + "** "
					+ Text.safe(e.kind(), 16) + (e.body() == null ? "" : ": " + Text.safe(e.body(), 200)))) {
				break;
			}
		}
		return out.toString();
	}

	/** Server totals and staff numbers. */
	public static String analytics(DiscordAnswer<io.github.alphain24.staffcore.api.DiscordAnalytics> answer) {
		if (!answer.answered()) return answer.refusal();
		var a = answer.value();
		StringBuilder out = new StringBuilder("**Server** · " + a.punishments() + " punishment(s) · " + a.activeBans()
				+ " active ban(s) · " + a.openReports() + " open report(s) · " + a.notes() + " note(s)");
		for (var s : a.staff()) {
			String line = "• **" + Text.safe(s.name(), 32) + "** — " + s.punishments() + " punishment(s), "
					+ s.reportsHandled() + " report(s) handled (" + s.reportsResolved() + " resolved), "
					+ s.overturned() + " overturned, " + s.commands() + " command(s)"
					+ (s.lastSeen() > 0 ? ", last active " + Text.relative(s.lastSeen()) : "");
			if (!append(out, line)) break;
		}
		return out.toString();
	}

	/** Adds a line if it fits in a Discord message; says so and stops when it does not. */
	private static boolean append(StringBuilder out, String line) {
		if (out.length() + line.length() + 1 > 1900) {
			out.append("\n…");
			return false;
		}
		if (out.length() > 0) out.append('\n');
		out.append(line);
		return true;
	}

	private static String code(String id) {
		return Text.clip(id == null ? "" : id, 16).replace('`', '\'');
	}

	private static String inForce(DiscordPunishment p) {
		return Text.punishment(p.type()) + " — " + Text.safe(p.reason(), 200)
				+ (p.expiresAt() == null ? ", permanent" : ", ends " + Text.relative(p.expiresAt()));
	}

	/**
	 * What to say when StaffCore did not answer.
	 * <p>
	 * Never the exception's message: it came from somewhere in the server and could say anything.
	 */
	public static String failure(Throwable failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				? failure.getCause() : failure;
		if (cause instanceof TimeoutException) {
			return "The server did not answer in time. It may be busy; if what you asked for changes "
					+ "something, check before asking again.";
		}
		return "Something went wrong on the server, so nothing was done. The server log has the details.";
	}
}
