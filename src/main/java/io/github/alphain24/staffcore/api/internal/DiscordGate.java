package io.github.alphain24.staffcore.api.internal;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.DiscordAnswer;
import io.github.alphain24.staffcore.api.DiscordDecision;
import io.github.alphain24.staffcore.api.DiscordLinkResult;
import io.github.alphain24.staffcore.api.DiscordOperation;
import io.github.alphain24.staffcore.api.DiscordProfile;
import io.github.alphain24.staffcore.api.DiscordPunishment;
import io.github.alphain24.staffcore.api.DiscordResult;
import io.github.alphain24.staffcore.api.DiscordStanding;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.Signal;
import io.github.alphain24.staffcore.modules.discord.DiscordLinks;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.DiscordAuthority;
import io.github.alphain24.staffcore.permission.Rank;
import io.github.alphain24.staffcore.util.PlayerLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Where a Discord user becomes somebody StaffCore can check: an {@link Actor} with the permissions
 * they may use from Discord, resolved fresh for every request.
 *
 * <h2>Nothing is cached</h2>
 * Every request reads the link, the ban list and the in-game permissions again. Caching any of
 * them would open a window in which somebody demoted, banned or unlinked in game could still act
 * from Discord, and the cost it would save is a few lookups per click.
 *
 * <h2>On the server thread</h2>
 * The companion calls in from Discord's threads; the work runs on the server thread, where the
 * in-game commands run, and the answer comes back as a future. Nothing here blocks a Discord
 * thread on the server or the server on Discord.
 */
public final class DiscordGate {
	private DiscordGate() {}

	/**
	 * A Discord user, resolved for one request.
	 * <p>
	 * Holds the facts rather than an {@link Actor}, which is only ever built at the moment of a
	 * decision; see {@code ActorBoundaryTest} for why nothing keeps one.
	 *
	 * @param standing who they are and what they hold, in the published shape
	 * @param link     the link, or null
	 * @param discordName their Discord name, for an unlinked actor's attribution
	 */
	public record Resolved(DiscordStanding standing, DiscordLinks.Link link, String discordName) {

		/**
		 * Who actions run as: the linked Minecraft account, from Discord, holding only what
		 * {@link DiscordAuthority} granted; identity-less and holding nothing when unlinked.
		 */
		public Actor actor() {
			return standing.linked()
					? Actor.of(standing.minecraftId(), standing.minecraftName(),
							Actor.Source.DISCORD_LINKED, standing.nodes())
					: Actor.of(null, discordName, Actor.Source.DISCORD_UNLINKED, Set.of());
		}
	}

	// ------------------------------------------------------------------ resolving

	/** Server thread only. */
	public static Resolved resolve(MinecraftServer server, DiscordUser user) {
		DiscordLinks.Link link = user == null ? null : Mods.discord().links().forDiscord(user.id());
		String discordName = user == null ? "unknown" : user.name();

		if (link == null) {
			var grant = DiscordAuthority.grant(false, false, null,
					user == null ? Set.of() : user.roleNodes(), Actor.all());
			return new Resolved(new DiscordStanding(false, null, null, Set.of(), grant.limitation()),
					null, discordName);
		}

		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(link.playerId());
		String name = online != null ? Mc.name(online) : link.playerName();

		boolean banned = Mods.punish().activeBan(link.playerId()) != null;
		Rank.Held inGame = Rank.inGame(server, link.playerId(), name);
		var grant = DiscordAuthority.grant(true, banned, inGame, user.roleNodes(), Actor.all());

		return new Resolved(new DiscordStanding(true, link.playerId(), name, grant.nodes(),
				grant.limitation()), link, discordName);
	}

	/**
	 * Whether a resolved user may do this.
	 * <p>
	 * Reading needs a link as well as the node. An unlinked account reads what the companion posts
	 * to channels — Discord's own channel permissions decide who sees those — and nothing more:
	 * a lookup by player name is still a lookup, and "somebody we cannot identify asked about
	 * this player's history" is not a record anybody wants.
	 */
	public static DiscordDecision decide(Resolved resolved, DiscordOperation operation) {
		if (operation == null) return DiscordDecision.no("Nothing was asked for.");
		DiscordStanding standing = resolved.standing();
		if (!standing.linked()) return DiscordDecision.no(standing.limitation());

		if (!resolved.actor().has(operation.node())) {
			return DiscordDecision.no(standing.limitation() != null ? standing.limitation()
					: "This needs " + operation.node() + ", in game and in the role mapping. You "
							+ "hold it in at most one of the two.");
		}
		return DiscordDecision.yes();
	}

