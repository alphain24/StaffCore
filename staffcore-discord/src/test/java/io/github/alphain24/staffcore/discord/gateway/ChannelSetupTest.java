package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.discord.channels.Outbound.Channel;
import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the private channels are made with: who can see them, and what the bot is given.
 */
class ChannelSetupTest {

	@Test
	@DisplayName("the bot is given exactly what it posts with, and nothing that manages anything")
	void botPermissions() {
		assertEquals(Set.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS,
				Permission.MESSAGE_HISTORY, Permission.CREATE_PUBLIC_THREADS, Permission.MESSAGE_SEND_IN_THREADS),
				ChannelSetup.BOT);
		for (Permission permission : ChannelSetup.BOT) {
			assertFalse(permission.name().startsWith("MANAGE") || permission == Permission.ADMINISTRATOR,
					permission + " is more than posting needs");
		}
	}

	@Test
	@DisplayName("staff can read every channel and its threads, and type directly only in staff chat")
	void staffPermissions() {
		for (Channel channel : Channel.values()) {
			Set<Permission> allowed = ChannelSetup.staff(channel);
			assertTrue(allowed.contains(Permission.VIEW_CHANNEL), channel + " is hidden from staff");
			if (channel == Channel.PUNISH_PANEL) {
				assertEquals(Set.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY), allowed,
						"the punishment panel is a button to press, nothing more");
				continue;
			}
			assertTrue(allowed.contains(Permission.MESSAGE_SEND_IN_THREADS), channel + " threads cannot be answered");
			assertEquals(channel == Channel.STAFF_CHAT, allowed.contains(Permission.MESSAGE_SEND), channel.toString());
			assertFalse(allowed.stream().anyMatch(p -> p.name().startsWith("MANAGE")), channel + " gives staff a manage permission");
		}
	}

	@Test
	@DisplayName("the punishment panel's channel is for the roles that hold its permission")
	void panelNode() {
		assertEquals("discord.punishpanel", ChannelSetup.PANEL_NODE);
		assertEquals("punish", ChannelSetup.name(Channel.PUNISH_PANEL));
	}

	@Test
	@DisplayName("the players' appeal channel can be read by everybody, written in by nobody, and set up by the bot")
	void publicAppealChannel() {
		assertTrue(ChannelSetup.PUBLIC_ALLOWED.contains(Permission.VIEW_CHANNEL));
		assertFalse(ChannelSetup.PUBLIC_ALLOWED.contains(Permission.MESSAGE_SEND), "players can type in #appeal");
		assertTrue(ChannelSetup.PUBLIC_DENIED.contains(Permission.MESSAGE_SEND));
		assertTrue(ChannelSetup.PUBLIC_DENIED.contains(Permission.CREATE_PUBLIC_THREADS));
		// Discord refuses an override naming a permission the bot does not hold, and the invite link grants
		// exactly BOT plus the two it needs to make channels.
		for (Permission permission : ChannelSetup.PUBLIC_ALLOWED) {
			assertTrue(ChannelSetup.BOT.contains(permission), permission + " is not the bot's to grant");
		}
		for (Permission permission : ChannelSetup.PUBLIC_DENIED) {
			assertTrue(ChannelSetup.BOT.contains(permission), permission + " is not the bot's to deny");
		}
		assertFalse(ChannelSetup.names().contains(ChannelSetup.INTAKE), "#appeal shares a name with a staff channel");
	}

	@Test
	@DisplayName("each channel has its own name, and making them needs only Manage Channels and Manage Roles")
	void namesAndRequirements() {
		Set<String> names = new HashSet<>();
		for (Channel channel : Channel.values()) names.add(ChannelSetup.name(channel));
		assertEquals(Channel.values().length, names.size());
		assertEquals(Set.of(Permission.MANAGE_CHANNEL, Permission.MANAGE_ROLES), ChannelSetup.TO_CREATE);
	}
}
