package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the bot asks Discord for: the one privileged intent only for the staff chat bridge, and a way to
 * connect without it.
 */
class IntentsTest {

	@Test
	@DisplayName("message content is asked for only with staff chat bridged, and can be left out")
	void intents() {
		DiscordSettings settings = new DiscordSettings();
		assertEquals(DiscordSettings.CREATE, settings.staffChatChannelId, "a new file does not bridge staff chat");
		assertTrue(JdaGateway.intents(settings, true).contains(GatewayIntent.MESSAGE_CONTENT));

		var without = JdaGateway.intents(settings, false);
		assertFalse(without.contains(GatewayIntent.MESSAGE_CONTENT), "the fallback still asks for the privileged intent");
		assertTrue(without.contains(GatewayIntent.GUILD_MESSAGES),
				"without message events a typed staff chat line cannot even be answered");

		settings.staffChatChannelId = "";
		settings.appealsChannelId = "";
		assertTrue(JdaGateway.intents(settings, true).isEmpty(), "asked for intents nothing uses");

		// The other two privileged intents are never asked for.
		var everything = JdaGateway.intents(new DiscordSettings(), true);
		assertFalse(everything.contains(GatewayIntent.GUILD_MEMBERS));
		assertFalse(everything.contains(GatewayIntent.GUILD_PRESENCES));
	}
}
