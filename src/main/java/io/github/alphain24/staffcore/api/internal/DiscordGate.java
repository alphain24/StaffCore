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
import net.minecraft.server.players.NameAndId;

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

	/** A player named from Discord: exactly one, or why not. */
	private record Named(NameAndId player, String refusal) {}

	/**
	 * Who a name typed in Discord means, by the same rules as a name typed in game: an exact name first,
	 * then a prefix only when it matches one player. {@code Steve_} and {@code Steve__} are both real
	 * accounts often enough that guessing between them is how the wrong person gets banned.
	 */
	private static Named named(MinecraftServer server, String typed) {
		if (typed == null || typed.isBlank()) return new Named(null, "Name a player.");
		var match = io.github.alphain24.staffcore.command.KnownPlayers.resolve(server, typed.strip());
		if (match.isResolved()) return new Named(match.profile(), null);
		if (match.isAmbiguous()) {
			return new Named(null, "\"" + typed.strip() + "\" could be " + String.join(", ",
					match.candidates().stream().limit(10).toList()) + ". Type the whole name.");
		}
		return new Named(null, "Nobody called \"" + typed.strip() + "\" has joined this server.");
	}

	public static CompletableFuture<DiscordAnswer<DiscordProfile>> profile(DiscordUser user, UUID playerId) {
		return read(user, DiscordOperation.VIEW_PROFILE, (server, resolved) -> playerId == null
				? DiscordAnswer.no("No player named.") : profileOf(server, resolved, user, playerId));
	}

	public static CompletableFuture<DiscordAnswer<DiscordProfile>> profile(DiscordUser user, String player) {
		return read(user, DiscordOperation.VIEW_PROFILE, (server, resolved) -> {
			Named named = named(server, player);
			return named.player() == null ? DiscordAnswer.no(named.refusal())
					: profileOf(server, resolved, user, named.player().id());
		});
	}

	private static DiscordAnswer<DiscordProfile> profileOf(MinecraftServer server, Resolved resolved, DiscordUser user,
			UUID playerId) {
		String name = nameOf(server, playerId);
		var punish = Mods.punish();
		List<String> openCases = Mods.cases().store().openCasesFor(playerId).stream().map(Case::id).toList();

		audit(resolved, user, "profile " + name, null);
		return DiscordAnswer.of(new DiscordProfile(playerId, name,
				server.getPlayerList().getPlayer(playerId) != null,
				punish.historyCount(playerId),
				io.github.alphain24.staffcore.modules.punish.WarningPoints.standingOf(playerId).points(),
				Mods.notes().count(playerId), Mods.appeals().openCountFor(playerId),
				published(punish.activeBan(playerId)), published(punish.activeMute(playerId)), openCases));
	}

	public static CompletableFuture<DiscordAnswer<List<DiscordPunishment>>> history(DiscordUser user,
			UUID playerId) {
		return read(user, DiscordOperation.VIEW_HISTORY, (server, resolved) -> playerId == null
				? DiscordAnswer.no("No player named.") : historyOf(server, resolved, user, playerId));
	}

	public static CompletableFuture<DiscordAnswer<List<DiscordPunishment>>> history(DiscordUser user,
			String player) {
		return read(user, DiscordOperation.VIEW_HISTORY, (server, resolved) -> {
			Named named = named(server, player);
			return named.player() == null ? DiscordAnswer.no(named.refusal())
					: historyOf(server, resolved, user, named.player().id());
		});
	}

	private static DiscordAnswer<List<DiscordPunishment>> historyOf(MinecraftServer server, Resolved resolved,
			DiscordUser user, UUID playerId) {
		List<DiscordPunishment> out = new ArrayList<>();
		for (Punishment p : Mods.punish().history(playerId)) {
			if (out.size() >= HISTORY_LIMIT) break;
			out.add(published(p));
		}
		audit(resolved, user, "history " + nameOf(server, playerId), null);
		return DiscordAnswer.of(out);
	}

	/** A player's notes, newest first, retracted ones included and marked. */
	public static CompletableFuture<DiscordAnswer<List<io.github.alphain24.staffcore.api.DiscordNote>>> notes(
			DiscordUser user, String player) {
		return read(user, DiscordOperation.VIEW_NOTES, (server, resolved) -> {
			Named named = namedOrId(server, player);
			if (named.player() == null) return DiscordAnswer.no(named.refusal());
			List<io.github.alphain24.staffcore.api.DiscordNote> out = new ArrayList<>();
			for (var note : Mods.notes().list(named.player().id())) {
				if (out.size() >= HISTORY_LIMIT) break;
				out.add(new io.github.alphain24.staffcore.api.DiscordNote(note.id(), note.author(), note.text(),
						note.createdAt(), note.caseId(), note.retractedBy()));
			}
			audit(resolved, user, "notes " + named.player().name(), null);
			return DiscordAnswer.of(out);
		});
	}

	/** Notes from Discord are capped where a note typed in game is: at what fits in one chat line. */
	static final int NOTE_LIMIT = 256;

	public static CompletableFuture<DiscordResult> addNote(DiscordUser user, UUID playerId, String text) {
		return act(user, DiscordOperation.NOTE, (server, resolved) -> playerId == null
				? DiscordResult.no("No player named.") : noteOn(server, resolved, user, playerId, text));
	}

	public static CompletableFuture<DiscordResult> addNote(DiscordUser user, String player, String text) {
		return act(user, DiscordOperation.NOTE, (server, resolved) -> {
			Named named = named(server, player);
			return named.player() == null ? DiscordResult.no(named.refusal())
					: noteOn(server, resolved, user, named.player().id(), text);
		});
	}

	private static DiscordResult noteOn(MinecraftServer server, Resolved resolved, DiscordUser user, UUID playerId,
			String text) {
		String clean = cleanText(text, NOTE_LIMIT);
		if (clean.isEmpty()) return DiscordResult.no("A note needs some text.");

		String name = nameOf(server, playerId);
		var written = Mods.notes().write(playerId, name, resolved.standing().minecraftName(), clean);
		if (!written.saved()) return DiscordResult.no("The note could not be saved. The server log says why.");

		audit(resolved, user, "note " + name, written.caseId());
		return new DiscordResult(true, "Noted on " + name + " (" + written.total() + " total)"
				+ (written.caseId() == null ? "." : ", attached to case " + written.caseId() + "."));
	}

	public static CompletableFuture<DiscordResult> freeze(DiscordUser user, UUID playerId) {
		return act(user, DiscordOperation.FREEZE, (server, resolved) -> playerId == null
				? DiscordResult.no("No player named.") : freezeOn(server, resolved, user, playerId, true));
	}

	public static CompletableFuture<DiscordResult> freeze(DiscordUser user, String player) {
		return act(user, DiscordOperation.FREEZE, (server, resolved) -> {
			Named named = named(server, player);
			return named.player() == null ? DiscordResult.no(named.refusal())
					: freezeOn(server, resolved, user, named.player().id(), true);
		});
	}

	public static CompletableFuture<DiscordResult> unfreeze(DiscordUser user, String player) {
		return act(user, DiscordOperation.UNFREEZE, (server, resolved) -> {
			Named named = namedOrId(server, player);
			return named.player() == null ? DiscordResult.no(named.refusal())
					: freezeOn(server, resolved, user, named.player().id(), false);
		});
	}

	/**
	 * Freezes or releases a player who is online. A freeze holds somebody where they stand, so it needs
	 * somebody standing; releasing is refused for somebody not frozen, rather than toggling them frozen.
	 */
	private static DiscordResult freezeOn(MinecraftServer server, Resolved resolved, DiscordUser user, UUID playerId,
			boolean freeze) {
		ServerPlayer target = server.getPlayerList().getPlayer(playerId);
		if (target == null) {
			return DiscordResult.no(nameOf(server, playerId) + " is not online. A freeze holds somebody where they "
					+ "stand, so there is nobody to " + (freeze ? "hold." : "release."));
		}
		if (Mods.freeze().isFrozen(target) == freeze) {
			return DiscordResult.no(Mc.name(target) + (freeze ? " is already frozen." : " is not frozen."));
		}
		audit(resolved, user, (freeze ? "freeze " : "unfreeze ") + Mc.name(target), null);
		String by = resolved.standing().minecraftName() == null ? user.name() + " (Discord)"
				: resolved.standing().minecraftName() + " (from Discord)";
		Mods.freeze().toggle(target, by);
		return new DiscordResult(true, Mc.name(target) + (freeze ? " is frozen." : " is free to move."));
	}

	// ------------------------------------------------------------------ punishing

	/**
	 * Bans, mutes or warns from Discord.
	 * <p>
	 * Through {@code PunishmentModule.apply} with the Discord actor, which is the whole point: the rate
	 * limit, the self-punishment guard and the rank guard there are the ones every in-game punishment
	 * meets, and the reason one refused is handed back rather than lost.
	 *
	 * @param type     {@code BAN}, {@code MUTE} or {@code WARN}
	 * @param duration {@code 7d}, {@code 12h} and the like; empty or {@code perm} for no end; ignored for a warning
	 */
	public static CompletableFuture<DiscordResult> punish(DiscordUser user, String player, String type,
			String duration, String reason) {
		io.github.alphain24.staffcore.modules.punish.PunishmentType base;
		DiscordOperation operation;
		switch (type == null ? "" : type) {
			case "BAN" -> {
				base = io.github.alphain24.staffcore.modules.punish.PunishmentType.BAN;
				operation = DiscordOperation.BAN;
			}
			case "MUTE" -> {
				base = io.github.alphain24.staffcore.modules.punish.PunishmentType.MUTE;
				operation = DiscordOperation.MUTE;
			}
			case "WARN" -> {
				base = io.github.alphain24.staffcore.modules.punish.PunishmentType.WARN;
				operation = DiscordOperation.WARN;
			}
			default -> {
				return CompletableFuture.completedFuture(DiscordResult.no("Only bans, mutes and warnings are given "
						+ "from Discord."));
			}
		}
		return act(user, operation, (server, resolved) -> {
			Named named = named(server, player);
			if (named.player() == null) return DiscordResult.no(named.refusal());

			String cleanReason = cleanText(reason, NOTE_LIMIT);
			if (StaffConfig.get().requireReason && cleanReason.isEmpty()) {
				return DiscordResult.no("This server requires a reason.");
			}

			Long millis = null;
			if (base != io.github.alphain24.staffcore.modules.punish.PunishmentType.WARN
					&& duration != null && !duration.isBlank()) {
				var length = io.github.alphain24.staffcore.util.DurationParser.of(duration.strip());
				if (!length.valid()) return DiscordResult.no(length.problem());
				if (!length.isPermanent()) millis = length.millis();
			}

			String staffName = resolved.standing().minecraftName();
			audit(resolved, user, type.toLowerCase(java.util.Locale.ROOT) + " " + named.player().name() + " "
					+ (millis == null ? "" : duration.strip() + " ") + cleanReason, null);

			String[] refusal = new String[1];
			Punishment issued = Mods.punish().apply(server, named.player(), staffName, base, millis, cleanReason,
					null, null, resolved.actor(), why -> refusal[0] = why);
			if (issued == null) {
				return DiscordResult.no(refusal[0] != null ? refusal[0]
						: "The punishment could not be saved. The server log says why.");
			}
			return new DiscordResult(true, io.github.alphain24.staffcore.modules.cases.CaseClosing.label(issued)
					+ " issued to " + issued.targetName() + " — #" + issued.id() + ".");
		});
	}

	/**
	 * Lifts a player's ban or mute from Discord, through the same revoke {@code /staff unban} uses.
	 *
	 * @param bans true to lift bans, false to lift mutes
	 */
	public static CompletableFuture<DiscordResult> lift(DiscordUser user, String player, boolean bans, String reason) {
		return act(user, bans ? DiscordOperation.UNBAN : DiscordOperation.UNMUTE, (server, resolved) -> {
			Named named = named(server, player);
			if (named.player() == null) return DiscordResult.no(named.refusal());

			String what = bans ? "ban" : "mute";
			String staffName = resolved.standing().minecraftName();
			String cleanReason = cleanText(reason, NOTE_LIMIT);
			audit(resolved, user, "un" + what + " " + named.player().name()
					+ (cleanReason.isEmpty() ? "" : " " + cleanReason), null);

			int lifted = Mods.punish().revoke(server, named.player().id(), staffName, bans,
					cleanReason.isEmpty() ? null : cleanReason);
			if (lifted == 0) return DiscordResult.no(named.player().name() + " has no active " + what + ".");
			Mods.alerts().onStaffAction(server, staffName + " lifted a " + what + " on " + named.player().name()
					+ " from Discord");
			return new DiscordResult(true, "Lifted the " + what + " on " + named.player().name() + ".");
		});
	}

	// ------------------------------------------------------------------ staff and cases

	/** How many of a staff member's actions one answer carries. */
	static final int STAFF_HISTORY_LIMIT = 25;

	/**
	 * What a staff member did, as {@code /staff audit} shows it. The addresses they acted from are behind
	 * their own admin permission in game and not offered here at all.
	 */
	public static CompletableFuture<DiscordAnswer<List<io.github.alphain24.staffcore.api.DiscordStaffAction>>> staffHistory(
			DiscordUser user, String staffName, int days) {
		return read(user, DiscordOperation.VIEW_STAFF_HISTORY, (server, resolved) -> {
			if (staffName == null || staffName.isBlank()) return DiscordAnswer.no("Name a staff member.");
			int window = Math.max(1, Math.min(90, days));
			List<io.github.alphain24.staffcore.api.DiscordStaffAction> out = new ArrayList<>();
			for (var entry : Mods.accountability().audit().forStaff(staffName.strip(), window, STAFF_HISTORY_LIMIT)) {
				out.add(new io.github.alphain24.staffcore.api.DiscordStaffAction(entry.at(), entry.kind(), entry.detail(),
						entry.caseId()));
			}
			audit(resolved, user, "audit " + staffName.strip() + " " + window + "d", null);
			return DiscordAnswer.of(out);
		});
	}

	/** How many of a case's history lines one answer carries. */
	static final int CASE_EVENTS = CaseSnapshots.EVENTS;

	public static CompletableFuture<DiscordAnswer<io.github.alphain24.staffcore.api.DiscordCase>> caseView(
			DiscordUser user, String caseId) {
		return read(user, DiscordOperation.VIEW_CASE, (server, resolved) -> {
			var found = caseFor(caseId);
			if (found == null) return DiscordAnswer.no("There is no case " + (caseId == null ? "" : caseId.strip()) + ".");
			audit(resolved, user, "case " + found.id() + " " + found.subjectName(), found.id());
			return DiscordAnswer.of(CaseSnapshots.of(found));
		});
	}

	/**
	 * A line in a case's history, as {@code /staff case <id> note} writes one in game: on the case, behind
	 * the case commands' node.
	 */
	public static CompletableFuture<DiscordResult> caseNote(DiscordUser user, String caseId, String text) {
		return act(user, DiscordOperation.CASE_NOTE, (server, resolved) -> {
			var found = caseFor(caseId);
			if (found == null) return DiscordResult.no("There is no case " + (caseId == null ? "" : caseId.strip()) + ".");
			String clean = cleanText(text, NOTE_LIMIT);
			if (clean.isEmpty()) return DiscordResult.no("A note needs some text.");
			if (!Mods.cases().store().note(found.id(), resolved.standing().minecraftName(), clean)) {
				return DiscordResult.no("The note could not be saved. The server log says why.");
			}
			audit(resolved, user, "case " + found.id() + " note", found.id());
			return new DiscordResult(true, "Noted on case " + found.id() + ".");
		});
	}

	/** The evidence filed on a case, by the case's id. */
	public static CompletableFuture<DiscordAnswer<List<io.github.alphain24.staffcore.api.DiscordEvidence>>> caseEvidence(
			DiscordUser user, String caseId) {
		return read(user, DiscordOperation.VIEW_EVIDENCE, (server, resolved) -> {
			var found = caseFor(caseId);
			if (found == null) return DiscordAnswer.no("There is no case " + (caseId == null ? "" : caseId.strip()) + ".");
			audit(resolved, user, "evidence case " + found.id(), found.id());
			return DiscordAnswer.of(evidenceOf(found.id()));
		});
	}

	private static Case caseFor(String typed) {
		String id = io.github.alphain24.staffcore.modules.cases.CaseId.normalise(typed);
		return id == null ? null : Mods.cases().store().byId(id).orElse(null);
	}

	private static List<io.github.alphain24.staffcore.api.DiscordEvidence> evidenceOf(String caseId) {
		List<io.github.alphain24.staffcore.api.DiscordEvidence> out = new ArrayList<>();
		for (var item : Mods.cases().evidence().forCase(caseId)) {
			out.add(new io.github.alphain24.staffcore.api.DiscordEvidence(item.id(), item.kind().label(),
					"#" + item.id() + " " + item.describe(), item.addedAt(), item.addedBy()));
		}
		return out;
	}

	// ------------------------------------------------------------------ replays as maps

	private static boolean replayable(io.github.alphain24.staffcore.modules.cases.CaseEvidence.Item item) {
		return item.subjectId() != null && (item.kind() == io.github.alphain24.staffcore.modules.cases.CaseEvidence.Kind.REPLAY
				|| item.kind() == io.github.alphain24.staffcore.modules.cases.CaseEvidence.Kind.XRAY_DIG);
	}

	/** Who and when a track is for, once the gate has said yes. */
	private record TrackTarget(UUID playerId, String playerName, long from, long to, MinecraftServer server) {}

	private static String trackingOff() {
		return "Position tracking is off on this server, so there is no movement to draw. It is "
				+ "positionTracking in config/staffcore/staffcore.json, and records only from when it is on.";
	}

	public static CompletableFuture<DiscordAnswer<io.github.alphain24.staffcore.api.DiscordReplayTrack>> replayTrack(
			DiscordUser user, String player, long from, long to) {
		CompletableFuture<DiscordAnswer<TrackTarget>> checked = read(user, DiscordOperation.VIEW_REPLAY,
				(server, resolved) -> {
					if (!io.github.alphain24.staffcore.config.StaffConfig.get().positionTracking) {
						return DiscordAnswer.no(trackingOff());
					}
					if (to <= from || to - from > io.github.alphain24.staffcore.api.DiscordAccess.MAX_REPLAY_WINDOW_MS) {
						return DiscordAnswer.no("A map covers between a minute and six hours.");
					}
					Named named = namedOrId(server, player);
					if (named.player() == null) return DiscordAnswer.no(named.refusal());
					audit(resolved, user, "replay map " + named.player().name() + " "
							+ io.github.alphain24.staffcore.util.TimeFormat.utcStamp(from) + " for "
							+ io.github.alphain24.staffcore.util.TimeFormat.length(to - from), null);
					return DiscordAnswer.of(new TrackTarget(named.player().id(), named.player().name(), from, to, server));
				});
		return track(checked);
	}

	public static CompletableFuture<DiscordAnswer<io.github.alphain24.staffcore.api.DiscordReplayTrack>> replayForEvidence(
			DiscordUser user, String caseId, long evidenceId) {
		CompletableFuture<DiscordAnswer<TrackTarget>> checked = read(user, DiscordOperation.VIEW_REPLAY,
				(server, resolved) -> {
					if (!resolved.standing().holds(DiscordOperation.VIEW_EVIDENCE.node())) {
						return DiscordAnswer.no("Looking at a case's evidence needs " + DiscordOperation.VIEW_EVIDENCE.node()
								+ " in game and in your Discord role.");
					}
					if (!io.github.alphain24.staffcore.config.StaffConfig.get().positionTracking) {
						return DiscordAnswer.no(trackingOff());
					}
					var found = caseFor(caseId);
					if (found == null) return DiscordAnswer.no("There is no case " + (caseId == null ? "" : caseId.strip()) + ".");
					var item = Mods.cases().evidence().byId(evidenceId).filter(i -> i.caseId().equals(found.id())).orElse(null);
					if (item == null || !replayable(item)) {
						return DiscordAnswer.no("Case " + found.id() + " has no replay evidence #" + evidenceId + ".");
					}
					audit(resolved, user, "replay map of evidence #" + evidenceId + " case " + found.id(), found.id());
					return DiscordAnswer.of(new TrackTarget(item.subjectId(), item.subjectName(), item.from(), item.to(), server));
				});
		return track(checked);
	}

	/** Reads the track for a target the gate allowed, on the grief log's worker, never on the tick. */
	private static CompletableFuture<DiscordAnswer<io.github.alphain24.staffcore.api.DiscordReplayTrack>> track(
			CompletableFuture<DiscordAnswer<TrackTarget>> checked) {
		return checked.thenCompose(answer -> {
			if (!answer.answered()) {
				return CompletableFuture.completedFuture(
						DiscordAnswer.<io.github.alphain24.staffcore.api.DiscordReplayTrack>no(answer.refusal()));
			}
			TrackTarget target = answer.value();
			return CompletableFuture.supplyAsync(() -> readTrack(target), java.util.concurrent.ForkJoinPool.commonPool());
		});
	}

	private static DiscordAnswer<io.github.alphain24.staffcore.api.DiscordReplayTrack> readTrack(TrackTarget target) {
		var track = io.github.alphain24.staffcore.modules.replay.PositionLog.reconstruct(target.playerId(),
				target.playerName(), target.from(), target.to());
		if (track.isEmpty()) {
			int days = io.github.alphain24.staffcore.config.StaffConfig.get().positionRetentionDays;
			return DiscordAnswer.no(target.playerName() + " has no recorded movement in that window. They may not have "
					+ "been online, or it may be past the " + (days == 0 ? "retention window" : days
					+ "-day retention window") + ".");
		}
		List<io.github.alphain24.staffcore.api.DiscordReplayTrack.Change> changes = new ArrayList<>();
		boolean more = false;
		if (io.github.alphain24.staffcore.StaffCore.storage().isReady()) {
			try (var ps = io.github.alphain24.staffcore.StaffCore.storage().conn().prepareStatement(
					"SELECT world, x, y, z, action, block, created_at FROM block_log WHERE player_name = ? "
							+ "AND created_at BETWEEN ? AND ? AND action IN ('BREAK', 'PLACE') ORDER BY created_at LIMIT ?")) {
				ps.setString(1, target.playerName());
				ps.setLong(2, target.from());
				ps.setLong(3, target.to());
				ps.setInt(4, io.github.alphain24.staffcore.api.DiscordReplayTrack.MAX_CHANGES + 1);
				try (var rs = ps.executeQuery()) {
					while (rs.next()) {
						if (changes.size() == io.github.alphain24.staffcore.api.DiscordReplayTrack.MAX_CHANGES) {
							more = true;
							break;
						}
						changes.add(new io.github.alphain24.staffcore.api.DiscordReplayTrack.Change(rs.getLong("created_at"),
								rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
								"BREAK".equals(rs.getString("action")), rs.getString("block")));
					}
				}
			} catch (java.sql.SQLException e) {
				io.github.alphain24.staffcore.StaffCore.LOGGER.warn("[Replay] could not read block changes for a map: {}",
						e.getMessage());
			}
		}
		return DiscordAnswer.of(assemble(target.playerId(), target.playerName(), target.from(), target.to(), track,
				changes, more));
	}

	/**
	 * A reconstructed window as a track for drawing: every frame when there are few, evenly thinned to at
	 * most {@link io.github.alphain24.staffcore.api.DiscordReplayTrack#MAX_POINTS} when there are many, and
	 * always the last, so the map ends where the player did.
	 */
	static io.github.alphain24.staffcore.api.DiscordReplayTrack assemble(UUID playerId, String playerName, long from,
			long to, io.github.alphain24.staffcore.modules.replay.PositionLog.Track track,
			List<io.github.alphain24.staffcore.api.DiscordReplayTrack.Change> changes, boolean moreChanges) {
		List<io.github.alphain24.staffcore.api.DiscordReplayTrack.Point> points = new ArrayList<>();
		var frames = track.frames();
		int max = io.github.alphain24.staffcore.api.DiscordReplayTrack.MAX_POINTS;
		// One place is kept for the last frame.
		int step = frames.size() <= max ? 1 : (int) Math.ceil(frames.size() / (double) (max - 1));
		for (int i = 0; i < frames.size(); i += step) {
			var frame = frames.get(i);
			points.add(new io.github.alphain24.staffcore.api.DiscordReplayTrack.Point(frame.at(), frame.world(),
					frame.x(), frame.y(), frame.z()));
		}
		if (!frames.isEmpty()) {
			var last = frames.get(frames.size() - 1);
			if (points.get(points.size() - 1).at() != last.at()) {
				points.add(new io.github.alphain24.staffcore.api.DiscordReplayTrack.Point(last.at(), last.world(),
						last.x(), last.y(), last.z()));
			}
		}
		return new io.github.alphain24.staffcore.api.DiscordReplayTrack(playerId, playerName, from, to, points, changes,
				track.runs(), track.truncated(), moreChanges);
	}

	public static CompletableFuture<DiscordResult> fileReplayEvidence(DiscordUser user, String caseId, long from, long to) {
		return act(user, DiscordOperation.ADD_EVIDENCE, (server, resolved) -> {
			var found = caseFor(caseId);
			if (found == null) return DiscordResult.no("There is no case " + (caseId == null ? "" : caseId.strip()) + ".");
			if (to <= from || to - from > io.github.alphain24.staffcore.api.DiscordAccess.MAX_REPLAY_WINDOW_MS) {
				return DiscordResult.no("A replay covers between a minute and six hours.");
			}
			long id = Mods.cases().evidence().add(found.id(),
					io.github.alphain24.staffcore.modules.cases.CaseEvidence.Draft.replay(found.subjectId(),
							found.subjectName(), null, null, from, to,
							"filed by " + resolved.standing().minecraftName() + " from Discord"),
					resolved.standing().minecraftName());
			if (id < 0) return DiscordResult.no("The evidence could not be saved. Try again.");
			audit(resolved, user, "evidence replay #" + id + " on case " + found.id(), found.id());
			return new DiscordResult(true, "Filed as replay evidence #" + id + " on case " + found.id() + " ("
					+ found.subjectName() + ", " + io.github.alphain24.staffcore.util.TimeFormat.length(to - from) + ").");
		});
	}

	// ------------------------------------------------------------------ the punishment panel

	/** A player named by id, as the panel hands it back, or by name as anything else is. */
	private static Named namedOrId(MinecraftServer server, String typed) {
		if (typed != null) {
			try {
				UUID id = UUID.fromString(typed.strip());
				String name = io.github.alphain24.staffcore.util.PlayerLookup.nameOf(server, id, null);
				if (name != null) return new Named(new NameAndId(id, name), null);
				return new Named(null, "That player is not known to this server any more.");
			} catch (IllegalArgumentException notAnId) {
				// A name, then.
			}
		}
		return named(server, typed);
	}

	private static io.github.alphain24.staffcore.modules.punish.Offence offence(String id) {
		for (var offence : io.github.alphain24.staffcore.config.StaffConfig.get().offences) {
			if (offence.id.equalsIgnoreCase(id == null ? "" : id.strip())) return offence;
		}
		return null;
	}

	public static CompletableFuture<List<io.github.alphain24.staffcore.api.DiscordSuggestion>> suggestOffences(
			DiscordUser user, String prefix) {
		return onServer(server -> {
			var standing = resolve(server, user).standing();
			if (!standing.linked() || !standing.holds(DiscordOperation.PUNISH_PANEL.node())) {
				return List.<io.github.alphain24.staffcore.api.DiscordSuggestion>of();
			}
			String typed = prefix == null ? "" : prefix.strip().toLowerCase(java.util.Locale.ROOT);
			List<io.github.alphain24.staffcore.api.DiscordSuggestion> out = new ArrayList<>();
			for (var offence : io.github.alphain24.staffcore.config.StaffConfig.get().offences) {
				if (!offence.id.toLowerCase(java.util.Locale.ROOT).startsWith(typed)
						&& !offence.label.toLowerCase(java.util.Locale.ROOT).contains(typed)) continue;
				out.add(new io.github.alphain24.staffcore.api.DiscordSuggestion(offence.label, offence.id));
				if (out.size() == 25) break;
			}
			return out;
		}, List.of());
	}

	public static CompletableFuture<DiscordAnswer<io.github.alphain24.staffcore.api.DiscordLadder>> ladder(
			DiscordUser user, String player) {
		return read(user, DiscordOperation.PUNISH_PANEL, (server, resolved) -> {
			Named named = namedOrId(server, player);
			if (named.player() == null) return DiscordAnswer.no(named.refusal());
			UUID id = named.player().id();
			var punish = Mods.punish();
			List<io.github.alphain24.staffcore.api.DiscordLadder.Rung> rungs = new ArrayList<>();
			for (var offence : io.github.alphain24.staffcore.config.StaffConfig.get().offences) {
				int priors = punish.countForOffence(id, offence.id);
				var step = offence.stepFor(priors);
				rungs.add(new io.github.alphain24.staffcore.api.DiscordLadder.Rung(offence.id, offence.label,
						offence.description, priors, step.describe(), step.baseType().name(),
						offence.escalatesFurther(priors) ? offence.stepFor(priors + 1).describe() : null,
						resolved.standing().holds(step.baseType().node())));
			}
			var ban = punish.activeBan(id);
			var mute = punish.activeMute(id);
			audit(resolved, user, "punish panel " + named.player().name(), null);
			return DiscordAnswer.of(new io.github.alphain24.staffcore.api.DiscordLadder(id, named.player().name(),
					punish.history(id).size(),
					ban == null ? null : ban.type().label() + ", " + ban.remaining() + ": " + ban.reasonOr("no reason"),
					mute == null ? null : mute.type().label() + ", " + mute.remaining() + ": " + mute.reasonOr("no reason"),
					rungs));
		});
	}

	public static CompletableFuture<DiscordResult> punishByOffence(DiscordUser user, String player, String offenceId,
			int expectedPriors) {
		return act(user, DiscordOperation.PUNISH_PANEL, (server, resolved) -> {
			Named named = namedOrId(server, player);
			if (named.player() == null) return DiscordResult.no(named.refusal());
			var offence = offence(offenceId);
			if (offence == null) return DiscordResult.no("There is no offence \"" + offenceId + "\" on this server.");

			int priors = Mods.punish().countForOffence(named.player().id(), offence.id);
			if (priors != expectedPriors) {
				return DiscordResult.no(named.player().name() + "'s record for " + offence.label + " has changed since "
						+ "you looked (" + expectedPriors + " before, " + priors + " now). Open the panel again.");
			}
			var step = offence.stepFor(priors);
			var base = step.baseType();
			// The ladder picks the punishment; the person still needs to be allowed to give that one, on
			// both sides, as they would to give it by name.
			if (!resolved.standing().holds(base.node())) {
				return DiscordResult.no("This rung is a " + base.label().toLowerCase(java.util.Locale.ROOT)
						+ ", which needs " + base.node() + " in game and in your Discord role.");
			}
			Long durationMs = step.durationMs();
			String staffName = resolved.standing().minecraftName();
			String[] refusal = new String[1];
			var issued = Mods.punish().apply(server, named.player(), staffName, base, durationMs, offence.label,
					offence.id, null, resolved.actor(), why -> refusal[0] = why);
			audit(resolved, user, "punish " + named.player().name() + " for " + offence.id + " (" + step.describe()
					+ (issued == null ? ", refused" : "") + ")", issued == null ? null : issued.caseId());
			if (issued == null) {
				return DiscordResult.no(refusal[0] != null ? refusal[0] : "The punishment was not issued.");
			}
			return new DiscordResult(true, io.github.alphain24.staffcore.modules.cases.CaseClosing.label(issued) + " "
					+ named.player().name() + " for " + offence.label + " (" + step.describe() + ", "
					+ (priors == 0 ? "first time" : priors + " before") + ").");
		});
	}

	// ------------------------------------------------------------------ evidence from Discord

	public static java.nio.file.Path evidenceFolder() {
		return io.github.alphain24.staffcore.StaffCore.storage().evidenceDir();
	}

	public static java.nio.file.Path keptFile(io.github.alphain24.staffcore.api.DiscordEvidenceFile file) {
		java.nio.file.Path folder = evidenceFolder();
		if (file == null || file.storedPath() == null || folder == null
				|| !io.github.alphain24.staffcore.modules.cases.DiscordEvidenceStore.STORED_PATH.matcher(file.storedPath()).matches()) {
			return null;
		}
		java.nio.file.Path path = folder.resolve(file.storedPath()).normalize();
		return path.startsWith(folder.normalize()) ? path : null;
	}

	public static CompletableFuture<DiscordResult> mayFileEvidence(DiscordUser user, String caseId) {
		return gated(user, DiscordOperation.ADD_EVIDENCE, (server, resolved) -> {
			var found = caseFor(caseId);
			if (found == null) return DiscordResult.no("There is no case " + (caseId == null ? "" : caseId.strip()) + ".");
			return new DiscordResult(true, found.id());
		}, DiscordResult::no, DiscordResult.no(STOPPED));
	}

	/** The longest note kept with evidence. */
	static final int EVIDENCE_NOTE_LIMIT = 500;

	public static CompletableFuture<DiscordResult> fileEvidence(DiscordUser user,
			io.github.alphain24.staffcore.api.DiscordEvidenceFiling filing) {
		return act(user, DiscordOperation.ADD_EVIDENCE, (server, resolved) -> {
			if (filing == null) return DiscordResult.no("Nothing to file.");
			var found = caseFor(filing.caseId());
			if (found == null) return DiscordResult.no("There is no case " + filing.caseId() + ".");

			String note = cleanText(filing.note(), EVIDENCE_NOTE_LIMIT);
			String content = filing.content() == null ? null : filing.content().length()
					> io.github.alphain24.staffcore.modules.cases.DiscordEvidenceStore.CONTENT_LIMIT
					? filing.content().substring(0, io.github.alphain24.staffcore.modules.cases.DiscordEvidenceStore.CONTENT_LIMIT)
					: filing.content();
			if (note.isEmpty() && (content == null || content.isBlank()) && filing.files().isEmpty()) {
				return DiscordResult.no("Give a note, a file, or a message to file.");
			}
			if (filing.files().size() > io.github.alphain24.staffcore.modules.cases.DiscordEvidenceStore.FILE_LIMIT) {
				return DiscordResult.no("At most " + io.github.alphain24.staffcore.modules.cases.DiscordEvidenceStore.FILE_LIMIT
						+ " files at once.");
			}
			String url = filing.messageUrl();
			if (url != null && !url.startsWith("https://discord.com/channels/")) url = null;

			List<io.github.alphain24.staffcore.modules.cases.DiscordEvidenceStore.File> files = new ArrayList<>();
			for (var file : filing.files()) {
				String stored = file.storedPath();
				String notKept = file.notKeptWhy();
				if (stored != null) {
					java.nio.file.Path path = keptFile(file);
					boolean sound = path != null && java.nio.file.Files.isRegularFile(path)
							&& file.sha256() != null && stored.contains(file.sha256());
					if (!sound) {
						// Recorded, and said to be missing, rather than pointing at something that is not there.
						stored = null;
						notKept = "the kept copy was not where the bot said";
					}
				}
				files.add(new io.github.alphain24.staffcore.modules.cases.DiscordEvidenceStore.File(
						cleanText(file.name(), 200), file.contentType() == null ? null : cleanText(file.contentType(), 100),
						Math.max(0, file.sizeBytes()), file.sha256(), stored,
						notKept == null ? null : cleanText(notKept, 200)));
			}

			String label = !note.isEmpty() ? note
					: filing.authorName() != null ? "message by " + cleanText(filing.authorName(), 100)
					: files.size() == 1 ? files.get(0).name() : files.size() + " files";
			long id = Mods.cases().discordEvidence().file(found.id(), found.subjectId(), found.subjectName(), label,
					new io.github.alphain24.staffcore.modules.cases.DiscordEvidenceStore.Message(url,
							filing.authorId(), filing.authorName() == null ? null : cleanText(filing.authorName(), 100),
							filing.postedAt(), content, "command".equals(filing.via()) ? "command" : "message"),
					files, resolved.standing().minecraftName());
			if (id < 0) return DiscordResult.no("The evidence could not be saved. Try again.");

			long kept = files.stream().filter(f -> f.storedPath() != null).count();
			audit(resolved, user, "evidence #" + id + " on case " + found.id() + " (" + files.size() + " file(s), "
					+ kept + " kept)", found.id());
			return new DiscordResult(true, "Filed as evidence #" + id + " on case " + found.id() + " (" + found.subjectName()
					+ ")" + (files.isEmpty() ? "." : ", " + kept + " of " + files.size() + " file(s) kept."));
		});
	}

	public static CompletableFuture<DiscordAnswer<io.github.alphain24.staffcore.api.DiscordEvidenceDetail>> evidenceItem(
			DiscordUser user, String caseId, long evidenceId) {
		return read(user, DiscordOperation.VIEW_EVIDENCE, (server, resolved) -> {
			var found = caseFor(caseId);
			if (found == null) return DiscordAnswer.no("There is no case " + (caseId == null ? "" : caseId.strip()) + ".");
			var item = Mods.cases().evidence().byId(evidenceId).filter(i -> i.caseId().equals(found.id())).orElse(null);
			if (item == null) return DiscordAnswer.no("Case " + found.id() + " has no evidence #" + evidenceId + ".");
			audit(resolved, user, "evidence #" + evidenceId + " case " + found.id(), found.id());

			var filed = Mods.cases().discordEvidence().byEvidence(item).orElse(null);
			List<io.github.alphain24.staffcore.api.DiscordEvidenceFile> files = new ArrayList<>();
			if (filed != null) {
				for (var file : filed.files()) {
					files.add(new io.github.alphain24.staffcore.api.DiscordEvidenceFile(file.name(), file.contentType(),
							file.sizeBytes(), file.sha256(), file.storedPath(), file.notKeptWhy()));
				}
			}
			var message = filed == null ? null : filed.message();
			return DiscordAnswer.of(new io.github.alphain24.staffcore.api.DiscordEvidenceDetail(item.id(), item.caseId(),
					item.kind().label(), item.describe(), item.addedBy(), item.addedAt(),
					message == null ? null : message.messageUrl(), message == null ? null : message.authorName(),
					message == null ? null : message.postedAt(), message == null ? null : message.content(), files,
					replayable(item)));
		});
	}

	/** How many staff an analytics answer lists when nobody in particular was asked about. */
	static final int LEADERBOARD = 10;

	/** Server totals, and one staff member's numbers or the busiest staff's. */
	public static CompletableFuture<DiscordAnswer<io.github.alphain24.staffcore.api.DiscordAnalytics>> analytics(
			DiscordUser user, String staffName) {
		return read(user, DiscordOperation.VIEW_ANALYTICS, (server, resolved) -> {
			var analytics = Mods.analytics();
			var totals = analytics.totals();
			List<io.github.alphain24.staffcore.api.DiscordAnalytics.Staff> staff = new ArrayList<>();
			List<io.github.alphain24.staffcore.modules.analytics.AnalyticsModule.StaffStat> stats =
					staffName == null || staffName.isBlank() ? analytics.leaderboard(LEADERBOARD)
							: List.of(analytics.forStaff(staffName.strip()));
			for (var s : stats) {
				staff.add(new io.github.alphain24.staffcore.api.DiscordAnalytics.Staff(s.name(), s.punishments(),
						s.reportsHandled(), s.reportsResolved(), s.overturned(), s.medianResponseMs(), s.commands(),
						s.lastSeen()));
			}
			audit(resolved, user, "stats" + (staffName == null || staffName.isBlank() ? "" : " " + staffName.strip()),
					null);
			return DiscordAnswer.of(new io.github.alphain24.staffcore.api.DiscordAnalytics(totals.punishments(),
					totals.activeBans(), totals.openReports(), totals.notes(), staff));
		});
	}

	/**
	 * Known player names starting with what has been typed, for Discord's autocomplete. Only for a linked
	 * account holding something; everybody else gets nothing, so autocomplete is not a way to list who
	 * plays here. Not audited: it runs on every keystroke, and the command it completes is.
	 */
	public static CompletableFuture<List<String>> suggestPlayers(DiscordUser user, String prefix) {
		return onServer(server -> {
			var standing = resolve(server, user).standing();
			if (!standing.linked() || standing.nodes().isEmpty()) return List.<String>of();
			return io.github.alphain24.staffcore.command.KnownPlayers.startingWith(server,
					prefix == null ? "" : prefix.strip(), 25);
		}, List.of());
	}

	/**
	 * Case ids starting with what has been typed, live cases first, for autocomplete. Only for a linked
	 * account allowed to look at cases, from both sides: a case id names a player and what they are
	 * suspected of, which is not something to list to anybody else. Not audited, for the same reason
	 * player names are not.
	 */
	public static CompletableFuture<List<io.github.alphain24.staffcore.api.DiscordSuggestion>> suggestCases(
			DiscordUser user, String prefix) {
		return onServer(server -> {
			var standing = resolve(server, user).standing();
			if (!standing.linked() || !standing.holds(DiscordOperation.VIEW_CASE.node())) {
				return List.<io.github.alphain24.staffcore.api.DiscordSuggestion>of();
			}
			List<io.github.alphain24.staffcore.api.DiscordSuggestion> out = new ArrayList<>();
			for (var found : Mods.cases().store().startingWith(prefix == null ? "" : prefix.strip(), 25)) {
				out.add(new io.github.alphain24.staffcore.api.DiscordSuggestion(found.id() + " · "
						+ found.subjectName() + " · " + found.category().stored() + ", "
						+ found.status().name().toLowerCase(java.util.Locale.ROOT), found.id()));
			}
			return out;
		}, List.of());
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

			var found = Mods.appeals().codes().lookup(code);
			Punishment against = found == null ? null : Mods.punish().byId(found.punishmentId());
			if (against == null) {
				return DiscordResult.no("That code does not match any ban or mute. It is the twelve characters "
						+ "on the ban screen, like ABCD-EFGH-JKMN; letters and numbers are easy to mix up in a "
						+ "photograph.");
			}
			switch (found.state(System.currentTimeMillis())) {
				case RETIRED -> {
					return DiscordResult.no("That code has already been used for an appeal that was decided, so it "
							+ "no longer works. If you were told you can appeal again, the ban screen shows a new "
							+ "code — join the server to see it.");
				}
				case WAITING -> {
					return DiscordResult.no("That code starts working " + discordTime(found.usableFrom())
							+ ". You can appeal then.");
				}
				case USABLE -> { }
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
				case NO_CODE -> DiscordResult.no("That punishment can no longer be appealed.");
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
		return decideAppeal(user, appealId, verdict, null);
	}

	/** @param waitDays for a rejection, the wait it sets; null for the server's usual one */
	public static CompletableFuture<DiscordResult> decideAppeal(DiscordUser user, long appealId,
			io.github.alphain24.staffcore.modules.appeal.AppealModule.Verdict verdict, Integer waitDays) {
		return act(user, DiscordOperation.HANDLE_APPEAL, (server, resolved) -> {
			var outcome = Mods.appeals().decide(server, appealId, verdict, resolved.standing().minecraftName(),
					waitDays);
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
			audit(resolved, user, "evidence " + p.targetName() + " case " + p.caseId(), p.caseId());
			return DiscordAnswer.of(evidenceOf(p.caseId()));
		});
	}

	// ------------------------------------------------------------------ asking staff for help

	/** How many times one Discord account may ask for help in an hour. */
	static final int HELP_REQUESTS_PER_HOUR = 3;
	/** How many help requests are said in game in an hour, whoever asks, so many accounts cannot flood staff. */
	static final int HELP_ALERTS_PER_HOUR = 30;
	static final int HELP_LIMIT = 1000;
	private static final java.util.regex.Pattern MINECRAFT_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{3,16}");

	/**
	 * Somebody on Discord asked staff for help, naming a Minecraft player. Open to anybody, as appealing is:
	 * the people who need this are players, frozen or banned, not staff. Limited per account, and said in
	 * game with what StaffCore knows about the player. Written into the player's open case only when the
	 * account asking is linked to them, since otherwise the name is only what somebody typed.
	 *
	 * @param requestId the companion's number for the request, so staff can find it
	 */
	public static CompletableFuture<DiscordAnswer<io.github.alphain24.staffcore.api.DiscordHelpInfo>> helpRequest(
			DiscordUser asker, String minecraftName, String text, long requestId) {
		return onServer(server -> {
			if (asker == null) return DiscordAnswer.no("No Discord account.");
			String typed = minecraftName == null ? "" : minecraftName.strip();
			if (!MINECRAFT_NAME.matcher(typed).matches()) {
				return DiscordAnswer.no("\"" + clip(typed.replaceAll("[^A-Za-z0-9_ ]", ""), 32) + "\" is not a Minecraft name. Type the name you "
						+ "play as, 3 to 16 letters, numbers or underscores.");
			}
			String clean = cleanText(text, HELP_LIMIT);
			if (clean.isEmpty()) return DiscordAnswer.no("Say what you need help with.");

			Long wait = Mods.discord().appealAttempts().attempt("help:" + asker.id(), HELP_REQUESTS_PER_HOUR);
			if (wait != null) {
				return DiscordAnswer.no("You have asked for help " + HELP_REQUESTS_PER_HOUR + " times in the last hour. "
						+ "Try again in " + Math.max(1, wait / 60_000L) + " minute(s).");
			}

			// Only players the server has seen: a name it does not know is not looked up anywhere, so typing
			// made-up names cannot make the server ask Mojang about them.
			NameAndId player = null;
			for (String known : io.github.alphain24.staffcore.command.KnownPlayers.startingWith(server, typed, 40)) {
				if (known.equalsIgnoreCase(typed)) {
					player = PlayerLookup.profile(server, known).orElse(null);
					break;
				}
			}
			UUID id = player == null ? null : player.id();
			String name = player == null ? typed : player.name();

			var link = Mods.discord().links().forDiscord(asker.id());
			ServerPlayer online = id == null ? null : server.getPlayerList().getPlayer(id);
			boolean frozen = id != null && (online != null ? Mods.freeze().isFrozen(online) : storedFrozen(id));
			boolean banned = id != null && Mods.punish().activeBan(id) != null;
			String caseId = id == null ? null : Mods.cases().store().openCaseFor(id).map(Case::id).orElse(null);
			boolean linkedHere = link != null && id != null && link.playerId().equals(id);

			if (Mods.discord().appealAttempts().attempt("help-alerts", HELP_ALERTS_PER_HOUR) == null) {
				String who = linkedHere ? name
						: user(asker) + " (as " + name + (link == null ? ", not linked" : ", linked to " + link.playerName()) + ")";
				String state = frozen ? " - they are frozen" : banned ? " - they are banned" : id == null
						? " - nobody of that name has joined" : "";
				String line = "[Discord] " + who + " asked staff for help, request #" + requestId + state;
				if (frozen) Mods.alerts().onSecurityFlag(server, name, line);
				else Mods.alerts().onStaffAction(server, line);
			}
			if (linkedHere && caseId != null) {
				Mods.cases().store().note(caseId, Case.SYSTEM, "asked staff for help on Discord, request #" + requestId);
			}
			return DiscordAnswer.of(new io.github.alphain24.staffcore.api.DiscordHelpInfo(id, name,
					link == null ? null : link.playerName(), online != null, frozen, banned, caseId));
		}, DiscordAnswer.no(STOPPED));
	}

	/**
	 * A staff member joining or closing a player's help request. The request itself lives in Discord; this is
	 * the gate, the rate limit and the record. The answer's message is the linked Minecraft name, for the
	 * line the companion writes in the request's thread.
	 */
	public static CompletableFuture<DiscordResult> helpDesk(DiscordUser user, long requestId, String what) {
		return act(user, DiscordOperation.HELP_DESK, (server, resolved) -> {
			String action = what == null ? "" : what.replaceAll("[^a-z]", "");
			audit(resolved, user, "help request #" + requestId + " " + action, null);
			return new DiscordResult(true, resolved.standing().minecraftName());
		});
	}

	private static boolean storedFrozen(UUID id) {
		var stored = StaffCore.state().loadAll().get(id);
		return stored != null && stored.frozen();
	}

	/** A Discord account as staff read it in game: its name, which anybody can choose, and its id. */
	private static String user(DiscordUser asker) {
		String name = asker.name() == null ? "" : asker.name().replaceAll("[^A-Za-z0-9_.]", "");
		return "@" + clip(name, 32) + " (" + asker.id() + ")";
	}

	private static String clip(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
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

	/**
	 * Something that changes something, from Discord. Past the gate it is counted against the Discord
	 * action limit — on top of whatever limit the action has in game, which it still meets inside its own
	 * service. Staff chat is talking, not acting, and is not counted.
	 */
	private static CompletableFuture<DiscordResult> act(DiscordUser user, DiscordOperation operation,
			BiFunction<MinecraftServer, Resolved, DiscordResult> work) {
		return gated(user, operation, (server, resolved) -> {
			if (operation != DiscordOperation.STAFF_CHAT) {
				var verdict = Mods.accountability().limits().check(resolved.actor(),
						io.github.alphain24.staffcore.modules.accountability.RateLimits.Kind.DISCORD_ACTION);
				if (!verdict.allowed()) return DiscordResult.no(verdict.refusal());
			}
			return work.apply(server, resolved);
		}, DiscordResult::no, DiscordResult.no(STOPPED));
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
