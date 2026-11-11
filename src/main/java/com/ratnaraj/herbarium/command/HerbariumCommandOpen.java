package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ratnaraj.herbarium.Herbarium;

import javax.annotation.Nonnull;

public class HerbariumCommandOpen extends AbstractPlayerCommand {

    private static final String OPEN_DESC_KEY = "server.herbarium.command.open.desc";

    public HerbariumCommandOpen() {
        super("open", OPEN_DESC_KEY);
        requirePermission(HytalePermissions.fromCommand("herbarium", "open"));
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
