package io.github.alphain24.staffcore.discord.channels;

import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.StaffCoreListener;
import io.github.alphain24.staffcore.discord.StaffCoreDiscord;
import io.github.alphain24.staffcore.discord.channels.Outbound.Button;
import io.github.alphain24.staffcore.discord.channels.Outbound.Channel;
import io.github.alphain24.staffcore.discord.channels.Outbound.Direct;
import io.github.alphain24.staffcore.discord.channels.Outbound.InThread;
import io.github.alphain24.staffcore.discord.channels.Outbound.Message;
import io.github.alphain24.staffcore.discord.channels.Outbound.Send;
import io.github.alphain24.staffcore.discord.channels.Outbound.Update;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Decides what each StaffCore event becomes in Discord.
 *
 * <h2>Threads</h2>
 * Every report, appeal and case gets a thread on its post, and what happens to it afterwards is
 * said there: a claim, a note, a verdict, the punishment that closed it. A case opened by a report is
 * discussed in the report's thread rather than in a second one, so the report and the investigation
 * it started read as one story.
 *
 * <h2>Nothing a player typed is trusted</h2>
 * Reasons, appeals, names and summaries all pass through {@link Text#safe} on the way in, and the
 * gateway sends every message with mentions switched off.
 * <p>
 * Called on StaffCore's event thread, never the server's. Decides only; the gateway posts.
 */
public final class Router implements StaffCoreListener {

	static final int BAN = 0xE0563C;
	static final int MUTE = 0xFFC93C;
	static final int WARN = 0xF5A623;
	static final int QUIET = 0x9099A2;
	static final int CLEARED = 0x5BE87A;
	static final int REPORT = 0xB15CFF;
	static final int APPEAL = 0x5865F2;
	static final int SEVERE = 0xFF5C57;
	static final int SERIOUS = 0xFF8C42;
	/** Worth a look, nothing wrong: somebody back after a ban. */
	static final int NOTICE = 0x4FA3F7;

	private static final Pattern CASE_ID = Pattern.compile("[0-9A-Z]{1,16}");
	private static final Pattern SNOWFLAKE = Pattern.compile("\\d{15,22}");

	private final DiscordSettings settings;
	private final ThreadBook book;
	private final Consumer<Outbound> outbox;

	/**
	 * What this router has already asked to be posted, by key, before Discord has necessarily done it.
	 * <p>
	 * The book only learns about a post once Discord has answered, and the gateway works through its
	 * list in order. So a claim arriving a moment after its report would find the book empty and be
	 * posted as a stray line, when by the time the gateway reaches it the report will be there to
	 * edit. Remembering what was sent, not only what arrived, makes the decision against the state
	 * the gateway will actually be in. Bounded, oldest first; the book covers anything older.
	 */
	private final java.util.Map<String, Message> sent = new java.util.LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<String, Message> eldest) {
			return size() > REMEMBERED;
		}
	};
	private final java.util.Set<String> threaded = java.util.Collections.newSetFromMap(
			new java.util.LinkedHashMap<>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
					return size() > REMEMBERED;
				}
			});

	static final int REMEMBERED = 2000;

	public Router(DiscordSettings settings, ThreadBook book, Consumer<Outbound> outbox) {
		this.settings = settings;
		this.book = book;
		this.outbox = outbox;
	}

	@Override
	public void onEvent(StaffCoreEvent event) {
		try {
			for (Outbound outbound : route(event)) outbox.accept(outbound);
		} catch (RuntimeException e) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Could not route {} ({})",
					event.getClass().getSimpleName(), e.getClass().getSimpleName());
		}
	}

	/** What this event becomes. Empty when nothing is posted for it. */
	public synchronized List<Outbound> route(StaffCoreEvent event) {
		List<Outbound> out = switch (event) {
			case StaffCoreEvent.PunishmentIssued e -> punishment(e);
			case StaffCoreEvent.PunishmentReversed e -> reversed(e);
			case StaffCoreEvent.PlayerReturned e -> returned(e);
			case StaffCoreEvent.ReportFiled e -> report(e);
			case StaffCoreEvent.ReportChanged e -> reportChanged(e);
			case StaffCoreEvent.SignalRaised e -> signal(e);
			case StaffCoreEvent.CaseOpened e -> caseOpened(e);
			case StaffCoreEvent.CaseChanged e -> caseChanged(e);
			case StaffCoreEvent.AppealFiled e -> appeal(e);
			case StaffCoreEvent.AppealDecided e -> appealDecided(e);
			case StaffCoreEvent.AppealConversation e -> appealConversation(e);
			case StaffCoreEvent.StaffAction e -> staffAction(e);
			case StaffCoreEvent.StaffChat e -> staffChat(e);
		};
		for (Outbound outbound : out) {
			if (outbound instanceof Send send && send.key() != null) {
				sent.put(send.key(), send.message());
				if (send.threadName() != null) {
					threaded.add(send.key());
					threaded.addAll(send.sharing());
				}
			} else if (outbound instanceof Update update) {
				sent.put(update.key(), update.message());
			}
		}
		return out;
	}

	/** The message last posted under this key, by this router or before a restart. */
	private Message posted(String key) {
		Message message = sent.get(key);
		if (message != null) return message;
		ThreadBook.Entry entry = book.get(key);
		return entry == null ? null : entry.message();
	}

	/** Whether this key has, or is about to have, a thread. */
	private boolean hasThread(String key) {
		return key != null && (threaded.contains(key) || book.hasThread(key));
	}

	// ------------------------------------------------------------------ punishments

	private List<Outbound> punishment(StaffCoreEvent.PunishmentIssued e) {
		if (off(Channel.PUNISHMENTS)) return List.of();

		Embed embed = Embed.builder(Text.punishment(e.type()) + " · " + e.targetName())
				.color(colour(e.type()))
				.thumbnail(head(e.targetId()))
				.field("Reason", Text.safe(e.reason(), 1000))
				.inline("Staff", Text.safe(e.staffName(), 100))
				.inline("Player", Text.safe(e.targetName(), 100) + " — " + priors(e.priorPunishments()))
				.inline("Duration", duration(e.type(), e.at(), e.expiresAt()))
				.inline("Expires", e.expiresAt() == null ? "—" : Text.when(e.expiresAt()))
				.inline("Case", caseLink(e.caseId()))
				.footer("Punishment #" + e.id())
				.timestamp(e.at())
				.build();
		return List.of(new Send(Channel.PUNISHMENTS, Message.of(embed), "punishment:" + e.id(), null, List.of()));
	}

	private List<Outbound> reversed(StaffCoreEvent.PunishmentReversed e) {
		String key = "punishment:" + e.id();
		String line = "Reversed by " + Text.safe(e.reversedBy(), 100)
				+ (e.reason() == null || e.reason().isBlank() ? "" : " — " + Text.safe(e.reason(), 600))
				+ " · " + Text.relative(e.at());

		Message posted = posted(key);
		if (posted != null && posted.embed() != null) {
			Embed changed = posted.embed().withField("Reversed", line, false).withColor(CLEARED);
			return List.of(new Update(key, posted.withEmbed(changed)));
		}
		if (off(Channel.PUNISHMENTS)) return List.of();
		Embed embed = Embed.builder(Text.punishment(e.type()) + " reversed · " + e.targetName())
				.color(CLEARED).description(line).footer("Punishment #" + e.id()).timestamp(e.at()).build();
		return List.of(new Send(Channel.PUNISHMENTS, Message.of(embed), null, null, List.of()));
	}

	/**
	 * Somebody back for the first time since a ban ended, in the alerts channel where staff watch for
	 * what needs attention. The ban's case, if it has one, hears about it through the case's own note.
	 */
	private List<Outbound> returned(StaffCoreEvent.PlayerReturned e) {
		if (off(Channel.ALERTS)) return List.of();
		String how = "LIFTED".equals(e.howEnded())
				? "Lifted by " + Text.safe(e.liftedBy() == null ? "staff" : e.liftedBy(), 100)
						+ (e.liftReason() == null || e.liftReason().isBlank() ? "" : " — " + Text.safe(e.liftReason(), 300))
						+ " · " + Text.relative(e.endedAt())
				: "Ran out " + Text.relative(e.endedAt());
		Embed.Builder embed = Embed.builder("Back after a ban · " + e.playerName())
				.color(NOTICE)
				.thumbnail(head(e.playerId()))
				.description(Text.safe(e.playerName(), 100) + " joined for the first time since this ended.")
				.field(Text.punishment(e.type()) + " #" + e.punishmentId(), Text.safe(
						e.reason() == null || e.reason().isBlank() ? "No reason given" : e.reason(), 600))
				.field("Ended", how)
				.footer("Punishment #" + e.punishmentId())
				.timestamp(e.at());
		if (e.caseId() != null) embed.inline("Case", "`" + Text.safe(e.caseId(), 16) + "`");
		return List.of(new Send(Channel.ALERTS, Message.of(embed.build()), null, null, List.of()));
	}

	// ------------------------------------------------------------------ reports

	private List<Outbound> report(StaffCoreEvent.ReportFiled e) {
		if (off(Channel.REPORTS)) return List.of();

		String caseKey = e.caseId() == null ? null : "case:" + e.caseId();
		boolean caseSharesThisThread = caseKey != null && !hasThread(caseKey);

		Embed.Builder embed = Embed.builder("Report #" + e.id() + " · " + e.targetName())
				.color(REPORT)
				.thumbnail(head(e.targetId()))
				.field("Reason", Text.safe(e.reason(), 1000))
				.inline("Player", Text.safe(e.targetName(), 100) + " — " + priors(e.priorPunishments()))
				.inline("Reporter", Text.safe(e.reporterName(), 100))
				.inline("Assignee", "Unclaimed")
				.inline("Status", "Open");
		if (!settings.serverName.isEmpty()) embed.inline("Server", Text.safe(settings.serverName, 64));
		embed.inline("Created", Text.when(e.at()))
				.inline("Case", caseSharesThisThread ? code(e.caseId()) + " — in this report's thread" : caseLink(e.caseId()))
				.footer("Report #" + e.id())
				.timestamp(e.at());

		Message message = new Message(null, embed.build(), reportButtons(e.id(), e.targetId()));
		return List.of(new Send(Channel.REPORTS, message, "report:" + e.id(),
				"Report #" + e.id() + " — " + e.targetName(),
				caseSharesThisThread ? List.of(caseKey) : List.of()));
	}

	/** Claim, resolve and escalate on the first row; looking and acting on the player on the second. */
	static List<List<Button>> reportButtons(long reportId, UUID player) {
		return List.of(
				List.of(new Button("sc:claim:" + reportId, "Claim", Button.Style.PRIMARY, false),
						new Button("sc:resolve:" + reportId, "Resolve", Button.Style.SUCCESS, false),
						new Button("sc:escalate:" + reportId, "Escalate", Button.Style.DANGER, false)),
				List.of(new Button("sc:profile:" + player, "Profile", Button.Style.SECONDARY, false),
						new Button("sc:history:" + player, "History", Button.Style.SECONDARY, false),
						new Button("sc:note:" + player, "Add Note", Button.Style.SECONDARY, false),
						new Button("sc:freeze:" + player, "Freeze", Button.Style.DANGER, false)));
	}

	private List<Outbound> reportChanged(StaffCoreEvent.ReportChanged e) {
		String key = "report:" + e.id();
		String who = e.staffName() == null ? "somebody" : Text.safe(e.staffName(), 100);
		boolean resolved = "RESOLVED".equals(e.status());
		String line = switch (e.status()) {
			case "CLAIMED" -> "Claimed by **" + who + "**";
			case "OPEN" -> "Released back into the queue";
			case "RESOLVED" -> "Resolved by **" + who + "**";
			default -> "Now " + Text.safe(e.status(), 32).toLowerCase(java.util.Locale.ROOT);
		};

		Message posted = posted(key);
		if (posted == null || posted.embed() == null) {
			if (off(Channel.REPORTS)) return List.of();
			return List.of(new Send(Channel.REPORTS, Message.text("Report #" + e.id() + ": " + line), null, null,
					List.of()));
		}

		Embed embed = posted.embed()
				.withField("Status", switch (e.status()) {
					case "CLAIMED" -> "Claimed";
					case "RESOLVED" -> "Resolved";
					default -> "Open";
				}, true)
				.withField("Assignee", "OPEN".equals(e.status()) ? "Unclaimed" : who, true)
				.withColor(resolved ? CLEARED : REPORT);
		Message message = posted.withEmbed(embed);
		if (resolved && !message.rows().isEmpty()) {
			// The report's own buttons go quiet; the ones about the player stay, since the player is
			// still there to be looked at.
			List<List<Button>> rows = new ArrayList<>(message.rows());
			rows.set(0, rows.get(0).stream().map(Button::disable).toList());
			message = message.withRows(rows);
		}
		return List.of(new Update(key, message), new InThread(key, line, resolved));
	}

	// ------------------------------------------------------------------ signals and cases

	private List<Outbound> signal(StaffCoreEvent.SignalRaised e) {
		String caseKey = e.caseId() == null ? null : "case:" + e.caseId();

		// A report is posted as a report, with its buttons, and the case it opened lives in its
		// thread. Posting it again as an alert would put one report in front of staff twice.
		if ("REPORT".equals(e.type()) && !off(Channel.REPORTS)) return List.of();

		String line = "**" + Text.signal(e.type()) + "**, confidence " + e.confidence() + ": "
				+ Text.safe(e.summary(), 1500);
		boolean loud = e.openedCase() || e.confidence() >= settings.discordAlertSeverity;
		if (off(Channel.ALERTS) || !loud) {
			return hasThread(caseKey) ? List.of(new InThread(caseKey, line, false)) : List.of();
		}

		Embed embed = Embed.builder(Text.signal(e.type()) + " · " + e.subjectName())
				.description(Text.safe(e.summary(), 2000))
				.color(e.confidence() >= 90 ? SEVERE : e.confidence() >= 70 ? SERIOUS : MUTE)
				.thumbnail(head(e.subjectId()))
				.inline("Confidence", e.confidence() + "/100")
				.inline("Case", e.caseId() == null ? "None — below the case threshold"
						: e.openedCase() ? code(e.caseId()) + " opened — in this alert's thread" : caseLink(e.caseId()))
				.timestamp(e.at())
				.build();

		if (e.openedCase()) {
			return List.of(new Send(Channel.ALERTS, Message.of(embed), caseKey,
					"Case " + e.caseId() + " — " + e.subjectName(), List.of()));
		}
		List<Outbound> out = new ArrayList<>();
		out.add(new Send(Channel.ALERTS, Message.of(embed), null, null, List.of()));
		if (hasThread(caseKey)) out.add(new InThread(caseKey, line, false));
		return out;
	}

	private List<Outbound> caseOpened(StaffCoreEvent.CaseOpened e) {
		if (off(Channel.ALERTS)) return List.of();
		Embed embed = Embed.builder("Case " + e.caseId() + " opened · " + e.subjectName())
				.description(Text.safe(e.summary(), 2000))
				.color(REPORT)
				.thumbnail(head(e.subjectId()))
				.inline("Kind", Text.safe(e.category(), 64))
				.inline("Opened by", Text.safe(e.openedBy(), 100))
				.timestamp(e.at())
				.build();
		return List.of(new Send(Channel.ALERTS, Message.of(embed), "case:" + e.caseId(),
				"Case " + e.caseId() + " — " + e.subjectName(), List.of()));
	}

	private List<Outbound> caseChanged(StaffCoreEvent.CaseChanged e) {
		String key = "case:" + e.caseId();
		if (!hasThread(key)) return List.of();

		String actor = e.actor() == null || "system".equalsIgnoreCase(e.actor()) ? "StaffCore"
				: Text.safe(e.actor(), 100);
		String body = e.body() == null || e.body().isBlank() ? "" : Text.safe(e.body(), 1500);
		String line = switch (e.kind()) {
			case "note", "assigned" -> "**" + actor + "**: " + body;
			default -> "**" + actor + "** marked the case " + Text.safe(e.kind(), 32) + (body.isEmpty() ? "" : " — " + body);
		};
		return List.of(new InThread(key, line, e.closed()));
	}

	// ------------------------------------------------------------------ appeals

	private List<Outbound> appeal(StaffCoreEvent.AppealFiled e) {
		if (off(Channel.APPEALS)) return List.of();

		Embed embed = Embed.builder("Appeal #" + e.id() + " · " + e.playerName())
				.color(APPEAL)
				.thumbnail(head(e.playerId()))
				.inline("Minecraft", Text.safe(e.playerName(), 100))
				.inline("Discord", appellant(e))
				.inline("Punishment", "#" + e.punishmentId() + " · " + Text.punishment(e.punishmentType()))
				.field("Original punishment", "Reason: " + Text.safe(e.punishmentReason(), 600)
						+ "\nIssued by: " + Text.safe(e.punishmentBy(), 100)
						+ "\nIssued: " + Text.when(e.punishmentAt()))
				.field("Appeal", Text.safe(e.text(), 1000))
				.inline("Evidence", e.evidenceCount() + (e.evidenceCount() == 1 ? " item" : " items"))
				.inline("Created", Text.when(e.at()))
				.inline("Case", caseLink(e.caseId()))
				.inline("Status", "Open")
				.footer("Appeal #" + e.id() + " · filed " + ("DISCORD".equals(e.source()) ? "in Discord" : "in game"))
				.timestamp(e.at())
				.build();
		Message message = new Message(null, embed, appealButtons(e.id(), e.punishmentId(), e.playerId()));
		return List.of(new Send(Channel.APPEALS, message, "appeal:" + e.id(),
				"Appeal #" + e.id() + " — " + e.playerName(), List.of()));
	}

	/**
	 * Who is behind an appeal on Discord, and whether that is the punished player.
	 * <p>
	 * Anybody holding a photograph of a ban screen can file, which is accepted on purpose. What
	 * staff need is to see it when the account filing is linked to somebody else.
	 */
	static String appellant(StaffCoreEvent.AppealFiled e) {
		boolean known = e.discordId() != null && SNOWFLAKE.matcher(e.discordId()).matches();
		if (!known) return "DISCORD".equals(e.source()) ? "Unknown account" : "Filed in game · no linked account";
		String mention = "<@" + e.discordId() + ">";
		if (e.discordLinkedTo() == null) return mention + " · not linked";
		if (e.discordLinkedTo().equalsIgnoreCase(e.playerName())) return mention + " · linked to this player";
		return mention + " · linked to **" + Text.safe(e.discordLinkedTo(), 32) + "**, not this player";
	}

	/** The verdicts on the first row; looking at what the appeal is about on the second. */
	static List<List<Button>> appealButtons(long appealId, long punishmentId, UUID player) {
		return List.of(
				List.of(new Button("sc:accept:" + appealId, "Accept", Button.Style.SUCCESS, false),
						new Button("sc:reject:" + appealId, "Reject", Button.Style.DANGER, false),
						new Button("sc:info:" + appealId, "Request More Info", Button.Style.PRIMARY, false),
						new Button("sc:close:" + appealId, "Close", Button.Style.SECONDARY, false)),
				List.of(new Button("sc:punishment:" + punishmentId, "Punishment", Button.Style.SECONDARY, false),
						new Button("sc:profile:" + player, "Profile", Button.Style.SECONDARY, false),
						new Button("sc:evidence:" + punishmentId, "Evidence", Button.Style.SECONDARY, false),
						new Button("sc:note:" + player, "Staff Note", Button.Style.SECONDARY, false)));
	}

	private List<Outbound> appealDecided(StaffCoreEvent.AppealDecided e) {
		String key = "appeal:" + e.id();
		String who = Text.safe(e.staffName(), 100);
		String line = switch (e.verdict()) {
			case "ACCEPTED" -> "Accepted by **" + who + "**; the punishment is lifted";
			case "REJECTED" -> "Rejected by **" + who + "**; " + (e.mayAppealAgainAt() == null
					? "can be appealed again at once with a new code"
					: "can be appealed again " + Text.relative(e.mayAppealAgainAt()) + " with a new code");
			case "STALE" -> "Went stale: the player did not answer";
			default -> "Closed without a decision by **" + who + "**";
		};
		int colour = switch (e.verdict()) {
			case "ACCEPTED" -> CLEARED;
			case "REJECTED" -> SEVERE;
			default -> QUIET;
		};

		List<Outbound> out = new ArrayList<>();
		// The player first, so a message that cannot be delivered is said in the thread before it closes.
		if (e.discordId() != null && SNOWFLAKE.matcher(e.discordId()).matches()) {
			out.add(new Direct(e.discordId(), Message.text(verdictForPlayer(e)), key));
		}

		Message posted = posted(key);
		if (posted == null || posted.embed() == null) {
			if (!off(Channel.APPEALS)) {
				out.add(new Send(Channel.APPEALS, Message.text("Appeal #" + e.id() + ": " + line), null, null, List.of()));
			}
			return out;
		}
		Message message = posted.withEmbed(posted.embed().withField("Status", line, true).withColor(colour));
		if (!message.rows().isEmpty()) {
			List<List<Button>> rows = new ArrayList<>(message.rows());
			rows.set(0, rows.get(0).stream().map(Button::disable).toList());
			message = message.withRows(rows);
		}
		out.add(new Update(key, message));
		out.add(new InThread(key, line, true));
		return out;
	}

	/**
	 * What the player is told. Never who decided: a name in a verdict message is a name somebody
	 * upset has just been handed.
	 */
	static String verdictForPlayer(StaffCoreEvent.AppealDecided e) {
		return switch (e.verdict()) {
			case "ACCEPTED" -> "Your appeal #" + e.id() + " was accepted, and the punishment it was about has been lifted. "
					+ "Its appeal code no longer works.";
			case "REJECTED" -> "Your appeal #" + e.id() + " was reviewed and rejected. The code you used no longer "
					+ "works. " + (e.mayAppealAgainAt() == null
							? "You can appeal again with the new code the server shows you: on the ban screen, or "
									+ "in chat if you are muted."
							: "You can appeal again " + Text.relative(e.mayAppealAgainAt())
									+ ", with the new code the server shows you: on the ban screen, or in chat if you "
									+ "are muted.");
			case "STALE" -> "Your appeal #" + e.id() + " was closed because staff did not hear back from you. You can "
					+ "appeal again with /appeal.";
			default -> "Your appeal #" + e.id() + " was closed without a decision. You can appeal again with /appeal.";
		};
	}

	private List<Outbound> appealConversation(StaffCoreEvent.AppealConversation e) {
		String key = "appeal:" + e.id();
		List<Outbound> out = new ArrayList<>();
		if (!e.fromAppellant() && e.discordId() != null && SNOWFLAKE.matcher(e.discordId()).matches()) {
			out.add(new Direct(e.discordId(), Message.text("Staff have a question about your appeal #" + e.id()
					+ ":\n> " + Text.safe(e.text(), 1500) + "\n\nReply here to answer."), key));
		}
		if (hasThread(key)) {
			out.add(new InThread(key, e.fromAppellant()
					? "**The player answered:** " + Text.safe(e.text(), 1800)
					: "**" + Text.safe(e.author(), 100) + "** asked the player: " + Text.safe(e.text(), 1800), false));
		}
		return out;
	}

	// ------------------------------------------------------------------ staff

	private List<Outbound> staffAction(StaffCoreEvent.StaffAction e) {
		if (off(Channel.STAFF_LOG)) return List.of();
		// A code span shows the command exactly as recorded; the one character that could end it
		// early is swapped for a lookalike.
		String action = Text.clip(e.action() == null ? "" : e.action().replace('`', '\''), 300);
		Embed embed = Embed.builder("")
				.description("**" + Text.safe(e.staffName(), 100) + "** `" + action + "`")
				.color(QUIET)
				.inline("Player", e.target() == null ? "—" : Text.safe(e.target(), 100))
				.inline("Case", caseLink(e.caseId()))
				.inline("When", Text.relative(e.at()))
				.timestamp(e.at())
				.build();
		return List.of(new Send(Channel.STAFF_LOG, Message.of(embed), null, null, List.of()));
	}

	private List<Outbound> staffChat(StaffCoreEvent.StaffChat e) {
		// Lines that came from Discord are already there. Sending them back would echo every message.
		if (e.fromDiscord() || off(Channel.STAFF_CHAT)) return List.of();
		return List.of(new Send(Channel.STAFF_CHAT,
				Message.text("**" + Text.safe(e.senderName(), 64) + "**: " + Text.safe(e.message(), 1800)),
				null, null, List.of()));
	}

	// ------------------------------------------------------------------ pieces

	private boolean off(Channel channel) {
		return settings.channelId(channel).isEmpty();
	}

	private String head(UUID player) {
		if (player == null || settings.playerHeadUrl.isEmpty()) return null;
		return settings.playerHeadUrl.replace("{uuid}", player.toString());
	}

	/** A case id, linked to its thread when it has one. */
	String caseLink(String caseId) {
		if (caseId == null || caseId.isBlank()) return "None";
		ThreadBook.Entry entry = book.get("case:" + caseId);
		if (entry != null && entry.threadId() != null && SNOWFLAKE.matcher(settings.guildId).matches()) {
			return "[" + code(caseId) + "](https://discord.com/channels/" + settings.guildId + "/"
					+ entry.threadId() + ")";
		}
		return code(caseId);
	}

	private static String code(String caseId) {
		return CASE_ID.matcher(caseId).matches() ? "`" + caseId + "`" : Text.safe(caseId, 32);
	}

	private static String priors(int count) {
		return count <= 0 ? "no prior punishments" : count + " prior";
	}

	private static String duration(String type, long at, Long expiresAt) {
		if ("WARN".equals(type) || "KICK".equals(type)) return "—";
		return expiresAt == null ? "Permanent" : Text.duration(expiresAt - at);
	}

	private static int colour(String type) {
		if (type == null) return QUIET;
		return switch (type) {
			case "BAN", "TEMPBAN" -> BAN;
			case "MUTE", "TEMPMUTE" -> MUTE;
			case "WARN" -> WARN;
			default -> QUIET;
		};
	}
}
