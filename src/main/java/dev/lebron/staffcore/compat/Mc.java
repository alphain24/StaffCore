package dev.lebron.staffcore.compat;

import dev.lebron.staffcore.StaffCore;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.Set;

/**
 * The one file that touches version-sensitive Minecraft internals.
 * <p>
 * Everything else in StaffCore goes through here, so when a release shifts a signature it
 * is fixed in one place instead of across forty modules. This has already earned its keep:
 * 26.2 moved player identity to {@link NameAndId}, replaced op levels with a real
 * permission system, folded coloured blocks into {@code ColorCollection}, dropped
 * {@code Entity#getServer()} and removed {@code playNotifySound} — every one of those is
 * absorbed below.
 */
public final class Mc {
	private Mc() {}

	// --------------------------------------------------------------- inventory

	/**
	 * Every slot of a player inventory, equipment included — 43 in 26.2.
	 * <p>
	 * {@code Inventory.INVENTORY_SIZE} is 36 and covers storage and hotbar only. Armour,
	 * offhand, body and saddle live past the end of that array, reached through the same
	 * {@code getItem}/{@code setItem} pair by way of {@code EQUIPMENT_SLOT_MAPPING}. Sizing
	 * a buffer by {@code INVENTORY_SIZE} therefore looks right, compiles, and silently drops
	 * everything a player is wearing — which is exactly what the snapshot reader did.
	 * <p>
	 * Derived from vanilla's own two constants rather than written as 43, so a version that
	 * adds an equipment slot widens this with it instead of quietly truncating again.
	 */
	public static final int PLAYER_SLOTS =
			net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE
					+ net.minecraft.world.entity.player.Inventory.EQUIPMENT_SLOT_MAPPING.size();

	/**
	 * A block state as text, properties and all — {@code minecraft:chest[facing=east,type=left]}.
	 * <p>
	 * The grief log used to keep only the block id, which is enough to know what was destroyed
	 * and not enough to put it back: a restored chest came out facing north and single, so
	 * rolling back a broken double chest gave you two unpaired boxes pointing the wrong way.
	 * Stairs, slabs, doors, beds and anything else with orientation had the same problem, just
	 * less visibly.
	 */
	public static String stateToString(BlockState state) {
		if (state == null) return null;
		try {
			return BlockStateParser.serialize(state);
		} catch (RuntimeException e) {
			// This runs on the game thread inside a block-break event. Throwing here would
			// take the rest of that event's handlers with it, so a state that will not
			// serialise costs its own orientation and nothing else — the row is still written
			// with its block id, and a rollback falls back to the default state.
			dev.lebron.staffcore.StaffCore.LOGGER.warn("[StaffCore] Could not serialise {}: {}",
					state.getBlock(), e.toString());
			return null;
		}
	}

	/**
	 * Parses what {@link #stateToString} wrote, or returns null if it cannot.
	 * <p>
	 * Null covers both an old row that predates the column and a state this version can no
	 * longer parse — a property removed by an update, say. Callers fall back to the block's
	 * default state, which is what they restored before this existed.
	 */
	public static BlockState stateFromString(MinecraftServer server, String serialized) {
		if (serialized == null || serialized.isBlank() || server == null) return null;
		try {
			return BlockStateParser.parseForBlock(
					server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
					serialized, false).blockState();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Fixes a restored chest that claims a partner it does not have.
	 * <p>
	 * A double chest is two blocks, each holding a {@code type} of {@code left} or
	 * {@code right}. Restoring both halves is fine — vanilla re-pairs them. Restoring only
	 * one, which happens whenever the other half falls outside the rollback's radius or
	 * window, leaves a block insisting it is half of something that is not there: it renders
	 * as a double chest with a hole in it and opens as a single.
	 * <p>
	 * Rather than guess, this asks the same question vanilla asks — is the block in my
	 * connected direction a matching chest facing the same way, with the opposite type — and
	 * demotes the state to {@code single} when the answer is no.
	 *
	 * @return true when the block was changed
	 */
	public static boolean normalizeChestPair(net.minecraft.world.level.Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)) return false;
		if (!state.hasProperty(ChestBlock.TYPE)) return false;
		if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return false;

		// A partner that satisfies vanilla's own connection rule is a real pair, whatever the
		// two blocks are called — copper halves at different weather states included.
		if (chestPartner(level, pos) != null) return false;

		level.setBlockAndUpdate(pos, state.setValue(ChestBlock.TYPE, ChestType.SINGLE));
		return true;
	}


	/**
	 * A container position plus its other half, when it has one.
	 * <p>
	 * A double chest is one inventory reachable from two coordinates, so a rule expressed
	 * about "this chest" has to cover both or it covers neither reliably — approach the same
	 * chest from the other block and the rule silently stops applying.
	 */
	public static java.util.Set<BlockPos> containerHalves(
			net.minecraft.world.level.Level level, BlockPos pos) {

		java.util.Set<BlockPos> out = new java.util.HashSet<>(2);
		out.add(pos.immutable());

		BlockPos partner = chestPartner(level, pos);
		if (partner != null) out.add(partner);
		return out;
	}

