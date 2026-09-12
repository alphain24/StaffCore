package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.security.AntiXrayCompanion;
import io.github.alphain24.staffcore.modules.security.Canaries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The decoys currently out for you, and what breaking one actually does.
 *
 * <h2>Why this screen exists</h2>
 * A decoy is a block only your client has been told about, in rock that is really rock. There
 * is no way to confirm the feature is working without knowing where one is — and hunting for a
 * fake diamond through an x-ray pack, in a world full of real ones, is a research task rather
 * than a check. That is how the decoy layer stayed unverified for months.
 * <p>
 * <b>This does let an admin avoid their own decoys.</b> That is a real cost and a small one:
 * decoys never punish anybody, the node is the admin one, and a server owner who wants to
 * cheat on their own server has a much shorter route than this. Being unable to confirm the
 * feature works at all was the larger risk.
 *
 * <h2>The thresholds are on the screen because nobody remembers them</h2>
 * "Does breaking one alert me?" has a three-part answer that depends on a config value, and
 * the honest place for it is next to the count of how many you have broken. The alternative is
 * a staff member assuming one hit opened a case, or assuming three did nothing.
 */
public final class CanaryMenu extends Gui {

	private static final int TITLE = 4;
	private static final int STATE = 20;
	private static final int WHAT_HAPPENS = 22;
	private static final int YOUR_HITS = 24;

	/** Where the positions go. Two rows is plenty: density is six by default. */
	private static final int[] SLOTS = {
			28, 29, 30, 31, 32, 33, 34,
			37, 38, 39, 40, 41, 42, 43};

	private static final int BACK = 45;
	private static final int CLOSE = 49;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Decoys"),
				(id, inv, v) -> new CanaryMenu(id, inv, v));
	}

	private CanaryMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer, 6);
		render();
	}

	@Override
	protected void build() {
		StaffConfig cfg = StaffConfig.get();
		boolean on = Canaries.enabled();

		set(TITLE, Icon.of(Items.SCULK_SENSOR)
				.name("Decoy blocks", Theme.ACCENT)
				.lore("Fake ore in solid rock that only your client is told about.")
				.lore("Nothing an honest player can see, so nothing they dig to.", Theme.MUTED)
				.build());

		set(STATE, state(cfg, on));
		set(WHAT_HAPPENS, whatHappens(cfg));
		set(YOUR_HITS, hits());

		if (on) placePositions();

		backButton(BACK, "X-ray & cheats", () -> StaffSections.antiCheat(viewer));

		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	/** Whether decoys are running, and the reason when they are not. */
	private net.minecraft.world.item.ItemStack state(StaffConfig cfg, boolean on) {
		Icon icon = Icon.of(on ? Items.EMERALD : Items.BARRIER)
				.name(on ? "Running" : "Off", on ? Theme.GOOD : Theme.WARN);

		if (on) {
			return icon.lore("Topping up to " + cfg.canaryDensity + " per player.")
					.gap()
					.field("Below y", String.valueOf(cfg.canaryMaxY))
					.field("Within", cfg.canaryRadius + " blocks")
					.build();
		}

		String why = AntiXrayCompanion.whyCanariesAreOff();
		if (why != null) {
			return icon.lore("An anti-xray mod is installed.")
					.gap()
					.lore("That mod already fills the world with ore that", Theme.MUTED)
					.lore("is not there, so an x-ray user stops trusting any", Theme.MUTED)
					.lore("of it — which costs decoys their true positives,", Theme.MUTED)
					.lore("not just the reading of them.", Theme.MUTED)
					.build();
		}
		return icon.lore("canaryBlocks is off, or the density is zero.")
				.gap()
				.lore("Set canaryBlocks in config/staffcore.json.", Theme.MUTED)
				.build();
	}

	/** The three-part answer to "does breaking one alert me". */
	private net.minecraft.world.item.ItemStack whatHappens(StaffConfig cfg) {
		int threshold = Math.max(1, cfg.canaryCaseThreshold);

		return Icon.of(Items.PAPER)
				.name("What breaking one does", Theme.ACCENT)
				.lore("Only breaking counts. Walking past does nothing.")
				.gap()
				.field("1st and 2nd", "noted quietly, no case")
				.field("At " + threshold, "opens a case, alerts staff")
				.field("After that", "added to the same case")
				.gap()
				.lore("Breaking a block NEXT to a decoy retires it", Theme.MUTED)
				.lore("silently — that is the honest-miner rule, and it", Theme.MUTED)
				.lore("is why a tunnel through one is not a hit.", Theme.MUTED)
				.gap()
				.lore("Nothing here ever punishes anybody.", Theme.MUTED)
				.build();
	}

	/** How many this viewer has broken, which is nearly always zero and should say so. */
	private net.minecraft.world.item.ItemStack hits() {
		int mine = Canaries.hitsFor(viewer.getUUID());

		return Icon.of(mine > 0 ? Items.REDSTONE : Items.GLASS_PANE)
				.name("Your hits this session", mine > 0 ? Theme.WARN : Theme.MUTED)
				.field("Broken", String.valueOf(mine))
				.gap()
				.lore(mine > 0
						? "You have broken a decoy. Expected while testing."
						: "Counted per player, and reset when the server restarts.",
						Theme.MUTED)
				.build();
	}

	/**
	 * Where this viewer's own decoys are.
	 * <p>
	 * Their own only. A staff member seeing everybody's would be able to tell which blocks are
	 * fake for a player they are investigating, which is the one thing that would make a hit
	 * mean less.
	 */
	private void placePositions() {
		List<Canaries.Canary> mine = Canaries.all().stream()
				.filter(c -> c.owner().equals(viewer.getUUID()))
				.toList();

		if (mine.isEmpty()) {
			set(SLOTS[0], Icon.of(Items.GLASS_PANE)
					.name("None placed yet", Theme.MUTED)
					.lore("They are placed on a five-second timer, in rock")
					.lore("with solid blocks on all six sides — so a cave")
					.lore("wall has none. Dig into stone below y "
							+ StaffConfig.get().canaryMaxY + " and wait.")
					.build());
			return;
		}

		for (int i = 0; i < mine.size() && i < SLOTS.length; i++) {
			Canaries.Canary canary = mine.get(i);
			set(SLOTS[i], Icon.of(Items.DIAMOND_ORE)
					.name(canary.pos().toShortString(), Theme.ACCENT)
					.field("World", canary.world())
					.field("Shown as", canary.shown().getBlock().getName().getString())
					.gap()
					.lore("The world really has ordinary rock here.", Theme.MUTED)
					.build());
		}
	}
}
