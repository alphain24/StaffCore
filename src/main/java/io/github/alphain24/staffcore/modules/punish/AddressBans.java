package io.github.alphain24.staffcore.modules.punish;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Link;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.accountability.Approvals;
import io.github.alphain24.staffcore.modules.identity.AddressPrivacy;
import io.github.alphain24.staffcore.modules.identity.IdentityModule;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bans on an address rather than an account.
 *
 * <h2>What is banned, and what staff see</h2>
 * The address a player last joined from — never one typed in. Staff ban a <em>player's</em>
 * address and never read it: the row keeps the address in the same stored form the connection
 * history does, which is a salted hash whenever {@code hashConnectionAddresses} is on, and every
 * screen and line names the ban by the player it was taken from. An address typed into a command
 * would sit in the command log in plain text, which is exactly what hashing exists to avoid.
 * <p>
 * An IP ban also bans the account itself, as an ordinary ban with its own id and appeal code, so
 * the person it was aimed at sees their own ban screen and can appeal it. The address ban is what
 * stops the next account: anybody else joining from that address is refused with a screen that
 * says it is the address, not them, and staff are told who tried.
 *
 * <h2>Why a second person</h2>
 * An address is not a person. A household shares one, a university shares one, and under
 * carrier-grade NAT a whole town can. So an IP ban goes through the two-person approval when
 * {@code requireTwoPersonApproval} is on: one staff member stages it, another reads what it is
 * and confirms. It is never available from Discord, whatever the link state of the account.
 *
 * <h2>What it is not</h2>
 * Not a range ban. A /24 catches hundreds of households; the alt detector's range match is a lead
 * for a person to follow, and turning it into a refusal at the door is not a decision this mod
 * makes for anybody.
 */
public final class AddressBans {

	public static final String TABLE = """
			CREATE TABLE IF NOT EXISTS address_bans (
			    id                INTEGER PRIMARY KEY AUTOINCREMENT,
			    address           TEXT    NOT NULL,
			    source_uuid       TEXT,
			    source_name       TEXT,
			    staff_uuid        TEXT,
			    staff_name        TEXT,
			    approved_by       TEXT,
			    reason            TEXT,
			    created_at        INTEGER NOT NULL,
			    expires_at        INTEGER,
			    active            INTEGER NOT NULL DEFAULT 1,
			    revoked_by        TEXT,
			    revoked_at        INTEGER,
			    revoke_reason     TEXT,
			    case_id           TEXT,
			    punishment_id     INTEGER,
			    refused           INTEGER NOT NULL DEFAULT 0,
			    last_refused_name TEXT,
			    last_refused_at   INTEGER
			)
			""";

	public static final String INDEX =
			"CREATE INDEX IF NOT EXISTS idx_address_bans_address ON address_bans(address, active)";

	/** One address ban. The address itself is deliberately not a field: nothing needs to show it. */
	public record AddressBan(long id, UUID sourceId, String sourceName, String staffName,
			String approvedBy, String reason, long createdAt, Long expiresAt, boolean active,
			String revokedBy, Long revokedAt, String revokeReason, String caseId,
			Long punishmentId, int refused, String lastRefusedName, Long lastRefusedAt) {

		public boolean isExpired() {
			return expiresAt != null && System.currentTimeMillis() > expiresAt;
		}

		public boolean inForce() {
			return active && !isExpired();
		}

		public boolean isPermanent() {
			return expiresAt == null;
		}

		public String reasonOr(String fallback) {
			return reason == null || reason.isBlank() ? fallback : reason;
		}
	}

	/** What asking for an IP ban did. */
	public record Outcome(Kind kind, String message, AddressBan ban, Approvals.Staged staged) {
		public enum Kind { BANNED, STAGED, REFUSED }

		static Outcome refused(String why) {
			return new Outcome(Kind.REFUSED, why, null, null);
		}
	}

	// ------------------------------------------------------------------ asking

