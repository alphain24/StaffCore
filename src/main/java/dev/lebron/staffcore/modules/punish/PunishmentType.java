package dev.lebron.staffcore.modules.punish;

import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.permission.Nodes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The punishment ladder. Each rung carries its own icon, colour and permission node so
 * the GUI, the commands and the audit log all agree without a lookup table somewhere else.
 * <p>
 * Temporary variants are separate values because the database stores the type verbatim —
 * a row that says {@code TEMPBAN} is unambiguous years later, whereas {@code BAN} with a
 * non-null expiry relies on nobody ever writing a bad row.
 */
public enum PunishmentType {

	/** A logged talking-to. Nothing is enforced; it exists so history has a trail. */
	WARN("Warn", Items.PAPER, Theme.WARN, Nodes.WARN, false),

	/** Immediate disconnect, nothing persistent. */
	KICK("Kick", Items.IRON_BOOTS, Theme.WARN, Nodes.KICK, false),

	MUTE("Mute", Items.NOTE_BLOCK, Theme.PUNISH, Nodes.MUTE, false),
	TEMPMUTE("Temp-mute", Items.NOTE_BLOCK, Theme.PUNISH, Nodes.MUTE, true),

	BAN("Ban", Items.NETHERITE_AXE, Theme.BAD, Nodes.BAN, false),
	TEMPBAN("Temp-ban", Items.IRON_AXE, Theme.BAD, Nodes.BAN, true);

	private final String label;
	private final Item icon;
	private final int color;
	private final String node;
	private final boolean temporary;

	PunishmentType(String label, Item icon, int color, String node, boolean temporary) {
		this.label = label;
		this.icon = icon;
		this.color = color;
		this.node = node;
		this.temporary = temporary;
	}

	public String label() {
		return label;
	}

	/** Used in broadcast lines: "Notch <b>temp-banned</b> Herobrine". */
	public String pastTense() {
		return switch (this) {
			case WARN -> "warned";
			case KICK -> "kicked";
			case MUTE -> "muted";
			case TEMPMUTE -> "temp-muted";
			case BAN -> "banned";
			case TEMPBAN -> "temp-banned";
		};
	}

	public Item icon() {
		return icon;
	}

	public int color() {
		return color;
	}

	public String node() {
		return node;
	}

	public boolean temporary() {
		return temporary;
	}

	public boolean isBan() {
		return this == BAN || this == TEMPBAN;
	}

	public boolean isMute() {
		return this == MUTE || this == TEMPMUTE;
	}

	/** True when this type keeps enforcing after the command returns. */
	public boolean persistent() {
		return isBan() || isMute();
	}

	/** Whether the GUI should offer a duration picker for this rung. */
	public boolean supportsDuration() {
		return isBan() || isMute();
	}

	/** Picks the right concrete value once a duration has been chosen. */
	public PunishmentType withDuration(Long durationMs) {
		if (!supportsDuration()) return this;
		boolean temp = durationMs != null;
		if (isBan()) return temp ? TEMPBAN : BAN;
		return temp ? TEMPMUTE : MUTE;
	}

	/** The four rungs the punish menu shows, in escalation order. */
	public static PunishmentType[] ladder() {
		return new PunishmentType[] { WARN, KICK, MUTE, BAN };
	}
}
