package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ratnaraj.herbarium.Herbarium;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class HerbariumCommandReset extends AbstractAsyncCommand {

    private static final String RESET_DESC_KEY = "server.herbarium.command.reset.desc";
    private static final String RESET_DONE_KEY = "server.herbarium.command.reset.done";

    public HerbariumCommandReset() {
        super("reset", RESET_DESC_KEY);
        requirePermission(HytalePermissions.fromCommand("herbarium", "reset"));
    }

    @Override
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        CommandSender sender = ctx.sender();
        PlayerRef playerRef = (sender instanceof PlayerRef) ? (PlayerRef) sender : null;

        if (playerRef == null) {
            ctx.sendMessage(Message.translation(HerbariumCommand.PLAYERS_ONLY_KEY));
            return CompletableFuture.completedFuture(null);
        }

        Herbarium.getPlayerStore().clearPlayerMemories(playerRef);

        ctx.sendMessage(Message.translation(RESET_DONE_KEY));

        return CompletableFuture.completedFuture(null);
    }
}