	/**
	 * Bans a player and the address they last joined from — straight away, or staged for a
	 * second person when the server requires one.
	 *
	 * @param durationMs null for permanent
	 * @param caseId     the case this belongs to, or null
	 */
	public Outcome request(MinecraftServer server, Actor staff, NameAndId target, Long durationMs,
			String reason, String caseId) {

		if (server == null || target == null) return Outcome.refused("Nobody to ban.");
		if (!StaffCore.storage().isReady()) return Outcome.refused("Storage is unavailable.");

		// Never from Discord, linked or not. The rule is about the channel, not the person:
		// an address ban is aimed at somebody the sender cannot see, from a place where
		// nobody can see them either.
		String fromDiscord = io.github.alphain24.staffcore.permission.DiscordReach.refusal(staff,
				"IP bans");
		if (fromDiscord != null) return Outcome.refused(fromDiscord);
		if (staff != null && staff.source() == Actor.Source.PLAYER && !staff.has(Nodes.IP_BAN)) {
			return Outcome.refused("An IP ban needs " + Nodes.IP_BAN + ".");
		}

		var ranking = io.github.alphain24.staffcore.permission.Rank.mayPunish(staff, server,
				target.id(), target.name());
		if (!ranking.allowed()) return Outcome.refused(ranking.refusal());

		Check check = check(server, target);
		if (check.refusal() != null) return Outcome.refused(check.refusal());
		String address = check.address();
		List<String> sharing = check.othersSharing();

		String cleanReason = reason == null || reason.isBlank() ? "No reason given" : reason.trim();
		String summary = "IP ban " + target.name() + " and the address they last joined from"
				+ (durationMs == null ? ", permanently" : ", for " + TimeFormat.length(durationMs))
				+ " — " + cleanReason
				+ (sharing.isEmpty() ? "" : " (" + sharing.size() + " other account(s) have used "
						+ "that address: " + String.join(", ", sharing.stream().limit(5).toList())
						+ (sharing.size() > 5 ? ", …" : "") + ")");

		Approvals approvals = Mods.accountability().approvals();
		if (approvals.required(Approvals.Action.IP_BAN)) {
			// The stager's name and id are kept, never their Actor. An Actor is a permission
			// snapshot, and one held until somebody approves would authorise the ban with
			// permissions read up to half an hour earlier. The ban runs as the approver, who
			// is resolved at the moment they approve, and is attributed to whoever proposed it.
			String stagerName = staff == null ? "CONSOLE" : staff.name();
			UUID stagerId = staff == null ? null : staff.id();
			Approvals.Staged staged = approvals.stage(staff == null ? Actor.console() : staff,
					Approvals.Action.IP_BAN, summary, "target=" + target.id(),
					approver -> {
						AddressBan done = execute(server, approver, stagerName, stagerId, target,
								address, durationMs, cleanReason, caseId, approver.name());
						if (done != null) {
							Mods.alerts().onStaffAction(server, approver.name() + " approved "
									+ stagerName + "'s IP ban of " + target.name()
									+ " (#" + done.id() + ")");
						}
					});
			tellApprovers(server, staged, staff);
			return new Outcome(Outcome.Kind.STAGED, "Staged IP ban " + staged.id()
					+ ". A second staff member confirms it with /staff approve " + staged.id()
					+ ".", null, staged);
		}

		AddressBan done = execute(server, staff, staff == null ? "CONSOLE" : staff.name(),
				staff == null ? null : staff.id(), target, address, durationMs, cleanReason, caseId,
				null);
		return done == null
				? Outcome.refused("The IP ban could not be saved. The server log says why.")
				: new Outcome(Outcome.Kind.BANNED, "IP banned " + target.name() + " (#" + done.id()
						+ ").", done, null);
	}

	/**
	 * Whether a player's address can be banned, before anybody is asked to confirm it.
	 *
	 * @param refusal       why not, or null when it can
	 * @param othersSharing other accounts that have joined from it, by name
	 */
	public record Check(String refusal, List<String> othersSharing, String address) {}

	/** What a screen shows before the confirm button, and what {@link #request} refuses on. */
	public Check check(MinecraftServer server, NameAndId target) {
		if (server == null || target == null || !StaffCore.storage().isReady()) {
			return new Check("Storage is unavailable.", List.of(), null);
		}
		String address = addressOf(server, target.id());
		if (address == null) {
			return new Check(target.name() + " has no address on record — they have never "
					+ "joined since StaffCore was installed, or their connection history was "
					+ "purged. Ban the account instead.", List.of(), null);
		}

		String local = localRefusal(server, target, address);
		if (local != null) return new Check(local, List.of(), address);

		List<String> sharing = accountsAt(address, target.id());
		if (sharing.size() >= SHARED_LIMIT) {
			return new Check(sharing.size() + " other accounts have joined from the address "
					+ target.name() + " last used. That is a proxy, a school or a shared network, "
					+ "not a household — an IP ban there would lock all of them out. Ban the "
					+ "account instead.", sharing, address);
		}
		return new Check(null, sharing, address);
	}