	// ------------------------------------------------------------------ the published calls

	public static CompletableFuture<DiscordStanding> standing(DiscordUser user) {
		return onServer(server -> resolve(server, user).standing(),
				new DiscordStanding(false, null, null, Set.of(), "The server is not running."));
	}

	public static CompletableFuture<DiscordDecision> check(DiscordUser user, DiscordOperation operation) {
		return onServer(server -> decide(resolve(server, user), operation),
				DiscordDecision.no("The server is not running."));
	}

	public static CompletableFuture<DiscordLinkResult> link(DiscordUser user, String code) {
		return onServer(server -> {
			if (user == null) return new DiscordLinkResult(false, null, "No Discord account.");

			var redeemed = Mods.discord().links().redeem(user.id(), user.name(), code);
			if (!redeemed.linked()) return new DiscordLinkResult(false, null, redeemed.refusal());

			DiscordLinks.Link link = redeemed.link();
			Actor actor = resolve(server, user).actor();
			Mods.accountability().audit().record(actor, null, link.playerName(),
					"discord link to " + user.name() + " (" + user.id() + ")", null);
			StaffCore.LOGGER.info("[Discord] {} linked to Discord user {}", link.playerName(),
					user.name());

			// Told in game as well, so a link nobody meant to make is noticed by the one person
			// who can undo it.
			ServerPlayer online = server.getPlayerList().getPlayer(link.playerId());
			if (online != null) {
				online.sendSystemMessage(Theme.good("Your account is now linked to Discord user "
						+ user.name() + ". If that was not you, run /staff discord unlink."));
			}
			return new DiscordLinkResult(true, link.playerName(), "Linked to " + link.playerName()
					+ ". What you can do from here is whatever your roles allow, and never more than "
					+ link.playerName() + " can do in game.");
		}, new DiscordLinkResult(false, null, "The server is not running."));
	}

	public static CompletableFuture<DiscordLinkResult> unlink(DiscordUser user) {
		return onServer(server -> {
			if (user == null) return new DiscordLinkResult(false, null, "No Discord account.");

			Resolved resolved = resolve(server, user);
			DiscordLinks.Link link = resolved.link();
			if (link == null) return new DiscordLinkResult(false, null, "You were not linked.");

			Mods.discord().links().endForDiscord(user.id(), user.name() + " (Discord)",
					"unlinked from Discord");
			Mods.accountability().audit().record(resolved.actor(), null, link.playerName(),
					"discord unlink from " + user.name() + " (" + user.id() + ")", null);
			return new DiscordLinkResult(true, link.playerName(), "Unlinked from "
					+ link.playerName() + ".");
		}, new DiscordLinkResult(false, null, "The server is not running."));
	}

	// ------------------------------------------------------------------ reports

	public static CompletableFuture<DiscordResult> claimReport(DiscordUser user, long reportId) {
		return act(user, DiscordOperation.CLAIM_REPORT, (server, resolved) -> {
			var report = Mods.reports().byId(reportId);
			if (report == null) return DiscordResult.no("There is no report #" + reportId + ".");
			if ("RESOLVED".equals(report.status())) {
				return DiscordResult.no("Report #" + reportId + " is already resolved.");
			}
			String name = resolved.standing().minecraftName();
			if (!Mods.reports().claimOrTakeOver(reportId, name)) {
				return DiscordResult.no("Report #" + reportId + " changed while you clicked. Look again.");
			}
			audit(resolved, user, "claim " + report.targetName() + " report #" + reportId, null);
			Mods.alerts().onStaffAction(server, name + " claimed report #" + reportId + " from Discord");
			return new DiscordResult(true, "You claimed report #" + reportId + " against "
					+ report.targetName() + ".");
		});
	}

	public static CompletableFuture<DiscordResult> resolveReport(DiscordUser user, long reportId) {
		return act(user, DiscordOperation.RESOLVE_REPORT, (server, resolved) -> {
			var report = Mods.reports().byId(reportId);
			if (report == null) return DiscordResult.no("There is no report #" + reportId + ".");
			if ("RESOLVED".equals(report.status())) {
				return DiscordResult.no("Report #" + reportId + " is already resolved.");
			}
			String name = resolved.standing().minecraftName();
			if (!Mods.reports().resolve(reportId, name)) {
				return DiscordResult.no("Report #" + reportId + " could not be resolved. The server log says why.");
			}
			audit(resolved, user, "resolve " + report.targetName() + " report #" + reportId, null);
			Mods.alerts().onStaffAction(server, name + " resolved report #" + reportId + " from Discord");
			return new DiscordResult(true, "Report #" + reportId + " resolved. The record stays.");
		});
	}

