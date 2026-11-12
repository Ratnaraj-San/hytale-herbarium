package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ratnaraj.herbarium.Herbarium;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class HerbariumCommandResetAll extends AbstractAsyncCommand {

    private static final String RESET_ALL_DESC_KEY = "server.herbarium.command.resetall.desc";
    private static final String RESET_ALL_DONE_KEY = "server.herbarium.command.resetall.done";
    private static final String PLAYER_ARG_DESC_KEY = "server.herbarium.command.common.player.desc";

    private final OptionalArg<PlayerRef> playerArg;

    public HerbariumCommandResetAll() {
        super("resetall", RESET_ALL_DESC_KEY);
        requirePermission(HytalePermissions.fromCommand("herbarium", "resetall"));
        playerArg = withOptionalArg("player", PLAYER_ARG_DESC_KEY, ArgTypes.PLAYER_REF);
    }

    @Override
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        CommandSender sender = ctx.sender();
        PlayerRef playerRef = (sender instanceof PlayerRef) ? (PlayerRef) sender : null;
        PlayerRef targetRef = ctx.provided(playerArg) ? playerArg.get(ctx) : playerRef;

        if (playerRef == null) {
            ctx.sendMessage(Message.translation(HerbariumCommand.PLAYERS_ONLY_KEY));
            return CompletableFuture.completedFuture(null);
        }

        Herbarium.getPlayerStore().clearPlayerMemories(targetRef);

        ctx.sendMessage(Message
                .translation(RESET_ALL_DONE_KEY)
                .param("player", targetRef.getUsername())
        );

        return CompletableFuture.completedFuture(null);
    }
}
