package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

/**
 * Filing evidence against a case without typing: a replay of the last stretch, the block
 * damage around where you stand, the spot itself, or the player's inventory right now.
 */
public final class CaseEvidenceMenu extends Gui {

	private static final int TITLE = 4;
	private static final int[] REPLAYS = {10, 11, 12, 13};
	private static final long[] REPLAY_MINUTES = {5, 15, 60, 360};
	private static final int[] BLOCKS = {19, 20, 21};
	private static final int[] BLOCK_RADII = {8, 16, 32};
	private static final int LOCATION = 23;
	private static final int SNAPSHOT = 25;
	private static final int BACK = 27;
	private static final int CLOSE = 31;

	private final String caseId;

	public static void open(ServerPlayer viewer, String caseId) {
		Guis.navigate(viewer, Theme.title("Case", caseId, "Evidence"),
				(id, inv, v) -> new CaseEvidenceMenu(id, inv, v, caseId));
	}

	private CaseEvidenceMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String caseId) {
		super(containerId, playerInventory, viewer, 4);
		this.caseId = caseId;
		render();
	}

	@Override
	protected void build() {
		Case found = Mods.cases().store().byId(caseId).orElse(null);
		backButton(BACK, "the case", () -> CaseMenu.open(viewer, caseId));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		if (found == null) {
			fillEmpty(Theme.filler());
			return;
		}
		String subject = found.subjectName() == null ? "them" : found.subjectName();

		set(TITLE, Icon.of(Items.WRITABLE_BOOK)
				.name("File evidence on " + found.id(), Theme.ACCENT)
				.lore("Evidence points at a replay or the log; it does not")
				.lore("copy them. It plays for as long as they are kept.", Theme.MUTED)
				.build());

		for (int i = 0; i < REPLAYS.length; i++) {
			long minutes = REPLAY_MINUTES[i];
			String span = minutes >= 60 ? (minutes / 60) + " hour" + (minutes >= 120 ? "s" : "")
					: minutes + " minutes";
			button(REPLAYS[i], Icon.of(Items.RECOVERY_COMPASS)
					.name("Replay: last " + span, Theme.ACCENT)
					.lore("What " + subject + " did in the last " + span + ".")
					.build(), click -> {
				long now = System.currentTimeMillis();
				file(found, CaseEvidence.Draft.replay(found.subjectId(), found.subjectName(), null,
						null, now - minutes * 60_000L, now, "the last " + span));
			});
		}

		for (int i = 0; i < BLOCKS.length; i++) {
			int radius = BLOCK_RADII[i];
			button(BLOCKS[i], Icon.of(Items.TNT)
					.name("Block damage within " + radius, Theme.ACCENT)
					.lore("Everything changed around where you stand,")
					.lore("in the last hour.")
					.build(), click -> {
				long now = System.currentTimeMillis();
				file(found, CaseEvidence.Draft.blocks(found.subjectId(), found.subjectName(),
						Mc.dimensionId(viewer.level()), viewer.blockPosition(), radius,
						now - 3_600_000L, now, "the hour before it was filed"));
			});
		}

		button(LOCATION, Icon.of(Items.ENDER_PEARL)
				.name("This location", Theme.ACCENT)
				.lore("Where you are standing, to come back to.")
				.build(), click -> file(found, CaseEvidence.Draft.location(found.subjectId(),
						found.subjectName(), Mc.dimensionId(viewer.level()), viewer.blockPosition(),
						"filed where it was seen")));

		ServerPlayer online = Mc.server(viewer) == null ? null
				: Mc.server(viewer).getPlayerList().getPlayer(found.subjectId());
		button(SNAPSHOT, Icon.of(online == null ? Mc.dye(net.minecraft.world.item.DyeColor.GRAY) : Items.CHEST)
				.name("Their inventory, now", online == null ? Theme.MUTED : Theme.ACCENT)
				.lore(online == null ? subject + " is offline." : "Takes a snapshot and files it.")
				.build(), click -> {
			if (online == null || !Permissions.check(viewer, Nodes.INVSEE)) {
				viewer.sendSystemMessage(Theme.bad(online == null ? subject + " is offline."
						: "Taking a snapshot needs " + Nodes.INVSEE + "."));
				Sfx.deny(viewer);
				return;
			}
			var snapshot = Mods.inventory().capture(online, "Evidence for case " + found.id(),
					Mc.name(viewer));
			if (snapshot == null) {
				viewer.sendSystemMessage(Theme.bad("The snapshot could not be taken."));
				Sfx.deny(viewer);
				return;
			}
			file(found, CaseEvidence.Draft.snapshot(found.subjectId(), found.subjectName(),
					snapshot.id(), snapshot.label()));
		});

		fillEmpty(Theme.filler());
	}

	private void file(Case found, CaseEvidence.Draft draft) {
		long id = Mods.cases().evidence().add(found.id(), draft, Mc.name(viewer));
		if (id < 0) {
			viewer.sendSystemMessage(Theme.bad("The evidence could not be saved."));
			Sfx.deny(viewer);
			return;
		}
		viewer.sendSystemMessage(Theme.good("Filed " + draft.kind().label().toLowerCase(java.util.Locale.ROOT)
				+ " as #" + id + " on " + found.id() + "."));
		Sfx.success(viewer);
		CaseMenu.reopen(viewer, found.id());
	}
}
