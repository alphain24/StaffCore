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
