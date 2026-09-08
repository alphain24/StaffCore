package io.github.alphain24.staffcore.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Clickable things in chat.
 * <p>
 * Chat is the primary surface for this mod and this is most of why. A chest GUI needs a
 * screen, a slot layout and a click handler for every action, desyncs under lag, and cannot be
 * copied out or scrolled back to. A line of chat where the player's name opens their file and
 * the coordinates teleport you there costs one component wrapper and works everywhere,
 * including in a client that has scrolled two hundred lines past it.
 * <p>
 * <b>Hover carries the detail.</b> A staff line that tries to say everything is unreadable at
 * a glance, and a glance is all anybody gives it while three other things are happening. So
 * the line stays short and the hover holds the rest — which also means the same component
 * works in a busy chat and in a quiet one.
 * <p>
 * Everything here runs a command rather than doing anything itself. That keeps one permission
 * check and one audit trail: a clickable ban would otherwise be a second path into
 * {@code PunishmentModule} with its own idea of who is allowed to use it.
 */
public final class Link {
	private Link() {}

	/** Underlined so it is visibly clickable. Colour still carries the meaning. */
	private static Style style(int rgb, ClickEvent click, Component hover) {
		Style style = Style.EMPTY
				.withColor(TextColor.fromRgb(rgb))
				.withItalic(false)
				.withUnderlined(true)
				.withClickEvent(click);
		return hover == null ? style : style.withHoverEvent(new HoverEvent.ShowText(hover));
	}

	/** Text that runs a command when clicked. */
	public static MutableComponent run(String text, String command, int rgb, String hover) {
		return Component.literal(text).setStyle(style(rgb, new ClickEvent.RunCommand(command),
				hover == null ? null : Component.literal(hover)));
	}

	/**
	 * Text that types a command into the box without sending it.
	 * <p>
	 * For anything destructive. A one-click ban in a scrolling chat window is a mis-click
	 * away from being the wrong person, and the difference between running and suggesting is
	 * the half-second somebody needs to read what they are about to do.
	 */
	public static MutableComponent suggest(String text, String command, int rgb, String hover) {
		return Component.literal(text).setStyle(style(rgb, new ClickEvent.SuggestCommand(command),
				hover == null ? null : Component.literal(hover)));
	}

	public static MutableComponent copy(String text, String value, int rgb, String hover) {
		return Component.literal(text).setStyle(style(rgb, new ClickEvent.CopyToClipboard(value),
				hover == null ? null : Component.literal(hover)));
	}

	// ------------------------------------------------------------- the usual things

	/**
	 * A player's name, opening their file.
	 * <p>
	 * The single highest-value link in the mod: every line that names somebody becomes a way
	 * to find out who they are, without anybody having to retype a name they cannot see the
	 * spelling of. {@code Steve_} and {@code Steve__} is how the wrong person gets banned.
	 */
	public static MutableComponent player(String name) {
		return player(name, null);
	}

	/** As above, with the prior-punishment count that should follow a name everywhere. */
	public static MutableComponent player(String name, Integer priors) {
		String shown = priors == null || priors == 0 ? name : name + " (" + priors + " prior)";
		return run(shown, "/staff " + name, Theme.ACCENT,
				"Open " + name + "'s file"
						+ (priors == null ? "" : "\n" + priors + " previous punishment(s)"));
	}

	/** A case id, opening the case. */
	public static MutableComponent caseId(String id) {
		return run(id, "/staff case " + id, Theme.ACCENT, "Open case " + id);
	}

	/** A punishment id, opening its record. */
	public static MutableComponent punishment(long id, String summary) {
		return run("#" + id, "/staff history id " + id, Theme.MUTED,
				summary == null ? "Punishment #" + id : summary);
	}

	/**
	 * Coordinates that teleport.
	 * <p>
	 * Suggested rather than run: a teleport moves you out of wherever you were, which is
	 * occasionally the middle of something. One extra keypress is a fair price.
	 */
	public static MutableComponent position(String world, BlockPos pos) {
		String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
		return suggest(coords,
				"/execute in " + world + " run tp @s " + pos.getX() + " " + pos.getY() + " "
						+ pos.getZ(),
				Theme.MUTED, "Click to fill in a teleport to " + coords + "\nin " + world);
	}
}
