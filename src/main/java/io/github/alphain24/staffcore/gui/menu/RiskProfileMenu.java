package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.cases.RiskProfile;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * One player's risk profile: the rough size, and every reason behind it, each opening the screen
 * where its evidence is. See {@link RiskProfile} for what it is and, more importantly, is not.
 */
public final class RiskProfileMenu extends Gui {

	private static final int HEADER = 4;
	private static final int[] FACTORS = {
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34};
	private static final int BACK = 45;
	private static final int CLOSE = 49;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Risk", target.name()),
				(id, inv, v) -> new RiskProfileMenu(id, inv, v, target));
	}

	private RiskProfileMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target) {
		super(containerId, playerInventory, viewer, 6);
		this.target = target;
		render();
	}

	@Override
	protected void build() {
		RiskProfile.Assessment assessment = RiskProfile.assess(
				RiskProfile.gather(Mc.server(viewer), target.id()));

		int colour = switch (assessment.level()) {
			case HIGH -> Theme.BAD;
			case ELEVATED -> Theme.WARN;
			case LOW -> Theme.GOOD;
		};
		Icon header = Icon.head(target)
				.name(target.name() + " — " + assessment.level().label() + " (" + assessment.score() + ")", colour)
				.lore("What is already on record, weighed and added up.")
				.gap()
				.lore("Not a verdict. Nothing acts on this number: no", Theme.MUTED)
				.lore("alert, no case, no punishment. Read the reasons.", Theme.MUTED);
		if (assessment.level() == RiskProfile.Level.HIGH) header.glow();
		set(HEADER, header.build());

		if (assessment.factors().isEmpty()) {
			set(22, Icon.of(Mc.pane(net.minecraft.world.item.DyeColor.LIME))
					.name("Nothing on record", Theme.GOOD)
					.lore("No punishments, cases, signals, reports or")
					.lore("banned linked accounts that count.")
					.build());
		}

		for (int i = 0; i < assessment.factors().size() && i < FACTORS.length; i++) {
			RiskProfile.Factor factor = assessment.factors().get(i);
			button(FACTORS[i], Icon.of(iconFor(factor.source()))
					.name(factor.label() + "  +" + factor.points(), Theme.ACCENT)
					.lore(factor.detail(), Theme.TEXT)
					.gap()
					.action("Click", whereTo(factor.source()))
					.build(), click -> follow(factor.source()));
		}

		backButton(BACK, target.name() + "'s file", () -> PlayerActionsMenu.reopen(viewer, target));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	private static Item iconFor(RiskProfile.Source source) {
		return switch (source) {
			case PUNISHMENTS -> Items.NETHERITE_AXE;
			case CASES -> Items.WRITABLE_BOOK;
			case SIGNALS -> Items.OBSERVER;
			case REPORTS -> Items.PAPER;
			case ACCOUNTS -> Items.PLAYER_HEAD;
			case MINING -> Items.DIAMOND_PICKAXE;
		};
	}

	private static String whereTo(RiskProfile.Source source) {
		return switch (source) {
			case PUNISHMENTS -> "read their punishment history";
			case CASES, SIGNALS -> "open the case board";
			case REPORTS -> "open the report queue";
			case ACCOUNTS -> "review their linked accounts";
			case MINING -> "open the decoy and mining screen";
		};
	}

	private void follow(RiskProfile.Source source) {
		String node = switch (source) {
			case PUNISHMENTS -> Nodes.HISTORY;
			case CASES, SIGNALS -> Nodes.SECURITY_CHECK;
			case REPORTS -> Nodes.REPORT_VIEW;
			case ACCOUNTS -> Nodes.ALTS;
			case MINING -> Nodes.SECURITY_CHECK;
		};
		if (!Permissions.check(viewer, node)) {
			viewer.sendSystemMessage(Theme.bad("That needs " + node + "."));
			return;
		}
		switch (source) {
			case PUNISHMENTS -> HistoryMenu.open(viewer, target);
			case CASES, SIGNALS -> CasesMenu.open(viewer);
			case REPORTS -> ReportsMenu.open(viewer);
			case ACCOUNTS -> AltsMenu.open(viewer, target);
			case MINING -> CanaryMenu.open(viewer);
		}
	}
}
