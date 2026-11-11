package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;

public class HerbariumCommand extends AbstractCommandCollection {

    private static final String ROOT_DESC_KEY = "server.herbarium.command.desc";

    public static final String PLAYERS_ONLY_KEY = "server.herbarium.command.playersOnly";

    public HerbariumCommand() {
        super("herbarium", ROOT_DESC_KEY);

        requirePermission(HytalePermissions.fromCommand("herbarium"));

        addSubCommand(new HerbariumCommandReset());
        addSubCommand(new HerbariumCommandUnlockAll());
        addSubCommand(new HerbariumCommandOpen());
        addSubCommand(new HerbariumCommandGive());
    }
}