	/**
	 * Hands a report to an investigation: the player's open case if they have one, otherwise a new
	 * one of the kind the report's words suggest, with the report linked and the case marked as
	 * being investigated.
	 * <p>
	 * The same things {@code /staff case open} and the case screen do, in the same services, and
	 * behind the same node. The report itself is left as it is: somebody still has to answer it.
	 */
	public static CompletableFuture<DiscordResult> escalateReport(DiscordUser user, long reportId) {
		return act(user, DiscordOperation.ESCALATE_REPORT, (server, resolved) -> {
			var report = Mods.reports().byId(reportId);
			if (report == null) return DiscordResult.no("There is no report #" + reportId + ".");
			String name = resolved.standing().minecraftName();
			var store = Mods.cases().store();

			String caseId = store.openCaseFor(report.targetUuid()).map(Case::id).orElse(null);
			boolean opened = false;
			if (caseId == null) {
				int severity = StaffConfig.get().reportSignalConfidence;
				CaseCategory category = CaseCategory.of(Signal.of(Signal.Type.REPORT, report.targetUuid(),
						report.targetName(), severity, report.reason(), "report"));
				caseId = store.openManually(report.targetUuid(), report.targetName(), name,
						"Escalated from report #" + reportId + ": " + report.reason(), severity, category);
				if (caseId == null) return DiscordResult.no("A case could not be opened. The server log says why.");
				opened = true;
			}

			store.link(caseId, "report", String.valueOf(reportId), name);
			var current = store.byId(caseId);
			if (current.isPresent() && current.get().status() == Case.Status.OPEN) {
				store.setStatus(caseId, Case.Status.INVESTIGATING, name, "escalated from report #" + reportId);
			} else {
				store.note(caseId, name, "report #" + reportId + " escalated to this case");
			}

			audit(resolved, user, "escalate " + report.targetName() + " report #" + reportId + " to case "
					+ caseId, caseId);
			Mods.alerts().onStaffAction(server, name + " escalated report #" + reportId + " to case "
					+ caseId + " from Discord");
			return new DiscordResult(true, (opened ? "Opened case " + caseId : "Added to case " + caseId)
					+ " for " + report.targetName() + ", marked as being investigated.");
		});
	}

	// ------------------------------------------------------------------ players

	/** How many punishments a history answer carries, newest first. */
	static final int HISTORY_LIMIT = 20;

	public static CompletableFuture<DiscordAnswer<DiscordProfile>> profile(DiscordUser user, UUID playerId) {
		return read(user, DiscordOperation.VIEW_PROFILE, (server, resolved) -> {
			if (playerId == null) return DiscordAnswer.no("No player named.");
			String name = nameOf(server, playerId);
			var punish = Mods.punish();
			List<String> openCases = Mods.cases().store().openCasesFor(playerId).stream()
					.map(Case::id).toList();

			audit(resolved, user, "profile " + name, null);
			return DiscordAnswer.of(new DiscordProfile(playerId, name,
					server.getPlayerList().getPlayer(playerId) != null,
					punish.historyCount(playerId),
					io.github.alphain24.staffcore.modules.punish.WarningPoints.standingOf(playerId).points(),
					Mods.notes().count(playerId), Mods.appeals().openCountFor(playerId),
					published(punish.activeBan(playerId)), published(punish.activeMute(playerId)), openCases));
		});
	}

	public static CompletableFuture<DiscordAnswer<List<DiscordPunishment>>> history(DiscordUser user,
			UUID playerId) {
		return read(user, DiscordOperation.VIEW_HISTORY, (server, resolved) -> {
			if (playerId == null) return DiscordAnswer.no("No player named.");
			List<DiscordPunishment> out = new ArrayList<>();
			for (Punishment p : Mods.punish().history(playerId)) {
				if (out.size() >= HISTORY_LIMIT) break;
				out.add(published(p));
			}
			audit(resolved, user, "history " + nameOf(server, playerId), null);
			return DiscordAnswer.of(out);
		});
	}

	/** Notes from Discord are capped where a note typed in game is: at what fits in one chat line. */
	static final int NOTE_LIMIT = 256;

