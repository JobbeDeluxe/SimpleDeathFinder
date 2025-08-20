package dev.yourserver.simpledeathfinder.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;

public final class Versions {
    private Versions() {}

    public static String pluginVersion(String pluginName) {
        PluginDescriptionFile d = Bukkit.getPluginManager().getPlugin(pluginName).getDescription();
        return d.getVersion();
    }
}
