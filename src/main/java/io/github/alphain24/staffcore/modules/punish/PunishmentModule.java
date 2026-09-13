package io.github.alphain24.staffcore.modules.punish;

import net.minecraft.server.players.NameAndId;
import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.appeal.AppealCode;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.modules.alerts.AlertsModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records punishments, enforces them, and announces them.
 * <p>
 * {@link #apply} is the single entry point — the GUI, the flat commands and any future
 * automation all funnel through it, so there is exactly one place where a punishment is
 * written, enforced, broadcast and mirrored to Discord. Nothing else in the mod calls
 * {@code disconnect} on a punished player.
 */
public class PunishmentModule implements Module {

	@Override
	public String id() {
		return "punishment";
	}

	@Override
	public String displayName() {
		return "Punishments";
	}

	// ------------------------------------------------------------------ the one door

	/**
	 * Applies a punishment end to end.
	 *
	 * @param base       WARN / KICK / MUTE / BAN — the temporary variant is derived from {@code durationMs}
	 * @param durationMs null for permanent (or for types that have no duration)
	 * @return the stored record, or null when storage rejected it
	 */
	public Punishment apply(MinecraftServer server, NameAndId target, String staffName,
			PunishmentType base, Long durationMs, String reason) {
		return apply(server, target, staffName, base, durationMs, reason, null, null);
	}

	/** As above, tagged with the offence ladder it came from so escalation can count it. */
	public Punishment apply(MinecraftServer server, NameAndId target, String staffName,
			PunishmentType base, Long durationMs, String reason, String offenceId) {
		return apply(server, target, staffName, base, durationMs, reason, offenceId, null);
	}

	/**
	 * As above, attached to the case it came out of.
	 * <p>
	 * Still the only way anybody gets punished. The case is optional and stays optional: a
	 * punishment issued directly records null rather than being refused, because a staff
	 * member watching somebody grief in front of them should not have to open a case first.
	 * What matters is that the null is <em>recorded</em> and shown, so "how often do we punish
	 * without evidence attached" is a question with an answer.
	 *
	 * @param caseId the case this came from, or null when issued directly
	 */
	public Punishment apply(MinecraftServer server, NameAndId target, String staffName,
			PunishmentType base, Long durationMs, String reason, String offenceId, String caseId) {

		// Resolved to an identity here rather than carried as a player object. Everything
		// downstream is policy — a rate limit, a rank comparison, an audit row — and none of
		// it wants a world, a position or a connection.
		ServerPlayer acting = server == null ? null
				: server.getPlayerList().getPlayerByName(staffName);
		return apply(server, target, staffName, base, durationMs, reason, offenceId, caseId,
				acting == null ? io.github.alphain24.staffcore.permission.Actor.named(staffName)
						: io.github.alphain24.staffcore.permission.Actor.of(acting));
	}

	/**
	 * The one door. Every punishment in the mod arrives here.
	 * <p>
	 * The rate limit, the self-punishment guard and the rank guard are all checked <em>here</em>
	 * rather than in the command, and that placement is the point: a check in {@code /staff ban}
	 * would leave the GUI, the API and any future Discord path unguarded, each of them a way in
	 * somebody would have to remember to close. A new caller gets all three without being told
	 * they exist.
	 *
	 * @param actor who is acting, as identity rather than as a player object. Null or
	 *              identity-less means the console, which is the server owner's own hand and is
	 *              exempt from the guards that exist to hold staff to each other
	 */
	public Punishment apply(MinecraftServer server, NameAndId target, String staffName,
			PunishmentType base, Long durationMs, String reason, String offenceId, String caseId,
			io.github.alphain24.staffcore.permission.Actor actor) {

		var verdict = Mods.accountability().limits().check(actor,
				io.github.alphain24.staffcore.modules.accountability.RateLimits.Kind.PUNISHMENT);
		if (!verdict.allowed()) {
			refuse(server, actor, verdict.refusal());
			StaffCore.LOGGER.warn("[Punish] rate limit refused {} punishing {}",
					staffName, target.name());
			return null;
		}

		// Checked before anything is written, and refused rather than logged, because the
		// damage from punishing upwards is done the moment it takes effect.
		var ranking = io.github.alphain24.staffcore.permission.Rank.mayPunish(
				actor, server, target.id(), target.name());
		if (!ranking.allowed()) {
			refuse(server, actor, ranking.refusal());
			StaffCore.LOGGER.warn("[Punish] refused {} punishing {}: rank", staffName,
					target.name());
			return null;
		}

		PunishmentType type = base.withDuration(durationMs);
		Long expiresAt = durationMs == null ? null : System.currentTimeMillis() + durationMs;
		String cleanReason = (reason == null || reason.isBlank()) ? "No reason given" : reason.trim();

		Punishment record = record(target.id(), target.name(), staffName, type,
				cleanReason, expiresAt, offenceId, caseId);
		if (record == null) return null;

		// Linked from both ends. The punishment row says which case it came from; the case
		// gets a link and an event, so its log reads as the story of what was done rather
		// than needing a join to find out.
		if (record.hasCase()) {
			var cases = Mods.cases().store();
			cases.link(caseId, "punishment", String.valueOf(record.id()), staffName);
			cases.note(caseId, staffName,
					type.name().toLowerCase(java.util.Locale.ROOT) + " issued: " + cleanReason);
		}

		// Who else was connected, recorded here so no path can issue a punishment without
		// it. The question that decides a contested appeal — was there anybody who could say
		// what happened — has no record otherwise and cannot be reconstructed afterwards.
		io.github.alphain24.staffcore.modules.accountability.Witnesses.record(server,
				io.github.alphain24.staffcore.modules.accountability.Witnesses.Kind.PUNISHMENT,
				String.valueOf(record.id()), record.targetName());

		enforce(server, record);
		announce(server, record);

		if (type == PunishmentType.WARN) suggestEscalation(server, record, online(server, actor));
		return record;
	}

	/**
	 * Tells the acting staff member why this did not happen.
	 * <p>
	 * The refusal has to reach a person, and the identity that authorised the action is not a
	 * person until it is looked up. Console refusals go to the log, where the console is
	 * reading anyway.
	 */
	private void refuse(MinecraftServer server,
			io.github.alphain24.staffcore.permission.Actor actor, String why) {

		ServerPlayer online = online(server, actor);
		if (online != null) online.sendSystemMessage(Theme.bad(why));
		else StaffCore.LOGGER.warn("[Punish] {}", why);
	}

	/** The player behind an identity, if they are here. Only ever for showing them something. */
	private ServerPlayer online(MinecraftServer server,
			io.github.alphain24.staffcore.permission.Actor actor) {

		if (server == null || actor == null || actor.id() == null) return null;
		return server.getPlayerList().getPlayer(actor.id());
	}

	/**
	 * Tells the staff member the ladder thinks this player has crossed a line.
	 * <p>
	 * A suggestion, and it stays one. Nothing here calls {@link #apply} — it prints a command
	 * the staff member can click, with the reason pre-filled, and stops. An automatic
	 * escalation fires on a count rather than a judgement, and the case where a count is most
	 * likely to be wrong is a player being warned repeatedly by one staff member with a
	 * grudge, which is exactly where a human in the loop is the only safeguard.
	 * <p>
	 * Shown only to whoever issued the warning. Broadcasting it to staff chat would turn a
	 * private prompt into pressure to act.
	 */
	private void suggestEscalation(MinecraftServer server, Punishment warning, ServerPlayer actor) {
		if (actor == null) return;

		var standing = WarningPoints.standingOf(warning.targetUuid());
		if (!standing.escalates()) return;

		actor.sendSystemMessage(Theme.warn("%s is at %d/%d warning points — %s."
				.formatted(warning.targetName(), standing.points(), standing.threshold(),
						standing.reason())));

		String suggestion = "/staff %s %s %s".formatted(
				standing.suggested().name().toLowerCase(java.util.Locale.ROOT),
				warning.targetName(),
				"repeated warnings (" + standing.points() + " points)");

		actor.sendSystemMessage(io.github.alphain24.staffcore.gui.Icon.text("  ", Theme.MUTED)
				.append(io.github.alphain24.staffcore.gui.Link.suggest(
						"[" + standing.suggested().name().toLowerCase(java.util.Locale.ROOT) + "]",
						suggestion, Theme.ACCENT,
						"Fills in the command. Nothing happens until you send it.")));
		actor.sendSystemMessage(io.github.alphain24.staffcore.gui.Icon.text(
				"  A suggestion, not a rule. Ignore it if it does not fit.", Theme.MUTED));
	}

	/** How many times this player has already been done for this offence. */
	public int countForOffence(UUID target, String offenceId) {
		Connection c = conn();
		if (c == null || offenceId == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM punishments WHERE target_uuid=? AND offence=?")) {
			ps.setString(1, target.toString());
			ps.setString(2, offenceId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/** Applies the immediate, in-world consequence. */
	private void enforce(MinecraftServer server, Punishment p) {
		ServerPlayer online = server.getPlayerList().getPlayer(p.targetUuid());

		if (p.type().isBan() || p.type() == PunishmentType.KICK) {
			if (online != null) {
				Mc.disconnect(online, disconnectScreen(p));
			}
			return;
		}

		if (online == null) return;

		if (p.type().isMute()) {
			online.sendSystemMessage(Theme.bad("You have been muted — " + p.reason()));
			online.sendSystemMessage(Theme.warn("Expires: " + p.remaining()));
			if (StaffConfig.get().allowInGameAppeals) {
				online.sendSystemMessage(Theme.info("Disagree? Use /appeal <what you want to say>."));
			}
			String invite = StaffConfig.get().discordInvite;
			if (invite != null && !invite.isBlank()) {
				// Chat is the one place a link can simply be clicked, unlike the ban screen.
				online.sendSystemMessage(Theme.info("Or appeal on Discord: ").append(inviteText(invite)));
			}
			Sfx.muted(online);
		} else if (p.type() == PunishmentType.WARN) {
			online.sendSystemMessage(Theme.warn("Warning from " + p.staffName() + " — " + p.reason()));
			Sfx.warned(online);
		}
	}

	/**
	 * The full-screen text a banned or kicked player sees.
	 * <p>
	 * This is the last channel the server has to somebody it has just removed. They cannot ask
	 * a question, cannot look anything up and will not be back for a while — so everything
	 * that decides what happens next has to be on this one screen, in words a person reads
	 * once, upset, and possibly not in their first language.
	 * <p>
	 * Hence: no unit abbreviations, a real date rather than only "7 days", both the punishment
	 * id and the appeal code, and the appeal line even when there is nowhere to appeal to. What
	 * people do with this screen is photograph it, and the photograph is the only evidence
	 * they will have.
	 */
	public Component disconnectScreen(Punishment p) {
		boolean ban = p.type().isBan();
		MutableComponent out = Icon.text(ban ? "You are banned\n\n" : "You were kicked\n\n",
				Theme.BAD);

		out.append(Icon.text(p.reasonOr("No reason given") + "\n\n", Theme.TEXT));
		out.append(Icon.text("By " + p.staffName() + " on " + TimeFormat.stamp(p.createdAt())
				+ "\n", Theme.MUTED));

		if (ban) {
			out.append(lengthLine(p));
			out.append(appealBlock());
		}
		out.append(referenceBlock(p));
		return out;
	}

	/**
	 * The ban screen's text without its heading, for the appeal window — whose title already
	 * says "You are banned", and whose buttons replace the line telling them to go and type the
	 * invite in.
	 * <p>
	 * The same facts in the same order as {@link #disconnectScreen}, so a player who saw one and
	 * then the other is not left wondering which is right.
	 */
	public Component noticeBody(Punishment p) {
		MutableComponent out = Icon.text(p.reasonOr("No reason given") + "\n\n", Theme.TEXT);
		out.append(Icon.text("By " + p.staffName() + " on " + TimeFormat.stamp(p.createdAt())
				+ "\n", Theme.MUTED));
		out.append(lengthLine(p));

		boolean link = io.github.alphain24.staffcore.modules.appeal.BanNotice
				.inviteLink(StaffConfig.get().discordInvite) != null;
		out.append(Icon.text("\nThink this is a mistake? You can appeal.\n", Theme.TEXT));
		if (link && p.isAppealable()) {
			out.append(Icon.text("Copy your appeal code, then open our Discord and paste it "
					+ "into a ban appeal.\n", Theme.MUTED));
		} else if (link) {
			out.append(Icon.text("Open our Discord and ask for a ban appeal.\n", Theme.MUTED));
		} else {
			out.append(Icon.text("Contact a staff member and quote your appeal code.\n",
					Theme.MUTED));
		}
		out.append(referenceBlock(p));
		return out;
	}

	/**
	 * The invite as chat text that opens in a browser when clicked, or plain text when it is
	 * not a link a client would open.
	 */
	private static MutableComponent inviteText(String invite) {
		java.net.URI link = io.github.alphain24.staffcore.modules.appeal.BanNotice.inviteLink(invite);
		MutableComponent text = Icon.text(invite, Theme.ACCENT);
		if (link == null) return text;
		return text.withStyle(s -> s.withUnderlined(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(link))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
						Component.literal("Open in your browser"))));
	}

	/**
	 * How long, and until when, spelled out.
	 * <p>
	 * Three facts rather than one, because each answers a different question and a player
	 * asked all three: how long is this, when does it end, and how far away is that. The old
	 * line printed {@code Expires in 6h 12m left}, which is neither English nor a date.
	 */
	private MutableComponent lengthLine(Punishment p) {
		if (p.isPermanent()) {
			return Icon.text("This ban does not expire.\n", Theme.BAD);
		}

		long length = p.expiresAt() - p.createdAt();
		long left = p.expiresAt() - System.currentTimeMillis();

		MutableComponent out = Icon.text("Length: " + TimeFormat.length(length) + "\n",
				Theme.MUTED);

		// "0 seconds from now" on an already-expired ban is worse than saying nothing: it is
		// a number, so it reads as precise, and it is false. This screen should not be shown
		// for an expired ban at all - but a screen that lies when it is shown by mistake is
		// how a player ends up waiting for something that has already happened.
		String until = left > 0
				? " (" + TimeFormat.length(left) + " from now)"
				: " (already passed - rejoin, and tell staff if you cannot)";
		out.append(Icon.text("Ends: " + TimeFormat.stamp(p.expiresAt()) + until + "\n",
				Theme.MUTED));
		return out;
	}

	/**
	 * The "you can argue about this" half of a ban screen.
	 * <p>
	 * Always shown, even with no Discord invite configured, because the failure mode of
	 * staying silent is exactly the thing a ban is meant to prevent: a player who concludes
	 * there is no way back and simply returns on another account. With an invite set they
	 * get a destination; without one they are at least told an appeal exists.
	 */
	private MutableComponent appealBlock() {
		MutableComponent out = Icon.text("\n", Theme.MUTED);
		out.append(Icon.text("Think this is a mistake? You can appeal.\n", Theme.TEXT));

		String invite = StaffConfig.get().discordInvite;
		if (invite != null && !invite.isBlank()) {
			out.append(Icon.text("Join our Discord and open a ban appeal:\n", Theme.MUTED));
			out.append(Icon.text(invite + "\n", Theme.ACCENT));
		} else {
			out.append(Icon.text("Contact a staff member to open an appeal.\n", Theme.MUTED));
		}
		return out;
	}

	/**
	 * The two identifiers, last, where a photograph will still catch them.
	 * <p>
	 * The id is what staff look the record up by. The code is what the player quotes, and it
	 * is deliberately not the id — see {@link AppealCode}. A punishment issued before the
	 * code existed simply has no line, rather than a line saying it has none.
	 */
	private MutableComponent referenceBlock(Punishment p) {
		MutableComponent out = Icon.text("\n", Theme.MUTED);
		out.append(Icon.text("Reference: #" + p.id() + "\n", Theme.MUTED));

		if (p.isAppealable()) {
			out.append(Icon.text("Appeal code: ", Theme.MUTED));
			out.append(Icon.text(AppealCode.display(p.appealCode()) + "\n", Theme.TEXT));
		}
		return out;
	}

	/** Staff chat line, alert bus, Discord, and the thunderclap for bans. */
	private void announce(MinecraftServer server, Punishment p) {
		// Two lines, because they are for two audiences. Staff get the prior count and a
		// clickable name, which is the difference between "somebody was banned" and "the
		// person banned has been here four times before". Everyone else gets the plain
		// sentence: a prior count broadcast to the whole server is a punishment of its own,
		// and it is not one anybody decided to hand out.
		MutableComponent common = Theme.prefix()
				.append(Icon.text(p.staffName(), Theme.ACCENT))
				.append(Icon.text(" " + p.type().pastTense() + " ", Theme.MUTED));

		MutableComponent staffLine = common.copy()
				.append(io.github.alphain24.staffcore.gui.Link.subject(p.targetName(),
						p.targetUuid()))
				.append(Icon.text(" — " + p.reasonOr("no reason given"), Theme.MUTED));

		MutableComponent publicLine = common.copy()
				.append(Icon.text(p.targetName(), Theme.TEXT))
				.append(Icon.text(" — " + p.reasonOr("no reason given"), Theme.MUTED));

		boolean everyone = StaffConfig.get().publicPunishmentBroadcast && p.type().persistent();
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			boolean isStaff = Permissions.check(viewer, Nodes.PUNISH);
			if (isStaff) viewer.sendSystemMessage(staffLine);
			else if (everyone) viewer.sendSystemMessage(publicLine);
		}

		if (p.type().isBan()) {
			Sfx.banBroadcast(server, viewer -> Permissions.check(viewer, Nodes.PUNISH));
		}

		StaffCore.modules().get("alerts", AlertsModule.class).ifPresent(a ->
				a.onPunishment(server, p.staffName(), p.targetName(), p.type(), p.reason()));
	}

	// -------------------------------------------------------------------- expiry

	/**
	 * Deactivates punishments whose time is up, and says so in their case.
	 * <p>
	 * Expiry used to happen only on read: {@code activeBan} noticed a stale row and cleared
	 * it. That is correct for enforcement — a banned player is checked on login, so an expired
	 * ban never keeps anybody out — and useless for everything else. A ban that ran out while
	 * the player was offline, on a case nobody had open, left no trace of having ended at all;
	 * the row simply stayed {@code active=1} until somebody happened to look, and the case log
	 * skipped from "banned" to whatever came next with the ending missing.
	 * <p>
	 * That gap is exactly the one an appeal falls into. "It expired three weeks ago" and
	 * "somebody lifted it" are different facts, and only one of them is anybody's decision.
	 *
	 * @return how many were retired
	 */
	public int sweepExpired(MinecraftServer server) {
		Connection c = conn();
		if (c == null) return 0;

		long now = System.currentTimeMillis();
		List<Punishment> expired = new java.util.ArrayList<>();

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM punishments WHERE active = 1 AND expires_at IS NOT NULL "
						+ "AND expires_at <= ?")) {
			ps.setLong(1, now);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) expired.add(map(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Punish] expiry sweep could not read: {}", e.getMessage());
			return 0;
		}

		for (Punishment p : expired) {
			deactivate(p.id());
			noteExpiry(server, p);
		}
		if (!expired.isEmpty()) {
			StaffCore.LOGGER.info("[Punish] {} punishment(s) expired.", expired.size());
		}
		return expired.size();
	}

	/**
	 * Writes the ending into the case, saying whether anybody was there for it.
	 * <p>
	 * Whether the player was online matters to whoever reads this later. A ban that ended
	 * while they were connected is one they noticed; one that ended while they were away is a
	 * player who does not yet know they can come back — which is worth knowing before somebody
	 * concludes they left for good.
	 */
	private void noteExpiry(MinecraftServer server, Punishment p) {
		boolean online = server != null && server.getPlayerList().getPlayer(p.targetUuid()) != null;

		if (p.hasCase()) {
			Mods.cases().store().note(p.caseId(),
					io.github.alphain24.staffcore.modules.cases.Case.SYSTEM,
					p.type().name().toLowerCase(java.util.Locale.ROOT) + " on " + p.targetName()
							+ " expired after " + TimeFormat.length(
									p.expiresAt() - p.createdAt())
							+ (online ? ", while they were online."
									: ", while they were offline — they may not know yet."));
		}

		if (online) {
			ServerPlayer player = server.getPlayerList().getPlayer(p.targetUuid());
			if (player != null && p.type() == PunishmentType.MUTE) {
				player.sendSystemMessage(Theme.good("Your mute has expired."));
			}
		}
	}

	// -------------------------------------------------------------------- revoking

	/** Lifts an active ban or mute. Returns the number of rows affected. */
	public int revoke(MinecraftServer server, UUID target, String staffName, boolean bans) {
		return revoke(server, target, staffName, bans, null);
	}

	/**
	 * Lifts an active ban or mute, on stated grounds.
	 * <p>
	 * <b>Never deletes.</b> The row is marked reversed with who, when and why, and stays
	 * exactly where it was. A punishment that disappears on reversal takes the history with
	 * it: the player's record silently improves, an appeal that was upheld leaves no trace of
	 * having been upheld, and "has this happened before" quietly starts returning the wrong
	 * answer.
	 * <p>
	 * The linked case, if there is one, gets an event for the reversal as well as for the
	 * punishment — so its log reads as both halves of what happened rather than only the part
	 * that stuck.
	 *
	 * @return the number of punishments lifted
	 */
	public int revoke(MinecraftServer server, UUID target, String staffName, boolean bans,
			String reason) {

		String types = bans ? "('BAN','TEMPBAN')" : "('MUTE','TEMPMUTE')";
		Connection c = conn();
		if (c == null) return 0;

		// Read before writing, so the case events can name what was actually lifted.
		List<Punishment> lifting = activeOfTypes(target, types);

		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE punishments SET active=0, revoked_by=?, revoked_at=?, revoke_reason=? "
						+ "WHERE target_uuid=? AND active=1 AND type IN " + types)) {
			ps.setString(1, staffName);
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, reason);
			ps.setString(4, target.toString());
			int n = ps.executeUpdate();

			for (Punishment lifted : lifting) {
				if (!lifted.hasCase()) continue;
				Mods.cases().store().note(lifted.caseId(), staffName,
						lifted.type().name().toLowerCase(java.util.Locale.ROOT) + " #"
								+ lifted.id() + " reversed"
								+ (reason == null || reason.isBlank() ? "" : ": " + reason));
			}

			if (n > 0 && !bans) {
				ServerPlayer online = server.getPlayerList().getPlayer(target);
				if (online != null) {
					online.sendSystemMessage(Theme.good("You have been unmuted."));
					Sfx.success(online);
				}
			}
			return n;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] revoke failed", e);
			return 0;
		}
	}

	// -------------------------------------------------------------------- querying

	/** Active ban for this uuid, or null. Expired rows are retired as a side effect. */
	public Punishment activeBan(UUID target) {
		return activeOfTypes(target, PunishmentType.BAN, PunishmentType.TEMPBAN);
	}

	/** Active mute for this uuid, or null. */
	public Punishment activeMute(UUID target) {
		return activeOfTypes(target, PunishmentType.MUTE, PunishmentType.TEMPMUTE);
	}

	private Punishment activeOfTypes(UUID target, PunishmentType a, PunishmentType b) {
		Connection c = conn();
		if (c == null) return null;

		String sql = "SELECT * FROM punishments WHERE target_uuid=? AND active=1 "
				+ "AND type IN (?,?) ORDER BY created_at DESC LIMIT 1";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, target.toString());
			ps.setString(2, a.name());
			ps.setString(3, b.name());
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;
				Punishment p = map(rs);
				if (p.isExpired()) {
					deactivate(p.id());
					return null;
				}
				return p;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] lookup failed", e);
			return null;
		}
	}

	public List<Punishment> history(UUID target) {
		List<Punishment> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM punishments WHERE target_uuid=? ORDER BY created_at DESC")) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(map(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] history failed", e);
		}
		return out;
	}

	public int historyCount(UUID target) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM punishments WHERE target_uuid=?")) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	// -------------------------------------------------------------------- writing

	public Punishment record(UUID target, String targetName, String staffName,
			PunishmentType type, String reason, Long expiresAt) {
		return record(target, targetName, staffName, type, reason, expiresAt, null);
	}

	public Punishment record(UUID target, String targetName, String staffName,
			PunishmentType type, String reason, Long expiresAt, String offenceId) {
		return record(target, targetName, staffName, type, reason, expiresAt, offenceId, null);
	}

	public Punishment record(UUID target, String targetName, String staffName,
			PunishmentType type, String reason, Long expiresAt, String offenceId, String caseId) {
		Connection c = conn();
		if (c == null) return null;

		String sql = """
				INSERT INTO punishments
				  (target_uuid, target_name, staff_name, type, reason, duration_ms,
				   created_at, expires_at, active, offence, case_id, points, appeal_code,
				   server_version, mod_version)
				VALUES (?,?,?,?,?,?,?,?,1,?,?,?,?,?,?)
				""";
		long now = System.currentTimeMillis();

		// Only for punishments an appeal could actually reverse. A kick has already ended by
		// the time the player reads the screen, so a code on it would open a ticket with
		// nothing to action — and a route that goes nowhere is worse than no route, because
		// the player waits on it instead of talking to somebody.
		String appealCode = type.persistent() ? AppealCode.generate() : null;
		try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, target.toString());
			ps.setString(2, targetName);
			ps.setString(3, staffName);
			ps.setString(4, type.name());
			ps.setString(5, reason);
			if (expiresAt == null) ps.setNull(6, java.sql.Types.INTEGER);
			else ps.setLong(6, expiresAt - now);
			ps.setLong(7, now);
			if (expiresAt == null) ps.setNull(8, java.sql.Types.INTEGER);
			else ps.setLong(8, expiresAt);
			ps.setString(9, offenceId);
			ps.setString(10, caseId);
			// Only warnings carry points. A ban is not three warnings, and counting it as
			// such would let the ladder escalate off the back of its own escalation.
			ps.setInt(11, type == PunishmentType.WARN ? WarningPoints.defaultPoints() : 0);
			ps.setString(12, appealCode);
			// The build this was issued under. A punishment handed out while a detector was
			// silently broken reads differently from the same one on a healthy server, and
			// nothing else in the row can tell an investigation which it was.
			ps.setString(13, io.github.alphain24.staffcore.modules.cases.Versions.minecraft());
			ps.setString(14, io.github.alphain24.staffcore.modules.cases.Versions.mod());
			ps.executeUpdate();

			try (ResultSet keys = ps.getGeneratedKeys()) {
				long id = keys.next() ? keys.getLong(1) : -1;
				return new Punishment(id, target, targetName, staffName, type, reason, now,
						expiresAt, true, null, caseId, null, null, appealCode);
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] record failed", e);
			return null;
		}
	}

	/**
	 * One punishment by its id, whatever state it is in.
	 * <p>
	 * Deliberately not filtered to active rows. The reference on a ban screen has to resolve
	 * after the ban is lifted, or the first thing an appeal conversation produces is "no such
	 * punishment" for the record everybody is looking at.
	 */
	public Punishment byId(long id) {
		Connection c = conn();
		if (c == null) return null;

		try (PreparedStatement ps = c.prepareStatement("SELECT * FROM punishments WHERE id=?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? map(rs) : null;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Punish] could not read punishment {}: {}", id, e.getMessage());
			return null;
		}
	}

	/**
	 * One punishment by the code printed on its disconnect screen.
	 * <p>
	 * The lookup an appeal starts from. Normalised first, so a player who typed {@code O} for
	 * {@code 0} off a photograph is answered rather than told the code does not exist.
	 */
	public Punishment byAppealCode(String code) {
		String normalised = AppealCode.normalise(code);
		Connection c = conn();
		if (normalised == null || c == null) return null;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM punishments WHERE appeal_code=?")) {
			ps.setString(1, normalised);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? map(rs) : null;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Punish] could not resolve an appeal code: {}", e.getMessage());
			return null;
		}
	}

	public void clearHistory(UUID target) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement("DELETE FROM punishments WHERE target_uuid=?")) {
			ps.setString(1, target.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] clear failed", e);
		}
	}

	public void deactivate(long id) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement("UPDATE punishments SET active=0 WHERE id=?")) {
			ps.setLong(1, id);
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] deactivate failed", e);
		}
	}

	// --------------------------------------------------------------------- plumbing

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}

	private static Long revokedAt(ResultSet rs) throws SQLException {
		long at = rs.getLong("revoked_at");
		return rs.wasNull() ? null : at;
	}

	/** The punishments a revoke is about to lift, read before it lifts them. */
	private List<Punishment> activeOfTypes(UUID target, String types) {
		List<Punishment> out = new java.util.ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM punishments WHERE target_uuid=? AND active=1 AND type IN " + types)) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(map(rs));
			}
		} catch (SQLException e) {
			// Losing the case note is a smaller failure than refusing the reversal.
			StaffCore.LOGGER.warn("[Punish] could not read what is being revoked: {}", e.getMessage());
		}
		return out;
	}

	private Punishment map(ResultSet rs) throws SQLException {
		long exp = rs.getLong("expires_at");
		Long expires = rs.wasNull() ? null : exp;
		return new Punishment(
				rs.getLong("id"),
				UUID.fromString(rs.getString("target_uuid")),
				rs.getString("target_name"),
				rs.getString("staff_name"),
				PunishmentType.valueOf(rs.getString("type")),
				rs.getString("reason"),
				rs.getLong("created_at"),
				expires,
				rs.getInt("active") == 1,
				rs.getString("revoked_by"),
				rs.getString("case_id"),
				revokedAt(rs),
				rs.getString("revoke_reason"),
				rs.getString("appeal_code"));
	}
}
