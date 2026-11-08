package com.ratnaraj.herbarium.event;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public class FeedbackParticle {

    private FeedbackParticle() {}

    public static void spawn(String particle, Ref<EntityStore> ref, Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());

        if (transform != null) {
            ParticleUtil.spawnParticleEffect(particle, transform.getPosition(), store);
        }
    }

}
