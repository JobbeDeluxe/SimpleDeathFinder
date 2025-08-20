package dev.yourserver.simpledeathfinder;

import dev.yourserver.simpledeathfinder.path.DeathPathState;
import dev.yourserver.simpledeathfinder.path.PathManager;
import dev.yourserver.simpledeathfinder.path.PathMode;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SimpleDeathFinderPlugin extends JavaPlugin implements Listener {

    // Tagging-Keys fürs Kompass-Item
    private NamespacedKey KEY_TAG, KEY_W, KEY_X, KEY_Y, KEY_Z;

    // Letzte Todesposition pro Spieler (für Lore/Tag)
    private final Map<UUID, Location> lastDeathLocation = new HashMap<>();

    // Konfig
    private boolean giveOnRespawn;
    private boolean removeEnabled;
    private double removeRadius;
    private long checkInterval;
    private boolean removeOld;
    private String msgGiven, msgArrived, msgFull, msgOtherDim;
    private String itemName;
    private List<String> itemLore;
    private String soundArrived;

    // Checker Task
    private int taskId = -1;

    // NEU: Pfadmanager
    private PathManager pathManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigDefaults();   // ergänzt neue Keys aus der JAR-config.yml
        loadCfg();

        KEY_TAG = new NamespacedKey(this, "sdf");
        KEY_W   = new NamespacedKey(this, "target_world");
        KEY_X   = new NamespacedKey(this, "target_x");
        KEY_Y   = new NamespacedKey(this, "target_y");
        KEY_Z   = new NamespacedKey(this, "target_z");

        Bukkit.getPluginManager().registerEvents(this, this);

        // PathManager initialisieren + starten
        pathManager = new PathManager(this, KEY_TAG, KEY_W, KEY_X, KEY_Y, KEY_Z);
        pathManager.start();

        startChecker();
        getLogger().info("SimpleDeathFinder aktiviert.");
    }

    @Override
    public void onDisable() {
        stopChecker();
        if (pathManager != null) pathManager.stop();
    }

    /** Merged neue Default-Keys in bestehende config.yml (ohne Werte zu überschreiben). */
    private void ensureConfigDefaults() {
        // Stellt sicher, dass eine Datei existiert
        saveDefaultConfig();
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                getLogger().warning("config.yml nicht im JAR gefunden – Defaults können nicht gemerged werden.");
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));

            FileConfiguration cfg = getConfig();
            cfg.addDefaults(defaults);
            cfg.options().copyDefaults(true);

            saveConfig();
            reloadConfig();
        } catch (Exception ex) {
            getLogger().warning("Konnte Defaults nicht in config.yml mergen: " + ex.getMessage());
        }
    }

    private void loadCfg() {
        FileConfiguration c = getConfig();
        giveOnRespawn = c.getBoolean("give-on-respawn", true);
        removeEnabled = c.getConfigurationSection("remove-on-approach").getBoolean("enabled", true);
        removeRadius  = c.getConfigurationSection("remove-on-approach").getDouble("radius", 5.0);
        checkInterval = c.getConfigurationSection("remove-on-approach").getLong("check-interval-ticks", 20L);
        removeOld     = c.getBoolean("remove-old-compasses", true);

        msgGiven     = color(c.getString("messages.given", "&aBergungskompass erhalten."));
        msgArrived   = color(c.getString("messages.arrived", "&7Du bist an deiner Todesstelle angekommen. Der Kompass zerfällt."));
        msgFull      = color(c.getString("messages.full-inventory", "&eInventar voll – Kompass wurde vor dir gedroppt."));
        msgOtherDim  = color(c.getString("messages.other-dimension", "&eDeine Todesstelle ist in einer anderen Dimension."));

        itemName     = color(c.getString("item.name", "&bBergungskompass"));
        itemLore     = new ArrayList<>();
        for (String line : c.getStringList("item.lore")) itemLore.add(color(line));

        soundArrived = c.getString("sound.arrived", "entity.experience_orb.pickup");
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }

    // --- Events ---

    @EventHandler (priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        // Position sichern für Respawn
        lastDeathLocation.put(e.getEntity().getUniqueId(), e.getEntity().getLocation().clone());
    }

    @EventHandler (priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            Location death = lastDeathLocation.get(p.getUniqueId());

            // Pfad-Ziel setzen + Sichtbarkeit gemäß Config
            if (death != null && pathManager != null) {
                pathManager.setTargetFromDeath(p, death);
            }

            if (!giveOnRespawn) return;

            // Kompass geben (mit getaggtem Ziel)
            if (death == null) {
                giveCompass(p, null);
            } else {
                giveCompass(p, death);
            }
        });
    }

    @EventHandler (priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // bewusst leer gelassen
    }

    // --- Kompass geben ---

    private void giveCompass(Player p, Location death) {
        if (removeOld) {
            removeTaggedCompasses(p.getInventory());
        }

        ItemStack is = new ItemStack(Material.RECOVERY_COMPASS, 1);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(itemName);

            List<String> lore = new ArrayList<>(itemLore);
            if (death != null) {
                String dimName = prettyDim(death.getWorld());
                for (int i = 0; i < lore.size(); i++) {
                    lore.set(i, lore.get(i)
                            .replace("{dim}", dimName)
                            .replace("{x}", String.valueOf(death.getBlockX()))
                            .replace("{y}", String.valueOf(death.getBlockY()))
                            .replace("{z}", String.valueOf(death.getBlockZ()))
                    );
                }
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(KEY_TAG, PersistentDataType.BYTE, (byte)1);
                pdc.set(KEY_W, PersistentDataType.STRING, death.getWorld().getUID().toString());
                pdc.set(KEY_X, PersistentDataType.INTEGER, death.getBlockX());
                pdc.set(KEY_Y, PersistentDataType.INTEGER, death.getBlockY());
                pdc.set(KEY_Z, PersistentDataType.INTEGER, death.getBlockZ());
            } else {
                // ohne gespeicherte Death-Pos wenigstens taggen, aber ohne Koordinaten
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(KEY_TAG, PersistentDataType.BYTE, (byte)1);
            }
            meta.setLore(lore);
            is.setItemMeta(meta);
        }

        HashMap<Integer, ItemStack> left = p.getInventory().addItem(is);
        if (!left.isEmpty()) {
            p.sendMessage(msgFull);
            p.getWorld().dropItemNaturally(p.getLocation(), is);
        } else {
            p.sendMessage(msgGiven);
            if (death != null && !sameWorld(p.getWorld(), death.getWorld())) {
                p.sendMessage(msgOtherDim);
            }
        }
    }

    private void removeTaggedCompasses(PlayerInventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isTaggedCompass(it)) inv.setItem(i, null);
        }
    }

    private boolean isTaggedCompass(ItemStack it) {
        if (it == null || it.getType() != Material.RECOVERY_COMPASS) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte tag = pdc.get(KEY_TAG, PersistentDataType.BYTE);
        return tag != null && tag == (byte)1;
    }

    // --- Checker ---

    private void startChecker() {
        stopChecker();
        if (!removeEnabled) return;

        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) return;
            double r2 = removeRadius * removeRadius;

            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerInventory inv = p.getInventory();
                for (int slot = 0; slot < inv.getSize(); slot++) {
                    ItemStack it = inv.getItem(slot);
                    if (!isTaggedCompass(it)) continue;

                    Location target = readTargetFromItem(it);
                    if (target == null) continue;
                    if (!sameWorld(p.getWorld(), target.getWorld())) continue;

                    double dx = (p.getLocation().getX() - (target.getBlockX() + 0.5));
                    double dy = (p.getLocation().getY() - (target.getBlockY() + 0.5));
                    double dz = (p.getLocation().getZ() - (target.getBlockZ() + 0.5));
                    double dist2 = dx*dx + dy*dy + dz*dz;

                    if (dist2 <= r2) {
                        // Einen getaggten Kompass entfernen
                        int amount = it.getAmount();
                        if (amount <= 1) inv.setItem(slot, null);
                        else { it.setAmount(amount - 1); inv.setItem(slot, it); }

                        p.sendMessage(msgArrived);
                        try {
                            Sound s = Sound.valueOf(getConfig().getString("sound.arrived", "ENTITY_EXPERIENCE_ORB_PICKUP").toUpperCase(Locale.ROOT));
                            p.playSound(p.getLocation(), s, 1f, 1f);
                        } catch (IllegalArgumentException ignored) {}

                        // Pfad für den Spieler ausblenden
                        if (pathManager != null) {
                            pathManager.state(p).visible = false;
                            pathManager.state(p).clearPath();
                        }

                        break; // pro Tick nur einen entfernen
                    }
                }
            }
        }, 40L, Math.max(5L, checkInterval)); // Start nach 2s, dann alle N Ticks
    }

    private void stopChecker() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private Location readTargetFromItem(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String wStr = pdc.get(KEY_W, PersistentDataType.STRING);
        Integer x = pdc.get(KEY_X, PersistentDataType.INTEGER);
        Integer y = pdc.get(KEY_Y, PersistentDataType.INTEGER);
        Integer z = pdc.get(KEY_Z, PersistentDataType.INTEGER);
        if (wStr == null || x == null || y == null || z == null) return null;

        try {
            UUID wid = UUID.fromString(wStr);
            World w = Bukkit.getWorld(wid);
            if (w == null) return null;
            return new Location(w, x, y, z);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String prettyDim(World w) {
        if (w == null) return "Unbekannt";
        switch (w.getEnvironment()) {
            case NORMAL: return "Overworld";
            case NETHER: return "Nether";
            case THE_END: return "The End";
            default: return w.getName();
        }
    }

    private boolean sameWorld(World a, World b) {
        return a != null && b != null && a.getUID().equals(b.getUID());
    }

    // --- Commands ---

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("sdf")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!s.hasPermission("simpledeathfinder.reload")) { s.sendMessage("§cKeine Berechtigung."); return true; }
                reloadConfig();
                ensureConfigDefaults();   // ergänzt neue Keys bei Reload
                loadCfg();
                if (pathManager != null) { pathManager.stop(); pathManager.start(); }
                startChecker();
                s.sendMessage("§aSimpleDeathFinder neu geladen.");
                return true;
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                if (!s.hasPermission("simpledeathfinder.give")) { s.sendMessage("§cKeine Berechtigung."); return true; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) { s.sendMessage("§cSpieler nicht gefunden."); return true; }
                giveCompass(t, lastDeathLocation.get(t.getUniqueId()));
                s.sendMessage("§aKompass an " + t.getName() + " gegeben.");
                return true;
            }

            s.sendMessage("§7/sdf reload §8| §7/sdf give <player>");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("deathpath")) {
            if (!(s instanceof Player)) { s.sendMessage("§cNur Ingame."); return true; }
            Player p = (Player) s;
            if (!p.hasPermission("simpledeathfinder.path")) { p.sendMessage("§cKeine Berechtigung."); return true; }
            DeathPathState st = pathManager.state(p);

            if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                p.sendMessage("§7Pfad: " + (st.visible ? "§aAN" : "§cAUS") + " §8| §7Modus: §f" + st.mode);
                return true;
            }
            if (args[0].equalsIgnoreCase("on")) {
                st.visible = true;  p.sendMessage(color(getConfig().getString("messages.path-on"))); return true;
            }
            if (args[0].equalsIgnoreCase("off")) {
                st.visible = false; p.sendMessage(color(getConfig().getString("messages.path-off"))); return true;
            }
            if (args[0].equalsIgnoreCase("mode")) {
                if (args.length < 2) { p.sendMessage("§7/deathpath mode <foot|boat>"); return true; }
                PathMode m = PathMode.fromString(args[1]);
                st.mode = m;
                st.clearPath();
                p.sendMessage("§7Modus gesetzt: §f" + m);
                return true;
            }
            p.sendMessage("§7/deathpath [on|off|mode <foot|boat>|status]");
            return true;
        }

        return false;
    }
}
