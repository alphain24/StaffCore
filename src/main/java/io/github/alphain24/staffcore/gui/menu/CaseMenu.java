package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.modules.cases.Signal;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One case: what kind it is, who it is about, and three rows of things to do with it.
 * <pre>
 *   .  .  .  .  CASE .  .  .  .
 *   C  .  ■  ■  ■    ■  ■  .  C     Case — claim, investigating, close, assign, kind
 *   L  .  ■  ■  ■    ■  ■  .  L     Look into it — evidence, dig info, signals, history, file
 *   A  .  ■  ■  ■    ■  ■  .  A     Act on it — scene, roll back, punish, file, inventory
 *   ←  .  .  .  .    .  .  .  ✕
 * </pre>
 * Evidence, the x-ray dig, what the detectors said and the case history each open a screen of
 * their own. They used to be laid straight onto this one, fourteen items across the middle, so
 * the case grew a different shape with every piece of evidence filed and anything past the
 * fourteenth was not on screen at all. Now this screen is the same grid for every case.
 * <p>
 * Every action re-reads the case when it is clicked rather than trusting what was drawn, and
 * every one of them goes through the same permission check, rate limit and confirmation as its
 * own command. This screen is a shorter route to those, not a way around them.
 */
public final class CaseMenu extends Gui {

	private static final int HEADER = 4;
	private static final int CASE_ROW = 9;
	private static final int LOOK_ROW = 18;
	private static final int ACT_ROW = 27;
	private static final int BACK = 36;
	private static final int CLOSE = 44;

	private final String caseId;

	public static void open(ServerPlayer viewer, String caseId) {
		Guis.navigate(viewer, Theme.title("Case", caseId),
				(id, inv, v) -> new CaseMenu(id, inv, v, caseId));
	}

	static void reopen(ServerPlayer viewer, String caseId) {
		Guis.silent(viewer, Theme.title("Case", caseId),
				(id, inv, v) -> new CaseMenu(id, inv, v, caseId));
	}

	/** Back to this case from a screen it opened, dropping that screen from the path. */
	static void returnTo(ServerPlayer viewer, String caseId) {
		Guis.goBack(viewer, Theme.title("Case", caseId),
				(id, inv, v) -> new CaseMenu(id, inv, v, caseId));
	}

