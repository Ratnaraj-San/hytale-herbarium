package com.ratnaraj.herbarium.data;

/**
 * An "other" source of a plant (plants-v2.json obtainedFrom.other), shown as a tile in the
 * Obtained from / Other section. type "merchant" = sold by a barter shop: nameKey is the
 * shop's DisplayNameKey (a vanilla translation key, e.g. server.barter.kweebec_merchant.title),
 * shop is the Server/BarterShops file stem (e.g. "Kweebec_Merchant"), icon is the merchant
 * NPC's generated model icon (e.g. "Icons/ModelsGenerated/Kweebec_Rootling.png"), and
 * costItem/costAmount describe the trade's price (e.g. Ingredient_Life_Essence x30).
 */
public record PlantOther(
        String type, String nameKey, String shop, String icon, String costItem, int costAmount) {}