	/**
	 * Accounts other than the target that may have used an address before it is refused as
	 * shared. A family is a handful; ten is a network somebody else runs.
	 */
	static final int SHARED_LIMIT = 10;

	/**
	 * Why this address cannot be banned because it is not the player's own, or null.
	 * <p>
	 * Behind a proxy that does not forward addresses, or on a server somebody joins from the same
	 * machine, every player arrives from the same loopback or private address — and banning it
	 * bans the server. An online player's address is checked directly; an offline one only as a
	 * stored form, which can still be compared against the loopback addresses.
	 */
	private static String localRefusal(MinecraftServer server, NameAndId target, String stored) {
		String why = "That address is not " + target.name() + "'s own connection — it is "
				+ "this machine or a private network, which is what every player looks like "
				+ "behind a proxy that does not forward addresses. Banning it would ban everybody. "
				+ "Ban the account instead.";

		ServerPlayer online = server.getPlayerList().getPlayer(target.id());
		if (online != null) {
			try {
				java.net.InetAddress raw = com.google.common.net.InetAddresses.forString(
						IdentityModule.normalise(online.getIpAddress()));
				if (raw.isLoopbackAddress() || raw.isSiteLocalAddress() || raw.isAnyLocalAddress()
						|| raw.isLinkLocalAddress()) {
					return why;
				}
			} catch (IllegalArgumentException notAnAddress) {
				return why;
			}
		}

		Connection c = StaffCore.storage().conn();
		for (String loopback : List.of("unknown", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")) {
			if (AddressPrivacy.candidates(c, loopback).contains(stored)) return why;
		}
		return null;
	}

	/** Says so to everybody online who could confirm it — which excludes whoever staged it. */
	private static void tellApprovers(MinecraftServer server, Approvals.Staged staged, Actor staff) {
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			if (!Permissions.check(online, Nodes.APPROVE)) continue;
			if (staff != null && online.getUUID().equals(staff.id())) continue;
			online.sendSystemMessage(Theme.prefix()
					.append(Icon.text(staged.stagedByName() + " staged an IP ban: ", Theme.WARN))
					.append(Icon.text(staged.summary() + " ", Theme.MUTED))
					.append(Link.run("[review and approve]", "/staff approve " + staged.id(),
							Theme.ACCENT, "Confirm this IP ban as the second person")));
		}
	}

	/**
	 * Does it: bans the account, records the address ban, and removes everybody online on that
	 * address.
	 *
	 * @param acting     whose permissions and limits the ban is checked against right now — the
	 *                   approver when there was one
	 * @param staffName  who the ban is recorded as coming from: whoever proposed it
	 * @param approvedBy the second person, or null when none was required
	 * @return the ban, or null when it could not be written
	 */
	AddressBan execute(MinecraftServer server, Actor acting, String staffName, UUID staffId,
			NameAndId target, String address, Long durationMs, String reason, String caseId,
			String approvedBy) {

		// The account first, through the ordinary path — its rate limit, rank check, record,
		// appeal code, announcement and kick. If that refuses, so does this.
		Punishment accountBan = Mods.punish().activeBan(target.id());
		if (accountBan == null) {
			accountBan = Mods.punish().apply(server, target, staffName, PunishmentType.BAN,
					durationMs, "[IP ban] " + reason, null, caseId, acting);
			if (accountBan == null) return null;
		}

		long now = System.currentTimeMillis();
		long id;
		Connection c = StaffCore.storage().conn();
		try (PreparedStatement ps = c.prepareStatement("""
				INSERT INTO address_bans (address, source_uuid, source_name, staff_uuid,
				                          staff_name, approved_by, reason, created_at, expires_at,
				                          case_id, punishment_id)
				VALUES (?,?,?,?,?,?,?,?,?,?,?)
				""", java.sql.Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, address);
			ps.setString(2, target.id().toString());
			ps.setString(3, target.name());
			ps.setString(4, staffId == null ? null : staffId.toString());
			ps.setString(5, staffName);
			ps.setString(6, approvedBy);
			ps.setString(7, reason);
			ps.setLong(8, now);
			if (durationMs == null) ps.setNull(9, java.sql.Types.INTEGER);
			else ps.setLong(9, now + durationMs);
			ps.setString(10, caseId);
			ps.setLong(11, accountBan.id());
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (!keys.next()) return null;
				id = keys.getLong(1);
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] Could not record an IP ban", e);
			return null;
		}

