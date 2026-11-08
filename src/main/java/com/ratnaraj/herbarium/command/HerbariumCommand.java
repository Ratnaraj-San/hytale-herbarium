package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ratnaraj.herbarium.Herbarium;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class HerbariumCommand extends AbstractCommandCollection {

    private static final String ROOT_DESC_KEY = "server.herbarium.command.desc";
    private static final String UI_DESC_KEY = "server.herbarium.command.ui.desc";
    private static final String RESET_DESC_KEY = "server.herbarium.command.reset.desc";
    private static final String UNLOCK_ALL_DESC_KEY = "server.herbarium.command.unlockall.desc";

    private static final String PLAYERS_ONLY_KEY = "server.herbarium.command.playersOnly";
    private static final String RESET_DONE_KEY = "server.herbarium.command.reset.done";
    private static final String UNLOCK_ALL_DONE_KEY = "server.herbarium.command.unlockall.done";

    public HerbariumCommand() {
        super("herbarium", ROOT_DESC_KEY);

        requirePermission(HytalePermissions.fromCommand("herbarium"));

        addSubCommand(new HerbariumCommandReset());
        addSubCommand(new HerbariumCommandUnlockAll());
        addSubCommand(new HerbariumCommandUI());
    }

    private static class HerbariumCommandUI extends AbstractPlayerCommand {
        public HerbariumCommandUI() {
            super("ui", UI_DESC_KEY);
            requirePermission(HytalePermissions.fromCommand("herbarium", "ui"));
        }

        @Override
        protected void execute(
                @Nonnull CommandContext ctx,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world
        ) {
            Player player = store.getComponent(ref, Player.getComponentType());

            CustomUIPage page = Herbarium.createJournalPage(playerRef);
            player.getPageManager().openCustomPage(ref, store, page);
        }
    }

    private static class HerbariumCommandReset extends AbstractAsyncCommand {
        public HerbariumCommandReset() {
            super("reset", RESET_DESC_KEY);
            requirePermission(HytalePermissions.fromCommand("herbarium", "reset"));
        }

        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
            CommandSender sender = ctx.sender();
            PlayerRef playerRef = (sender instanceof PlayerRef) ? (PlayerRef) sender : null;

            if (playerRef == null) {
                ctx.sendMessage(Message.translation(PLAYERS_ONLY_KEY));
                return CompletableFuture.completedFuture(null);
            }

            Herbarium.getPlayerStore().clearPlayerMemories(playerRef);

            ctx.sendMessage(Message.translation(RESET_DONE_KEY));

            return CompletableFuture.completedFuture(null);
        }
    }

    private static class HerbariumCommandUnlockAll extends AbstractAsyncCommand {
        public HerbariumCommandUnlockAll() {
            super("unlockall", UNLOCK_ALL_DESC_KEY);
            requirePermission(HytalePermissions.fromCommand("herbarium", "unlockall"));
        }

        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
            CommandSender sender = ctx.sender();
            PlayerRef playerRef = (sender instanceof PlayerRef) ? (PlayerRef) sender : null;

            if (playerRef == null) {
                ctx.sendMessage(Message.translation(PLAYERS_ONLY_KEY));
                return CompletableFuture.completedFuture(null);
            }

            Herbarium.getPlayerStore().unlockAllPlayerMemories(playerRef);

            ctx.sendMessage(Message.translation(UNLOCK_ALL_DONE_KEY));

            return CompletableFuture.completedFuture(null);
        }
    }
}
