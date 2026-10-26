package com.ratnaraj.herbarium;

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

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Main command to manage the herbarium.
 * Sub-commands: list, reset
 */
public class HerbariumCommand extends AbstractCommandCollection {

    public HerbariumCommand() {
        super("herbarium", "Herbarium commands");

        requirePermission(HytalePermissions.fromCommand("herbarium"));

        addSubCommand(new HerbariumCommandReset());
        addSubCommand(new HerbariumCommandUnlockAll());
        addSubCommand(new HerbariumCommandUI());
    }

    private static class HerbariumCommandUI extends AbstractPlayerCommand {
        public HerbariumCommandUI() {
            super("ui", "Opens the Herbarium UI.");
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
            super("reset", "Deletes all your progression.");
            requirePermission(HytalePermissions.fromCommand("herbarium", "reset"));
        }

        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
            CommandSender sender = ctx.sender();
            PlayerRef playerRef = (sender instanceof PlayerRef) ? (PlayerRef) sender : null;
            PlantMemoryManager memoryManager = Herbarium.getMemoryManager();

            if (playerRef == null) {
                ctx.sendMessage(Message.raw("This command can only be executed by players."));
                return CompletableFuture.completedFuture(null);
            }

            memoryManager.clearPlayerMemories(playerRef);

            ctx.sendMessage(Message.raw("Your progress has been reset."));

            return CompletableFuture.completedFuture(null);
        }
    }

    private static class HerbariumCommandUnlockAll extends AbstractAsyncCommand {
        public HerbariumCommandUnlockAll() {
            super("unlockall", "Unlocks all plants.");
            requirePermission(HytalePermissions.fromCommand("herbarium", "unlockall"));
        }

        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
            CommandSender sender = ctx.sender();
            PlayerRef playerRef = (sender instanceof PlayerRef) ? (PlayerRef) sender : null;
            PlantMemoryManager memoryManager = Herbarium.getMemoryManager();

            if (playerRef == null) {
                ctx.sendMessage(Message.raw("This command can only be executed by players."));
                return CompletableFuture.completedFuture(null);
            }

            memoryManager.unlockAllPlayerMemories(playerRef);

            ctx.sendMessage(Message.raw("All plants have been unlocked."));

            return CompletableFuture.completedFuture(null);
        }
    }
}
