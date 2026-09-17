package io.github.alphain24.staffcore.discord.channels;

import java.util.List;

/**
 * The message in the public contact channel, and what a request's private thread says.
 * <p>
 * For a player who needs a person: frozen in game and told to come here, stuck, or with something to
 * report that does not fit a command. They press one button, say who they are and what they need, and get
 * a private thread only they and the staff who join can see.
 */
public final class ContactPanel {
	private ContactPanel() {}

	/** What the panel is remembered under. */
	public static final String KEY = "panel:contact";

	/** The button's id; the form it opens is {@link #FORM_ID}. */
	public static final String BUTTON_ID = "sc:contactpanel:0";
	public static final String FORM_ID = "sc:contactform:0";

	private static final int COLOUR = 0x57F287;

	public static Outbound.Message message() {
		Embed embed = Embed.builder("Contact staff")
				.color(COLOUR)
				.description("Need a member of staff? Press **Contact Staff**, say who you are in game and what you "
						+ "need. You get a private thread that only you and the staff who answer can see.")
				.field("Frozen in game?", "Press the button and say so. Staff are told at once, and you can talk to them "
						+ "in your thread. Do not leave the server while you are frozen: staff are told that too.")
				.field("What happens next", "Staff join your thread when they can. Everything you write there is "
						+ "seen only by you and them. One request at a time; it closes when your question is answered.")
				.field("Banned or muted?", "To ask for a punishment to be lifted, use the appeal channel instead: it "
						+ "needs the code from your ban screen.")
				.footer("Only you see the form and the bot's answer to it.")
				.build();
		return Outbound.Message.of(embed).withRows(List.of(List.of(
				new Outbound.Button(BUTTON_ID, "Contact Staff", Outbound.Button.Style.SUCCESS, false))));
	}

	/** The first message in a request's thread: what they asked, and a way to close it themselves. */
	public static Outbound.Message opening(HelpDesk.Request request) {
		Embed embed = Embed.builder("Request #" + request.id())
				.color(COLOUR)
				.description(Text.safe(request.text(), 1500))
				.inline("Minecraft", Text.safe(request.minecraftName(), 32))
				.inline("Opened", Text.when(request.openedAt()))
				.footer("A member of staff will join this thread. Add anything else they should know here.")
				.build();
		return Outbound.Message.of(embed).withRows(List.of(List.of(
				new Outbound.Button("sc:helpclose:" + request.id(), "Close request", Outbound.Button.Style.SECONDARY,
						false))));
	}
}