	/**
	 * The other half of a double chest, or null.
	 * <p>
	 * "Is that block the same chest as this one" is not a question to answer by comparing
	 * block instances. Copper chests weather, and vanilla lets halves at different weather
	 * states pair — it normalises them to the least oxidised of the two. An identity check
	 * therefore reads a perfectly ordinary copper double chest as two unrelated singles, and
	 * everything downstream inherits that: the log query looks at one coordinate, the theft
	 * undo puts back half of what was taken, and the pairing repair demotes a valid double to
	 * a single.
	 * <p>
	 * {@code chestCanConnectTo} is the block's own answer to the same question, overridden by
	 * the copper variants for exactly this reason. Ask it rather than guessing.
	 */
	public static BlockPos chestPartner(net.minecraft.world.level.Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock chest)) return null;
		if (!state.hasProperty(ChestBlock.TYPE) || !state.hasProperty(ChestBlock.FACING)) return null;

		ChestType type = state.getValue(ChestBlock.TYPE);
		if (type == ChestType.SINGLE) return null;

		BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
		BlockState partner = level.getBlockState(other);

		// Vanilla's own test, plus the two properties that decide the pairing is mutual
		// rather than merely adjacent.
		if (!chest.chestCanConnectTo(partner)) return null;
		if (!partner.hasProperty(ChestBlock.TYPE) || !partner.hasProperty(ChestBlock.FACING)) return null;
		if (partner.getValue(ChestBlock.TYPE) != type.getOpposite()) return null;
		if (partner.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)) return null;

		return other.immutable();
	}


	/**
	 * Loads the chunks covering an area so its entities can be seen, up to a cap.
	 * <p>
	 * {@code getEntitiesOfClass} only answers for chunks that are already in memory. An item
	 * lying in an unloaded chunk is not merely hard to find — it is invisible, and it does not
	 * despawn either, because entities do not tick while their chunk is unloaded. So a death
	 * pile at an abandoned spot sits there indefinitely, a restore hands the owner a second
	 * copy of everything, and the first player to wander past collects the first copy.
	 * <p>
	 * Loading is capped because the cost is real and grows with the square of the radius: a
	 * 96-block rollback covers about a hundred and seventy chunks, and pulling those off disk
	 * on the server thread is a stall players would feel. Past the cap the sweep does what it
	 * always did and looks at what is already loaded, which is a partial answer rather than a
	 * wrong one — and the pickup log covers what it misses.
	 *
	 * @return true when the whole area is loaded, false when the cap stopped it short
	 */
	public static boolean ensureLoaded(ServerLevel level, BlockPos centre, int radius,
			int maxChunks) {

		int minX = (centre.getX() - radius) >> 4;
		int maxX = (centre.getX() + radius) >> 4;
		int minZ = (centre.getZ() - radius) >> 4;
		int maxZ = (centre.getZ() + radius) >> 4;

		long wanted = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
		if (wanted > maxChunks) {
			StaffCore.LOGGER.warn("[StaffCore] Area covers {} chunks, more than the {} this will "
					+ "load; item recovery falls back to whatever is already in memory.",
					wanted, maxChunks);
			return false;
		}

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				// getChunk loads from disk, generating if it has to. The return value is
				// ignored on purpose: the point is the side effect of it being resident.
				level.getChunk(x, z);
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- identity

	/**
	 * 26.2 identity. {@code NameAndId} is what command arguments hand back and what the
	 * player list keys on; {@code GameProfile} is now only needed for skin lookups.
	 */
	public static NameAndId profile(ServerPlayer player) {
		return player.nameAndId();
	}

	public static String name(ServerPlayer player) {
		return player.nameAndId().name();
	}

	/** Skins still come from authlib, so build a profile on demand for head rendering. */
	public static GameProfile gameProfile(NameAndId id) {
		return new GameProfile(id.id(), id.name());
	}

	// ------------------------------------------------------------------- context

	/**
	 * {@code Entity#getServer()} is gone in 26.2; the level is the way through.
	 * Returns null for a player who is mid-disconnect.
	 */
	public static MinecraftServer server(ServerPlayer player) {
		return player.level().getServer();
	}

	/** {@code ServerPlayer#level()} is covariant now, so there is no {@code serverLevel()}. */
	public static ServerLevel level(ServerPlayer player) {
		return player.level();
	}

	/** {@code minecraft:overworld} — {@code ResourceKey#location()} is now {@code identifier()}. */
	public static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}

	public static String dimensionName(Level level) {
		return level.dimension().identifier().getPath();
	}

	// ---------------------------------------------------------------- permissions

	/**
	 * The standalone fallback when no permissions plugin is installed.
	 * <p>
	 * 26.2 replaced numeric op levels with named vanilla permissions;
	 * {@code COMMANDS_MODERATOR} is the modern spelling of what used to be op level 2.
	 */
	public static boolean isModerator(ServerPlayer player) {
		return player.permissions().hasPermission(
				net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR);
	}

	// ------------------------------------------------------------------ sounds

	/**
	 * Plays a sound for one player only, at their own position.
	 * <p>
	 * {@code playNotifySound} no longer exists, so this sends the packet directly. Two
	 * overloads on purpose: {@code SoundEvents} declares some entries as a bare
	 * {@link SoundEvent} and others as a {@link Holder} — {@code UI_BUTTON_CLICK} and the
	 * note blocks are Holders, everything else is not — and having both means {@code Sfx}
	 * compiles whichever way a given constant is declared.
	 */
	public static void sound(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
		if (player.hasDisconnected()) return;
		player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER,
				player.getX(), player.getY(), player.getZ(), volume, pitch,
				player.getRandom().nextLong()));
	}

	public static void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		sound(player, Holder.direct(sound), volume, pitch);
	}

	// -------------------------------------------------------------- teleporting

	/** Absolute teleport with an explicit facing. */
	public static void teleport(ServerPlayer player, ServerLevel level,
			double x, double y, double z, float yRot, float xRot) {
		player.teleportTo(level, x, y, z, Set.<net.minecraft.world.entity.Relative>of(), yRot, xRot, true);
	}

	// ------------------------------------------------------------- disconnecting

	public static void disconnect(ServerPlayer player, Component reason) {
		player.connection.disconnect(reason);
	}

	// -------------------------------------------------------------- player heads

	/**
	 * A player head wearing this player's actual skin.
	 * <p>
	 * The subtlety that matters: a {@code GameProfile} built by hand from a uuid and a name
	 * carries no texture properties, so {@code createResolved} on one produces the default
	 * Steve head — which is what StaffCore used to render. An online player already has a
	 * fully populated profile from authentication, so that is used directly; for anyone else
	 * the profile is left <em>unresolved</em> and the game fetches the skin itself.
	 */
	public static ItemStack head(NameAndId id) {
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		stack.set(DataComponents.PROFILE, profileFor(id));
		return stack;
	}

	private static ResolvableProfile profileFor(NameAndId id) {
		MinecraftServer server = dev.lebron.staffcore.StaffCore.server();
		if (server != null) {
			ServerPlayer online = server.getPlayerList().getPlayer(id.id());
			if (online != null) {
				// Carries the texture properties Mojang sent at login.
				return ResolvableProfile.createResolved(online.getGameProfile());
			}
		}
		return ResolvableProfile.createUnresolved(id.id());
	}

	// ---------------------------------------------------------------- item stacks

	/**
	 * 26.2 moved the stack limit into a data component, so there is no
	 * {@code ItemStack#getMaxStackSize()} to compare an over-stacked item against.
	 */
	public static int maxStackSize(ItemStack stack) {
		return stack.getOrDefault(DataComponents.MAX_STACK_SIZE, 64);
	}

	/**
	 * Coloured blocks are {@code ColorCollection}s now — there is no
	 * {@code Items.BLACK_STAINED_GLASS_PANE} field to reference.
	 */
	public static Item pane(DyeColor color) {
		return Items.STAINED_GLASS_PANE.pick(color);
	}

	public static Item concrete(DyeColor color) {
		return Items.CONCRETE.pick(color);
	}

	public static Item dye(DyeColor color) {
		return Items.DYE.pick(color);
	}

	/** Resolves an item id from config, falling back rather than crashing on a typo. */
	public static Item itemFromId(String id, Item fallback) {
		if (id == null || id.isBlank()) return fallback;
		try {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
			return item == null || item == Items.AIR ? fallback : item;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** The registry id of an item, e.g. {@code minecraft:bedrock}. */
	public static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	// --------------------------------------------------------------- block registry

	/** {@code minecraft:deepslate_diamond_ore} for a block, as stored in the grief log. */
	public static String blockId(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	/**
	 * The container a player actually sees when they open the block at this position.
	 * <p>
	 * For a double chest that is <em>both</em> halves. {@code getBlockEntity} returns only
	 * the half that was clicked — twenty-seven slots of a fifty-four slot inventory — so
	 * anything taken from the far half was invisible to the container log entirely. A thief
	 * emptying the right-hand side of a double chest left no record at all, and rolling the
	 * theft back put nothing into the half it came from.
	 * <p>
	 * Which half was clicked does not change the ordering: vanilla builds the compound view
	 * from the chest's own LEFT/RIGHT type, so slot 30 means the same slot whichever side you
	 * opened. That is what lets the log and the rollback agree.
	 *
	 * @return the container, or null when there is not one here
	 */
	public static net.minecraft.world.Container containerAt(
			net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {

		net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chest) {
			// true: resolve even when something is sitting on top. A cat on the lid stops a
			// player opening it; it should not stop staff reading what is inside.
			net.minecraft.world.Container both =
					net.minecraft.world.level.block.ChestBlock.getContainer(chest, state, level, pos, true);
			if (both != null) return both;
		}
		return level.getBlockEntity(pos) instanceof net.minecraft.world.Container container
				? container
				: null;
	}

	/** Resolves a logged block id back to a block, or null if the id is no longer known. */
	public static Block blockFromId(String id) {
		try {
			return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- server tps

	/** Mean tick time in milliseconds. */
	public static double meanTickMs(MinecraftServer server) {
		return server.getAverageTickTimeNanos() / 1_000_000.0D;
	}
}
