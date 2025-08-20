package dev.yourserver.simpledeathfinder;

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

// Spigot ActionBar
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

public class SimpleDeathFinderPlugin extends JavaPlugin implements Listener {

    // Tagging keys
    private NamespacedKey KEY_TAG, KEY_W, KEY_X, KEY_Y, KEY_Z, KEY_OWNER;

    // Last death pos per player (for respawn give)
    private final Map<UUID, Location> lastDeathLocation = new HashMap<>();

    // Config
    private boolean giveOnRespawn;
    private boolean removeEnabled;
    private double removeRadius;
    private long checkInterval;
    private boolean removeOld;
    private String msgGiven, msgArrived, msgFull, msgOtherDim;
    private String msgStatus, msgDifferentWorld, msgNoTarget;
    private String itemName;
    private List<String> itemLore;
    private String soundArrived;

    // Tasks
    private int taskIdRemove = -1;
    private int taskIdHud = -1;

    // Track HUD visibility per player, to clear when needed
    private final Set<UUID> hudShown = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigDefaults();
        loadCfg();

        KEY_TAG   = new NamespacedKey(this, "sdf");
        KEY_W     = new NamespacedKey(this, "target_world");
        KEY_X     = new NamespacedKey(this, "target_x");
        KEY_Y     = new NamespacedKey(this, "target_y");
        KEY_Z     = new NamespacedKey(this, "target_z");
        KEY_OWNER = new NamespacedKey(this, "owner_name");

        Bukkit.getPluginManager().registerEvents(this, this);

        startRemoveChecker();
        startDistanceHud();

