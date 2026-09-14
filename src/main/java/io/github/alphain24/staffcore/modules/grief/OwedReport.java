package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Theme;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a rollback left somebody owing, said the same way on every path that runs one.
 * <p>
 * It used to be one line — "items are owed by an offline player" — which named nobody, listed
 * nothing, and read as though the items were owed <em>to</em> somebody who was never going to
 * get them. They are not owed to anybody. The rollback has already put the originals back; a
 * debt only stops the person who had the copies from keeping them, and what is collected is
 * removed rather than handed on. So the lines say who, what, and exactly that.
 */
public final class OwedReport {
	private OwedReport() {}

	public static List<Component> lines(GriefModule.RollbackResult result) {
		List<Component> out = new ArrayList<>();

		result.owedBy().forEach((name, items) -> {
			String what = describe(items);
			if (what.isEmpty()) return;
			out.add(Theme.warn(name + " still owes " + what
					+ " — items the rollback put back that were not on them."));
		});

		if (!out.isEmpty()) {
			out.add(Theme.info("Taken off them the next time they log in. Nobody receives them: "
					+ "the originals are already back, so this only stops a copy being kept. "
					+ "See or forgive it under World → Owed items, or /staff owed."));
		} else if (result.debitsQueued() > 0) {
			out.add(Theme.warn(result.debitsQueued() + " item(s) the rollback put back were not "
					+ "on the player who had them — taken off them on their next login. "
					+ "See /staff owed."));
		}

		if (result.unclaimed() > 0) {
			out.add(Theme.info(result.unclaimed() + " item(s) that went back were never picked "
					+ "up by anybody — they despawned, burnt or were blown up — so nobody owes them."));
		}
		return out;
	}

	/** "5× Diamond, 1× Chest", biggest first. */
	public static String describe(Map<Item, Integer> items) {
		List<String> parts = new ArrayList<>();
		items.entrySet().stream()
				.filter(e -> e.getValue() != null && e.getValue() > 0)
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.forEach(e -> parts.add(e.getValue() + "× "
						+ new ItemStack(e.getKey()).getHoverName().getString()));
		return String.join(", ", parts);
	}

	/** The same for a debt row, whose item is stored as an id. */
	public static String describe(String itemId, int count) {
		Item item = Mc.itemFromId(itemId, null);
		return count + "× " + (item == null ? itemId : new ItemStack(item).getHoverName().getString());
	}
}
