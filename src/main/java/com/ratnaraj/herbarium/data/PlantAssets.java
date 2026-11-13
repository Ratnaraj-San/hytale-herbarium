package com.ratnaraj.herbarium.data;

import java.util.logging.Level;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ratnaraj.herbarium.Herbarium;

public final class PlantAssets {

    public static String getPlantIcon(String plantId) {
        try {
            Item item = Item.getAssetMap().getAsset(plantId);
            if (item != null && item.getIcon() != null && !item.getIcon().isEmpty()) {
                return item.getIcon();
            }
        } catch (Exception e) {
            // Plant has no item asset; no icon available.
        }
        return null;
    }

    public static Message getPlantNameMessage(String plantId) {
        String translationKey = getPlantTranslationKey(plantId);
        if (translationKey != null) {
            return Message.translation(translationKey);
        }
        return Message.raw(plantId);
    }

    public static String getPlantTranslationKey(String plantId) {
        try {
            Item item = Item.getAssetMap().getAsset(plantId);
            if (item != null && item.getTranslationProperties() != null) {
                return item.getTranslationProperties().getName();
            }
        } catch (Exception e) {
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.WARNING)
                    .log(
                            "[Herbarium] Failed to get translation key for "
                                    + plantId
                                    + ": "
                                    + e.getMessage());
        }
        return null;
    }
}
