package io.github.alphain24.staffcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.security.XrayDetector;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.gui.menu.AltsMenu;
import io.github.alphain24.staffcore.gui.menu.AppealsMenu;
import io.github.alphain24.staffcore.gui.menu.ContrabandMenu;
import io.github.alphain24.staffcore.gui.menu.VaultMenu;
import io.github.alphain24.staffcore.gui.menu.EnderChestMenu;
import io.github.alphain24.staffcore.gui.menu.InvseeMenu;
import io.github.alphain24.staffcore.gui.menu.LogsMenu;
import io.github.alphain24.staffcore.gui.menu.PlayerActionsMenu;
import io.github.alphain24.staffcore.gui.menu.ReportsMenu;
import io.github.alphain24.staffcore.gui.menu.SecurityMenu;
import io.github.alphain24.staffcore.gui.menu.StaffPanelMenu;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import io.github.alphain24.staffcore.modules.grief.LogQuery;
import io.github.alphain24.staffcore.modules.notes.NotesModule;
import io.github.alphain24.staffcore.modules.staffmode.StaffToolset;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.modules.report.ReportModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.DurationParser;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;

/**
 * Every StaffCore command, under one root.
 * <p>
 * Everything lives at {@code /staff …}, so a staff member who remembers one word can tab
 * their way to the rest, and nothing StaffCore adds can collide with another mod's
 * {@code /ban} or {@code /kick}. Two commands deliberately stay at the top level:
 * <ul>
 *   <li>{@code /report} — it belongs to players, not staff, and burying a player-facing
 *       command under {@code /staff} would be actively confusing.</li>
 *   <li>{@code /sc} — typed dozens of times a shift; a shortcut for {@code /staff say}.</li>
 * </ul>
 * The panel is still the primary interface; these are the escape hatch for free text,
 * for speed, and for people who would rather type.
 */
public final class StaffCommands {
	private StaffCommands() {}

	/** Any one of these makes the {@code /staff} root worth showing to a source. */
	private static final String[] ANY_STAFF_NODE = {
			Nodes.STAFF_GUI, Nodes.STAFF_MODE, Nodes.VANISH, Nodes.FREEZE, Nodes.TP,
			Nodes.PUNISH, Nodes.BAN, Nodes.MUTE, Nodes.KICK, Nodes.WARN, Nodes.UNPUNISH,
			Nodes.HISTORY, Nodes.NOTES, Nodes.CHAT, Nodes.ALERTS, Nodes.REPORT_VIEW,
			Nodes.INVSEE, Nodes.SECURITY_CHECK, Nodes.ITEMSCAN, Nodes.ROLLBACK,
			Nodes.SPY, Nodes.CHAT_CONTROL, Nodes.BROADCAST, Nodes.MAINTENANCE,
			Nodes.ANALYTICS, Nodes.RELOAD, Nodes.ALTS, Nodes.LOGS, Nodes.APPEALS, Nodes.VAULT,
			Nodes.ENDERCHEST, Nodes.GRIEF_SEARCH, Nodes.GRIEF_PURGE, Nodes.INSPECT_MODE,
			Nodes.PERMS_ADMIN
	};

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> staff = Commands.literal("staff")
				.requires(src -> Permissions.checkAny(src, ANY_STAFF_NODE))
				.executes(StaffCommands::openPanel);

		staff.then(Commands.literal("panel")
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.executes(StaffCommands::openPanel));

		duty(staff);
		punishments(staff);
		records(staff);
		communication(staff);
		movement(staff);
		inspection(staff);
		serverControl(staff);

		// Added last so Brigadier tries every literal before falling back to a bare name:
		// `/staff ban Notch` hits the literal, `/staff Notch` lands here.
		staff.then(Commands.argument("player", GameProfileArgument.gameProfile())
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.executes(ctx -> openFile(ctx, "player")));

		dispatcher.register(staff);

