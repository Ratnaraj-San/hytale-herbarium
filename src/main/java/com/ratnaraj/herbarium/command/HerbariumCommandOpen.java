package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
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
    private static final String PLAYER_ARG_DESC_KEY = "server.herbarium.command.common.player.desc";

    private final OptionalArg<PlayerRef> playerArg;

    public HerbariumCommandOpen() {
        super("open", OPEN_DESC_KEY);
        requirePermission(HytalePermissions.fromCommand("herbarium", "open"));
        playerArg = withOptionalArg("player", PLAYER_ARG_DESC_KEY, ArgTypes.PLAYER_REF);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        PlayerRef targetRef = ctx.provided(playerArg) ? playerArg.get(ctx) : playerRef;

        CustomUIPage page = Herbarium.createJournalPage(targetRef, playerRef);
        Player player = store.getComponent(ref, Player.getComponentType());

        if (player == null) {
            ctx.sendMessage(Message.translation(HerbariumCommand.PLAYERS_ONLY_KEY));
            return;
        }

        player.getPageManager().openCustomPage(ref, store, page);
    }
}
