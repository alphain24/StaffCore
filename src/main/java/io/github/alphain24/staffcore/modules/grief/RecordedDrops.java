package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a destroyed block actually came out as.
 *
 * <h2>Why rollback needs this</h2>
 * A rollback puts a block back and takes away the item it dropped, so the griefer does not keep
 * both. It used to assume the item was the block itself. For a plank that is right. For stone
 * it is cobblestone, for grass it is dirt, for an ore it is the raw ore, for glass it is
 * nothing — so a rollback of a TNT crater in natural ground went looking for stone, found none,
 * and left every piece of cobblestone lying where the blast threw it.
 * <p>
 * Explosions made it worse in the other direction: crystals, beds and anchors drop a random few
 * of the blocks they take, and there is no working out afterwards which.
 * <p>
 * So the drops are read off the game at the moment it hands them out — the loot for a player's
 * break, the item list an explosion collects — and a rollback owes exactly those.
 *
 * <h2>How a drop is tied to its row</h2>
 * By world, position and the timestamp the block-log row was written with, which both writers
 * share with the row on purpose. A row with {@code drops_recorded} set means this table is the
 * truth for it, including when it holds nothing: that block dropped nothing. A row without it
 * predates the recording, or came from a break the hook did not see, and keeps the old rule.
 */
public final class RecordedDrops {

	/** Which block-log row a set of drops belongs to: where, and the moment it was written. */
	public record Key(int x, int y, int z, long at) {}

	/** Drops handed out during a player's break, held until the break event claims them. */
	private record Pending(long pos, Map<String, Integer> items) {}

	/**
	 * One per player, and replaced by their next break. The drops are handed out inside the
	 * break, before the break event fires, on the same thread — so the one waiting is always
	 * the one for the break being logged, and one that was never claimed is simply overwritten.
	 */
	private final Map<UUID, Pending> handDrops = new HashMap<>();

	/** Called from the block-drops mixin while a player breaks a block. */
	public void onHandDrops(UUID player, ServerLevel level, BlockPos pos, List<ItemStack> drops) {
		handDrops.put(player, new Pending(pos.asLong(), summarise(level, drops)));
	}

	/** The drops for this break, or {@code null} when none were seen for it. */
	Map<String, Integer> takeHandDrops(UUID player, BlockPos pos) {
		Pending pending = handDrops.remove(player);
		return pending != null && pending.pos() == pos.asLong() ? pending.items() : null;
	}

	void forget(UUID player) {
		handDrops.remove(player);
	}

	void clear() {
		handDrops.clear();
	}

	/**
	 * Item id to total count, in the order first seen.
	 * <p>
	 * Empty when the server has block drops switched off: the loot is still worked out, but
	 * nothing is spawned, and owing it would bill somebody for items that never existed.
	 */
	static Map<String, Integer> summarise(ServerLevel level, List<ItemStack> drops) {
		Map<String, Integer> items = new LinkedHashMap<>();
		if (level != null && !Boolean.TRUE.equals(level.getGameRules().get(GameRules.BLOCK_DROPS))) {
			return items;
		}
		for (ItemStack stack : drops) {
			if (stack == null || stack.isEmpty()) continue;
			items.merge(Mc.itemId(stack.getItem()), stack.getCount(), Integer::sum);
		}
		return items;
	}

	/** Writes one block's drops. On the log writer, inside the caller's work. */
	static void write(Connection conn, String world, BlockPos pos, long at,
			Map<String, Integer> items) throws SQLException {

		if (items == null || items.isEmpty()) return;
		try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO block_drops (world, x, y, z, created_at, item, count)
				VALUES (?,?,?,?,?,?,?)
				""")) {
			for (Map.Entry<String, Integer> item : items.entrySet()) {
				ps.setString(1, world);
				ps.setInt(2, pos.getX());
				ps.setInt(3, pos.getY());
				ps.setInt(4, pos.getZ());
				ps.setLong(5, at);
				ps.setString(6, item.getKey());
				ps.setInt(7, item.getValue());
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	/** Every recorded drop in a box of the world since a time, keyed to the row it belongs to. */
	static Map<Key, Map<String, Integer>> readArea(Connection conn, String world, long since,
			BlockPos centre, int radius) throws SQLException {

		Map<Key, Map<String, Integer>> out = new HashMap<>();
		try (PreparedStatement ps = conn.prepareStatement("""
				SELECT x, y, z, created_at, item, count FROM block_drops
				WHERE world = ? AND created_at >= ?
				  AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
				""")) {
			ps.setString(1, world);
			ps.setLong(2, since);
			ps.setInt(3, centre.getX() - radius);
			ps.setInt(4, centre.getX() + radius);
			ps.setInt(5, centre.getY() - radius);
			ps.setInt(6, centre.getY() + radius);
			ps.setInt(7, centre.getZ() - radius);
			ps.setInt(8, centre.getZ() + radius);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Key key = new Key(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
							rs.getLong("created_at"));
					out.computeIfAbsent(key, k -> new LinkedHashMap<>())
							.merge(rs.getString("item"), rs.getInt("count"), Integer::sum);
				}
			}
		}
		return out;
	}
}
