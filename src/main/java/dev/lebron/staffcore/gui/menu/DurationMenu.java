package dev.lebron.staffcore.gui.menu;

import dev.lebron.staffcore.config.StaffConfig;
import dev.lebron.staffcore.gui.Gui;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.util.DurationParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * How long the ban or mute lasts.
 * <p>
 * Every option comes from {@code presetDurations} in the config, so a server can set its
 * own ladder without touching code. Permanent is rendered in bedrock and sits apart from
 * the timed options, because it is a different kind of decision.
 */
public class DurationMenu extends Gui {

	private static final int HEADER = 4;
	private static final int[] SLOTS = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25
	};
	private static final int BACK = 36;
	private static final int CLOSE = 44;

	private final PunishDraft draft;

	public static void open(ServerPlayer viewer, PunishDraft draft) {
		Guis.navigate(viewer, Theme.title("Punish", draft.target().name(), "Duration"),
				(id, inv, v) -> new DurationMenu(id, inv, v, draft));
	}

	private DurationMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, PunishDraft draft) {
		super(containerId, playerInventory, viewer, 5);
		this.draft = draft;
		render();
	}

	@Override
	protected void build() {
		set(HEADER, Icon.head(draft.target())
				.name("How long?", Theme.ACCENT)
				.field("Punishment", draft.base().label(), draft.base().color())
				.field("Target", draft.target().name())
				.gap()
				.lore("Pick a length, then a reason.")
				.build());

		List<StaffConfig.Duration> presets = StaffConfig.get().presetDurations;
		for (int i = 0; i < presets.size() && i < SLOTS.length; i++) {
			place(SLOTS[i], presets.get(i));
		}

		button(BACK, Theme.backButton("the punish menu"), click ->
				ManualPunishMenu.reopen(viewer, draft.target()));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	private void place(int slot, StaffConfig.Duration preset) {
		Long ms = DurationParser.parse(preset.spec());
		boolean permanent = ms == null;

		Icon icon = Icon.of(permanent ? Items.BEDROCK : Items.CLOCK)
				.name(preset.label(), permanent ? Theme.BAD : Theme.TEXT)
				.field("Spec", preset.spec());

		if (permanent) {
			icon.gap().warn("Does not expire on its own.");
		}
		icon.gap().action("Click", "choose a reason");

		button(slot, icon.build(), click -> {
			Sfx.click(viewer);
			ReasonMenu.open(viewer, draft.withDuration(ms));
		});
	}
}