	public static CompletableFuture<DiscordResult> addNote(DiscordUser user, UUID playerId, String text) {
		return act(user, DiscordOperation.NOTE, (server, resolved) -> {
			if (playerId == null) return DiscordResult.no("No player named.");
			String clean = cleanText(text, NOTE_LIMIT);
			if (clean.isEmpty()) return DiscordResult.no("A note needs some text.");

			String name = nameOf(server, playerId);
			var written = Mods.notes().write(playerId, name, resolved.standing().minecraftName(), clean);
			if (!written.saved()) return DiscordResult.no("The note could not be saved. The server log says why.");

			audit(resolved, user, "note " + name, written.caseId());
			return new DiscordResult(true, "Noted on " + name + " (" + written.total() + " total)"
					+ (written.caseId() == null ? "." : ", attached to case " + written.caseId() + "."));
		});
	}

	public static CompletableFuture<DiscordResult> freeze(DiscordUser user, UUID playerId) {
		return act(user, DiscordOperation.FREEZE, (server, resolved) -> {
			if (playerId == null) return DiscordResult.no("No player named.");
			ServerPlayer target = server.getPlayerList().getPlayer(playerId);
			if (target == null) {
				return DiscordResult.no(nameOf(server, playerId) + " is not online. A freeze holds somebody "
						+ "where they stand, so there is nobody to hold.");
			}
			if (Mods.freeze().isFrozen(target)) return DiscordResult.no(Mc.name(target) + " is already frozen.");

			audit(resolved, user, "freeze " + Mc.name(target), null);
			Mods.freeze().toggle(target);
			return new DiscordResult(true, Mc.name(target) + " is frozen.");
		});
	}

	// ------------------------------------------------------------------ staff chat

	/** The longest line a staff chat message from Discord becomes: one chat line in game. */
	static final int CHAT_LIMIT = 256;

	public static CompletableFuture<DiscordResult> staffChat(DiscordUser user, String message) {
		return act(user, DiscordOperation.STAFF_CHAT, (server, resolved) -> {
			String clean = cleanText(message, CHAT_LIMIT);
			if (clean.isEmpty()) return DiscordResult.no("Nothing to send.");
			int delivered = Mods.staffChat().sendFromDiscord(server, resolved.standing().minecraftName(), clean);
			return new DiscordResult(true, "Sent to " + delivered + " staff in game.");
		});
	}

