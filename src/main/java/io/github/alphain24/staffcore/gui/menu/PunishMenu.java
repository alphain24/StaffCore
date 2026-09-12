package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.Offence;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Pick what they did, not what to do about it.
 * <p>
 * This is the change that matters most to a staff team. Choosing a punishment from scratch
 * every time produces drift — the new moderator who bans for swearing, the veteran who lets
 * their friends off with a warning — and neither is visible until somebody complains. Here
 * the server decides once, in config, that a second instance of chat abuse is a one-day
 * mute, and every staff member applies that same ladder identically.
 * <p>
 * The rung is chosen from how many times this player has already been done for <em>this
 * specific offence</em>, so escalation is per-offence rather than a single global counter:
 * three warnings for spam do not make their first griefing report a permanent ban.
 */
public class PunishMenu extends Gui {

	private static final int HEADER = 4;
	private static final int[] SLOTS = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34
	};
	private static final int RECORD = 39;
	private static final int MANUAL = 41;
	private static final int BACK = 36;
	private static final int CLOSE = 44;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Punish", target.name()),
				(id, inv, v) -> new PunishMenu(id, inv, v, target));
	}

	static void reopen(ServerPlayer viewer, NameAndId target) {
		Guis.goBack(viewer, Theme.title("Punish", target.name()),
				(id, inv, v) -> new PunishMenu(id, inv, v, target));
	}

	private PunishMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId target) {
		super(containerId, playerInventory, viewer, 5);
		this.target = target;
		render();
	}

	// -------------------------------------------------------------------- layout

	@Override
	protected void build() {
		set(HEADER, header());

		List<Offence> offences = StaffConfig.get().offences;
		for (int i = 0; i < offences.size() && i < SLOTS.length; i++) {
			place(SLOTS[i], offences.get(i));
		}

		set(RECORD, recordCard());

		button(MANUAL, Icon.of(Items.NAME_TAG)
				.name("Something else", Theme.MUTED)
				.paragraph("Choose the punishment and duration by hand, for anything the "
						+ "ladders do not cover.", Theme.MUTED)
				.gap()
				.warn("Hand-picked punishments drift between staff. Prefer an offence.")
				.build(), click -> {
			Sfx.click(viewer);
			ManualPunishMenu.open(viewer, target);
		});

		backButton(BACK, target.name() + "'s file", () -> PlayerActionsMenu.reopen(viewer, target));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	private void place(int slot, Offence offence) {
		int priors = Mods.punish().countForOffence(target.id(), offence.id);
		Offence.Step step = offence.stepFor(priors);
		PunishmentType base = step.baseType();

		if (!Permissions.check(viewer, base.node())) {
			set(slot, Theme.lockedButton("Needs " + base.node()));
			return;
		}

		Icon icon = Icon.of(offence.iconItem())
				.name(offence.label, base.color())
				.paragraph(offence.description, Theme.MUTED)
				.gap()
				.field("Prior offences", String.valueOf(priors), priors > 0 ? Theme.WARN : Theme.MUTED)
				.field("Applies", step.describe(), base.color());

		if (offence.escalatesFurther(priors)) {
			Offence.Step next = offence.stepFor(priors + 1);
			icon.field("Next time", next.describe(), Theme.MUTED);
		} else if (offence.ladder.size() > 1) {
			icon.lore("● Top of the ladder", Theme.BAD);
		}

		icon.gap().action("Click", "review and confirm");
		if (priors > 0) icon.glow();

		button(slot, icon.build(), click -> {
			Sfx.click(viewer);
			confirm(offence, priors, step);
		});
	}

	// ------------------------------------------------------------------- confirm

	private void confirm(Offence offence, int priors, Offence.Step step) {
		PunishmentType base = step.baseType();
		Long durationMs = step.durationMs();
		PunishmentType resolved = base.withDuration(durationMs);

		Icon summary = Icon.of(offence.iconItem())
				.name(resolved.label() + " " + target.name(), resolved.color())
				.field("Offence", offence.label)
				.field("Their record", priors == 0
						? "first time"
						: priors + " prior" + (priors == 1 ? "" : "s"))
				.field("Duration", !base.supportsDuration() ? "n/a"
						: durationMs == null ? "Permanent" : TimeFormat.duration(durationMs))
				.field("Issued by", Mc.name(viewer))
				.gap()
				.warn(consequence(base));

		if (offence.escalatesFurther(priors)) {
			summary.lore("Next time this escalates to " + offence.stepFor(priors + 1).describe() + ".",
					Theme.MUTED);
		}

		ConfirmMenu.open(viewer, offence.label, summary.build(),
				() -> apply(offence, base, durationMs),
				() -> reopen(viewer, target));
	}

	private static String consequence(PunishmentType base) {
		return switch (base) {
			case WARN -> "They get a message. Nothing else changes.";
			case KICK -> "They are disconnected immediately.";
			case MUTE -> "They cannot talk until it expires or is lifted.";
			case BAN -> "They are disconnected and cannot rejoin.";
			default -> "";
		};
	}

	private void apply(Offence offence, PunishmentType base, Long durationMs) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		// The identity, not the name. Resolving a name back to a player is a step that can
		// fail, and the guards downstream treat an unresolvable name as untrusted.
		Punishment result = Mods.punish().apply(server, target, Mc.name(viewer),
				base, durationMs, offence.label, offence.id, null,
				io.github.alphain24.staffcore.permission.Actor.of(viewer));

		if (result == null) {
			viewer.sendSystemMessage(Theme.bad("The punishment could not be saved — check the server log."));
			Sfx.error(viewer);
		} else {
			viewer.sendSystemMessage(Theme.good(
					result.type().label() + " applied to " + target.name() + " for " + offence.label + "."));
			Sfx.bigSuccess(viewer);
		}
		PlayerActionsMenu.reopen(viewer, target);
	}

	// -------------------------------------------------------------------- context

	private ItemStack header() {
		return Icon.head(target)
				.name(target.name(), Theme.ACCENT)
				.lore("Pick what they did. The ladder decides the rest.")
				.gap()
				.lore("Nothing happens until you confirm.")
				.build();
	}

	/**
	 * The last few things that happened to this player, on the same screen as the decision.
	 * The most common moderation mistake is punishing at the wrong rung for want of context
	 * that was two menus away.
	 */
	private ItemStack recordCard() {
		List<Punishment> history = Mods.punish().history(target.id());
		Punishment ban = Mods.punish().activeBan(target.id());
		Punishment mute = Mods.punish().activeMute(target.id());

		Icon icon = Icon.of(Items.BOOK)
				.name("Their record", Theme.TEXT)
				.field("Total punishments", String.valueOf(history.size()),
						history.isEmpty() ? Theme.GOOD : Theme.WARN);

		if (ban != null) icon.warn("Currently banned — " + ban.remaining());
		if (mute != null) icon.warn("Currently muted — " + mute.remaining());

		if (history.isEmpty()) {
			icon.gap().lore("Clean. This would be their first.", Theme.GOOD);
		} else {
			icon.gap().lore("Most recent:", Theme.MUTED);
			history.stream().limit(4).forEach(p -> icon.lore("  " + p.type().label()
					+ " · " + TimeFormat.ago(p.createdAt())
					+ " · " + p.reasonOr("no reason")));
			if (history.size() > 4) {
				icon.lore("  … and " + (history.size() - 4) + " more");
			}
		}
		return icon.build();
	}
}
