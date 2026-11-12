package com.ratnaraj.herbarium.data;

public record PlantInteractions(boolean canBreak, boolean canCollect, boolean canShear) {

    public static final PlantInteractions NONE = new PlantInteractions(
            false,
            false,
            false
    );

    public boolean any() {
        return canBreak || canCollect || canShear;
    }
}