	/**
	 * Text from Discord made fit for the game: one line, no formatting codes, no control characters,
	 * no longer than the limit.
	 * <p>
	 * The section sign goes because it is how Minecraft text is coloured and styled, and a line that
	 * could carry it could make a Discord message look like a server announcement.
	 */
	static String cleanText(String text, int limit) {
		if (text == null) return "";
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length() && out.length() < limit; i++) {
			char c = text.charAt(i);
			if (c == (char) 0xA7) continue;
			out.append(Character.isISOControl(c) ? ' ' : c);
		}
		return out.toString().strip().replaceAll(" {2,}", " ");
	}

	// ------------------------------------------------------------------ appeals

	/** The longest appeal from Discord: what Discord's own form allows, and enough to make a case. */
	static final int APPEAL_LIMIT = 1000;

	/**
	 * How many answers one account may send about its appeals in an hour. Not a setting: it only has
	 * to stop a flood, and somebody answering a question in several messages should never meet it.
	 */
	static final int REPLIES_PER_HOUR = 20;

	/**
	 * Files an appeal from Discord with the code off a ban screen.
	 * <p>
	 * <b>The one action an unlinked Discord account can take.</b> Everybody appealing a ban is, by
	 * definition, somebody who cannot be linked staff: the ban keeps them out of the game where a link
	 * is made. So no permission is checked, and the code is what stands in for one — it names one
	 * punishment and nothing else, it cannot be guessed, and attempts are counted per Discord account
	 * whether the code was right or not. Who filed is recorded and shown to staff, including when
	 * that account is linked to a different player.
	 */
	public static CompletableFuture<DiscordResult> fileAppeal(DiscordUser filer, String code, String text) {
		return onServer(server -> {
			if (filer == null) return DiscordResult.no("No Discord account.");

			Long wait = Mods.discord().appealAttempts().attempt("file:" + filer.id(),
					StaffConfig.get().appealAttemptsPerHour);
			if (wait != null) {
				return DiscordResult.no("You have tried to appeal too many times in the last hour. Try again in "
						+ Math.max(1, wait / 60_000L) + " minute(s).");
			}

			String clean = cleanText(text, APPEAL_LIMIT);
			if (clean.isEmpty()) return DiscordResult.no("An appeal needs a reason.");

			Punishment against = Mods.punish().byAppealCode(code);
			if (against == null) {
				return DiscordResult.no("That code does not match any ban or mute. It is the twelve characters "
						+ "on the ban screen, like ABCD-EFGH-JKMN; letters and numbers are easy to mix up in a "
						+ "photograph.");
			}

			var filed = Mods.appeals().fileAgainst(against, clean, "DISCORD", filer.id(), filer.name());
			StaffCore.LOGGER.info("[Appeal] {} from Discord by {} against {} #{}", filed.result(), filer.name(),
					against.type().name().toLowerCase(java.util.Locale.ROOT), against.id());
			return switch (filed.result()) {
				case OK -> new DiscordResult(true, "Appeal #" + filed.appeal().id() + " filed. Staff will review it. "
						+ "If they need to know more, or when it is decided, this bot will message you — so keep "
						+ "direct messages from this server switched on.");
				case ALREADY_OPEN -> DiscordResult.no("There is already an appeal open against this punishment. "
						+ "Staff will get to it; a second one would not be read any sooner.");
				case COOLDOWN -> DiscordResult.no("An appeal against this punishment was rejected recently. You can "
						+ "appeal it again " + discordTime(filed.mayAppealAgain()) + ".");
				case NOT_IN_FORCE -> DiscordResult.no("That punishment is no longer in force, so there is nothing "
						+ "to appeal.");
				default -> DiscordResult.no("Appeals are unavailable right now. Try again later.");
			};
		}, DiscordResult.no(STOPPED));
	}

	/**
	 * The player's answer to a question staff asked about their appeal, from the account that filed it.
	 * No permission, for the same reason as {@link #fileAppeal}; it lands only on an open appeal that
	 * account filed and staff asked about.
	 */
	public static CompletableFuture<DiscordResult> replyToAppeal(DiscordUser appellant, String text) {
		return onServer(server -> {
			if (appellant == null) return DiscordResult.no("No Discord account.");
			Long wait = Mods.discord().appealAttempts().attempt("reply:" + appellant.id(), REPLIES_PER_HOUR);
			if (wait != null) return DiscordResult.no("That is a lot of messages. Try again in a few minutes.");

			String clean = cleanText(text, APPEAL_LIMIT);
			if (clean.isEmpty()) return DiscordResult.no("Nothing to add.");
			var outcome = Mods.appeals().reply(appellant.id(), clean);
			return new DiscordResult(outcome.done(), outcome.message());
		}, DiscordResult.no(STOPPED));
	}

	public static CompletableFuture<DiscordResult> decideAppeal(DiscordUser user, long appealId,
			io.github.alphain24.staffcore.modules.appeal.AppealModule.Verdict verdict) {
		return act(user, DiscordOperation.HANDLE_APPEAL, (server, resolved) -> {
			var outcome = Mods.appeals().decide(server, appealId, verdict, resolved.standing().minecraftName());
			if (outcome.done()) {
				var appeal = Mods.appeals().byId(appealId);
				audit(resolved, user, verdict.name().toLowerCase(java.util.Locale.ROOT) + " "
						+ (appeal == null ? "?" : appeal.targetName()) + " appeal #" + appealId, null);
			}
			return new DiscordResult(outcome.done(), outcome.message());
		});
	}

	public static CompletableFuture<DiscordResult> requestAppealInfo(DiscordUser user, long appealId,
			String question) {
		return act(user, DiscordOperation.HANDLE_APPEAL, (server, resolved) -> {
			String clean = cleanText(question, APPEAL_LIMIT);
			if (clean.isEmpty()) return DiscordResult.no("A question needs some text.");
			var outcome = Mods.appeals().requestInfo(appealId, resolved.standing().minecraftName(), clean);
			if (outcome.done()) {
				var appeal = Mods.appeals().byId(appealId);
				audit(resolved, user, "ask " + (appeal == null ? "?" : appeal.targetName()) + " appeal #" + appealId,
						null);
			}
			return new DiscordResult(outcome.done(), outcome.message());
		});
	}

	public static CompletableFuture<DiscordAnswer<DiscordPunishment>> punishment(DiscordUser user,
			long punishmentId) {
		return read(user, DiscordOperation.VIEW_HISTORY, (server, resolved) -> {
			Punishment p = Mods.punish().byId(punishmentId);
			if (p == null) return DiscordAnswer.no("There is no punishment #" + punishmentId + ".");
			audit(resolved, user, "punishment " + p.targetName() + " #" + punishmentId, p.caseId());
			return DiscordAnswer.of(published(p));
		});
	}

	/** The evidence on the case a punishment came from. */
	public static CompletableFuture<DiscordAnswer<List<io.github.alphain24.staffcore.api.DiscordEvidence>>> evidence(
			DiscordUser user, long punishmentId) {
		return read(user, DiscordOperation.VIEW_EVIDENCE, (server, resolved) -> {
			Punishment p = Mods.punish().byId(punishmentId);
			if (p == null) return DiscordAnswer.no("There is no punishment #" + punishmentId + ".");
			if (!p.hasCase()) return DiscordAnswer.no("Punishment #" + punishmentId + " was not issued from a case, "
					+ "so no evidence is filed against it.");
			List<io.github.alphain24.staffcore.api.DiscordEvidence> out = new ArrayList<>();
			for (var item : Mods.cases().evidence().forCase(p.caseId())) {
				out.add(new io.github.alphain24.staffcore.api.DiscordEvidence(item.id(), item.kind().label(),
						item.describe(), item.addedAt(), item.addedBy()));
			}
			audit(resolved, user, "evidence " + p.targetName() + " case " + p.caseId(), p.caseId());
			return DiscordAnswer.of(out);
		});
	}

	/** A time as Discord shows it to each reader in their own timezone. */
	private static String discordTime(Long epochMillis) {
		return epochMillis == null ? "later" : "<t:" + epochMillis / 1000 + ":R>";
	}

	// ------------------------------------------------------------------ shared

	private static final String STOPPED = "The server is not running.";

	/** Resolves the user, checks the operation, and runs the work only on a yes. */
	private static <T> CompletableFuture<T> gated(DiscordUser user, DiscordOperation operation,
			BiFunction<MinecraftServer, Resolved, T> work, Function<String, T> refused, T whenStopped) {
		return onServer(server -> {
			Resolved resolved = resolve(server, user);
			DiscordDecision decision = decide(resolved, operation);
			if (!decision.allowed()) return refused.apply(decision.refusal());
			return work.apply(server, resolved);
		}, whenStopped);
	}

	private static CompletableFuture<DiscordResult> act(DiscordUser user, DiscordOperation operation,
			BiFunction<MinecraftServer, Resolved, DiscordResult> work) {
		return gated(user, operation, work, DiscordResult::no, DiscordResult.no(STOPPED));
	}

	private static <T> CompletableFuture<DiscordAnswer<T>> read(DiscordUser user, DiscordOperation operation,
			BiFunction<MinecraftServer, Resolved, DiscordAnswer<T>> work) {
		return gated(user, operation, work, DiscordAnswer::no, DiscordAnswer.no(STOPPED));
	}

	/**
	 * Recorded as the linked account, naming the Discord account it came through. Reads are recorded
	 * too, as they are in game: a lookup is still somebody looking.
	 */
	private static void audit(Resolved resolved, DiscordUser user, String what, String caseId) {
		Mods.accountability().audit().record(resolved.actor(), null, resolved.standing().minecraftName(),
				"[discord] " + what + " (via Discord: " + user.name() + " " + user.id() + ")", caseId);
	}

	private static String nameOf(MinecraftServer server, UUID playerId) {
		String name = PlayerLookup.nameOf(server, playerId, null);
		return name != null ? name : playerId.toString().substring(0, 8);
	}

	private static DiscordPunishment published(Punishment p) {
		if (p == null) return null;
		return new DiscordPunishment(p.id(), p.type().name(), p.reason(), p.staffName(), p.createdAt(),
				p.expiresAt(), p.active(), p.revokedBy(), p.caseId());
	}

	// ------------------------------------------------------------------ plumbing

	/**
	 * Runs this on the server thread and hands back the answer.
	 * <p>
	 * Inline when already there, which is what a gametest is — queueing a task behind the tick
	 * the caller is running in would never complete.
	 */
	static <T> CompletableFuture<T> onServer(Function<MinecraftServer, T> work, T whenStopped) {
		MinecraftServer server = StaffCore.server();
		if (server == null) return CompletableFuture.completedFuture(whenStopped);

		CompletableFuture<T> answer = new CompletableFuture<>();
		Runnable task = () -> {
			try {
				answer.complete(work.apply(server));
			} catch (RuntimeException e) {
				StaffCore.LOGGER.warn("[Discord] a request failed: {}", e.getClass().getName());
				answer.completeExceptionally(e);
			}
		};
		if (server.isSameThread()) task.run();
		else server.execute(task);
		return answer;
	}
}
