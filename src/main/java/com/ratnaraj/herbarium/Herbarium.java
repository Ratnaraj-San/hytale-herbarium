package com.ratnaraj.herbarium;

import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;

public class Herbarium extends JavaPlugin {

    private static Herbarium instance;
    private static PlantMemoryManager memoryManager;

    public Herbarium(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        memoryManager = new PlantMemoryManager();
    }

    public static Herbarium getInstance() {
        return instance;
    }

    public static PlantMemoryManager getMemoryManager() {
        return memoryManager;
    }

    @Override
    protected void setup() {
        Path pluginDataDir = getPluginDataDirectory();
        memoryManager.initialize(pluginDataDir);
        memoryManager.loadAllMemories();

        registerEvents();
        registerJournalPage();
    }

    private Path getPluginDataDirectory() {
        try {
            // Method 1: Try to get via class loader
            Path codeSourcePath = Paths.get(
                Herbarium.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
            ).getParent();
            
            // If the plugin is in a JAR, the parent directory contains the data
            Path dataDir = codeSourcePath.resolve("Herbarium");
            Files.createDirectories(dataDir);
            return dataDir;
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log(
                "[Herbarium] Unable to get directory via CodeSource: " + e.getMessage()
            );
        }
        
        // Method 2: Use working directory + run/
        try {
            Path workingDir = Paths.get(System.getProperty("user.dir"));
            Path runDir = workingDir.resolve("run");
            if (Files.exists(runDir)) {
                Path dataDir = runDir.resolve("Herbarium");
                Files.createDirectories(dataDir);
                return dataDir;
            }
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log(
                "[Herbarium] Unable to get directory via working dir: " + e.getMessage()
            );
        }
        
        // Method 3: Current directory
        try {
            Path currentDir = Paths.get(".").toAbsolutePath().normalize();
            Path dataDir = currentDir.resolve("Herbarium");
            Files.createDirectories(dataDir);
            return dataDir;
        } catch (Exception e) {
            getLogger().at(Level.SEVERE).log(
                "[Herbarium] Unable to determine data directory: " + e.getMessage()
            );
        }
        
        // Return a default path
        return Paths.get("Herbarium");
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("[Herbarium] Plugin enabled!");
    }

    @Override
    public void shutdown() {
        getLogger().at(Level.INFO).log("[Herbarium] Plugin disabled!");

        memoryManager.saveAllMemories();
        
        getLogger().at(Level.INFO).log("[Herbarium] All memories saved!");
    }

    public static final String JOURNAL_PAGE_KEY = "Herbarium_Journal";

    private void registerEvents() {
        this.getEntityStoreRegistry().registerSystem(new InventoryChangeSystem());
    }

    private void registerJournalPage() {
        OpenCustomUIInteraction.registerCustomPageSupplier(
                this,
                HerbariumUIPage.class,
                JOURNAL_PAGE_KEY,
                (ref, componentAccessor, playerRef, interactionContext) -> createJournalPage(playerRef)
        );
    }

    /**
     * Creates the page to open when the player opens the journal: the intro
     * modal on the very first opening (with the vanilla Memories unlock
     * look and sound), the journal itself afterwards.
     */
    public static CustomUIPage createJournalPage(PlayerRef playerRef) {
        if (memoryManager.hasOpenedJournal(playerRef)) {
            return new HerbariumUIPage(playerRef);
        }
        return new HerbariumIntroPage(playerRef);
    }

}