		registerRootExceptions(dispatcher);
	}

	private static int openPanel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		if (!Permissions.check(ctx.getSource(), Nodes.STAFF_GUI)) {
			return fail(ctx, "You do not have " + Nodes.STAFF_GUI + ".");
		}
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		audit(ctx, "/staff");
		StaffPanelMenu.open(p);
		return 1;
	}

	private static int openFile(CommandContext<CommandSourceStack> ctx, String argument)
			throws CommandSyntaxException {

		ServerPlayer viewer = ctx.getSource().getPlayerOrException();
		NameAndId target = singleProfile(ctx, argument);
		if (target == null) return fail(ctx, "Could not work out who you meant.");
		audit(ctx, "/staff " + target.name());
		PlayerActionsMenu.open(viewer, target);
		return 1;
	}

	/** The two commands that stay at the root, and why. */
	private static void registerRootExceptions(CommandDispatcher<CommandSourceStack> dispatcher) {
		// Player-facing: checkOpen, not check, so it never falls back to an op-level gate.
		dispatcher.register(Commands.literal("report")
				.requires(src -> Permissions.checkOpen(src, Nodes.REPORT_USE))
				.then(Commands.argument("target", EntityArgument.player())
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(StaffCommands::fileReport))));

		// Reachable by muted players on purpose: a mute stops you talking in chat, it is
		// not meant to stop you contesting the mute.
		dispatcher.register(Commands.literal("appeal")
				.requires(src -> StaffConfig.get().allowInGameAppeals
						&& Permissions.checkOpen(src, Nodes.APPEAL_USE))
				.then(Commands.argument("text", StringArgumentType.greedyString())
						.executes(StaffCommands::fileAppeal)));

		dispatcher.register(Commands.literal("sc")
				.requires(src -> Permissions.check(src, Nodes.CHAT))
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(StaffCommands::staffSay)));
	}

	// ------------------------------------------------------------------ on duty

	private static void duty(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("mode")
				.requires(src -> Permissions.check(src, Nodes.STAFF_MODE))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					audit(ctx, "/staff mode");
					Mods.staffMode().toggle(p);
					return 1;
				}));

		staff.then(Commands.literal("vanish")
				.requires(src -> Permissions.check(src, Nodes.VANISH))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					audit(ctx, "/staff vanish");
					Mods.vanish().toggle(p);
					return 1;
				}));

		staff.then(Commands.literal("freeze")
				.requires(src -> Permissions.check(src, Nodes.FREEZE))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
							audit(ctx, "/staff freeze " + Mc.name(target));
							boolean frozen = Mods.freeze().toggle(target);
							return ok(ctx, frozen
									? Mc.name(target) + " is frozen."
									: Mc.name(target) + " is free to move.");
						})));
	}

	// -------------------------------------------------------------- punishments

	private static void punishments(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("warn")
				.requires(src -> Permissions.check(src, Nodes.WARN))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> punish(ctx, PunishmentType.WARN, null,
										StringArgumentType.getString(ctx, "reason"))))));

		staff.then(Commands.literal("kick")
				.requires(src -> Permissions.check(src, Nodes.KICK))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> punish(ctx, PunishmentType.KICK, null,
										StringArgumentType.getString(ctx, "reason"))))));

		ladder(staff, "mute", "tempmute", PunishmentType.MUTE, Nodes.MUTE);
		ladder(staff, "ban", "tempban", PunishmentType.BAN, Nodes.BAN);

		staff.then(Commands.literal("unban")
				.requires(src -> Permissions.check(src, Nodes.UNPUNISH))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(ctx -> revoke(ctx, true))));

		staff.then(Commands.literal("unmute")
				.requires(src -> Permissions.check(src, Nodes.UNPUNISH))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(ctx -> revoke(ctx, false))));
	}

	/** Registers the permanent and temporary forms of one rung. */
	private static void ladder(LiteralArgumentBuilder<CommandSourceStack> staff,
			String permanentName, String temporaryName, PunishmentType base, String node) {

		staff.then(Commands.literal(permanentName)
				.requires(src -> Permissions.check(src, node))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> punish(ctx, base, null,
										StringArgumentType.getString(ctx, "reason"))))));

		staff.then(Commands.literal(temporaryName)
				.requires(src -> Permissions.check(src, node))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.then(Commands.argument("duration", StringArgumentType.word())
								.then(Commands.argument("reason", StringArgumentType.greedyString())
										.executes(ctx -> {
											String spec = StringArgumentType.getString(ctx, "duration");
											Long ms = DurationParser.parse(spec);
											if (ms == null) {
												return fail(ctx, "'" + spec + "' is not a duration. "
														+ "Try 30m, 6h, 7d — or use /staff "
														+ permanentName + " for permanent.");
											}
											return punish(ctx, base, ms,
													StringArgumentType.getString(ctx, "reason"));
										})))));
	}

	private static int punish(CommandContext<CommandSourceStack> ctx, PunishmentType base,
			Long durationMs, String reason) throws CommandSyntaxException {

		MinecraftServer server = ctx.getSource().getServer();
		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return fail(ctx, "Could not work out who you meant.");

		if (StaffConfig.get().requireReason && (reason == null || reason.isBlank())) {
			return fail(ctx, "This server requires a reason.");
		}

		String staff = ctx.getSource().getTextName();
		audit(ctx, "/staff " + base.name().toLowerCase(java.util.Locale.ROOT)
				+ " " + target.name() + " " + reason);

		Punishment result = Mods.punish().apply(server, target, staff, base, durationMs, reason);
		if (result == null) {
			return fail(ctx, "The punishment could not be saved — check the server log.");
		}
		// PunishmentModule already broadcast it; no second confirmation needed here.
		return 1;
	}

	private static int revoke(CommandContext<CommandSourceStack> ctx, boolean bans)
			throws CommandSyntaxException {

		MinecraftServer server = ctx.getSource().getServer();
		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return fail(ctx, "Could not work out who you meant.");

		String what = bans ? "ban" : "mute";
		audit(ctx, "/staff un" + what + " " + target.name());

		int n = Mods.punish().revoke(server, target.id(), ctx.getSource().getTextName(), bans);
		if (n == 0) {
			return fail(ctx, target.name() + " has no active " + what + ".");
		}
		Mods.alerts().onStaffAction(server,
				ctx.getSource().getTextName() + " lifted a " + what + " on " + target.name());
		return ok(ctx, "Lifted the " + what + " on " + target.name() + ".");
	}

	// ------------------------------------------------------------------ records

	private static void records(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("history")
				.requires(src -> Permissions.check(src, Nodes.HISTORY))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(StaffCommands::printHistory)
						.then(Commands.literal("clear")
								.requires(src -> Permissions.check(src, Nodes.HISTORY_CLEAR))
								.executes(ctx -> {
									NameAndId target = singleProfile(ctx, "target");
									if (target == null) return fail(ctx, "Unknown player.");
									audit(ctx, "/staff history " + target.name() + " clear");
									Mods.punish().clearHistory(target.id());
									return ok(ctx, "Wiped " + target.name() + "'s history.");
								}))));

		staff.then(Commands.literal("notes")
				.requires(src -> Permissions.check(src, Nodes.NOTES))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(StaffCommands::listNotes)
						.then(Commands.literal("add")
								.then(Commands.argument("text", StringArgumentType.greedyString())
										.executes(ctx -> {
											NameAndId target = singleProfile(ctx, "target");
											if (target == null) return fail(ctx, "Unknown player.");
											String text = StringArgumentType.getString(ctx, "text");
											audit(ctx, "/staff notes " + target.name() + " add");

											if (!Mods.notes().add(target.id(),
													ctx.getSource().getTextName(), text)) {
												return fail(ctx, "The note could not be saved.");
											}
											playerSound(ctx, true);
											return ok(ctx, "Note added to " + target.name() + ".");
										})))
						.then(Commands.literal("list")
								.requires(src -> Permissions.check(src, Nodes.NOTES_VIEW))
								.executes(StaffCommands::listNotes))
						.then(Commands.literal("remove")
								.requires(src -> Permissions.check(src, Nodes.NOTES_REMOVE))
								.then(Commands.argument("index", IntegerArgumentType.integer(1))
										.executes(ctx -> {
											NameAndId target = singleProfile(ctx, "target");
											if (target == null) return fail(ctx, "Unknown player.");
											int index = IntegerArgumentType.getInteger(ctx, "index");
											audit(ctx, "/staff notes " + target.name() + " remove " + index);

											return Mods.notes().removeByIndex(target.id(), index)
													? ok(ctx, "Note " + index + " deleted.")
													: fail(ctx, "There is no note " + index + ".");
										})))));
	}

	private static int printHistory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return fail(ctx, "Unknown player.");
		audit(ctx, "/staff history " + target.name());

		List<Punishment> history = Mods.punish().history(target.id());
		if (history.isEmpty()) {
			return ok(ctx, target.name() + " has a clean record.");
		}
		ctx.getSource().sendSuccess(() -> Theme.info(
				target.name() + " — " + history.size() + " record(s):"), false);
		history.stream().limit(20).forEach(p -> ctx.getSource().sendSuccess(() ->
				Icon.text("  " + p.type().label(), p.type().color())
						.append(Icon.text(" · " + TimeFormat.ago(p.createdAt())
								+ " · by " + p.staffName()
								+ " · " + p.reasonOr("no reason"), Theme.MUTED)), false));
		if (history.size() > 20) {
			ctx.getSource().sendSuccess(() -> Theme.info(
					"  … " + (history.size() - 20) + " more. Use /staff for the full list."), false);
		}
		return 1;
	}

	private static int listNotes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return fail(ctx, "Unknown player.");

		List<NotesModule.Note> notes = Mods.notes().list(target.id());
		if (notes.isEmpty()) {
			return ok(ctx, "No notes on " + target.name() + ".");
		}

		ctx.getSource().sendSuccess(() -> Theme.info(
				target.name() + " — " + notes.size() + " note(s):"), false);
		for (int i = 0; i < notes.size(); i++) {
			NotesModule.Note note = notes.get(i);
			int index = i + 1;
			ctx.getSource().sendSuccess(() -> Icon.text("  " + index + ". ", Theme.ACCENT)
					.append(Icon.text(note.text(), Theme.TEXT))
					.append(Icon.text(" — " + note.author() + ", "
							+ TimeFormat.ago(note.createdAt()), Theme.MUTED)), false);
		}
		return 1;
	}

	// ------------------------------------------------------------ communication

	private static void communication(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("chat")
				.requires(src -> Permissions.check(src, Nodes.CHAT))
				.executes(ctx -> {
					Mods.staffChat().toggle(ctx.getSource().getPlayerOrException());
					return 1;
				}));

		staff.then(Commands.literal("say")
				.requires(src -> Permissions.check(src, Nodes.CHAT))
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(StaffCommands::staffSay)));

		staff.then(Commands.literal("alerts")
				.requires(src -> Permissions.check(src, Nodes.ALERTS))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					boolean on = Mods.alerts().toggle(p);
					return ok(ctx, on ? "Alerts on." : "Alerts silenced.");
				}));

		staff.then(Commands.literal("reports")
				.requires(src -> Permissions.check(src, Nodes.REPORT_VIEW))
				.executes(ctx -> {
					ReportsMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}));
	}

	private static int staffSay(CommandContext<CommandSourceStack> ctx) {
		String message = StringArgumentType.getString(ctx, "message");
		MinecraftServer server = ctx.getSource().getServer();
		ServerPlayer sender = ctx.getSource().getPlayer();

		if (sender != null) {
			Mods.staffChat().sendFrom(server, sender, message);
		} else {
			Mods.staffChat().send(server, ctx.getSource().getTextName(), message);
		}
		return 1;
	}

	private static int fileAppeal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String text = StringArgumentType.getString(ctx, "text");

		var result = Mods.appeals().file(player.getUUID(), Mc.name(player), text);
		switch (result) {
			case OK -> {
				Mods.alerts().onStaffAction(ctx.getSource().getServer(),
						Mc.name(player) + " filed an appeal");
				Sfx.success(player);
				return ok(ctx, "Appeal filed. Staff will review it.");
			}
			case ALREADY_OPEN -> {
				Sfx.deny(player);
				return fail(ctx, "You already have an appeal waiting on a verdict.");
			}
			default -> {
				Sfx.error(player);
				return fail(ctx, "Appeals are unavailable right now.");
			}
		}
	}

	private static int fileReport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer reporter = ctx.getSource().getPlayerOrException();
		ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
		String reason = StringArgumentType.getString(ctx, "reason");

		if (target == reporter) {
			return fail(ctx, "You cannot report yourself.");
		}

		ReportModule.Result result = Mods.reports().file(
				reporter.getUUID(), Mc.name(reporter),
				target.getUUID(), Mc.name(target), reason);

		switch (result) {
			case OK -> {
				Mods.alerts().onReport(ctx.getSource().getServer(),
						Mc.name(reporter), Mc.name(target), reason);
				Sfx.success(reporter);
				return ok(ctx, "Report submitted. Staff have been told.");
			}
			case DUPLICATE -> {
				Sfx.success(reporter);
				return ok(ctx, "Someone already reported them — yours has been merged in.");
			}
			case ON_COOLDOWN -> {
				Sfx.deny(reporter);
				return fail(ctx, "You reported someone recently. Give it a minute.");
			}
			default -> {
				Sfx.error(reporter);
				return fail(ctx, "Reports are unavailable right now.");
			}
		}
	}

	// ----------------------------------------------------------------- movement

	private static void movement(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("bring")
				.requires(src -> Permissions.check(src, Nodes.TP_HERE))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer self = ctx.getSource().getPlayerOrException();
							ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
							audit(ctx, "/staff bring " + Mc.name(target));
							Mods.teleport().bringHere(self, target);
							target.sendSystemMessage(Theme.info("You were brought to a staff member."));
							return ok(ctx, "Brought " + Mc.name(target) + " to you.");
						})));

		staff.then(Commands.literal("goto")
				.requires(src -> Permissions.check(src, Nodes.TP))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer self = ctx.getSource().getPlayerOrException();
							ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
							audit(ctx, "/staff goto " + Mc.name(target));
							Mods.teleport().toPlayer(self, target);
							return ok(ctx, "Teleported to " + Mc.name(target) + ".");
						})));

		staff.then(Commands.literal("tppos")
				.requires(src -> Permissions.check(src, Nodes.TP_POS))
				.then(Commands.argument("pos", Vec3Argument.vec3())
						.executes(ctx -> {
							ServerPlayer self = ctx.getSource().getPlayerOrException();
							Vec3 v = Vec3Argument.getVec3(ctx, "pos");
							audit(ctx, "/staff tppos");
							Mods.teleport().toPosition(self, v.x, v.y, v.z);
							return ok(ctx, "Teleported.");
						})));

		staff.then(Commands.literal("back")
				.requires(src -> Permissions.check(src, Nodes.TP))
				.executes(ctx -> {
					ServerPlayer self = ctx.getSource().getPlayerOrException();
					return Mods.teleport().back(self)
							? ok(ctx, "Back where you started.")
							: fail(ctx, "You have not teleported anywhere yet.");
				}));
	}

	// --------------------------------------------------------------- inspection

	private static void inspection(LiteralArgumentBuilder<CommandSourceStack> staff) {
		// Works on offline players too — the view falls back to their save file.
		staff.then(Commands.literal("invsee")
				.requires(src -> Permissions.check(src, Nodes.INVSEE))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							NameAndId target = singleProfile(ctx, "target");
							if (target == null) return fail(ctx, "Unknown player.");
							audit(ctx, "/staff invsee " + target.name());
							InvseeMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("lookup")
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(ctx -> openFile(ctx, "target"))));

		staff.then(Commands.literal("enderchest")
				.requires(src -> Permissions.check(src, Nodes.ENDERCHEST))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							NameAndId target = singleProfile(ctx, "target");
							if (target == null) return fail(ctx, "Unknown player.");
							audit(ctx, "/staff enderchest " + target.name());
							EnderChestMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("logs")
				.requires(src -> Permissions.check(src, Nodes.LOGS))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							NameAndId target = singleProfile(ctx, "target");
							if (target == null) return fail(ctx, "Unknown player.");
							audit(ctx, "/staff logs " + target.name());
							LogsMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("alts")
				.requires(src -> Permissions.check(src, Nodes.ALTS))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							NameAndId target = singleProfile(ctx, "target");
							if (target == null) return fail(ctx, "Unknown player.");
							audit(ctx, "/staff alts " + target.name());
							AltsMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("appeals")
				.requires(src -> Permissions.check(src, Nodes.APPEALS))
				.executes(ctx -> {
					AppealsMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}));

		staff.then(Commands.literal("seccheck")
				.requires(src -> Permissions.check(src, Nodes.SECURITY_CHECK))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
							audit(ctx, "/staff seccheck " + Mc.name(target));
							SecurityMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("scan")
				.requires(src -> Permissions.check(src, Nodes.ITEMSCAN))
				.executes(ctx -> {
					audit(ctx, "/staff scan");
					List<String> hits = Mods.security().sweep(ctx.getSource().getServer());
					if (hits.isEmpty()) {
						return ok(ctx, "Sweep clean — nothing impossible online.");
					}
					ctx.getSource().sendSuccess(() -> Theme.warn(
							"Sweep flagged " + hits.size() + " player(s):"), false);
					hits.forEach(h -> ctx.getSource().sendSuccess(() -> Theme.info("  " + h), false));
					return hits.size();
				}));

		// Asks the x-ray heuristic about one player, on demand, by name so it works on
		// somebody who has already logged off. The automatic sweep is deliberately quiet —
		// it only speaks above a confidence threshold and only on a large enough sample —
		// which makes "no alert" and "not working" look identical from the outside. This
		// always answers, and shows the numbers the verdict was built from.
		staff.then(Commands.literal("vault")
				.requires(src -> Permissions.check(src, Nodes.VAULT))
				.executes(ctx -> {
					audit(ctx, "/staff vault");
					VaultMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}));

		staff.then(Commands.literal("contraband")
				.requires(src -> Permissions.check(src, Nodes.VAULT))
				.executes(ctx -> {
					audit(ctx, "/staff contraband");
					ContrabandMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}));

		staff.then(Commands.literal("xray")
				.requires(src -> Permissions.check(src, Nodes.SECURITY_CHECK))
				.then(Commands.argument("player", StringArgumentType.word())
						.executes(ctx -> xray(ctx, 6))
						.then(Commands.argument("hours", IntegerArgumentType.integer(1, 168))
								.executes(ctx -> xray(ctx,
										IntegerArgumentType.getInteger(ctx, "hours"))))));

		// Preview mirrors the rollback tree exactly, and writes nothing. Rollback is the one
		// irreversible thing in the mod — it overwrites whatever is standing there now — and
		// the GUI has always shown a confirmation screen first. The commands did not, which
		// meant the fastest way to run one was also the only way to run one blind.
		staff.then(Commands.literal("preview")
				.requires(src -> Permissions.check(src, Nodes.ROLLBACK))
				.then(Commands.literal("area")
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> preview(ctx, null, StaffConfig.get().defaultRollbackMinutes))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
										.executes(ctx -> preview(ctx, null,
												IntegerArgumentType.getInteger(ctx, "minutes"))))))
				.then(Commands.argument("player", StringArgumentType.word())
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> preview(ctx, StringArgumentType.getString(ctx, "player"),
										StaffConfig.get().defaultRollbackMinutes))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
										.executes(ctx -> preview(ctx, StringArgumentType.getString(ctx, "player"),
												IntegerArgumentType.getInteger(ctx, "minutes")))))));

		staff.then(Commands.literal("rollback")
				.requires(src -> Permissions.check(src, Nodes.ROLLBACK))
				// Area form: undo everything here, whoever did it. The realistic case when
				// several accounts have hit one build and naming them one by one is hopeless.
				.then(Commands.literal("area")
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> rollbackArea(ctx, StaffConfig.get().defaultRollbackMinutes))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
										.executes(ctx -> rollbackArea(ctx,
												IntegerArgumentType.getInteger(ctx, "minutes"))))))
				.then(Commands.argument("player", StringArgumentType.word())
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> rollback(ctx, StaffConfig.get().defaultRollbackMinutes))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
										.executes(ctx -> rollback(ctx,
												IntegerArgumentType.getInteger(ctx, "minutes")))))));

		// Undo lives under `rollback` rather than at the top level so it tab-completes right
		// next to the thing it reverses.
		staff.then(Commands.literal("rollback")
				.requires(src -> Permissions.check(src, Nodes.ROLLBACK))
				.then(Commands.literal("undo")
						.executes(ctx -> undoRollback(ctx, 0L))
						.then(Commands.literal("list").executes(StaffCommands::listRestorePoints))
						.then(Commands.argument("id", IntegerArgumentType.integer(1))
								.executes(ctx -> undoRollback(ctx,
										IntegerArgumentType.getInteger(ctx, "id"))))));

		// Free-text search over both logs. greedyString so a whole query can be typed as one
		// argument — Brigadier would otherwise stop at the first space.
		staff.then(Commands.literal("search")
				.requires(src -> Permissions.check(src, Nodes.GRIEF_SEARCH))
				.then(Commands.argument("query", StringArgumentType.greedyString())
						.executes(StaffCommands::search)));

		staff.then(Commands.literal("purge")
				.requires(src -> Permissions.check(src, Nodes.GRIEF_PURGE))
				.then(Commands.argument("olderThan", StringArgumentType.word())
						.executes(ctx -> purge(ctx, null, true))
						.then(Commands.literal("confirm")
								.executes(ctx -> purge(ctx, null, false)))
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> purge(ctx,
										StringArgumentType.getString(ctx, "player"), true))
								.then(Commands.literal("confirm")
										.executes(ctx -> purge(ctx,
												StringArgumentType.getString(ctx, "player"), false))))));

		staff.then(Commands.literal("inspect")
				.requires(src -> Permissions.check(src, Nodes.INSPECT_MODE))
				.executes(StaffCommands::toggleInspect));
	}

	// ------------------------------------------------------------- rollback undo

	/**
	 * Puts the world back the way it was before a rollback.
	 * <p>
	 * With no id this undoes the most recent rollback that has not already been undone,
	 * which is the case that actually matters: somebody watches a repair land on the wrong
	 * build and wants it back within seconds, not after looking up a number.
	 */
	private static int undoRollback(CommandContext<CommandSourceStack> ctx, long id)
			throws CommandSyntaxException {

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		var points = Mods.grief().points();

		var point = id == 0L ? points.mostRecentUndoable() : points.byId(id);
		if (point == null) {
			return fail(ctx, id == 0L
					? "No rollback left to undo."
					: "No restore point #" + id + ".");
		}
		if (point.isUndone()) {
			return fail(ctx, "Restore point #" + point.id() + " was already undone by "
					+ point.undoneBy() + ".");
		}

		var result = points.undo(self.level(), point, Mc.name(self));
		if (result.didNothing()) {
			return fail(ctx, "Nothing was restored — the record may have been purged.");
		}

		audit(ctx, "/staff rollback undo " + point.id());
		Mods.alerts().onStaffAction(ctx.getSource().getServer(),
				"%s undid rollback #%d (%s)".formatted(
						Mc.name(self), point.id(), point.describe()));
		Sfx.bigSuccess(self);

		if (result.skipped() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn("  " + result.skipped()
					+ " block(s) could not be put back — their type no longer exists."), false);
		}
		return ok(ctx, "Undid rollback #" + point.id() + " — restored "
				+ result.restored() + " block(s).");
	}

	private static int listRestorePoints(CommandContext<CommandSourceStack> ctx) {
		var points = Mods.grief().points().recent(15);
		if (points.isEmpty()) {
			return fail(ctx, "No rollbacks on record. They are kept for "
					+ StaffConfig.get().rollbackPointRetentionDays + " days.");
		}

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Recent rollbacks", Theme.ACCENT)), false);

		for (var point : points) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  #%d  %s — %s%s".formatted(point.id(), TimeFormat.ago(point.createdAt()),
							point.describe(),
							point.isUndone() ? " (undone by " + point.undoneBy() + ")" : ""),
					point.isUndone() ? Theme.MUTED : Theme.TEXT), false);
		}
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Undo one with /staff rollback undo <id>", Theme.MUTED), false);
		return points.size();
	}

	// --------------------------------------------------------------------- searching

	/** How many rows a search will show before it stops being a list and starts being a dump. */
	private static final int SEARCH_LIMIT = 60;

	/**
	 * Runs a {@code key:value} query over the block and container logs.
	 * <p>
	 * Results print to chat rather than opening a screen: a search is usually the middle of
	 * an investigation, and chat is the one surface you can scroll back through while doing
	 * something else.
	 */
	private static int search(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer self = ctx.getSource().getPlayerOrException();
		String raw = StringArgumentType.getString(ctx, "query");

		LogQuery query = LogQuery.parse(raw);
		if (!query.isValid()) {
			for (String problem : query.problems()) {
				ctx.getSource().sendFailure(Theme.bad("  " + problem));
			}
			return fail(ctx, "Try: /staff search player:Steve action:break time:2d");
		}
		if (query.isEmpty()) {
			return fail(ctx, "That would match everything. Add a player, action or block.");
		}

		audit(ctx, "/staff search " + raw);
		ctx.getSource().sendSuccess(() -> Theme.info("Searching — " + query.describe() + "…"), false);

		Mods.grief().searchAsync(ctx.getSource().getServer(), self.level(), self.blockPosition(),
				query, SEARCH_LIMIT, hits -> showResults(self, query, hits));
		return 1;
	}

	private static void showResults(ServerPlayer viewer, LogQuery query, List<LogQuery.Hit> hits) {
		if (hits.isEmpty()) {
			viewer.sendSystemMessage(Theme.warn("Nothing matched " + query.describe() + "."));
			Sfx.deny(viewer);
			return;
		}

		viewer.sendSystemMessage(Theme.prefix()
				.append(Icon.text(hits.size() + (hits.size() == SEARCH_LIMIT ? "+" : "")
						+ " result(s)", Theme.ACCENT))
				.append(Icon.text(" — " + query.describe(), Theme.MUTED)));

		for (LogQuery.Hit hit : hits) {
			String what = hit.isItemMove()
					? hit.count() + "× " + shortId(hit.subject())
					: shortId(hit.subject());

			viewer.sendSystemMessage(Icon.text("  %s %s %s at %d, %d, %d — %s%s".formatted(
					hit.player(), hit.action().toLowerCase(java.util.Locale.ROOT), what,
					hit.x(), hit.y(), hit.z(), TimeFormat.ago(hit.at()),
					hit.rolledBack() ? " (rolled back)" : ""),
					hit.rolledBack() ? Theme.MUTED : Theme.TEXT));
		}

		if (hits.size() == SEARCH_LIMIT) {
			viewer.sendSystemMessage(Theme.warn(
					"  Showing the newest " + SEARCH_LIMIT + " — narrow the query for more."));
		}
		Sfx.success(viewer);
	}

	/**
	 * Trims a namespaced id down to something readable in a chat line.
	 * <p>
	 * Container rows store an encoded stack rather than an id, so anything that does not
	 * look like an id is left alone instead of being mangled into a misleading name.
	 */
	private static String shortId(String subject) {
		if (subject == null) return "?";
		if (subject.startsWith("{")) return "an item";
		int colon = subject.indexOf(':');
		return colon < 0 ? subject : subject.substring(colon + 1);
	}

	// ----------------------------------------------------------------------- purging

	/**
	 * Deletes log history early, previewing first unless {@code confirm} was typed.
	 * <p>
	 * Two-step by construction. Retention runs unattended and is reversible only by not
	 * having run it; this is a person choosing to destroy evidence, which is sometimes
	 * exactly right and is never something to do on a typo.
	 */
	private static int purge(CommandContext<CommandSourceStack> ctx, String player, boolean dryRun) {
		String spec = StringArgumentType.getString(ctx, "olderThan");
		Long window = DurationParser.parse(spec);
		if (window == null) {
			return fail(ctx, "Could not read \"" + spec + "\". Try 30d, 12h, 1w.");
		}

		String scope = player == null ? "everyone" : player;
		GriefModule.PurgeResult result = Mods.grief().purge(window, player, dryRun);

		if (result.total() == 0) {
			return fail(ctx, "Nothing logged for " + scope + " is older than " + spec + ".");
		}

		if (dryRun) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"Would delete %d block row(s) and %d container row(s) for %s older than %s."
							.formatted(result.blockRows(), result.containerRows(), scope, spec)), false);
			ctx.getSource().sendSuccess(() -> Icon.text("  This cannot be undone. Run: /staff purge "
					+ spec + (player == null ? "" : " " + player) + " confirm", Theme.MUTED), false);
			return result.total();
		}

		audit(ctx, "/staff purge " + spec + " " + scope);
		Mods.alerts().onStaffAction(ctx.getSource().getServer(),
				"%s purged %d log row(s) for %s older than %s".formatted(
						ctx.getSource().getTextName(), result.total(), scope, spec));

		return ok(ctx, "Deleted %d block row(s) and %d container row(s)."
				.formatted(result.blockRows(), result.containerRows()));
	}

	// ------------------------------------------------------------------ inspect mode

	private static int toggleInspect(CommandContext<CommandSourceStack> ctx)
			throws CommandSyntaxException {

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		boolean on = StaffToolset.toggleInspectMode(self);

		if (on) {
			ctx.getSource().sendSuccess(() -> Theme.good(
					"Inspect mode on — clicking any block shows its history."), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Blocks will not break or open while this is on.", Theme.MUTED), false);
			Sfx.toggleOn(self);
		} else {
			ctx.getSource().sendSuccess(() -> Theme.info("Inspect mode off."), false);
			Sfx.toggleOff(self);
		}
		return 1;
	}

	/**
	 * Prints the x-ray score for one player over the last {@code hours}.
	 * <p>
	 * The sample size is reported next to the configured floor on purpose. Far and away the
	 * most common reason nobody has ever seen an x-ray alert is that no player on a small or
	 * new server has mined enough blocks to clear that floor yet, and a bare "0%" gives no
	 * hint of that.
	 */
	private static int xray(CommandContext<CommandSourceStack> ctx, int hours) {
		String name = StringArgumentType.getString(ctx, "player");
		audit(ctx, "/staff xray " + name);

		StaffConfig cfg = StaffConfig.get();
		XrayDetector.Report report = XrayDetector.explain(name, hours * 3_600_000L);
		int sample = report.oreCount() + report.fillerCount();

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("X-ray report for ", Theme.MUTED))
				.append(Icon.text(name, Theme.ACCENT))
				.append(Icon.text(" — last " + hours + "h", Theme.MUTED)), false);

		ctx.getSource().sendSuccess(() -> Icon.text("  Confidence: ", Theme.MUTED)
				.append(Icon.text(report.confidence() + "%",
						report.confidence() >= cfg.xrayAlertConfidence ? Theme.BAD
								: report.confidence() > 0 ? Theme.WARN : Theme.GOOD))
				.append(Icon.text("  (alerts at " + cfg.xrayAlertConfidence + "%)", Theme.MUTED)), false);

		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Sample: %d blocks (%d ore, %d filler)".formatted(sample, report.oreCount(), report.fillerCount()),
				Theme.MUTED), false);

		// What they found, not just how much. This line answers the question staff actually
		// have — "is this a lot of diamond or a lot of coal" — which the percentage cannot.
		if (report.oreCount() > 0) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Ore: " + report.oreBreakdown(6), Theme.TEXT), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Rarity: %.1f of 10 (an ordinary session sits near 2)"
							.formatted(report.rarityIndex()), Theme.MUTED), false);
		}

		if (sample < cfg.xraySampleFloor) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  Below the sample floor of " + cfg.xraySampleFloor + " — the automatic sweep "
							+ "stays silent on this player until they mine more."), false);
		}

		if (report.reasons().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Nothing unusual.", Theme.GOOD), false);
		} else {
			report.reasons().forEach(r ->
					ctx.getSource().sendSuccess(() -> Icon.text("  • " + r, Theme.TEXT), false));
		}

		explainSilence(ctx, report, sample, cfg);
		windowComparison(ctx, name, cfg);
		return Math.max(1, report.confidence());
	}

	/**
	 * The same player scored over an hour, six hours, a day and a week.
	 * <p>
	 * One window hides two opposite things. Short, and an honest hour either side of a
	 * cheating run drowns it; long, and a twenty-minute burst of pure diamond averages into
	 * nothing. Four spans side by side make the shape obvious, and a row that is far worse
	 * than the ones around it is a timestamp — it tells staff which session to go and read.
	 */
	private static void windowComparison(CommandContext<CommandSourceStack> ctx, String name,
			StaffConfig cfg) {

		var windows = XrayDetector.acrossWindows(name);
		var worst = XrayDetector.worst(windows);
		if (worst.report().confidence() == 0) return;

		ctx.getSource().sendSuccess(() -> Icon.text("  Across time:", Theme.MUTED), false);
		for (var window : windows) {
			var report = window.report();
			int sample = report.oreCount() + report.fillerCount();
			ctx.getSource().sendSuccess(() -> Icon.text("    %-13s %3d%%  (%d blocks)"
									.formatted(window.label(), report.confidence(), sample),
							report.confidence() >= cfg.xrayAlertConfidence ? Theme.BAD
									: report.confidence() >= cfg.xrayNoticeConfidence ? Theme.WARN
									: Theme.MUTED),
					false);
		}

		if (worst.report().confidence() >= cfg.xrayNoticeConfidence) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"    Worst span: " + worst.label() + " — start there.", Theme.TEXT), false);
		}
	}

	/**
	 * Says which of the three things is keeping the detector quiet about this player.
	 * <p>
	 * Silence is the detector's normal state, and that makes a quiet server and a broken
	 * feature look identical from the outside. There are only ever three reasons — not enough
	 * mining, a score under the line, or the sweep being switched off — and naming the one
	 * that applies is the difference between trusting the tool and assuming it never ran.
	 */
	private static void explainSilence(CommandContext<CommandSourceStack> ctx,
			XrayDetector.Report report, int sample, StaffConfig cfg) {

		if (report.confidence() >= cfg.xrayAlertConfidence) return;   // it would have alerted

		ctx.getSource().sendSuccess(() -> Icon.text("  Why you have not been alerted:",
				Theme.MUTED), false);

		if (cfg.xraySweepMinutes <= 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"    The automatic sweep is off (xraySweepMinutes is 0)."), false);
		} else if (sample < cfg.xraySampleFloor) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"    Only %d of the %d blocks the sweep needs before it will commit."
							.formatted(sample, cfg.xraySampleFloor), Theme.MUTED), false);
		} else if (report.confidence() == 0) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"    Enough data, and nothing in it looks guided.", Theme.GOOD), false);
		} else {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"    %d%% is under the %d%% alert line%s."
							.formatted(report.confidence(), cfg.xrayAlertConfidence,
									cfg.xrayNoticeConfidence > 0
											&& report.confidence() >= cfg.xrayNoticeConfidence
											? " — staff did get the quiet notice" : ""),
					Theme.MUTED), false);
		}

		// The one that catches people out, because it is the natural way to test.
		ctx.getSource().sendSuccess(() -> Icon.text(
				"    Note: ore they placed themselves is excluded, so seeding a wall", Theme.MUTED), false);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"    with ore and mining it back proves nothing.", Theme.MUTED), false);
	}

	/**
	 * Reports what a rollback would change, without changing it.
	 *
	 * @param player null for "everything here, whoever did it"
	 */
	private static int preview(CommandContext<CommandSourceStack> ctx, String player, int minutes)
			throws CommandSyntaxException {

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		String scope = player == null ? "everyone" : player;
		audit(ctx, "/staff preview " + scope + " " + radius + " " + minutes);

		GriefModule.RollbackResult r = Mods.grief().rollback(
				self.level(), player, self.blockPosition(), radius, minutes * 60_000L, true);

		if (r.reverted() == 0 && r.itemsReturned() == 0) {
			return fail(ctx, "Nothing to roll back within " + radius + " blocks.");
		}

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Rollback preview", Theme.ACCENT))
				.append(Icon.text(" — nothing has been changed", Theme.MUTED)), false);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Scope: " + scope + ", " + radius + " blocks, last "
						+ TimeFormat.duration(minutes * 60_000L), Theme.TEXT), false);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Block changes to undo: " + r.reverted(), Theme.TEXT), false);

		if (r.itemsReturned() > 0) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Container stacks to put back: " + r.itemsReturned(), Theme.TEXT), false);
		}
		// Counts say how big; the list says what. Only the second one tells you whether this
		// is the rollback you meant to run.
		if (!r.restoring().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Putting back:", Theme.TEXT), false);
			r.restoring().stream().limit(8).forEach(item -> ctx.getSource().sendSuccess(
					() -> Icon.text("    " + item.count() + "× " + shortId(item.itemId()),
							Theme.MUTED), false));
			if (r.restoring().size() > 8) {
				ctx.getSource().sendSuccess(() -> Icon.text(
						"    … and " + (r.restoring().size() - 8) + " more kinds", Theme.MUTED), false);
			}
		}
		// Drawn in the world, not just counted in chat. The panel has done this from the
		// start and the command never did, which made the two disagree about what a preview
		// is: a list of numbers tells you how big the rollback is, and the blocks in front of
		// you tell you whether it is the right one. The second is the question a radius
		// actually poses.
		int drawn = Mods.grief().preview().show(self, (ServerLevel) self.level(), r.proposed());
		if (drawn > 0) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Showing " + drawn + " block(s) around you — only you can see them, and "
							+ "nothing has been written.", Theme.ACCENT), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  They clear themselves shortly, or run the rollback to make them real.",
					Theme.MUTED), false);
		}

		// The other half of the operation, and the half that cannot be undone by running it
		// again. Putting blocks back is visible and reversible; taking items off a player who
		// is not online to see it happen is neither, and the preview used to show only the
		// first. A rollback is two things, so a preview of one of them is not a preview.
		if (!r.charges().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Who would be charged:", Theme.TEXT), false);
			for (var charge : r.charges()) {
				ctx.getSource().sendSuccess(() -> Icon.text(
						"    " + charge.player() + (charge.online() ? "" : " (offline)")
								+ " — " + charge.items() + ", " + charge.route(),
						charge.online() ? Theme.MUTED : Theme.BAD), false);
			}
		}

		if (r.itemsDeferred() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  " + r.itemsDeferred() + " stack(s) have nowhere to go — those chests are full."), false);
		}
		if (r.skipped() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  " + r.skipped() + " entry/entries name blocks that no longer exist."), false);
		}
		ctx.getSource().sendSuccess(() -> Theme.warn(
				"  Anything built on top of these blocks would be overwritten."), false);

		String apply = player == null
				? "/staff rollback area " + radius + " " + minutes
				: "/staff rollback " + player + " " + radius + " " + minutes;
		ctx.getSource().sendSuccess(() -> Icon.text("  Run: " + apply, Theme.MUTED), false);
		return r.reverted();
	}

	private static int rollbackArea(CommandContext<CommandSourceStack> ctx, int minutes)
			throws CommandSyntaxException {

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		audit(ctx, "/staff rollback area " + radius + " " + minutes);

		GriefModule.RollbackResult result = Mods.grief().rollback(
				self.level(), null, self.blockPosition(), radius, minutes * 60_000L, false,
				ctx.getSource().getTextName());

		if (result.reverted() == 0) {
			return fail(ctx, "Nothing to roll back within " + radius + " blocks.");
		}
		Mods.alerts().onStaffAction(ctx.getSource().getServer(),
				"%s rolled back %d change(s) in a %d-block area".formatted(
						ctx.getSource().getTextName(), result.reverted(), radius));
		Sfx.bigSuccess(self);
		reportReclaim(ctx, result);
		return ok(ctx, "Reverted " + result.reverted() + " change(s) by everyone here.");
	}

	/**
	 * The parts of a rollback nobody can see happen.
	 * <p>
	 * Reverted blocks are visible from where you are standing; items taken back out of a
	 * chest three hundred blocks away, a debt booked against an offline player, and a stack
	 * that quietly would not fit are not. Every one of those is reported, because the
	 * alternative is staff discovering them later and not knowing whether the tool did it.
	 */
	private static void reportReclaim(CommandContext<CommandSourceStack> ctx,
			GriefModule.RollbackResult result) {

		if (result.dropsRemoved() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.info(
					"Reclaimed " + result.dropsRemoved() + " dropped item(s)."), false);
		}
		if (result.bankedRemoved() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.info("Took back " + result.bankedRemoved()
					+ " item(s) stashed in chests elsewhere."), false);
		}
		if (result.debitsQueued() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.info(result.debitsQueued()
					+ " item(s) owed by an offline player — collected on their next login."), false);
		}
		if (result.itemsReturned() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.info(
					"Put " + result.itemsReturned() + " stack(s) back into containers."), false);
		}
		if (result.itemsDeferred() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(result.itemsDeferred()
					+ " stack(s) would not fit — clear space and run the rollback again."), false);
		}
	}

	private static int rollback(CommandContext<CommandSourceStack> ctx, int minutes)
			throws CommandSyntaxException {

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		String player = StringArgumentType.getString(ctx, "player");
		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		audit(ctx, "/staff rollback " + player + " " + radius + " " + minutes);

		GriefModule.RollbackResult result = Mods.grief().rollback(
				self.level(), player, self.blockPosition(), radius, minutes * 60_000L, false,
				ctx.getSource().getTextName());

		if (result.reverted() == 0) {
			return fail(ctx, "Nothing of " + player + "'s to roll back within " + radius + " blocks.");
		}
		Mods.alerts().onStaffAction(ctx.getSource().getServer(),
				"%s rolled back %d change(s) by %s".formatted(
						ctx.getSource().getTextName(), result.reverted(), player));
		Sfx.bigSuccess(self);
		reportReclaim(ctx, result);
		return ok(ctx, "Reverted " + result.reverted() + " change(s) by " + player + ".");
	}

	// ------------------------------------------------------------------- server

	private static void serverControl(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("spy")
				.requires(src -> Permissions.check(src, Nodes.SPY))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					boolean on = Mods.control().toggleSpy(p);
					return ok(ctx, "Command spy " + (on ? "on." : "off."));
				}));

		staff.then(Commands.literal("clearchat")
				.requires(src -> Permissions.check(src, Nodes.CHAT_CONTROL))
				.executes(ctx -> {
					audit(ctx, "/staff clearchat");
					Mods.control().clearChat(ctx.getSource().getServer(), ctx.getSource().getTextName());
					return 1;
				}));

		staff.then(Commands.literal("lockchat")
				.requires(src -> Permissions.check(src, Nodes.CHAT_CONTROL))
				.executes(ctx -> {
					audit(ctx, "/staff lockchat");
					Mods.control().setChatMuted(ctx.getSource().getServer(), true);
					return 1;
				}));

		staff.then(Commands.literal("unlockchat")
				.requires(src -> Permissions.check(src, Nodes.CHAT_CONTROL))
				.executes(ctx -> {
					audit(ctx, "/staff unlockchat");
					Mods.control().setChatMuted(ctx.getSource().getServer(), false);
					return 1;
				}));

		staff.then(Commands.literal("broadcast")
				.requires(src -> Permissions.check(src, Nodes.BROADCAST))
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(ctx -> {
							String message = StringArgumentType.getString(ctx, "message");
							audit(ctx, "/staff broadcast " + message);
							Mods.control().broadcast(ctx.getSource().getServer(), message);
							return 1;
						})));

		staff.then(Commands.literal("maintenance")
				.requires(src -> Permissions.check(src, Nodes.MAINTENANCE))
				.executes(ctx -> {
					audit(ctx, "/staff maintenance");
					boolean on = Mods.control().toggleMaintenance(ctx.getSource().getServer());
					return ok(ctx, on
							? "Maintenance mode on — the server list now shows the maintenance notice."
							: "Maintenance mode off — the normal MOTD is back.");
				}));

		staff.then(Commands.literal("stats")
				.requires(src -> Permissions.check(src, Nodes.ANALYTICS))
				.then(Commands.argument("player", StringArgumentType.word())
						.executes(ctx -> {
							var st = Mods.analytics().forStaff(StringArgumentType.getString(ctx, "player"));
							ctx.getSource().sendSuccess(() -> Theme.info(
									"%s — %d punishment(s), %d report(s) handled, %d command(s)"
											.formatted(st.name(), st.punishments(),
													st.reportsHandled(), st.commands())), false);
							return 1;
						})));

		staff.then(Commands.literal("backup")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(StaffCommands::backupNow));

		staff.then(Commands.literal("export")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(StaffCommands::exportNow));

		staff.then(Commands.literal("nbt")
				.requires(src -> Permissions.check(src, Nodes.INVSEE))
				.executes(StaffCommands::inspectHeld));

		staff.then(Commands.literal("owed")
				.requires(src -> Permissions.check(src, Nodes.ROLLBACK))
				.executes(StaffCommands::owedList)
				// Symmetrical with /staff rollback undo. A debit is the half of a rollback
				// that removes items from somebody, and it was the half with no way back.
				.then(Commands.literal("undo")
						.then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
								.executes(ctx -> owedUndo(ctx, false))
								.then(Commands.literal("confirm")
										.executes(ctx -> owedUndo(ctx, true)))))
				.then(Commands.literal("forgive")
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> owedForgive(ctx, false))
								.then(Commands.literal("confirm")
										.executes(ctx -> owedForgive(ctx, true)))))
				.then(Commands.argument("player", StringArgumentType.word())
						.executes(StaffCommands::owedForPlayer)));

		staff.then(Commands.literal("anticheat")
				.requires(src -> Permissions.check(src, Nodes.SECURITY_CHECK))
				.executes(StaffCommands::antiCheatStatus)
				.then(Commands.literal("test")
						.requires(src -> Permissions.check(src, Nodes.RELOAD))
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> antiCheatTest(ctx, 90))
								.then(Commands.argument("confidence",
												IntegerArgumentType.integer(0, 100))
										.executes(ctx -> antiCheatTest(ctx,
												IntegerArgumentType.getInteger(ctx, "confidence"))))))
				.then(Commands.argument("player", StringArgumentType.word())
						.executes(StaffCommands::antiCheatForPlayer)));

		staff.then(Commands.literal("selftest")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(StaffCommands::selfTest));

		registerPerms(staff);

		staff.then(Commands.literal("reload")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(ctx -> {
					audit(ctx, "/staff reload");
					StaffConfig.load();
					// Group assignments are edited by hand as often as through the command,
					// so a reload that left them stale would be a trap.
					PermissionGroups.load(Permissions.hasProvider());
					return ok(ctx, "Config and permission groups reloaded from disk.");
				}));

		staff.then(Commands.literal("status")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(StaffCommands::status));
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * A {@link GameProfileArgument} can resolve to several profiles (selectors). Staff
	 * actions are deliberately one-at-a-time, so anything ambiguous is refused rather
	 * than applied to whoever happens to be first.
	 */
	private static NameAndId singleProfile(CommandContext<CommandSourceStack> ctx, String argument)
			throws CommandSyntaxException {

		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, argument);
		if (profiles.size() != 1) return null;
		return profiles.iterator().next();
	}

	// ----------------------------------------------------------------------- status

	/**
	 * What state StaffCore is actually in.
	 * <p>
	 * The mixin health lives here as well as on the diagnostics screen, because this is what
	 * somebody runs when a feature "isn't working" — and a non-fatal mixin that failed to
	 * apply is invisible from every other angle.
	 */
	private static int status(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		var broken = StaffCore.brokenFeatures();
		var findings = io.github.alphain24.staffcore.diagnostic.StartupCheck.report();

		ctx.getSource().sendSuccess(() -> Theme.info(
				"StaffCore — %d module(s), TPS %.2f, storage %s"
						.formatted(StaffCore.modules().count(),
								Mods.control().currentTps(server),
								StaffCore.storage().isReady() ? "ready" : "unavailable")), false);

		// Three numbers that separate the three reasons a grief log can look empty: the event
		// never fired, the write failed, or the query is not finding rows that exist. Without
		// them all three present as the same blank screen.
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Block log: " + Mods.grief().logCounters(), Theme.MUTED), false);

		if (findings.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Hooks: the startup check has not run yet.", Theme.MUTED), false);
		} else if (broken.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Hooks: all " + findings.size() + " present and applied.", Theme.GOOD), false);
		} else {
			ctx.getSource().sendSuccess(() -> Theme.bad(
					"  Hooks: %d of %d broken — the server runs without them."
							.formatted(broken.size(), findings.size())), false);
			broken.forEach(feature -> ctx.getSource().sendSuccess(
					() -> Icon.text("    • " + feature, Theme.BAD), false));
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Detail on the Server Status button in /staff.", Theme.MUTED), false);
		}

		int backups = StaffCore.storage().backups().size();
		if (StaffCore.storage().isReady()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Backups: " + backups + " on disk.", Theme.MUTED), false);
		}
		return broken.isEmpty() ? 1 : 0;
	}

	// ---------------------------------------------------------------------- backups

	/**
	 * Writes a database backup on demand.
	 * <p>
	 * One is taken on every server start, which covers the ordinary case. This is for the
	 * other one: about to run a mass rollback, about to purge history, about to try
	 * something. A copy taken deliberately beforehand is worth more than any amount of
	 * corruption handling afterwards.
	 */
	private static int backupNow(CommandContext<CommandSourceStack> ctx) {
		if (!StaffCore.storage().isReady()) {
			return fail(ctx, "Storage is not available — there is nothing to back up.");
		}

		java.nio.file.Path out = StaffCore.storage().backup("requested by "
				+ ctx.getSource().getTextName());
		if (out == null) {
			return fail(ctx, "Backup failed. The server log says why.");
		}

		audit(ctx, "/staff backup");
		int kept = StaffCore.storage().backups().size();
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Keeping the newest " + StaffConfig.get().databaseBackups
						+ "; " + kept + " on disk.", Theme.MUTED), false);
		return ok(ctx, "Backup written: " + out.getFileName());
	}

	/**
	 * Dumps every table to CSV.
	 * <p>
	 * Separate from a backup because they answer different questions. A backup restores; an
	 * export is readable — which is what a data request, an audit, or a look at the numbers in
	 * a spreadsheet actually needs.
	 */
	/**
	 * Opens the component breakdown for whatever the caller is holding.
	 * <p>
	 * The screens reach this by middle-clicking a slot, which is the faster route when you
	 * are already looking at somebody's inventory. This is for the other case: an item in
	 * your own hand, usually one just confiscated or picked up off the floor, where opening
	 * a menu about somebody else to read it would be a strange detour.
	 */
	private static int inspectHeld(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Theme.bad("Only a player can inspect a held item."));
			return 0;
		}

		net.minecraft.world.item.ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) held = player.getOffhandItem();
		if (held.isEmpty()) {
			ctx.getSource().sendFailure(Theme.bad("Hold the item you want to read."));
			return 0;
		}

		io.github.alphain24.staffcore.gui.menu.ItemDetailsMenu.open(player, held, "your hand", null);
		return 1;
	}

	/**
	 * Proves the mod works, rather than only that it started.
	 * <p>
	 * The health check answers whether our hooks attached. This answers whether the things
	 * behind them run: the database opens and is at the right schema version, the command
	 * tree registered, the contraband list resolves against the real registry, the detector
	 * scores, and a backup and an export both actually write files. Worth running after any
	 * update, and the fastest way to turn "something feels broken" into a specific answer.
	 */
	private static int selfTest(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		audit(ctx, "/staff selftest");

		var results = io.github.alphain24.staffcore.diagnostic.SelfTest.run(server);
		long failed = results.stream().filter(r -> !r.passed()).count();

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Self test — ", Theme.MUTED))
				.append(Icon.text((results.size() - failed) + "/" + results.size() + " passed",
						failed == 0 ? Theme.GOOD : Theme.BAD)), false);

		for (var result : results) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  " + (result.passed() ? "✓ " : "✗ ") + result.name() + " — "
							+ result.detail(),
					result.passed() ? Theme.MUTED : Theme.BAD), false);
		}

		if (failed > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  A failed check names the thing that is broken. The server keeps running "
							+ "— everything that still works is unaffected."), false);
		}
		return failed == 0 ? 1 : 0;
	}

	/**
	 * Bridge state and the newest findings across everybody.
	 * <p>
	 * The findings were being recorded and alerted on with nowhere to read them back, which
	 * made the whole thing write-only: an alert scrolls past in chat and the record it came
	 * from was unreachable. This is the way in.
	 */
	private static int antiCheatStatus(CommandContext<CommandSourceStack> ctx) {
		var bridge = Mods.antiCheat();
		StaffConfig cfg = StaffConfig.get();

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Anti-cheat bridge", Theme.ACCENT)), false);

		ctx.getSource().sendSuccess(() -> Icon.text("  Bridge: ", Theme.MUTED)
				.append(Icon.text(cfg.antiCheatBridge ? "on" : "off",
						cfg.antiCheatBridge ? Theme.GOOD : Theme.BAD))
				.append(Icon.text("   alerts at " + cfg.antiCheatAlertConfidence
						+ "%   kept " + cfg.antiCheatRetentionDays + "d", Theme.MUTED)), false);

		var attached = bridge.attachedProviders();
		ctx.getSource().sendSuccess(() -> Icon.text("  Providers: ", Theme.MUTED)
				.append(Icon.text(attached.isEmpty() ? "none attached" : String.join(", ", attached),
						attached.isEmpty() ? Theme.WARN : Theme.GOOD)), false);

		if (attached.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Findings can still be pushed in through the API — see the handbook.",
					Theme.MUTED), false);
		}

		var recent = bridge.recent(0, 10);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Findings on record: " + bridge.count(), Theme.MUTED), false);

		if (recent.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Nothing recorded yet. /staff anticheat test <player> proves the pipeline.",
					Theme.MUTED), false);
			return 1;
		}

		for (var event : recent) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + TimeFormat.ago(event.at()) + "  ",
							Theme.MUTED)
					.append(Icon.text(event.playerName(), Theme.TEXT))
					.append(Icon.text("  " + event.headline(),
							event.kind().isAction() ? Theme.BAD : Theme.WARN)), false);
		}
		return 1;
	}

	/** Everything one player has been flagged for, and which checks they trip most. */
	private static int antiCheatForPlayer(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		var bridge = Mods.antiCheat();

		var events = bridge.forPlayer(name, 20);
		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Anti-cheat findings for ", Theme.MUTED))
				.append(Icon.text(name, Theme.ACCENT))
				.append(Icon.text("  (" + bridge.countFor(name) + " total)", Theme.MUTED)), false);

		if (events.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Nothing on file.", Theme.GOOD), false);
			return 1;
		}

		var tally = bridge.checkTally(name, 5);
		if (!tally.isEmpty()) {
			String summary = tally.entrySet().stream()
					.map(e -> e.getValue() + "× " + e.getKey())
					.reduce((a, b) -> a + ", " + b).orElse("");
			ctx.getSource().sendSuccess(() -> Icon.text("  Most tripped: " + summary,
					Theme.MUTED), false);
		}

		for (var event : events) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + TimeFormat.ago(event.at()) + "  ",
							Theme.MUTED)
					.append(Icon.text("[" + event.provider() + "] ", Theme.MUTED))
					.append(Icon.text(event.headline(),
							event.kind().isAction() ? Theme.BAD : Theme.WARN)), false);
		}
		return 1;
	}

	/**
	 * Pushes a synthetic finding through the whole pipeline.
	 * <p>
	 * Without an anti-cheat installed there is no way to know whether the bridge works until
	 * the day one matters, and "we will find out when it happens" is a poor plan for the part
	 * of the system that exists to tell you something is happening. This exercises the real
	 * path — stored, thresholded, alerted — so an integration can be proved before it is
	 * relied on.
	 * <p>
	 * The finding is recorded like any other and clearly labelled, so it is obvious in the
	 * log that it was a test rather than something the server saw.
	 */
	private static int antiCheatTest(CommandContext<CommandSourceStack> ctx, int confidence) {
		String name = StringArgumentType.getString(ctx, "player");
		audit(ctx, "/staff anticheat test " + name);

		Mods.antiCheat().report("StaffCore-test", name,
				io.github.alphain24.staffcore.modules.anticheat.AntiCheatEvent.Kind.DETECTION,
				"SelfTest", confidence,
				"synthetic finding raised by " + ctx.getSource().getTextName() + " to test the bridge");

		StaffConfig cfg = StaffConfig.get();
		ctx.getSource().sendSuccess(() -> Theme.good(
				"Pushed a test finding for " + name + " at " + confidence + "%."), false);

		if (!cfg.antiCheatBridge) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  The bridge is off, so it was discarded. Set antiCheatBridge to true."), false);
		} else if (confidence < cfg.antiCheatAlertConfidence) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Below the alert threshold of " + cfg.antiCheatAlertConfidence
							+ "%, so it was recorded without alerting — as designed. "
							+ "Check /staff anticheat " + name + ".", Theme.MUTED), false);
		} else {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Above the threshold, so staff chat should have it too.", Theme.MUTED), false);
		}
		return 1;
	}

	/** Everybody who still owes items from a rollback or a theft undo. */
	private static int owedList(CommandContext<CommandSourceStack> ctx) {
		var debts = StaffCore.pending().outstanding(20);

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Outstanding debts", Theme.ACCENT)), false);

		if (debts.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Nobody owes anything.", Theme.GOOD), false);
			return 1;
		}

		for (var debt : debts) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + debt.ownerName(), Theme.TEXT)
					.append(Icon.text("  " + debt.items() + " item(s) across "
							+ debt.rows() + " entr(y/ies)", Theme.WARN)), false);
		}
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  /staff owed forgive <player> writes one off.", Theme.MUTED), false);
		return 1;
	}

	/** What one player owes, item by item. */
	private static int owedForPlayer(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		MinecraftServer server = ctx.getSource().getServer();

		var id = io.github.alphain24.staffcore.util.PlayerLookup.uuid(server, name);
		if (id.isEmpty()) {
			ctx.getSource().sendFailure(Theme.bad("Never seen a player called " + name + "."));
			return 0;
		}

		var rows = StaffCore.pending().forPlayer(id.get());
		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Owed by ", Theme.MUTED))
				.append(Icon.text(name, Theme.ACCENT)), false);

		if (rows.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Nothing.", Theme.GOOD), false);
			return 1;
		}

		for (var row : rows) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + row.describe(server) + "  ",
							row.kind() == io.github.alphain24.staffcore.storage.PendingActions.Kind.GIVE
									? Theme.GOOD : Theme.WARN)
					.append(Icon.text(row.reason() == null ? "" : row.reason(), Theme.MUTED)), false);
		}
		return 1;
	}

	/**
	 * Writes off what a player owes.
	 * <p>
	 * Needed because a debt can outlive the reason for it. A rollback run during testing, or
	 * one aimed at the wrong player, leaves somebody nagged on every login with no way to
	 * settle it — the items are frequently ones they never had, so paying is not possible
	 * either. Confirmed rather than instant, since forgiving a real griefer's debt by
	 * mistyping a name should take more than one keystroke.
	 */
	/**
	 * Gives back what a debit took.
	 * <p>
	 * The counterpart to {@code /staff rollback undo}, and the reason it had to exist: a
	 * rollback is two operations, and only one of them could be taken back. Putting blocks
	 * back is visible and reversible by running the undo; removing items from somebody's
	 * inventory on the strength of a log query was neither, and the person it goes wrong for
	 * is most often the one who was offline when it happened.
	 * <p>
	 * Shows what it would do before doing it, for the same reason forgiveness does: the
	 * argument is an opaque number, and mistyping one should not cost items.
	 */
	private static int owedUndo(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
		long id = com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id");
		var gateway = io.github.alphain24.staffcore.inventory.InventoryGateway.describeReversal(id);

		if (!gateway.possible()) {
			ctx.getSource().sendFailure(Theme.bad(gateway.problem()));
			return 0;
		}

		if (!confirmed) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"Debit #" + id + " took " + gateway.items() + " from "
							+ gateway.targetName() + "."), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  /staff owed undo " + id + " confirm  gives it back.", Theme.MUTED), false);
			return 1;
		}

		MinecraftServer server = ctx.getSource().getServer();
		ServerPlayer target = server.getPlayerList().getPlayer(gateway.targetId());
		if (target == null) {
			ctx.getSource().sendFailure(Theme.bad(
					gateway.targetName() + " is offline. Giving items back needs them here, "
							+ "so this can wait until they next log in."));
			return 0;
		}

		audit(ctx, "/staff owed undo " + id);
		var outcome = io.github.alphain24.staffcore.inventory.InventoryGateway.reverse(
				id, target, Mc.name(ctx.getSource().getPlayer()));

		if (outcome.wasRefused()) {
			ctx.getSource().sendFailure(Theme.bad("Refused: " + outcome.refused()));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Theme.good(
				"Gave " + outcome.items() + " item(s) back to " + gateway.targetName() + "."), true);
		return 1;
	}

	private static int owedForgive(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
		String name = StringArgumentType.getString(ctx, "player");
		MinecraftServer server = ctx.getSource().getServer();

		var id = io.github.alphain24.staffcore.util.PlayerLookup.uuid(server, name);
		if (id.isEmpty()) {
			ctx.getSource().sendFailure(Theme.bad("Never seen a player called " + name + "."));
			return 0;
		}

		var rows = StaffCore.pending().forPlayer(id.get()).stream()
				.filter(r -> r.kind() == io.github.alphain24.staffcore.storage.PendingActions.Kind.DEBIT)
				.toList();

		if (rows.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Theme.info(name + " does not owe anything."), false);
			return 1;
		}

		int items = rows.stream().mapToInt(
				io.github.alphain24.staffcore.storage.PendingActions.Entry::count).sum();

		if (!confirmed) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					name + " owes " + items + " item(s) across " + rows.size() + " entr(y/ies)."), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  /staff owed forgive " + name + " confirm  writes it off for good.",
					Theme.MUTED), false);
			return 1;
		}

		audit(ctx, "/staff owed forgive " + name);
		int written = StaffCore.pending().forgive(id.get());

		ctx.getSource().sendSuccess(() -> Theme.good(
				"Wrote off " + written + " item(s) owed by " + name + "."), false);

		if (server != null) {
			Mods.alerts().onStaffAction(server, ctx.getSource().getTextName()
					+ " wrote off " + written + " item(s) owed by " + name);
		}
		return 1;
	}

	private static int exportNow(CommandContext<CommandSourceStack> ctx) {
		if (!StaffCore.storage().isReady()) {
			return fail(ctx, "Storage is not available — there is nothing to export.");
		}

		java.nio.file.Path out = StaffCore.storage().export();
		if (out == null) {
			return fail(ctx, "Export failed. The server log says why.");
		}

		audit(ctx, "/staff export");
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  One CSV per table, under the world folder.", Theme.MUTED), false);
		return ok(ctx, "Exported to " + out.getFileName());
	}

	// ------------------------------------------------------------------ permissions

	/**
	 * Manages the built-in permission groups, for servers with no permissions mod.
	 * <p>
	 * Every subcommand refuses outright when a permissions API is installed. Editing a file
	 * that is not being consulted would appear to work and change nothing, which is a worse
	 * outcome than being told to go and use LuckPerms.
	 */
	private static void registerPerms(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("perms")
				.requires(src -> Permissions.check(src, Nodes.PERMS_ADMIN))
				.executes(StaffCommands::permsList)
				.then(Commands.literal("list").executes(StaffCommands::permsList))
				.then(Commands.literal("set")
						.then(Commands.argument("player", StringArgumentType.word())
								.then(Commands.argument("group", StringArgumentType.word())
										.executes(ctx -> permsSet(ctx,
												StringArgumentType.getString(ctx, "group"))))))
				.then(Commands.literal("unset")
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> permsSet(ctx, null)))));
	}

	/** True when the built-in groups are not the authority, with an explanation. */
	private static boolean permsUnavailable(CommandContext<CommandSourceStack> ctx) {
		if (PermissionGroups.get() != null) return false;

		fail(ctx, Permissions.hasProvider()
				? "A permissions mod is installed — manage staff there, not here."
				: "staffcore-permissions.json could not be read. See the server log.");
		return true;
	}

	private static int permsList(CommandContext<CommandSourceStack> ctx) {
		if (permsUnavailable(ctx)) return 0;
		PermissionGroups groups = PermissionGroups.get();

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Permission groups", Theme.ACCENT)), false);

		groups.groups.forEach((name, nodes) -> ctx.getSource().sendSuccess(() -> Icon.text(
				"  " + name + " — " + nodes.size() + " entry/entries", Theme.TEXT), false));

		if (groups.players.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  Nobody is assigned yet — /staff perms set <player> <group>"), false);
		} else {
			// Assignments are keyed by UUID or by name, and a UUID on screen tells a human
			// nothing — not even whether it is the account they meant. Resolved to a name
			// where the server has ever seen one, with the raw key kept beside it so it can
			// still be matched against the file being edited.
			MinecraftServer server = ctx.getSource().getServer();
			groups.players.forEach((who, group) -> {
				String shown = io.github.alphain24.staffcore.util.PlayerLookup.display(server, who);
				String suffix = shown.equals(who) ? "" : "  [" + who.substring(0, Math.min(8, who.length())) + "…]";
				ctx.getSource().sendSuccess(
						() -> Icon.text("  " + shown + " → " + group + suffix, Theme.MUTED), false);
			});
		}

		if (groups.operatorsBypass) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  Operators currently bypass every group. Set operatorsBypass to false "
							+ "once your staff are assigned."), false);
		}
		return 1;
	}

	private static int permsSet(CommandContext<CommandSourceStack> ctx, String group) {
		if (permsUnavailable(ctx)) return 0;

		String player = StringArgumentType.getString(ctx, "player");
		PermissionGroups groups = PermissionGroups.get();

		if (group != null && !groups.hasGroup(group)) {
			return fail(ctx, "There is no group called " + group
					+ ". Known groups: " + String.join(", ", groups.groups.keySet()));
		}

		// Stored by UUID where the name resolves, because names change and a stale name
		// entry silently stops granting anything.
		String key = player;
		MinecraftServer server = ctx.getSource().getServer();
		if (server != null) {
			var profile = io.github.alphain24.staffcore.util.PlayerLookup.profile(server, player);
			if (profile.isPresent()) key = profile.get().id().toString();
		}

		groups.assign(key, group);
		audit(ctx, "/staff perms " + (group == null ? "unset " : "set ") + player
				+ (group == null ? "" : " " + group));

		return ok(ctx, group == null
				? "Removed " + player + " from their group."
				: player + " is now " + group + ".");
	}

	private static void audit(CommandContext<CommandSourceStack> ctx, String command) {
		Mods.analytics().logCommand(ctx.getSource().getTextName(), command);

		MinecraftServer server = ctx.getSource().getServer();
		if (server != null) {
			Mods.control().reportCommand(server, ctx.getSource().getTextName(), command);
		}
	}

	private static void playerSound(CommandContext<CommandSourceStack> ctx, boolean good) {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return;
		if (good) Sfx.success(p);
		else Sfx.deny(p);
	}

	private static int ok(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendSuccess(() -> Theme.good(message), false);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendFailure(Theme.bad(message));
		playerSound(ctx, false);
		return 0;
	}
}