		if (caseId != null && !caseId.isBlank()) {
			Mods.cases().store().link(caseId, "address_ban", String.valueOf(id), staffName);
		}
		StaffCore.LOGGER.info("[Punish] {} IP banned {} (#{}){}", staffName, target.name(), id,
				approvedBy == null ? "" : ", approved by " + approvedBy);

		AddressBan ban = byId(id);
		if (ban != null) removeOnline(server, address, ban);
		return ban;
	}

	/** Everybody online on the banned address goes, with the address screen. */
	private void removeOnline(MinecraftServer server, String address, AddressBan ban) {
		Connection c = StaffCore.storage().conn();
		for (ServerPlayer online : List.copyOf(server.getPlayerList().getPlayers())) {
			String ip = IdentityModule.normalise(online.getIpAddress());
			if (!AddressPrivacy.candidates(c, ip).contains(address)) continue;
			if (online.getUUID().equals(ban.sourceId())) continue;   // already kicked by the ban
			online.connection.disconnect(screen(ban));
		}
	}

	// ----------------------------------------------------------------- the door

	/**
	 * The ban refusing a login from this address, or null.
	 * <p>
	 * Compared in both stored forms, so a ban written before hashing was switched on — or after
	 * it was switched off — still matches.
	 */
	public AddressBan matching(SocketAddress socket) {
		if (!StaffCore.storage().isReady()) return null;
		// The same text ServerPlayer.getIpAddress() produces, reduced the same way the connection
		// history reduces it — so the stored form this compares against is the one recorded.
		if (!(socket instanceof InetSocketAddress inet) || inet.getAddress() == null) return null;
		String ip = IdentityModule.normalise(
				com.google.common.net.InetAddresses.toAddrString(inet.getAddress()));

		Connection c = StaffCore.storage().conn();
		List<String> forms = AddressPrivacy.candidates(c, ip);
		StringBuilder sql = new StringBuilder(
				"SELECT * FROM address_bans WHERE active = 1 AND address IN (");
		for (int i = 0; i < forms.size(); i++) sql.append(i == 0 ? "?" : ",?");
		sql.append(") ORDER BY created_at DESC");

		try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
			for (int i = 0; i < forms.size(); i++) ps.setString(i + 1, forms.get(i));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					AddressBan ban = read(rs);
					if (!ban.isExpired()) return ban;
					retire(ban.id());
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] Could not check address bans at login", e);
		}
		return null;
	}

	/**
	 * Counts a refused login and tells staff who it was.
	 * <p>
	 * An account turned away by somebody else's IP ban is either the person it was aimed at on a
	 * new account or somebody who shares their connection, and only a human can tell which — so
	 * staff hear about it, and nothing more happens to that account.
	 */
	public void noteRefused(MinecraftServer server, AddressBan ban, NameAndId who) {
		if (ban == null || who == null || !StaffCore.storage().isReady()) return;
		if (who.id().equals(ban.sourceId())) return;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				UPDATE address_bans SET refused = refused + 1, last_refused_name = ?,
				                        last_refused_at = ?
				WHERE id = ?
				""")) {
			ps.setString(1, who.name());
			ps.setLong(2, System.currentTimeMillis());
			ps.setLong(3, ban.id());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Punish] Could not count a refused login: {}", e.getMessage());
		}
		if (server != null) {
			Mods.alerts().onSecurityFlag(server, who.name(), who.name()
					+ " tried to join from the address " + ban.sourceName()
					+ " was IP banned on (#" + ban.id() + "). Refused at the door; nothing else "
					+ "was done to the account.");
		}
	}

	/** What somebody refused by an address ban reads. */
	public Component screen(AddressBan ban) {
		MutableComponent out = Icon.text("This connection is banned\n\n", Theme.BAD);
		out.append(Icon.text("An account that used this internet connection was banned, and "
				+ "the ban covers the connection too.\n\n", Theme.TEXT));
		if (ban.isPermanent()) {
			out.append(Icon.text("It does not expire.\n", Theme.MUTED));
		} else {
			out.append(Icon.text("Ends: " + TimeFormat.stamp(ban.expiresAt()) + "\n", Theme.MUTED));
		}
		out.append(Icon.text("\nIf you share this connection and have done nothing wrong, "
				+ "tell staff — this can be lifted for you.\n", Theme.TEXT));
		String invite = StaffConfig.get().discordInvite;
		if (invite != null && !invite.isBlank()) {
			out.append(Icon.text(invite + "\n", Theme.ACCENT));
		}
		out.append(Icon.text("\nReference: IP ban #" + ban.id() + "\n", Theme.MUTED));
		return out;
	}

	// ---------------------------------------------------------------- lifting

	/**
	 * Lifts every address ban taken from this player. The account ban is separate and stays
	 * unless lifted too — somebody unblocking a shared connection has not decided to unban the
	 * person it was aimed at.
	 *
	 * @return how many were lifted
	 */
	public int liftFor(UUID source, String staffName, String reason) {
		if (!StaffCore.storage().isReady() || source == null) return 0;
		List<AddressBan> lifting = forSource(source).stream().filter(AddressBan::inForce).toList();
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				UPDATE address_bans SET active = 0, revoked_by = ?, revoked_at = ?, revoke_reason = ?
				WHERE source_uuid = ? AND active = 1
				""")) {
			ps.setString(1, staffName);
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, reason);
			ps.setString(4, source.toString());
			int n = ps.executeUpdate();
			for (AddressBan lifted : lifting) {
				if (lifted.caseId() != null) {
					Mods.cases().store().note(lifted.caseId(), staffName, "IP ban #" + lifted.id()
							+ " lifted" + (reason == null || reason.isBlank() ? "" : ": " + reason));
				}
			}
			return n;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] Could not lift IP bans", e);
			return 0;
		}
	}

	// ---------------------------------------------------------------- reading

	public AddressBan byId(long id) {
		if (!StaffCore.storage().isReady()) return null;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT * FROM address_bans WHERE id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? read(rs) : null;
			}
		} catch (SQLException e) {
			return null;
		}
	}

	/** Every address ban taken from one player, newest first. */
	public List<AddressBan> forSource(UUID source) {
		return query("SELECT * FROM address_bans WHERE source_uuid = ? ORDER BY created_at DESC",
				source.toString());
	}

	/** Address bans in force, newest first. */
	public List<AddressBan> inForce(int limit) {
		List<AddressBan> out = new ArrayList<>();
		for (AddressBan ban : query("SELECT * FROM address_bans WHERE active = 1 "
				+ "ORDER BY created_at DESC LIMIT " + Math.max(1, limit))) {
			if (ban.isExpired()) retire(ban.id());
			else out.add(ban);
		}
		return out;
	}

	private List<AddressBan> query(String sql, String... args) {
		List<AddressBan> out = new ArrayList<>();
		if (!StaffCore.storage().isReady()) return out;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] Could not read address bans", e);
		}
		return out;
	}

	private void retire(long id) {
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"UPDATE address_bans SET active = 0 WHERE id = ?")) {
			ps.setLong(1, id);
			ps.executeUpdate();
		} catch (SQLException ignored) {
			// Read as expired either way; the next pass tries again.
		}
	}

	private static AddressBan read(ResultSet rs) throws SQLException {
		String source = rs.getString("source_uuid");
		return new AddressBan(rs.getLong("id"),
				source == null ? null : UUID.fromString(source),
				rs.getString("source_name"), rs.getString("staff_name"),
				rs.getString("approved_by"), rs.getString("reason"), rs.getLong("created_at"),
				nullableLong(rs, "expires_at"), rs.getInt("active") == 1,
				rs.getString("revoked_by"), nullableLong(rs, "revoked_at"),
				rs.getString("revoke_reason"), rs.getString("case_id"),
				nullableLong(rs, "punishment_id"), rs.getInt("refused"),
				rs.getString("last_refused_name"), nullableLong(rs, "last_refused_at"));
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	// --------------------------------------------------------------- addresses

	/** The stored form of the address a player is on now, or last joined from. */
	private static String addressOf(MinecraftServer server, UUID player) {
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) {
			return AddressPrivacy.store(StaffCore.storage().conn(),
					IdentityModule.normalise(online.getIpAddress()));
		}
		return Mods.identity().lastAddress(player);
	}

	/** Other accounts that have joined from a stored address, by name. */
	private static List<String> accountsAt(String address, UUID except) {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT DISTINCT name FROM connections WHERE ip = ? AND uuid <> ?")) {
			ps.setString(1, address);
			ps.setString(2, except.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(rs.getString(1));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Punish] Could not list accounts on an address: {}", e.getMessage());
		}
		return out;
	}
}
