package io.github.alphain24.staffcore.api.internal;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.DiscordDecision;
import io.github.alphain24.staffcore.api.DiscordLinkResult;
import io.github.alphain24.staffcore.api.DiscordOperation;
import io.github.alphain24.staffcore.api.DiscordStanding;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.discord.DiscordLinks;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.DiscordAuthority;
import io.github.alphain24.staffcore.permission.Rank;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
