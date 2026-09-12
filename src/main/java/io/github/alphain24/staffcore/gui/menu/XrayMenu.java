package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.security.Hypergeometric;
import io.github.alphain24.staffcore.modules.security.XrayReplayView;
import io.github.alphain24.staffcore.modules.security.XraySweep;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * What one player's mining looks like against chance.
 *
 * <h2>What the number is, and what it is not</h2>
 * Each row is one band of one world, scored as a hypergeometric tail: given how much rock they
 * removed and how much ore was reachable, how unlikely is it that somebody digging without
 * knowing would have come away with this much?
 * <p>
 * It is a <b>p-value, not a verdict</b>. A very small number says chance is a poor explanation;
 * it does not say which of the other explanations is right. That is what standing in the
 * excavation is for, and why the replay button is on this screen rather than a separate menu.
 *
 * <h2>Why silence gets a reason</h2>
 * An empty result has three completely different causes — nothing mined, not enough mined to
 * score, or mined plenty and it looked ordinary — and they need opposite responses. A screen
 * that showed nothing for all three would be the same failure as the detector that was too
 * quiet and got its thresholds lowered to compensate.
 */
public final class XrayMenu extends Gui {

	private static final int TITLE = 4;
	private static final int[] ROWS = {19, 20, 21, 22, 23, 24, 25};
	private static final int REPLAY = 40;
	private static final int BACK = 45;
	private static final int CLOSE = 49;

	/** Six hours, which is the window the command defaults to and the one staff mean. */
	private static final long WINDOW = 6L * 3_600_000L;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("X-ray", target.name()),
				(id, inv, v) -> new XrayMenu(id, inv, v, target));
	}

	private XrayMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target) {

		super(containerId, playerInventory, viewer, 6);
		this.target = target;
		render();
	}

	@Override
	protected void build() {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		List<XraySweep.Finding> findings = XraySweep.forPlayer(server, target.name(), WINDOW);

		set(TITLE, Icon.of(Items.DIAMOND_ORE)
				.name(target.name() + " — last 6 hours", Theme.ACCENT)
				.lore("How their mining compares to digging without knowing.")
				.gap()
				.field("Bands scored", String.valueOf(findings.size()))
				.build());

		if (findings.isEmpty()) {
			// The reason, not a blank screen. "Nothing found" and "not enough data to look"
			// are different answers and only one of them is about the player.
			set(22, Icon.of(Items.GLASS_PANE)
					.name("Nothing to report", Theme.MUTED)
					.lore(XraySweep.whyNothing(server, target.name(), WINDOW))
					.build());
		} else {
			for (int i = 0; i < findings.size() && i < ROWS.length; i++) {
				set(ROWS[i], row(findings.get(i)));
			}
		}

		button(REPLAY, Icon.of(Items.RECOVERY_COMPASS)
				.name("Stand in the dig", Theme.ACCENT)
				.lore("Spectate their biggest excavation with the path drawn.")
				.gap()
				.lore("A number decides whether to look.", Theme.MUTED)
				.lore("This is the looking.", Theme.MUTED)
				.build(), click -> {
			viewer.closeContainer();
			var entry = XrayReplayView.enter(server, viewer, target.name(), null, WINDOW);
			if (!entry.started()) {
				viewer.sendSystemMessage(Theme.bad(entry.refusal()));
				Sfx.deny(viewer);
			}
		});

		backButton(BACK, "the player list",
				() -> PlayerListMenu.open(viewer, PlayerListMenu.Purpose.XRAY));

		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	/**
	 * One band, with the two fractions the p-value was computed from.
	 * <p>
	 * Both are shown so the claim can be checked rather than taken. A staff member who can see
	 * "took 9 of 11 ore while removing 4% of the rock" can judge it; one who sees only
	 * "far beyond chance" is trusting arithmetic they cannot inspect.
	 */
	private net.minecraft.world.item.ItemStack row(XraySweep.Finding finding) {
		boolean striking = finding.pValue() < 0.001;

		return Icon.of(striking ? Items.DIAMOND : Items.DEEPSLATE)
				.name(finding.world() + ", y " + finding.band(),
						striking ? Theme.BAD : Theme.TEXT)
				.lore("Chance: " + Hypergeometric.describe(finding.pValue()),
						striking ? Theme.BAD : Theme.TEXT)
				.gap()
				.field("Ore taken", finding.found() + " of " + finding.ores()
						+ " (" + percent(finding.foundFraction()) + ")")
				.field("Rock removed", finding.drawn() + " of " + finding.population()
						+ " (" + percent(finding.drawn()
								/ (double) Math.max(1, finding.population())) + ")")
				.gap()
				.lore("Taking most of the ore while removing little of", Theme.MUTED)
				.lore("the rock is the shape that needs explaining.", Theme.MUTED)
				.build();
	}

	private static String percent(double fraction) {
		return Math.round(fraction * 100) + "%";
	}
}
