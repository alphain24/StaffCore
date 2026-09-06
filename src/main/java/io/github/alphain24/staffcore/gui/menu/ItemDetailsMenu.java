package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.util.ItemInspector;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Everything one item is carrying, component by component.
 * <p>
 * The tooltip shows what the game decides is worth showing. This shows what is actually on
 * the stack, which is the difference between "a diamond sword" and "a diamond sword with an
 * attribute modifier nobody put there". Read-only by construction: the stack is copied on the
 * way in, so nothing here can change what a player is holding.
 */
public final class ItemDetailsMenu extends PagedGui<ItemInspector.Entry> {

	private static final int SLOT_RAW = 47;
	private static final int SLOT_DEFAULTS = 51;

	private final ItemStack stack;
	private final String origin;
	private final Runnable back;
	private boolean showDefaults;

	/**
	 * @param origin where this item was found, for the header — a slot, a vault row, a hand
	 * @param back   where the back arrow goes, or null
	 */
	public static void open(ServerPlayer viewer, ItemStack stack, String origin, Runnable back) {
		if (stack == null || stack.isEmpty()) {
			viewer.sendSystemMessage(Theme.warn("That slot is empty."));
			Sfx.deny(viewer);
			return;
		}
		ItemStack copy = stack.copy();
		Sfx.open(viewer);
		Guis.silent(viewer, Theme.title("Item", copy.getHoverName().getString()),
				(id, inv, v) -> new ItemDetailsMenu(id, inv, v, copy, origin, back));
	}

	private ItemDetailsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			ItemStack stack, String origin, Runnable back) {
		super(containerId, playerInventory, viewer);
		this.stack = stack;
		this.origin = origin;
		this.back = back;
		render();
	}

	@Override
	protected List<ItemInspector.Entry> entries() {
		MinecraftServer server = Mc.server(viewer);
		List<ItemInspector.Entry> all = ItemInspector.components(server, stack);
		if (showDefaults) return all;

		// Default components are every item's baseline — max stack size, repair cost, the
		// tooltip order. Showing them by default buries the two rows that are actually
		// unusual under twenty that are not.
		return all.stream().filter(entry -> !entry.fromDefaults()).toList();
	}

	@Override
	protected ItemStack icon(ItemInspector.Entry entry) {
		Icon icon = Icon.of(entry.fromDefaults() ? Items.PAPER : Items.WRITABLE_BOOK)
				.name(shortId(entry.id()), entry.fromDefaults() ? Theme.MUTED : Theme.ACCENT)
				.lore(entry.id(), Theme.MUTED)
				.gap();

		for (String line : entry.wrapped(46)) {
			icon.lore(line, Theme.TEXT);
		}
		if (entry.fromDefaults()) {
			icon.gap().lore("Unchanged from this item's default.", Theme.MUTED);
		}
		return icon.action("Click", "print it to chat, where it can be copied").build();
	}

	@Override
	protected void onPick(ItemInspector.Entry entry, Click click) {
		viewer.sendSystemMessage(Theme.info(entry.id()));
		viewer.sendSystemMessage(Component.literal(entry.value()).withStyle(Style.EMPTY
				.withColor(Theme.TEXT)
				.withClickEvent(new ClickEvent.CopyToClipboard(entry.value()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy")))));
		Sfx.click(viewer);
	}

	@Override
	protected ItemStack header() {
		int total = ItemInspector.components(Mc.server(viewer), stack).size();
		int shown = entries().size();

		return Icon.of(stack.copy())
				.name(stack.getHoverName().getString(), Theme.ACCENT)
				.field("Item", Mc.itemId(stack.getItem()))
				.field("Count", String.valueOf(stack.getCount()))
				.field("Found in", origin == null ? "unknown" : origin)
				.field("Components", shown + " shown of " + total)
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("Nothing unusual", Theme.GOOD)
				.lore("This item carries only the components every", Theme.MUTED)
				.lore("one of its kind carries. Nothing was added to it.", Theme.MUTED)
				.gap()
				.lore("Show defaults to see them anyway.", Theme.TEXT)
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return back;
	}

	@Override
	protected String backLabel() {
		return "where you came from";
	}

	@Override
	protected void decorateFooter() {
		button(SLOT_DEFAULTS, Icon.of(Mc.dye(showDefaults ? net.minecraft.world.item.DyeColor.LIME : net.minecraft.world.item.DyeColor.GRAY))
				.name(showDefaults ? "Showing defaults" : "Hiding defaults",
						showDefaults ? Theme.GOOD : Theme.MUTED)
				.lore("Components every item of this type has.", Theme.MUTED)
				.lore("Hidden by default so the unusual ones stand out.", Theme.MUTED)
				.gap()
				.action("Click", showDefaults ? "hide them again" : "show them")
				.build(), click -> {
			showDefaults = !showDefaults;
			resetPage();
			Sfx.click(viewer);
			render();
		});

		button(SLOT_RAW, Icon.of(Items.NAME_TAG)
				.name("Raw data", Theme.TEXT)
				.lore("The whole stack as stored text — what the", Theme.MUTED)
				.lore("database keeps and what a bug report wants.", Theme.MUTED)
				.gap()
				.action("Click", "print it to chat, click there to copy")
				.build(), click -> {
			String raw = ItemInspector.raw(Mc.server(viewer), stack);
			viewer.sendSystemMessage(Theme.info("Raw item data — click to copy:"));
			viewer.sendSystemMessage(Component.literal(raw).withStyle(Style.EMPTY
					.withColor(Theme.TEXT)
					.withClickEvent(new ClickEvent.CopyToClipboard(raw))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy")))));
			Sfx.success(viewer);
		});
	}

	/** {@code minecraft:custom_name} reads better as {@code custom_name} on one line. */
	private static String shortId(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}
}
