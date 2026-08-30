package dev.lebron.staffcore.modules.punish;

import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.util.DurationParser;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * A named thing players do wrong, and the escalation that follows it.
 * <p>
 * The ladder is the point. "Ban" is not a decision staff should be making from scratch
 * every time — the server decides once, in config, that a second instance of chat abuse is
 * a one-day mute, and then every staff member applies that identically. It removes the two
 * failure modes of hand-picked punishments: the new moderator who is too harsh, and the
 * one who is too lenient with their friends.
 * <p>
 * Steps are consumed by prior-count: a player with two previous records for this offence
 * gets step three. Past the end of the ladder, the last step repeats.
 */
public class Offence {

	/** One rung: what to apply, and for how long. */
	public static class Step {
		public String type = "WARN";      // WARN | KICK | MUTE | BAN
		public String duration = "perm";  // a duration spec, or "perm"

		public Step() {}

		public Step(String type, String duration) {
			this.type = type;
			this.duration = duration;
		}

		public PunishmentType baseType() {
			try {
				return PunishmentType.valueOf(type.toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return PunishmentType.WARN;
			}
		}

		/** Null for permanent, or for a type that has no duration. */
		public Long durationMs() {
			PunishmentType base = baseType();
			if (!base.supportsDuration()) return null;
			return DurationParser.parse(duration);
		}

		public String describe() {
			PunishmentType base = baseType();
			if (!base.supportsDuration()) return base.label();
			Long ms = durationMs();
			return base.label() + " · " + (ms == null
					? "permanent"
					: dev.lebron.staffcore.util.TimeFormat.duration(ms));
		}
	}

	public String id = "custom";
	public String label = "Custom";
	public String description = "";
	/** Item id for the menu icon, e.g. {@code minecraft:netherite_sword}. */
	public String icon = "minecraft:paper";
	public List<Step> ladder = new ArrayList<>();

	public Offence() {}

	public Offence(String id, String label, String description, String icon, Step... steps) {
		this.id = id;
		this.label = label;
		this.description = description;
		this.icon = icon;
		this.ladder = new ArrayList<>(List.of(steps));
	}

	public Item iconItem() {
		return Mc.itemFromId(icon, Items.PAPER);
	}

	/** The rung for somebody with {@code priorCount} previous records of this offence. */
	public Step stepFor(int priorCount) {
		if (ladder.isEmpty()) return new Step("WARN", "perm");
		int index = Math.min(priorCount, ladder.size() - 1);
		return ladder.get(index);
	}

	public boolean escalatesFurther(int priorCount) {
		return priorCount < ladder.size() - 1;
	}

	// ------------------------------------------------------------------ defaults

	/**
	 * The starting set. Deliberately opinionated — cheating is an immediate permanent ban
	 * because there is no version of "a bit of cheating" worth a second chance, whereas
	 * chat offences ladder slowly because people have bad days and mostly stop after a mute.
	 */
	public static List<Offence> defaults() {
		List<Offence> out = new ArrayList<>();

		out.add(new Offence("cheating", "Hacking / Cheating",
				"Any client modification that gives an unfair advantage.",
				"minecraft:netherite_sword",
				new Step("BAN", "perm")));

		out.add(new Offence("griefing", "Griefing",
				"Destroying or stealing from another player's build.",
				"minecraft:tnt",
				// Graduated rather than an instant permanent ban. Most first-time "griefing"
				// reports are a fence post taken for a build or a misunderstood shared base,
				// and a ladder lets the same button handle that case and the serial griefer
				// without staff having to judge severity from scratch every time. A mute is
				// deliberately not on this ladder — it does nothing to stop someone breaking
				// blocks, so it would be a rung that reads as action while changing nothing.
				new Step("WARN", "perm"),
				new Step("BAN", "3d"),
				new Step("BAN", "14d"),
				new Step("BAN", "perm")));

		out.add(new Offence("toxicity", "Chat Abuse / Toxicity",
				"Harassment, slurs, or targeted abuse in chat.",
				"minecraft:note_block",
				new Step("MUTE", "1h"),
				new Step("MUTE", "1d"),
				new Step("MUTE", "7d"),
				new Step("BAN", "7d")));

		out.add(new Offence("staff_disrespect", "Disrespecting Staff",
				"Abuse aimed at staff carrying out their job.",
				"minecraft:shield",
				new Step("WARN", "perm"),
				new Step("MUTE", "1h"),
				new Step("MUTE", "1d")));

		out.add(new Offence("advertising", "Advertising",
				"Promoting another server or service.",
				"minecraft:oak_sign",
				new Step("MUTE", "1d"),
				new Step("BAN", "7d"),
				new Step("BAN", "perm")));

		out.add(new Offence("spam", "Spamming",
				"Flooding chat, repeating messages, or command spam.",
				"minecraft:paper",
				new Step("WARN", "perm"),
				new Step("MUTE", "30m"),
				new Step("MUTE", "6h")));

		out.add(new Offence("exploiting", "Exploiting a Bug",
				"Abusing a bug rather than reporting it.",
				"minecraft:redstone_torch",
				new Step("BAN", "7d"),
				new Step("BAN", "perm")));

		out.add(new Offence("scamming", "Scamming",
				"Taking items or currency in bad faith.",
				"minecraft:gold_ingot",
				new Step("BAN", "14d"),
				new Step("BAN", "perm")));

		out.add(new Offence("inappropriate", "Inappropriate Name or Skin",
				"A name or skin that breaks the server rules.",
				"minecraft:player_head",
				new Step("KICK", "perm"),
				new Step("BAN", "perm")));

		out.add(new Offence("evasion", "Ban Evasion",
				"Rejoining on another account to dodge a punishment.",
				"minecraft:barrier",
				new Step("BAN", "perm")));

		return out;
	}
}
