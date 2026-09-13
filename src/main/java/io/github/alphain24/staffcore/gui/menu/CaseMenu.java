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
import io.github.alphain24.staffcore.modules.cases.EvidenceViewer;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One case: what kind it is, who it is about, what the detectors said, the evidence to open —
 * and the things staff do about a case without leaving it: go to where it happened, roll back
 * the damage, punish the player, hand the case to somebody.
 * <p>
 * Every action re-reads the case when it is clicked rather than trusting what was drawn, and
 * every one of them goes through the same permission check, rate limit and confirmation as its
 * own command. This screen is a shorter route to those, not a way around them.
 */
public final class CaseMenu extends Gui {

	private static final int HEADER = 4;
	private static final int CLAIM = 10;
	private static final int INVESTIGATING = 11;
	private static final int CLOSE_CASE = 12;
	private static final int ASSIGN = 13;
	private static final int CATEGORY = 14;
	private static final int SIGNALS = 15;
	private static final int READ_IN_CHAT = 16;
	private static final int[] EVIDENCE = {
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34};
	private static final int SCENE = 38;
	private static final int ROLLBACK = 39;
	private static final int PUNISH = 40;
	private static final int PLAYER_FILE = 41;
	private static final int INVENTORY = 42;
	private static final int BACK = 45;
	private static final int FILE_EVIDENCE = 47;
	private static final int CLOSE = 49;

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
		super(containerId, playerInventory, viewer, 6);
		this.caseId = caseId;
		render();
	}

	@Override
	protected void build() {
		Case found = Mods.cases().store().byId(caseId).orElse(null);
		if (found == null) {
			set(22, Icon.of(Items.BARRIER)
					.name("Case " + caseId + " could not be read", Theme.BAD)
					.lore("Cases are never deleted, so storage is unavailable.")
					.build());
			backButton(BACK, "Cases", () -> CasesMenu.open(viewer));
			button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
			fillEmpty(Theme.filler());
			return;
		}

		List<CaseEvidence.Item> items = Mods.cases().evidence().forCase(found.id());

		set(HEADER, header(found));
		actions(found);
		evidence(found, items);
		doSomething(found, items);

		backButton(BACK, "Cases", () -> CasesMenu.open(viewer));
		button(FILE_EVIDENCE, Icon.of(Items.WRITABLE_BOOK)
				.name("File evidence", Theme.ACCENT)
				.lore("A replay of " + name(found) + ", the block damage")
				.lore("around you, where you stand, or their inventory.")
				.build(), click -> CaseEvidenceMenu.open(viewer, found.id()));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
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
		if (found.status().isLive() && found.severity() >= 70) icon.glow();
		return icon.build();
	}

	private void actions(Case found) {
		String me = Mc.name(viewer);

		button(CLAIM, Icon.of(Items.NAME_TAG)
				.name(me.equals(found.assignedTo()) ? "Assigned to you" : "Claim", Theme.ACCENT)
				.lore(me.equals(found.assignedTo()) ? "Click to unassign yourself."
						: "Assign this case to yourself.")
				.build(), click -> {
			Mods.cases().store().assign(found.id(), me.equals(found.assignedTo()) ? null : me, me);
			Sfx.click(viewer);
			render();
		});

		button(INVESTIGATING, Icon.of(Items.SPYGLASS)
				.name("Mark investigating", found.status() == Case.Status.INVESTIGATING
						? Theme.MUTED : Theme.ACCENT)
				.lore("Somebody is working on it.")
				.build(), click -> {
			if (found.status() != Case.Status.INVESTIGATING) {
				Mods.cases().store().setStatus(found.id(), Case.Status.INVESTIGATING, me, null);
				Sfx.click(viewer);
			}
			render();
		});

		button(CLOSE_CASE, Icon.of(Items.IRON_DOOR)
				.name("Close the case", Theme.ACCENT)
				.lore("Cleared, with the reason why — or actioned.")
				.build(), click -> CaseCloseMenu.open(viewer, found.id()));

		button(ASSIGN, Icon.of(Items.PLAYER_HEAD)
				.name("Assign to…", Theme.ACCENT)
				.field("Now", found.assignedTo() == null ? "nobody" : found.assignedTo())
				.gap()
				.lore("Pick from the staff online, fewest open cases", Theme.MUTED)
				.lore("first — or unassign it.", Theme.MUTED)
				.build(), click -> CaseAssignMenu.open(viewer, found.id()));

		CaseCategory[] all = CaseCategory.values();
		button(CATEGORY, Icon.of(CasesMenu.iconFor(found.category()))
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

		List<Signal> signals = Mods.cases().store().signalsFor(found.id());
		Icon signalIcon = Icon.of(Items.PAPER)
				.name("What the detectors said (" + signals.size() + ")", Theme.ACCENT);
		signals.stream().limit(6).forEach(signal -> signalIcon.lore(TimeFormat.ago(signal.occurredAt())
				+ "  " + signal.type().label() + "  " + signal.confidence() + "%", Theme.TEXT));
		if (signals.size() > 6) signalIcon.lore("… and " + (signals.size() - 6) + " more", Theme.MUTED);
		set(SIGNALS, signalIcon.build());

		button(READ_IN_CHAT, Icon.of(Items.BOOK)
				.name("Read the whole case in chat", Theme.ACCENT)
				.lore("Every signal, note and event, oldest first.")
				.build(), click -> {
			viewer.closeContainer();
			io.github.alphain24.staffcore.modules.cases.CaseView.print(
					viewer.createCommandSourceStack(), found);
		});
	}

	// ------------------------------------------------------------ doing something

	private void doSomething(Case found, List<CaseEvidence.Item> items) {
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
		Optional<CaseEvidence.Item> scene = sceneOf(items);
		if (scene.isEmpty()) {
			set(SCENE, Icon.of(Items.ENDER_EYE)
					.name("Go to the scene", Theme.MUTED)
					.lore("No evidence on this case says where it happened.")
					.lore("Stand there and file a location with the book.", Theme.MUTED)
					.build());
			return;
		}
		CaseEvidence.Item where = scene.get();
		button(SCENE, Icon.of(Items.ENDER_PEARL)
				.name("Go to the scene", Theme.ACCENT)
				.field("Where", where.where())
				.field("From", "#" + where.id() + " " + where.kind().label())
				.gap()
				.lore("/staff back returns you afterwards.", Theme.MUTED)
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
		Optional<CaseEvidence.Item> damage = items.stream()
				.filter(item -> item.kind() == CaseEvidence.Kind.BLOCKS && item.pos() != null)
				.max(Comparator.comparingLong(CaseEvidence.Item::addedAt));

		if (damage.isEmpty()) {
			if (found.category() == CaseCategory.GRIEFING) {
				set(ROLLBACK, Icon.of(Items.TNT)
						.name("Roll back the damage", Theme.MUTED)
						.lore("No block damage is filed on this case, so there is")
						.lore("nothing to say where or since when.")
						.lore("File it with the book, standing at the damage.", Theme.MUTED)
						.build());
			}
			return;
		}
		if (!Permissions.check(viewer, Nodes.ROLLBACK)) {
			set(ROLLBACK, Icon.of(Items.TNT)
					.name("Roll back the damage", Theme.MUTED)
					.lore("Needs " + Nodes.ROLLBACK + ".")
					.build());
			return;
		}

		CaseEvidence.Item area = damage.get();
		String subject = found.subjectName();
		button(ROLLBACK, Icon.of(Items.TNT)
				.name("Roll back the damage", Theme.BAD)
				.field("Where", area.where())
				.field("Radius", area.radius() + " blocks")
				.field("Since", TimeFormat.ago(area.from()))
				.gap()
				.action("Click", subject == null ? "roll back everything there"
						: "roll back what " + subject + " did there")
				.action("Right-click", "roll back everybody's changes there")
				.gap()
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
		if (!Permissions.check(viewer, Nodes.PUNISH)) return;
		NameAndId subject = subjectOf(found);
		button(PUNISH, Icon.of(Items.IRON_SWORD)
				.name("Punish " + subject.name(), Theme.BAD)
				.lore("Opens the punishment screen. Whatever you issue")
				.lore("from there is linked to this case and noted in it.")
				.build(), click -> {
			io.github.alphain24.staffcore.gui.PunishFromCase.remember(viewer.getUUID(), found.id(), found.subjectId());
			PunishMenu.open(viewer, subject);
		});
	}

	private void playerFile(Case found) {
		NameAndId subject = subjectOf(found);
		button(PLAYER_FILE, Icon.head(subject)
				.name(subject.name() + "'s file", Theme.ACCENT)
				.lore("History, notes, alts and everything else.")
				.build(), click -> PlayerActionsMenu.open(viewer, subject));
	}

	private void inventory(Case found) {
		if (!Permissions.check(viewer, Nodes.INVSEE)) return;
		NameAndId subject = subjectOf(found);
		button(INVENTORY, Icon.of(Items.CHEST)
				.name("Look in " + subject.name() + "'s inventory", Theme.ACCENT)
				.lore(found.category() == CaseCategory.ILLEGAL_ITEMS
						? "Where the item this case is about should be."
						: "Live if they are online, their saved one if not.")
				.build(), click -> InvseeMenu.open(viewer, subject));
	}

	private static NameAndId subjectOf(Case found) {
		return new NameAndId(found.subjectId(),
				found.subjectName() == null ? found.subjectId().toString() : found.subjectName());
	}

	// ------------------------------------------------------------------ evidence

	private void evidence(Case found, List<CaseEvidence.Item> items) {
		if (items.isEmpty()) {
			set(EVIDENCE[3], Icon.of(Items.GLASS_PANE)
					.name("No evidence filed yet", Theme.MUTED)
					.lore("Detectors file a replay and the damage when they")
					.lore("open a case. File your own with the book below.")
					.build());
			return;
		}

		for (int i = 0; i < items.size() && i < EVIDENCE.length; i++) {
			CaseEvidence.Item item = items.get(i);
			Icon icon = Icon.of(iconFor(item.kind()))
					.name("#" + item.id() + " " + item.kind().label(), Theme.ACCENT)
					.lore(item.describe(), Theme.TEXT);
			if (item.label() != null && !item.label().isBlank()) icon.lore(item.label(), Theme.MUTED);
			icon.field("Filed", TimeFormat.ago(item.addedAt()) + " by " + item.addedBy())
					.gap()
					.action("Click", "open it")
					.action("Shift-click", "retract it");

			button(EVIDENCE[i], icon.build(), click -> {
				if (click.isShift()) {
					ConfirmMenu.open(viewer, "Retract #" + item.id(),
							new ItemStack(iconFor(item.kind())),
							() -> {
								Mods.cases().evidence().retract(item.id(), Mc.name(viewer));
								reopen(viewer, found.id());
							},
							() -> reopen(viewer, found.id()));
					return;
				}
				EvidenceViewer.open(viewer, item);
			});
		}
		if (items.size() > EVIDENCE.length) {
			viewer.sendSystemMessage(Theme.info("Case " + found.id() + " has " + items.size()
					+ " pieces of evidence; the screen shows the first " + EVIDENCE.length
					+ ". /staff case " + found.id() + " evidence lists them all."));
		}
	}

	static Item iconFor(CaseEvidence.Kind kind) {
		return switch (kind) {
			case REPLAY -> Items.RECOVERY_COMPASS;
			case BLOCKS -> Items.TNT;
			case LOCATION -> Items.ENDER_PEARL;
			case SNAPSHOT -> Items.CHEST;
			case XRAY_DIG -> Items.DIAMOND_PICKAXE;
		};
	}

	private static String name(Case found) {
		return found.subjectName() == null ? found.subjectId().toString() : found.subjectName();
	}
}