        getLogger().info("SimpleDeathFinder aktiviert (simple distance HUD, multi-compass).");
    }

    @Override
    public void onDisable() {
        stopRemoveChecker();
        stopDistanceHud();
    }

    /** Merge defaults from JAR into existing config without overwriting existing values. */
    private void ensureConfigDefaults() {
        saveDefaultConfig();
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                getLogger().warning("config.yml not found in JAR.");
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            FileConfiguration cfg = getConfig();
            cfg.addDefaults(defaults);
            cfg.options().copyDefaults(true);
            saveConfig();
            reloadConfig();
        } catch (Exception ex) {
            getLogger().warning("Could not merge defaults into config.yml: " + ex.getMessage());
        }
    }

    private void loadCfg() {
        FileConfiguration c = getConfig();
        giveOnRespawn = c.getBoolean("give-on-respawn", true);
        removeEnabled = c.getConfigurationSection("remove-on-approach").getBoolean("enabled", true);
        removeRadius  = c.getConfigurationSection("remove-on-approach").getDouble("radius", 6.0);
        checkInterval = c.getConfigurationSection("remove-on-approach").getLong("check-interval-ticks", 20L);
        removeOld     = c.getBoolean("remove-old-compasses", false);

        msgGiven     = color(c.getString("messages.given", "&aReceived recovery compass."));
        msgArrived   = color(c.getString("messages.arrived", "&7You reached your death location. The compass crumbles."));
        msgFull      = color(c.getString("messages.full-inventory", "&eInventory full – the compass was dropped in front of you."));
        msgOtherDim  = color(c.getString("messages.other-dimension", "&eYour death location is in another dimension – the compass spins."));

        msgStatus         = color(c.getString("messages.path-status", "&bTarget:&f {dist}m &7(&f{dim} @ {x},{y},{z}&7)"));
        msgDifferentWorld = color(c.getString("messages.path-different-world", "&eTarget is in another dimension – no distance."));
        msgNoTarget       = color(c.getString("messages.path-no-target", "&eNo death target available."));

        itemName     = color(c.getString("item.name", "&bRecovery Compass"));
        itemLore     = new ArrayList<>();
        for (String line : c.getStringList("item.lore")) itemLore.add(color(line));

        soundArrived = c.getString("sound.arrived", "entity.experience_orb.pickup");
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }

    // === Events ===

    @EventHandler (priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Location deathLoc = p.getLocation().clone();
        lastDeathLocation.put(p.getUniqueId(), deathLoc);

        // IMPORTANT:
        // Do NOT retag any dropped compasses here.
        // We keep their original targets so old compasses still lead to their old death points.
        // Also, we do NOT spawn a new compass on death anymore.
    }

    @EventHandler (priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        if (!giveOnRespawn) return;

        Bukkit.getScheduler().runTask(this, () -> {
            Location death = lastDeathLocation.get(p.getUniqueId());
            if (removeOld) removeTaggedCompasses(p.getInventory()); // default false – keep multiple compasses
            giveCompass(p, death); // tag owner + (if available) this death coords
        });
    }

    @EventHandler (priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { /* nothing */ }

    // === Compass creation / tagging ===

    private void tagCompass(ItemStack is, Location death, String ownerName) {
        if (is == null || is.getType() != Material.RECOVERY_COMPASS) return;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return;

        List<String> lore = new ArrayList<>(itemLore);
        if (death != null) {
            String dimName = prettyDim(death.getWorld());
            for (int i = 0; i < lore.size(); i++) {
                lore.set(i, lore.get(i)
                        .replace("{dim}", dimName)
                        .replace("{x}", String.valueOf(death.getBlockX()))
                        .replace("{y}", String.valueOf(death.getBlockY()))
                        .replace("{z}", String.valueOf(death.getBlockZ()))
                        .replace("{owner}", ownerName == null ? "Unknown" : ownerName)
                );
            }
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_TAG, PersistentDataType.BYTE, (byte)1);
            pdc.set(KEY_W, PersistentDataType.STRING, death.getWorld().getUID().toString());
            pdc.set(KEY_X, PersistentDataType.INTEGER, death.getBlockX());
            pdc.set(KEY_Y, PersistentDataType.INTEGER, death.getBlockY());
            pdc.set(KEY_Z, PersistentDataType.INTEGER, death.getBlockZ());
            if (ownerName != null) pdc.set(KEY_OWNER, PersistentDataType.STRING, ownerName);
        } else {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_TAG, PersistentDataType.BYTE, (byte)1);
            if (ownerName != null) pdc.set(KEY_OWNER, PersistentDataType.STRING, ownerName);
        }
        meta.setDisplayName(itemName);
        meta.setLore(lore);
        is.setItemMeta(meta);
    }

    private void giveCompass(Player p, Location death) {
        ItemStack is = new ItemStack(Material.RECOVERY_COMPASS, 1);
        tagCompass(is, death, p.getName());

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

    private String ownerFromItem(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return "Unknown";
        String o = meta.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);
        return (o == null || o.isEmpty()) ? "Unknown" : o;
    }

    private String prettyDim(World w) {
        if (w == null) return "Unknown";
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

    // === Distance HUD (ActionBar while holding compass) ===

    private void startDistanceHud() {
        stopDistanceHud();
        taskIdHud = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack hand = p.getInventory().getItemInMainHand();
                boolean show = false;

                if (hand != null && hand.getType() == Material.RECOVERY_COMPASS && isTaggedCompass(hand)) {
                    Location target = readTargetFromItem(hand);
                    if (target != null) {
                        if (!sameWorld(p.getWorld(), target.getWorld())) {
                            sendActionBar(p, msgDifferentWorld);
                            show = true;
                        } else {
                            double dist = p.getLocation().distance(target);
                            String line = msgStatus
                                    .replace("{dist}", String.valueOf((int) dist))
                                    .replace("{x}", String.valueOf(target.getBlockX()))
                                    .replace("{y}", String.valueOf(target.getBlockY()))
                                    .replace("{z}", String.valueOf(target.getBlockZ()))
                                    .replace("{dim}", prettyDim(target.getWorld()))
                                    .replace("{owner}", ownerFromItem(hand));
                            sendActionBar(p, line);
                            show = true;
                        }
                    } else {
                        sendActionBar(p, msgNoTarget);
                        show = true;
                    }
                }

                UUID id = p.getUniqueId();
                if (!show) {
                    if (hudShown.remove(id)) {
                        sendActionBar(p, " "); // clear
                    }
                } else {
                    hudShown.add(id);
                }
            }
        }, 20L, 10L); // start after 1s, then every 0.5s
    }

    private void stopDistanceHud() {
        if (taskIdHud != -1) {
            Bukkit.getScheduler().cancelTask(taskIdHud);
            taskIdHud = -1;
        }
        hudShown.clear();
    }

    private void sendActionBar(Player p, String legacyColored) {
        BaseComponent[] comps = TextComponent.fromLegacyText(legacyColored);
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, comps);
    }

    // === Arrival removal (works for any tagged compass) ===

    private void startRemoveChecker() {
        stopRemoveChecker();
        if (!removeEnabled) return;

        taskIdRemove = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
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
                        int amount = it.getAmount();
                        if (amount <= 1) inv.setItem(slot, null);
                        else { it.setAmount(amount - 1); inv.setItem(slot, it); }

                        p.sendMessage(msgArrived);
                        try {
                            Sound s = Sound.valueOf(getConfig().getString("sound.arrived", "ENTITY_EXPERIENCE_ORB_PICKUP").toUpperCase(Locale.ROOT));
                            p.playSound(p.getLocation(), s, 1f, 1f);
                        } catch (IllegalArgumentException ignored) {}

                        break; // one per tick
                    }
                }
            }
        }, 40L, Math.max(5L, checkInterval));
    }

    private void stopRemoveChecker() {
        if (taskIdRemove != -1) {
            Bukkit.getScheduler().cancelTask(taskIdRemove);
            taskIdRemove = -1;
        }
    }

    // === Commands ===
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("sdf")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("simpledeathfinder.reload")) { s.sendMessage("§cKeine Berechtigung."); return true; }
            reloadConfig(); ensureConfigDefaults(); loadCfg();
            stopRemoveChecker(); startRemoveChecker();
            stopDistanceHud(); startDistanceHud();
            s.sendMessage("§aSimpleDeathFinder reloaded.");
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
}
