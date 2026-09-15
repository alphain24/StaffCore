package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseClosing;
import io.github.alphain24.staffcore.modules.cases.CaseStore;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that happened on one case, newest first: who did what, and why.
 * <p>
 * A punishment linked to the case is shown with its type and reason read from the punishment
 * itself, so even a link made before reasons were written into the history says what the
 * player got and what for.
 */
public final class CaseHistoryMenu extends PagedGui<CaseStore.Event> {

	private final String caseId;

	public static void open(ServerPlayer viewer, String caseId) {
		Guis.navigate(viewer, Theme.title("Case", caseId, "History"),
				(id, inv, v) -> new CaseHistoryMenu(id, inv, v, caseId));
	}

	private CaseHistoryMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String caseId) {
		super(containerId, playerInventory, viewer);
		this.caseId = caseId;
		render();
	}

	@Override
	protected List<CaseStore.Event> entries() {
		List<CaseStore.Event> events = new ArrayList<>(Mods.cases().store().eventsFor(caseId));
		java.util.Collections.reverse(events);
		return events;
	}

	@Override
	protected ItemStack header() {
		return Icon.of(Items.BOOK)
				.name("History of case " + caseId, Theme.ACCENT)
				.field("Entries", String.valueOf(entries().size()))
				.gap()
				.lore("Newest first. Nothing here is ever removed.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack icon(CaseStore.Event event) {
		Icon icon = Icon.of(iconFor(event.kind()))
				.name(title(event.kind()), Theme.ACCENT)
				.field("By", event.actor())
				.field("When", TimeFormat.ago(event.at()) + " (" + TimeFormat.stamp(event.at()) + ")");

		String body = bodyOf(event);
		if (body != null && !body.isBlank()) {
			icon.gap().paragraph(body.length() > 200 ? body.substring(0, 197) + "…" : body, Theme.TEXT);
			if (body.length() > 200) icon.gap().action("Click", "print it all in chat");
		}
		return icon.build();
	}

	@Override
	protected void onPick(CaseStore.Event event, Click click) {
		String body = bodyOf(event);
		if (body == null || body.length() <= 200) {
			Sfx.deny(viewer);
			return;
		}
		viewer.sendSystemMessage(Theme.info(title(event.kind()) + " by " + event.actor() + ": " + body));
		Sfx.click(viewer);
	}

	private static String bodyOf(CaseStore.Event event) {
		return CaseClosing.eventBody(event);
	}

	private static String title(String kind) {
		if (kind == null || kind.isBlank()) return "Event";
		return Character.toUpperCase(kind.charAt(0)) + kind.substring(1).replace('_', ' ');
	}

	private static Item iconFor(String kind) {
		if (kind == null) return Items.PAPER;
		return switch (kind) {
			case "note" -> Items.WRITABLE_BOOK;
			case "linked" -> Items.IRON_CHAIN;
			case "assigned" -> Items.NAME_TAG;
			case "open", "investigating" -> Items.SPYGLASS;
			case "actioned" -> Items.IRON_BARS;
			case "cleared" -> Items.EMERALD;
			case "stale" -> Items.CLOCK;
			default -> Items.PAPER;
		};
	}

	@Override
	protected Runnable backTarget() {
		return () -> CaseMenu.open(viewer, caseId);
	}

	@Override
	protected String backLabel() {
		return "the case";
	}
}
