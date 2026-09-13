package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.security.AntiXrayCompanion;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.security.Canaries;
import io.github.alphain24.staffcore.modules.security.OreSense;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The decoy veins currently out for you, and what uncovering one actually does.
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
 * <h2>The score is on the screen because "nothing happened" is the usual complaint</h2>
 * Uncovering a decoy is counted, not alerted: it goes into the same score as the real diamond
 * veins a player uncovers, and only an unlikely score says anything. A staff member testing by
 * digging to one decoy and hearing nothing needs to see that it was counted and why it was not
 * enough — which is their own session score, next to the count.
 */
public final class CanaryMenu extends Gui {

	private static final int TITLE = 4;
	private static final int STATE = 20;
	private static final int WHAT_HAPPENS = 22;
	private static final int YOUR_HITS = 24;

	/** Where the veins go, one icon each. Two rows hold the default of twelve with room. */
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
				.lore("Fake ore veins in solid rock that only your client is told about.")
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
			return icon.lore("Topping up to " + cfg.canaryDensity + " veins per player.")
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

	/** The answer to "does uncovering one alert me". */
	private net.minecraft.world.item.ItemStack whatHappens(StaffConfig cfg) {
		return Icon.of(Items.PAPER)
				.name("What uncovering one does", Theme.ACCENT)
				.lore("Breaking any block touching a decoy vein counts")
				.lore("as uncovering it, once for the whole vein.")
				.gap()
				.lore("It goes into the player's session score, with", Theme.MUTED)
				.lore("every sealed real diamond vein they uncover.", Theme.MUTED)
				.gap()
				.field("Under " + Math.max(1, cfg.xrayMinimumFinds) + " finds", "nothing said")
				.field("Score " + cfg.xrayNoticeConfidence + "+", "quiet line to staff")
				.field("Score " + cfg.xrayAlertConfidence + "+", "alert, cheating signal")
				.field("Score " + cfg.caseAutoOpenSeverity + "+", "opens a case with replay")
				.gap()
				.lore("An honest tunnel meets one now and then; the", Theme.MUTED)
				.lore("score expects that. Nothing here ever punishes.", Theme.MUTED)
				.build();
	}

	/** What this viewer has uncovered, and the score it adds up to. */
	private net.minecraft.world.item.ItemStack hits() {
		int mine = Canaries.hitsFor(viewer.getUUID());
		OreSense.Session session = Mods.security().oreSense().sessionFor(viewer.getUUID());

		Icon icon = Icon.of(mine > 0 ? Items.REDSTONE : Items.GLASS_PANE)
				.name("Your mining this session", mine > 0 ? Theme.WARN : Theme.MUTED)
				.field("Decoy veins uncovered", String.valueOf(mine));
		if (session != null) {
			icon.field("Sealed diamond veins", String.valueOf(session.hiddenVeins()))
					.field("Faces opened", String.valueOf(session.faces()))
					.field("Blind digging finds", String.format(java.util.Locale.ROOT, "%.1f",
							session.expected()))
					.field("Score", session.confidence() + " / 99");
		}
		return icon.gap()
				.lore(StaffConfig.get().xraySkipStaffOnDuty && Mods.staffMode().isActive(viewer)
						? "Not scored while you are in staff mode."
						: viewer.isCreative() || viewer.isSpectator()
								? "Not scored in creative. Test in survival."
								: "A session ends after 20 minutes without mining.",
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
		java.util.Map<Long, List<Canaries.Canary>> mine = new java.util.LinkedHashMap<>();
		for (Canaries.Canary canary : Canaries.all()) {
			if (!canary.owner().equals(viewer.getUUID())) continue;
			mine.computeIfAbsent(canary.vein(), k -> new java.util.ArrayList<>()).add(canary);
		}

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

		int i = 0;
		for (List<Canaries.Canary> vein : mine.values()) {
			if (i >= SLOTS.length) break;
			Canaries.Canary canary = vein.get(0);
			set(SLOTS[i++], Icon.of(canary.shown().getBlock().asItem())
					.name(canary.pos().toShortString(), Theme.ACCENT)
					.field("World", canary.world())
					.field("Blocks in vein", String.valueOf(vein.size()))
					.field("Shown as", canary.shown().getBlock().getName().getString())
					.gap()
					.lore("The world really has ordinary rock here.", Theme.MUTED)
					.build());
		}
	}
}
