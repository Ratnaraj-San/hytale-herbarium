package com.ratnaraj.herbarium;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class Herbarium extends JavaPlugin {

    private static Herbarium instance;

    public Herbarium(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static Herbarium getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("[Herbarium] Plugin enabled!");
    }

    @Override
    public void shutdown() {
        getLogger().at(Level.INFO).log("[Herbarium] Plugin disabled!");
    }
}