	private CaseMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, String caseId) {
		super(containerId, playerInventory, viewer, 5);
		this.caseId = caseId;
		render();
	}

	@Override
	protected void build() {
		backButton(BACK, "Cases", () -> CasesMenu.open(viewer));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());

		Case found = Mods.cases().store().byId(caseId).orElse(null);
		if (found == null) {
			set(22, Icon.of(Items.BARRIER)
					.name("Case " + caseId + " could not be read", Theme.BAD)
					.lore("Cases are never deleted, so storage is unavailable.")
					.build());
			fillEmpty(Theme.filler());
			return;
		}

		List<CaseEvidence.Item> items = Mods.cases().evidence().forCase(found.id());

		button(HEADER, header(found), click -> {
			viewer.closeContainer();
			io.github.alphain24.staffcore.modules.cases.CaseView.print(
					viewer.createCommandSourceStack(), found);
		});
		caseRow(found);
		lookRow(found, items);
		actRow(found, items);
		fillEmpty(Theme.filler());
	}

	private ItemStack header(Case found) {
		Icon icon = Icon.of(CasesMenu.iconFor(found.category()))
				.name(found.category().label() + " case " + found.id(), Theme.ACCENT)
				.field("Subject", name(found))
				.field("Status", found.status().stored())
				.field("Severity", String.valueOf(found.severity()))
				.field("Assigned", found.assignedTo() == null ? "nobody" : found.assignedTo())
				.field("Opened", TimeFormat.ago(found.openedAt()) + " by " + found.openedBy());
		if (found.summary() != null && !found.summary().isBlank()) {
			icon.gap().paragraph(found.summary(), Theme.TEXT);
		}
		icon.gap().action("Click", "read the whole case in chat");
		if (found.status().isLive() && found.severity() >= 70) icon.glow();
		return icon.build();
	}

	// ------------------------------------------------------------------- the case

	private void caseRow(Case found) {
		label(CASE_ROW, DyeColor.LIGHT_BLUE, "Case", "Claim · Investigating · Close · Assign · Kind");
		String me = Mc.name(viewer);

		boolean mine = me.equals(found.assignedTo());
		button(CASE_ROW + 2, Icon.of(Items.NAME_TAG)
				.name(mine ? "Assigned to you" : "Claim", Theme.ACCENT)
				.lore(mine ? "It is yours." : "Assign this case to yourself.")
				.gap()
				.action("Click", mine ? "unassign yourself" : "claim it")
				.build(), click -> {
			Mods.cases().store().assign(found.id(), mine ? null : me, me);
			Sfx.click(viewer);
			render();
		});

		boolean investigating = found.status() == Case.Status.INVESTIGATING;
		button(CASE_ROW + 3, Icon.of(Items.SPYGLASS)
				.name("Mark investigating", investigating ? Theme.MUTED : Theme.ACCENT)
				.lore(investigating ? "Already marked." : "Somebody is working on it.")
				.gap()
				.action("Click", investigating ? "nothing to change" : "mark it")
				.build(), click -> {
			if (!investigating) {
				Mods.cases().store().setStatus(found.id(), Case.Status.INVESTIGATING, me, null);
				Sfx.click(viewer);
			}
			render();
		});

		button(CASE_ROW + 4, Icon.of(Items.IRON_DOOR)
				.name("Close the case", Theme.ACCENT)
				.lore("Actioned, with the punishment they got —")
				.lore("or cleared, with why.")
				.gap()
				.action("Click", "choose how")
				.build(), click -> CaseCloseMenu.open(viewer, found.id()));

		button(CASE_ROW + 5, Icon.of(Items.PLAYER_HEAD)
				.name("Assign to…", Theme.ACCENT)
				.field("Now", found.assignedTo() == null ? "nobody" : found.assignedTo())
				.lore("Staff online, fewest open cases first.", Theme.MUTED)
				.gap()
				.action("Click", "pick somebody")
				.build(), click -> CaseAssignMenu.open(viewer, found.id()));

		CaseCategory[] all = CaseCategory.values();
		button(CASE_ROW + 6, Icon.of(CasesMenu.iconFor(found.category()))
				.name("Kind: " + found.category().label(), Theme.ACCENT)
				.lore("Reports are sorted by their words, which can be wrong.")
				.gap()
				.action("Click", "next kind")
				.action("Right-click", "previous kind")
				.build(), click -> {
			int step = click.isRight() ? all.length - 1 : 1;
			CaseCategory next = all[(found.category().ordinal() + step) % all.length];
			if (Mods.cases().store().setCategory(found.id(), next, me)) {
				Sfx.click(viewer);
			} else {
				viewer.sendSystemMessage(Theme.bad(name(found) + " already has an open "
						+ next.label() + " case. Add to that one instead."));
				Sfx.deny(viewer);
			}
			render();
		});
	}

	// ------------------------------------------------------------ looking into it

	private void lookRow(Case found, List<CaseEvidence.Item> items) {
		label(LOOK_ROW, DyeColor.YELLOW, "Look into it", "Evidence · Dig info · Signals · History · File");

		long digs = items.stream().filter(item -> item.kind() == CaseEvidence.Kind.XRAY_DIG).count();
		long evidence = items.size() - digs;

		button(LOOK_ROW + 2, Icon.of(Items.CHEST)
				.name("Evidence (" + evidence + ")", evidence > 0 ? Theme.ACCENT : Theme.MUTED)
				.lore("Replays, block damage, places and inventories", Theme.MUTED)
				.lore("filed on this case.", Theme.MUTED)
				.gap()
				.action("Click", "open the list")
				.build(), click -> CaseEvidenceListMenu.open(viewer, found.id(), false));

		button(LOOK_ROW + 3, Icon.of(Items.DIAMOND_PICKAXE)
				.name("Dig info (" + digs + ")", digs > 0 ? Theme.ACCENT : Theme.MUTED)
				.lore("The x-ray digs recorded for this case, to watch", Theme.MUTED)
				.lore("back from inside the tunnel.", Theme.MUTED)
				.gap()
				.action("Click", "open the list")
				.build(), click -> CaseEvidenceListMenu.open(viewer, found.id(), true));

		List<Signal> signals = Mods.cases().store().signalsFor(found.id());
		button(LOOK_ROW + 4, Icon.of(Items.PAPER)
				.name("Detector signals (" + signals.size() + ")", signals.isEmpty() ? Theme.MUTED : Theme.ACCENT)
				.lore("What the detectors said, and how sure they were.", Theme.MUTED)
				.gap()
				.action("Click", "open the list")
				.build(), click -> CaseSignalsMenu.open(viewer, found.id()));

		int events = Mods.cases().store().eventsFor(found.id()).size();
		button(LOOK_ROW + 5, Icon.of(Items.BOOK)
				.name("History (" + events + ")", Theme.ACCENT)
				.lore("Everything done on the case, with the reason", Theme.MUTED)
				.lore("for every punishment and every close.", Theme.MUTED)
				.gap()
				.action("Click", "open it")
				.build(), click -> CaseHistoryMenu.open(viewer, found.id()));

		button(LOOK_ROW + 6, Icon.of(Items.WRITABLE_BOOK)
				.name("File evidence", Theme.ACCENT)
				.lore("A replay of " + name(found) + ", the block damage")
				.lore("around you, where you stand, or their inventory.")
				.gap()
				.action("Click", "choose what")
				.build(), click -> CaseEvidenceMenu.open(viewer, found.id()));
	}

	// ------------------------------------------------------------------ acting on it

	private void actRow(Case found, List<CaseEvidence.Item> items) {
		label(ACT_ROW, DyeColor.RED, "Act on it", "Scene · Roll back · Punish · Their file · Inventory");
		scene(found, items);
		rollback(found, items);
		punish(found);
		playerFile(found);
		inventory(found);
	}

	/**
	 * Where a case happened: the newest piece of evidence that says where.
	 * <p>
	 * A place filed on purpose beats the centre of some block damage, which beats wherever a
	 * replay started — in that order, because that is how deliberately each one was chosen.
	 */
	public static Optional<CaseEvidence.Item> sceneOf(List<CaseEvidence.Item> items) {
		Comparator<CaseEvidence.Item> preference = Comparator
				.<CaseEvidence.Item>comparingInt(item -> switch (item.kind()) {
					case LOCATION -> 0;
					case BLOCKS -> 1;
					case REPLAY -> 2;
					default -> 3;
				})
				.thenComparing(Comparator.comparingLong(CaseEvidence.Item::addedAt).reversed());
		return items.stream()
				.filter(item -> item.pos() != null && item.kind() != CaseEvidence.Kind.SNAPSHOT
						&& item.kind() != CaseEvidence.Kind.XRAY_DIG)
				.min(preference);
	}

	private void scene(Case found, List<CaseEvidence.Item> items) {
		int slot = ACT_ROW + 2;
		Optional<CaseEvidence.Item> scene = sceneOf(items);
		if (scene.isEmpty()) {
			set(slot, Icon.of(Items.ENDER_EYE)
					.name("Go to the scene", Theme.MUTED)
					.lore("No evidence on this case says where it happened.", Theme.MUTED)
					.gap()
					.warn("File a location, standing there.")
					.build());
			return;
		}
		CaseEvidence.Item where = scene.get();
		button(slot, Icon.of(Items.ENDER_PEARL)
				.name("Go to the scene", Theme.ACCENT)
				.field("Where", where.where())
				.field("From", "#" + where.id() + " " + where.kind().label())
				.lore("/staff back returns you afterwards.", Theme.MUTED)
				.gap()
				.action("Click", "go there")
				.build(), click -> goTo(viewer, where));
	}

	/** Teleports to a piece of evidence's position. Shared with {@code /staff case <id> tp}. */
	public static boolean goTo(ServerPlayer viewer, CaseEvidence.Item where) {
		if (!Permissions.check(viewer, Nodes.TP)) {
			viewer.sendSystemMessage(Theme.bad("Going there needs " + Nodes.TP + "."));
			Sfx.deny(viewer);
			return false;
		}
		MinecraftServer server = Mc.server(viewer);
		ServerLevel level = server == null ? null : where.world() == null ? viewer.level()
				: io.github.alphain24.staffcore.modules.replay.ReplayStage.levelOf(server, where.world());
		if (level == null) {
			viewer.sendSystemMessage(Theme.bad("This server no longer has " + where.world() + "."));
			Sfx.deny(viewer);
			return false;
		}
		viewer.closeContainer();
		BlockPos pos = where.pos();
		Mods.teleport().toPosition(viewer, level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		viewer.sendSystemMessage(Theme.good("Taken to " + where.where() + " for case "
				+ where.caseId() + "."));
		return true;
	}

	/**
	 * Undoes the damage a case is about, from the block damage filed against it.
	 * <p>
	 * Two scopes. The subject's own changes is the safe one and the default: it covers what
	 * they broke by hand and every explosion blamed on them, and nothing anybody else did.
	 * Everybody's changes in the area is for damage nobody is recorded against — a creeper the
	 * griefer led there, a blast the log could not attribute — and it also undoes any repair
	 * made since, which the confirm screen says in so many words.
	 */
	private void rollback(Case found, List<CaseEvidence.Item> items) {
		int slot = ACT_ROW + 3;
		Optional<CaseEvidence.Item> damage = items.stream()
				.filter(item -> item.kind() == CaseEvidence.Kind.BLOCKS && item.pos() != null)
				.max(Comparator.comparingLong(CaseEvidence.Item::addedAt));

		if (!Permissions.check(viewer, Nodes.ROLLBACK)) {
			set(slot, locked(Items.TNT, "Roll back the damage", Nodes.ROLLBACK));
			return;
		}
		if (damage.isEmpty()) {
			set(slot, Icon.of(Items.TNT)
					.name("Roll back the damage", Theme.MUTED)
					.lore("No block damage is filed on this case, so there is", Theme.MUTED)
					.lore("nothing to say where or since when.", Theme.MUTED)
					.gap()
					.warn("File it with File evidence, standing at the damage.")
					.build());
			return;
		}

		CaseEvidence.Item area = damage.get();
		String subject = found.subjectName();
		button(slot, Icon.of(Items.TNT)
				.name("Roll back the damage", Theme.BAD)
				.field("Where", area.where())
				.field("Radius", area.radius() + " blocks")
				.field("Since", TimeFormat.ago(area.from()))
				.gap()
				.action("Click", subject == null ? "roll back everything there"
						: "roll back what " + subject + " did there")
				.action("Right-click", "roll back everybody's changes there")
				.warn("You see a preview before anything is written.")
				.build(), click -> previewRollback(found, area,
						click.isRight() || subject == null ? null : subject));
	}

	private void previewRollback(Case found, CaseEvidence.Item area, String who) {
		MinecraftServer server = Mc.server(viewer);
		ServerLevel level = server == null ? null : area.world() == null ? viewer.level()
				: io.github.alphain24.staffcore.modules.replay.ReplayStage.levelOf(server, area.world());
		if (level == null) {
			viewer.sendSystemMessage(Theme.bad("This server no longer has " + area.world() + "."));
			Sfx.deny(viewer);
			return;
		}

		RollbackPreview.open(viewer, scopeFor(level, area, who),
				result -> {
					if (result.reverted() > 0) {
						String me = Mc.name(viewer);
						if (result.isUndoable()) {
							Mods.cases().store().link(found.id(), "rollback",
									String.valueOf(result.pointId()), me);
						}
						Mods.cases().store().note(found.id(), me, "rolled back "
								+ result.reverted() + " change(s) by "
								+ (who == null ? "everyone" : who) + " within " + area.radius()
								+ " of " + area.where());
					}
					reopen(viewer, found.id());
				},
				() -> reopen(viewer, found.id()));
	}

	/**
	 * What a case's rollback covers: the filed damage's centre and radius, from a minute before
	 * it started until now. Now rather than the evidence's end, because the rollback reads "the
	 * last so long" — which for the subject's own changes is exactly right, and for everybody's
	 * is the reason the confirm screen warns about repairs.
	 */
	public static RollbackPreview.Scope scopeFor(ServerLevel level, CaseEvidence.Item area,
			String who) {
		long since = area.from() - 60_000L;
		long windowMs = Math.max(60_000L, System.currentTimeMillis() - since);
		List<String> warnings = who == null
				? List.of("Every player's changes here since " + TimeFormat.stamp(since)
						+ " — including anything built or repaired after the damage.")
				: List.of();
		return new RollbackPreview.Scope(level, who, area.pos(), Math.max(1, area.radius()),
				windowMs, warnings);
	}

	private void punish(Case found) {
		int slot = ACT_ROW + 4;
		NameAndId subject = subjectOf(found);
		if (!Permissions.check(viewer, Nodes.PUNISH)) {
			set(slot, locked(Items.IRON_SWORD, "Punish " + subject.name(), Nodes.PUNISH));
			return;
		}
		button(slot, Icon.of(Items.IRON_SWORD)
				.name("Punish " + subject.name(), Theme.BAD)
				.lore("Whatever you issue from here is linked to this")
				.lore("case, with its reason in the case history.")
				.gap()
				.action("Click", "open the punishment screen")
				.build(), click -> {
			io.github.alphain24.staffcore.gui.PunishFromCase.remember(viewer.getUUID(), found.id(), found.subjectId());
			PunishMenu.open(viewer, subject);
		});
	}

	private void playerFile(Case found) {
		NameAndId subject = subjectOf(found);
		button(ACT_ROW + 5, Icon.head(subject)
				.name(subject.name() + "'s file", Theme.ACCENT)
				.lore("History, notes, alts and everything else.")
				.gap()
				.action("Click", "open it")
				.build(), click -> PlayerActionsMenu.open(viewer, subject));
	}

	private void inventory(Case found) {
		int slot = ACT_ROW + 6;
		NameAndId subject = subjectOf(found);
		if (!Permissions.check(viewer, Nodes.INVSEE)) {
			set(slot, locked(Items.CHEST, "Their inventory", Nodes.INVSEE));
			return;
		}
		button(slot, Icon.of(Items.CHEST)
				.name("Look in " + subject.name() + "'s inventory", Theme.ACCENT)
				.lore(found.category() == CaseCategory.ILLEGAL_ITEMS
						? "Where the item this case is about should be."
						: "Live if they are online, their saved one if not.")
				.gap()
				.action("Click", "open it")
				.build(), click -> InvseeMenu.open(viewer, subject));
	}

	// ----------------------------------------------------------------------- helpers

	private static ItemStack locked(Item item, String name, String node) {
		return Icon.of(item).name(name, Theme.MUTED).lore("Locked — needs " + node + ".", Theme.MUTED).build();
	}

	private void label(int row, DyeColor colour, String name, String contents) {
		ItemStack label = Icon.of(Mc.pane(colour)).name(name, Theme.ACCENT).lore(contents, Theme.MUTED).build();
		set(row, label);
		set(row + 8, label.copy());
	}

	private static NameAndId subjectOf(Case found) {
		return new NameAndId(found.subjectId(),
				found.subjectName() == null ? found.subjectId().toString() : found.subjectName());
	}

	static Item iconFor(CaseEvidence.Kind kind) {
		return switch (kind) {
			case REPLAY -> Items.RECOVERY_COMPASS;
			case BLOCKS -> Items.TNT;
			case LOCATION -> Items.ENDER_PEARL;
			case SNAPSHOT -> Items.CHEST;
			case XRAY_DIG -> Items.DIAMOND_PICKAXE;
			case DISCORD -> Items.WRITABLE_BOOK;
		};
	}

	static String name(Case found) {
		return found.subjectName() == null ? found.subjectId().toString() : found.subjectName();
	}
}
