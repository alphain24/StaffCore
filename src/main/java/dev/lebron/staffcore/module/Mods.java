package dev.lebron.staffcore.module;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.modules.alerts.AlertsModule;
import dev.lebron.staffcore.modules.analytics.AnalyticsModule;
import dev.lebron.staffcore.modules.chat.StaffChatModule;
import dev.lebron.staffcore.modules.control.ControlModule;
import dev.lebron.staffcore.modules.discord.DiscordModule;
import dev.lebron.staffcore.modules.freeze.FreezeModule;
import dev.lebron.staffcore.modules.grief.GriefModule;
import dev.lebron.staffcore.modules.appeal.AppealModule;
import dev.lebron.staffcore.modules.identity.EvasionModule;
import dev.lebron.staffcore.modules.identity.IdentityModule;
import dev.lebron.staffcore.modules.inventory.InventoryModule;
import dev.lebron.staffcore.modules.notes.NotesModule;
import dev.lebron.staffcore.modules.punish.PunishmentModule;
import dev.lebron.staffcore.modules.report.ReportModule;
import dev.lebron.staffcore.modules.security.SecurityModule;
import dev.lebron.staffcore.modules.staffmode.StaffModeModule;
import dev.lebron.staffcore.modules.teleport.TeleportModule;
import dev.lebron.staffcore.modules.vanish.VanishModule;

/**
 * Typed shortcuts to the registered modules.
 * <p>
 * Menus and commands reach for modules constantly; without this every call site would be
 * a string literal plus a class literal plus an Optional unwrap. Getting the id wrong
 * here fails once, loudly, at the first use rather than silently in forty places.
 */
public final class Mods {
	private Mods() {}

	public static StaffModeModule staffMode() {
		return StaffCore.modules().require("staff_mode", StaffModeModule.class);
	}

	public static VanishModule vanish() {
		return StaffCore.modules().require("vanish", VanishModule.class);
	}

	public static FreezeModule freeze() {
		return StaffCore.modules().require("freeze", FreezeModule.class);
	}

	public static PunishmentModule punish() {
		return StaffCore.modules().require("punishment", PunishmentModule.class);
	}

	public static NotesModule notes() {
		return StaffCore.modules().require("notes", NotesModule.class);
	}

	public static StaffChatModule staffChat() {
		return StaffCore.modules().require("staff_chat", StaffChatModule.class);
	}

	public static AlertsModule alerts() {
		return StaffCore.modules().require("alerts", AlertsModule.class);
	}

	public static ReportModule reports() {
		return StaffCore.modules().require("report", ReportModule.class);
	}

	public static TeleportModule teleport() {
		return StaffCore.modules().require("teleport", TeleportModule.class);
	}

	public static InventoryModule inventory() {
		return StaffCore.modules().require("inventory", InventoryModule.class);
	}

	public static SecurityModule security() {
		return StaffCore.modules().require("security", SecurityModule.class);
	}

	public static GriefModule grief() {
		return StaffCore.modules().require("grief", GriefModule.class);
	}

	public static DiscordModule discord() {
		return StaffCore.modules().require("discord", DiscordModule.class);
	}

	public static AnalyticsModule analytics() {
		return StaffCore.modules().require("analytics", AnalyticsModule.class);
	}

	public static dev.lebron.staffcore.modules.anticheat.AntiCheatModule antiCheat() {
		return StaffCore.modules().require("anticheat",
				dev.lebron.staffcore.modules.anticheat.AntiCheatModule.class);
	}

	public static IdentityModule identity() {
		return StaffCore.modules().require("identity", IdentityModule.class);
	}

	public static EvasionModule evasion() {
		return StaffCore.modules().require("evasion", EvasionModule.class);
	}

	public static AppealModule appeals() {
		return StaffCore.modules().require("appeal", AppealModule.class);
	}

	public static ControlModule control() {
		return StaffCore.modules().require("control", ControlModule.class);
	}
}
