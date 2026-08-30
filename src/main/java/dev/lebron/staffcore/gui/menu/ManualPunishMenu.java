package dev.lebron.staffcore.gui.menu;

import net.minecraft.server.players.NameAndId;
import dev.lebron.staffcore.gui.Gui;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.modules.punish.Punishment;
import dev.lebron.staffcore.modules.punish.PunishmentType;
import dev.lebron.staffcore.permission.Permissions;
import dev.lebron.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The manual punishment ladder — the escape hatch behind the offence list.
 *
 * <p>Reached from "Something else" on {@link PunishMenu}, for the case an offence ladder
 * does not cover. Everything here is chosen by hand, which is exactly why it is not the
 * default route: hand-picked punishments drift between staff members over time.
 * <p>
 * The rungs sit in escalation order with the target's prior record spelled out above
 * them, because the single most common mistake in moderation is punishing someone at the
 * wrong rung for want of context that was two menus away.
 */
public class ManualPunishMenu extends Gui {

	private static final int HEADER = 4;
	private static final int[] RUNGS = { 20, 21, 23, 24 };
	private static final int RECORD = 31;
	private static final int BACK = 36;
	private static final int CLOSE = 44;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Punish", target.name(), "Manual"),
				(id, inv, v) -> new ManualPunishMenu(id, inv, v, target));
	}

	static void reopen(ServerPlayer viewer, NameAndId target) {
		Guis.goBack(viewer, Theme.title("Punish", target.name(), "Manual"),
				(id, inv, v) -> new ManualPunishMenu(id, inv, v, target));
	}

	private ManualPunishMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId target) {
		super(containerId, playerInventory, viewer, 5);
		this.target = target;
		render();
	}

	@Override
	protected void build() {
		set(HEADER, header());

		PunishmentType[] ladder = PunishmentType.ladder();
		for (int i = 0; i < ladder.length && i < RUNGS.length; i++) {
			placeRung(RUNGS[i], ladder[i]);
		}

		set(RECORD, recordCard());

		button(BACK, Theme.backButton(target.name() + "'s file"), click ->
				PlayerActionsMenu.reopen(viewer, target));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	private void placeRung(int slot, PunishmentType type) {
		if (!Permissions.check(viewer, type.node())) {
			set(slot, Theme.lockedButton("Needs " + type.node()));
			return;
		}

		Icon icon = Icon.of(type.icon())
				.name(type.label(), type.color())
				.lore(describe(type))
				.gap();

		if (type.supportsDuration()) {
			icon.action("Click", "choose how long");
		} else {
			icon.action("Click", "choose a reason");
		}

		button(slot, icon.build(), click -> {
			Sfx.click(viewer);
			PunishDraft draft = PunishDraft.of(target, type);
			if (type.supportsDuration()) {
				DurationMenu.open(viewer, draft);
			} else {
				ReasonMenu.open(viewer, draft);
			}
		});
	}

	private static String describe(PunishmentType type) {
		return switch (type) {
			case WARN -> "Logged, and they are told. Nothing is enforced.";
			case KICK -> "Disconnects them once. They can come straight back.";
			case MUTE -> "They can play but cannot talk.";
			case BAN -> "They cannot join at all.";
			default -> type.label();
		};
	}

	// -------------------------------------------------------------------- context

	private ItemStack header() {
		return Icon.head(target)
				.name(target.name(), Theme.ACCENT)
				.lore("Pick a rung. Nothing happens until you confirm.")
				.build();
	}

	/**
	 * The last few things that happened to this player. Shown as a card rather than a
	 * separate screen so the decision and the evidence are in the same glance.
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

		icon.gap().lore("Open History from their file for the full list.");
		return icon.build();
	}
}
