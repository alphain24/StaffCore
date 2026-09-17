package io.github.alphain24.staffcore.discord.channels;

import java.util.List;

/**
 * The message in the players' appeal channel: what an appeal is, and a button that opens the form.
 * <p>
 * A banned player arriving in the server knows nothing about slash commands and should not need to. One
 * message, kept at the top of a channel they can read and cannot type in, with a button that asks for the
 * code off their ban screen and what happened. {@code /appeal} still works there too.
 * <p>
 * Posted once and edited on every start after, so its wording follows the build; the message is found
 * again by the key it is remembered under, or by its button if that memory was lost.
 */
public final class AppealPanel {
	private AppealPanel() {}

	/** What the panel is remembered under. */
	public static final String KEY = "panel:appeal";

	/** The button's id; the form it opens is {@link #FORM_ID}. */
	public static final String BUTTON_ID = "sc:appealpanel:0";
	public static final String FORM_ID = "sc:appealform:0";

	/** The colour appeals are posted in, so the panel reads as part of the same thing. */
	private static final int COLOUR = 0x5865F2;

	public static Outbound.Message message() {
		Embed embed = Embed.builder("Appeal a ban or mute")
				.color(COLOUR)
				.description("Punished on the Minecraft server and think it was wrong? Tell staff here. "
						+ "Press **Appeal** below, or type `/appeal` in this channel.")
				.field("1. Find your appeal code", "On the screen you see when you try to join, if you are banned; "
						+ "in chat when you try to talk, if you are muted. It looks like `ABCD-EFGH-JKMN`.")
				.field("2. Press Appeal", "Enter the code, and say what happened and why the punishment should be "
						+ "lifted. Add anything staff should know.")
				.field("3. Wait for staff", "Staff read every appeal and answer by direct message, so keep direct "
						+ "messages from this server switched on. They may ask you a question first; reply to it there.")
				.field("Good to know", "One appeal per punishment at a time. If an appeal is rejected, the code "
						+ "stops working, and your ban screen shows a new one and when you can use it.")
				.footer("Only the account that files an appeal sees what it typed.")
				.build();
		return Outbound.Message.of(embed).withRows(List.of(List.of(
				new Outbound.Button(BUTTON_ID, "Appeal", Outbound.Button.Style.PRIMARY, false))));
	}
}
