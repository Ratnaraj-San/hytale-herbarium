package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ratnaraj.herbarium.Herbarium;
import com.ratnaraj.herbarium.data.PlantCatalogV2;

import javax.annotation.Nonnull;
import java.util.Locale;

public class HerbariumCommandReset extends AbstractPlayerCommand {

    private static final String RESET_DESC_KEY = "server.herbarium.command.reset.desc";
    private static final String CATEGORY_ARG_DESC_KEY = "server.herbarium.command.reset.category.desc";
    private static final String RESET_DONE_KEY = "server.herbarium.command.reset.done";
    private static final String UNKNOWN_CATEGORY_KEY = "server.herbarium.command.common.unknownCategory";
    private static final String PLAYER_ARG_DESC_KEY = "server.herbarium.command.common.player.desc";

    private static final SingleArgumentType<String> CATEGORY_TYPE = new SingleArgumentType<>(
            CATEGORY_ARG_DESC_KEY,
            CATEGORY_ARG_DESC_KEY,
            "Flower")
    {
        @Override
        public String parse(String input, ParseResult result) {
            return input;
        }

        @Override
        public void suggest(CommandSender sender, String current, int cursor, SuggestionResult result) {
            String prefix = current.toLowerCase(Locale.ROOT);

            for (String category : Herbarium.getCatalog().getCategoryNames()) {
                if (category.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.suggest(category);
                }
            }
        }
    };

    private final RequiredArg<String> categoryArg;
    private final OptionalArg<PlayerRef> playerArg;

    public HerbariumCommandReset() {
        super("reset", RESET_DESC_KEY);
        requirePermission(HytalePermissions.fromCommand("herbarium", "reset"));
        categoryArg = withRequiredArg("category", CATEGORY_ARG_DESC_KEY, CATEGORY_TYPE);
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
        String inputCategory = categoryArg.get(ctx);
        PlantCatalogV2 catalog = Herbarium.getCatalog();
        String categoryName = catalog.getCategoryName(inputCategory);
        PlayerRef targetRef = ctx.provided(playerArg) ? playerArg.get(ctx) : playerRef;

        if (categoryName == null) {
            ctx.sendMessage(Message.translation(UNKNOWN_CATEGORY_KEY).param("category", inputCategory));
            return;
        }

        Herbarium.getPlayerStore().resetCategoryMemories(targetRef, categoryName);

        ctx.sendMessage(Message
                .translation(RESET_DONE_KEY)
                .param("category", Message.translation("server.herbarium.category." + categoryName.toLowerCase()))
                .param("player", targetRef.getUsername())
        );
    }

}
