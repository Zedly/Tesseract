package dev.syndek.tesseract;

import org.bukkit.plugin.java.JavaPlugin;

public final class TesseractPlugin extends JavaPlugin {

    private static TesseractPlugin instance;

    public TesseractPlugin() {
        instance = this;
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(TesseractListener.getInstance(), this);
    }

    public static TesseractPlugin instance() {
        return instance;
    }
}
