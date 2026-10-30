package com.ratnaraj.herbarium;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Intro modal shown the first time a player opens the journal.
 * Mirrors the vanilla Memories feature unlock page (the modal displayed on
 * first interaction with Bench_Memories): same IntroModal layout, same
 * unlock sound, and a button leading to the journal itself.
 */
public class HerbariumIntroPage extends InteractiveCustomUIPage<HerbariumIntroPage.PageEventData> {

    private static final String TYPE_OPEN_JOURNAL = "OpenJournal";

    /**
     * Sound event played by the vanilla Heart of Orbis when the Memories
     * feature is unlocked on first interaction.
     */
    private static final String UNLOCK_SOUND_EVENT = "SFX_Memories_Unlock_Local";

    /**
     * Translation keys of the modal texts. They are set at runtime with
     * Message.translation: .ui documents cannot reference plugin
     * translation keys with %refs, the client fails to load the document
     * when the key is not resolvable at parse time.
     */
    private static final String TITLE_KEY = "server.herbarium.unlock.title";
    private static final String BULLET1_KEY = "server.herbarium.unlock.bullet1";
    private static final String BULLET2_KEY = "server.herbarium.unlock.bullet2";
    private static final String BULLET3_KEY = "server.herbarium.unlock.bullet3";
    private static final String OPEN_JOURNAL_KEY = "server.herbarium.unlock.openJournal";

    /**
     * Event payload sent by the client when the player interacts with the page.
     */
    public static final class PageEventData {
        public static final BuilderCodec<PageEventData> CODEC =
                BuilderCodec.builder(PageEventData.class, PageEventData::new)
                        .append(new KeyedCodec<>("Type", Codec.STRING),
                                PageEventData::setType, PageEventData::getType).add()
                        .build();

        private String type;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    public HerbariumIntroPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageEventData.CODEC);
    }

    @Override
    public void build(
            Ref<EntityStore> ref,
            UICommandBuilder commands,
            UIEventBuilder events,
            Store<EntityStore> store
    ) {
        // Remember the player on the very first opening. The modal is also
        // reopened later through the journal info button, and the vanilla
        // feature-unlock sound plays only on the first appearance.
        boolean firstOpening = !Herbarium.getMemoryManager().hasOpenedJournal(playerRef);
        Herbarium.getMemoryManager().markJournalOpened(playerRef);
        if (firstOpening) {
            SoundUtil.playSoundEvent2dToPlayer(playerRef, UNLOCK_SOUND_EVENT, SoundCategory.SFX);
        }

        commands.append("Pages/Herbarium/JournalUnlocked.ui");

        commands.set("#ModalTitle.Text", Message.translation(TITLE_KEY));
        commands.set("#ModalBullet1.Text", Message.translation(BULLET1_KEY));
        commands.set("#ModalBullet2.Text", Message.translation(BULLET2_KEY));
        commands.set("#ModalBullet3.Text", Message.translation(BULLET3_KEY));
        commands.set("#OpenJournalButton.Text", Message.translation(OPEN_JOURNAL_KEY));

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#OpenJournalButton",
                new EventData().append("Type", TYPE_OPEN_JOURNAL)
        );
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
        if (TYPE_OPEN_JOURNAL.equals(data.getType())) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new HerbariumUIPage(playerRef));
            }
        }
    }
}
