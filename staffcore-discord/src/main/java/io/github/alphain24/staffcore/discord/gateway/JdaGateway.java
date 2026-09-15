package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordLinkResult;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.discord.StaffCoreDiscord;
import io.github.alphain24.staffcore.discord.config.BotToken;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * The real connection, through JDA.
 * <p>
 * No privileged intents. The bot needs nothing but the interactions people send it, and those
 * carry the member's roles; asking Discord for the member list or message content would be asking
 * for data this does not use.
 */
public final class JdaGateway extends ListenerAdapter implements DiscordGateway {

	private final BotToken token;
	private final DiscordSettings settings;
	private final RoleMap roles;
	private final Executor replies;

	private volatile JDA jda;
	private volatile String state = "not started";

	/**
	 * @param replies where answers are sent from once StaffCore has them — the companion's own
	 *                thread, so a reply is never sent from the server thread the answer came back on
	 */
	public JdaGateway(BotToken token, DiscordSettings settings, RoleMap roles, Executor replies) {
		this.token = token;
		this.settings = settings;
		this.roles = roles;
		this.replies = replies;
	}

	@Override
	public void start() {
		state = "connecting";
		jda = JDABuilder.createLight(token.revealForLogin(), EnumSet.noneOf(GatewayIntent.class))
				.setEnableShutdownHook(false)
				.addEventListeners(this)
				.build();
	}

	@Override
	public void stop() {
		JDA connection = jda;
		if (connection == null) return;
		state = "stopping";
		connection.shutdown();
		// Given a few seconds to close cleanly, then closed hard, on the companion's thread — the
		// server is shutting down behind this and does not wait for it.
		CompletableFuture.runAsync(() -> {
			try {
				if (!connection.awaitShutdown(5, TimeUnit.SECONDS)) connection.shutdownNow();
			} catch (InterruptedException e) {
				connection.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}, replies);
	}

	@Override
	public String state() {
		JDA connection = jda;
		if (connection == null) return state;
		return switch (connection.getStatus()) {
			case CONNECTED -> state.startsWith("connected") ? state : "connected";
			case SHUTDOWN, SHUTTING_DOWN -> "stopped";
			case FAILED_TO_LOGIN -> "could not log in: Discord refused the token";
			default -> "connecting (" + connection.getStatus().name().toLowerCase(java.util.Locale.ROOT)
					.replace('_', ' ') + ")";
		};
	}

	// ------------------------------------------------------------------ events

	@Override
	public void onReady(ReadyEvent event) {
		Guild guild = event.getJDA().getGuildById(settings.guildId);
		if (guild == null) {
			state = "connected as " + event.getJDA().getSelfUser().getName() + ", but not in the guild "
					+ "named by guildId — invite the bot to that server";
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Connected, but the bot is not in guild {}. "
					+ "Invite it to that server, or correct guildId.", settings.guildId);
			return;
		}

		guild.updateCommands().addCommands(
				Commands.slash("link", "Link your Discord account to your Minecraft account")
						.addOption(OptionType.STRING, "code", "The code from /staff discord link in game", true),
				Commands.slash("unlink", "Unlink your Discord account from Minecraft"),
				Commands.slash("whoami", "Which Minecraft account you are linked to, and what you can use")
		).queue(ok -> { }, failure -> StaffCoreDiscord.LOGGER.warn(
				"[StaffCore Discord] Could not register commands in {} ({}).", guild.getName(),
				failure.getClass().getSimpleName()));

		state = "connected as " + event.getJDA().getSelfUser().getName() + " in " + guild.getName();
		StaffCoreDiscord.LOGGER.info("[StaffCore Discord] {}", state);
	}

	@Override
	public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
		// Only in the configured guild. A DM has no roles to read, and another server has nobody
		// from this one watching what the bot is asked to do there.
		if (event.getGuild() == null || !settings.guildId.equals(event.getGuild().getId())) {
			event.reply("This bot only answers in its own server.").setEphemeral(true).queue();
			return;
		}

		DiscordUser user = userOf(event);
		switch (event.getName()) {
			case "link" -> {
				String code = event.getOption("code", "", OptionMapping::getAsString);
				answer(event, DiscordAccess.link(user, code).thenApply(DiscordLinkResult::message));
			}
			case "unlink" -> answer(event, DiscordAccess.unlink(user).thenApply(DiscordLinkResult::message));
			case "whoami" -> answer(event, DiscordAccess.standing(user).thenApply(Replies::standing));
			default -> event.reply("Unknown command.").setEphemeral(true).queue();
		}
	}

	/** Who clicked, and what their roles in this guild map to. */
	private DiscordUser userOf(SlashCommandInteractionEvent event) {
		Member member = event.getMember();
		List<String> roleIds = member == null ? List.of()
				: member.getRoles().stream().map(Role::getId).toList();
		return new DiscordUser(event.getUser().getId(), event.getUser().getName(), roles.nodesFor(roleIds));
	}

	/**
	 * Acknowledges at once, privately, then answers when StaffCore has.
	 * <p>
	 * Private because the answers are about the person asking — which account they are linked to,
	 * what they hold — and a link code typed in a public channel should not be followed by a public
	 * confirmation of whose account it opened.
	 */
	private void answer(SlashCommandInteractionEvent event, CompletableFuture<String> reply) {
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		reply.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((text, failure) -> {
					String message = failure != null ? Replies.failure(failure) : text;
					if (failure != null) {
						StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] /{} failed ({})", event.getName(),
								failure.getClass().getSimpleName());
					}
					hook.sendMessage(token.redact(message)).queue();
				}, replies);
	}
}
