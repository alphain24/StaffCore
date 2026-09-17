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
				Permission.MESSAGE_HISTORY, Permission.CREATE_PUBLIC_THREADS, Permission.MESSAGE_SEND_IN_THREADS,
				Permission.MESSAGE_ATTACH_FILES), ChannelSetup.BOT);

		// The invite link in the setup guide asks for exactly these, private threads for the contact channel,
		// and the two that make channels.
		assertTrue(ChannelSetup.BOT_CONTACT.containsAll(ChannelSetup.BOT));
		assertEquals(Set.of(Permission.CREATE_PRIVATE_THREADS), new HashSet<>(java.util.EnumSet.complementOf(
				java.util.EnumSet.copyOf(ChannelSetup.BOT))).stream().filter(ChannelSetup.BOT_CONTACT::contains)
				.collect(java.util.stream.Collectors.toSet()));
		java.util.EnumSet<Permission> invited = java.util.EnumSet.copyOf(ChannelSetup.BOT_CONTACT);
		invited.addAll(ChannelSetup.TO_CREATE);
		assertEquals(Permission.getRaw(invited), ChannelSetup.INVITE_PERMISSIONS);
		assertTrue(readGuide().contains("permissions=" + ChannelSetup.INVITE_PERMISSIONS),
				"the setup guide's invite link asks for different permissions than the bot uses");
		for (Permission permission : ChannelSetup.BOT) {
			assertFalse(permission.name().startsWith("MANAGE") || permission == Permission.ADMINISTRATOR,
					permission + " is more than posting needs");
		}
	}

	private static String readGuide() {
		try {
			return java.nio.file.Files.readString(java.nio.file.Path.of("..", "docs", "discord-setup.md"));
		} catch (java.io.IOException e) {
			throw new AssertionError("the setup guide could not be read from the companion's tests", e);
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
	@DisplayName("the contact channel can be read by everybody, and players write only in their own private threads")
	void publicContactChannel() {
		assertTrue(ChannelSetup.CONTACT_ALLOWED.contains(Permission.VIEW_CHANNEL));
		assertTrue(ChannelSetup.CONTACT_ALLOWED.contains(Permission.MESSAGE_SEND_IN_THREADS),
				"players could not answer in their thread");
		assertFalse(ChannelSetup.CONTACT_ALLOWED.contains(Permission.MESSAGE_SEND), "players can type in #contact-staff");
		assertTrue(ChannelSetup.CONTACT_DENIED.containsAll(Set.of(Permission.MESSAGE_SEND,
				Permission.CREATE_PUBLIC_THREADS, Permission.CREATE_PRIVATE_THREADS)),
				"players could start threads of their own");
		for (Permission permission : ChannelSetup.CONTACT_ALLOWED) {
			assertTrue(ChannelSetup.BOT_CONTACT.contains(permission), permission + " is not the bot's to grant");
		}
		for (Permission permission : ChannelSetup.CONTACT_DENIED) {
			assertTrue(ChannelSetup.BOT_CONTACT.contains(permission), permission + " is not the bot's to deny");
		}
		assertFalse(ChannelSetup.names().contains(ChannelSetup.CONTACT), "#contact-staff shares a name with a staff channel");
		assertEquals("help-requests", ChannelSetup.name(Channel.HELP_REQUESTS));
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
