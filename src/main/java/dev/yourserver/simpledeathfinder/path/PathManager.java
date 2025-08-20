package dev.yourserver.simpledeathfinder.path;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class PathManager implements Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey keyTag, keyW, keyX, keyY, keyZ;

    // Config
    private final boolean enabledByDefault;
    private final int renderEvery;
    private final int recomputeEvery;
    private final int segmentLen;
    private final int maxNodes;
    private final boolean autoBoatMode;
    private final Particle particleType;
    private final double particleSpacing;
    private final float particleSize;
    private final Particle waypointParticle;
    private final int waypointEvery;

    private int taskId = -1;

    private final Map<UUID, DeathPathState> states = new HashMap<>();

    public PathManager(JavaPlugin plugin, NamespacedKey keyTag, NamespacedKey keyW, NamespacedKey keyX, NamespacedKey keyY, NamespacedKey keyZ) {
        this.plugin = plugin;
        this.keyTag = keyTag; this.keyW = keyW; this.keyX = keyX; this.keyY = keyY; this.keyZ = keyZ;

        FileConfiguration c = plugin.getConfig();
        enabledByDefault  = c.getBoolean("path.enabled-by-default", true);
        renderEvery       = Math.max(2, c.getInt("path.render-interval-ticks", 10));
        recomputeEvery    = Math.max(2, c.getInt("path.recompute-interval-ticks", 20));
        segmentLen        = Math.max(32, c.getInt("path.segment-length", 96));
        maxNodes          = Math.max(1000, c.getInt("path.max-search-nodes", 6000));
        autoBoatMode      = c.getBoolean("path.auto-boat-mode", true);

        particleType      = parseParticle(c.getString("path.particle.type", "REDSTONE"));
        particleSpacing   = Math.max(0.2, c.getDouble("path.particle.spacing-blocks", 1.0));
        particleSize      = (float) Math.max(0.2, c.getDouble("path.particle.size", 1.1));
        waypointParticle  = parseParticle(c.getString("path.waypoint.particle", "END_ROD"));
        waypointEvery     = Math.max(6, c.getInt("path.waypoint.every-blocks", 12));

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private Particle parseParticle(String s) {
        try {
            return Particle.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Particle.END_ROD;
        }
    }

    public DeathPathState state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new DeathPathState());
    }

    public void setTargetFromDeath(Player p, Location deathLoc) {
        if (deathLoc == null) return;
        DeathPathState st = state(p);
        st.target = deathLoc.clone();
        st.lastDeathWorldId = deathLoc.getWorld().getUID();
        if (autoBoatMode && p.isInsideVehicle() && p.getVehicle() instanceof Boat) {
            st.mode = PathMode.BOAT;
        } else {
            st.mode = PathMode.FOOT;
        }
        if (enabledByDefault) st.visible = true;
        st.clearPath();
    }

    public void start() {
        stop();
        // Gemeinsamer Ticker: rendern + ggf. re-segmentierte Suche
        taskId = new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                tick++;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    DeathPathState st = states.get(p.getUniqueId());
                    if (st == null) continue;
                    if (!st.visible) continue;

                    // Dimension check
                    if (st.target == null) continue;
                    if (!sameWorld(p.getWorld(), st.target.getWorld())) {
                        p.sendActionBar(mm(plugin.getConfig().getString("messages.path-different-world")));
                        continue;
                    }

                    // Modus Auto‑Boot
                    if (autoBoatMode && p.isInsideVehicle() && p.getVehicle() instanceof Boat) {
                        if (st.mode != PathMode.BOAT) { st.mode = PathMode.BOAT; st.clearPath(); }
                    }

                    // Recompute in Intervallen ODER wenn kein Pfad existiert
                    if (tick % recomputeEvery == 0 || st.currentPath.isEmpty()) {
                        computeSegment(p, st);
                    }

                    if (tick % renderEvery == 0) {
                        render(p, st);
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 2L).getTaskId(); // sehr fein getaktet; intern gedrosselt
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private boolean sameWorld(World a, World b) {
        return a != null && b != null && a.getUID().equals(b.getUID());
    }

    private Component mm(String legacy) {
        return Component.text(ChatColor.translateAlternateColorCodes('&', legacy == null ? "" : legacy));
    }

    // === Player-Interaction: Rechtsklick auf Recovery-Compass ===
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() == EquipmentSlot.OFF_HAND) return;
        if (e.getItem() == null || e.getItem().getType() != Material.RECOVERY_COMPASS) return;
        if (!e.getAction().isRightClick()) return;

        // Nur unsere getaggten Kompasse
        if (!isTaggedCompass(e.getItem())) return;

        Player p = e.getPlayer();
        DeathPathState st = state(p);

        if (p.isSneaking() || (p.isInsideVehicle() && p.getVehicle() instanceof Boat)) {
            // Modus toggeln
            st.mode = (st.mode == PathMode.FOOT ? PathMode.BOAT : PathMode.FOOT);
            st.clearPath();
            p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    st.mode == PathMode.FOOT ?
                            plugin.getConfig().getString("messages.path-mode-foot") :
                            plugin.getConfig().getString("messages.path-mode-boat")));
        } else {
            // Sichtbarkeit toggeln
            st.visible = !st.visible;
            p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    st.visible ?
                            plugin.getConfig().getString("messages.path-on") :
                            plugin.getConfig().getString("messages.path-off")));
        }
    }

    private boolean isTaggedCompass(ItemStack it) {
        if (it == null || it.getType() != Material.RECOVERY_COMPASS) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte tag = pdc.get(keyTag, PersistentDataType.BYTE);
        return tag != null && tag == (byte)1;
    }

    // === Rendering ===
    private void render(Player p, DeathPathState st) {
        if (st.currentPath.isEmpty()) return;

        // Partikel entlang des Pfads
        Particle.DustOptions dust = null;
        if (particleType == Particle.REDSTONE) {
            dust = new Particle.DustOptions(Color.fromRGB(60, 200, 255), particleSize);
        }

        double acc = 0.0;
        Location last = null;
        int counter = 0;

        for (Location step : st.currentPath) {
            if (last == null) { last = step; continue; }
            double segLen = last.distance(step);
            acc += segLen;
            if (acc >= particleSpacing) {
                Location loc = step.clone().add(0.5, 0.1, 0.5);
                if (particleType == Particle.REDSTONE) {
                    p.spawnParticle(Particle.REDSTONE, loc, 1, dust);
                } else {
                    p.spawnParticle(particleType, loc, 1, 0,0,0, 0.0);
                }
                acc = 0.0;
            }
            if ((++counter) % waypointEvery == 0) {
                Location wloc = step.clone().add(0.5, 1.0, 0.5);
                p.spawnParticle(waypointParticle, wloc, 6, 0.2, 0.3, 0.2, 0.01);
            }
            last = step;
        }

        // Distanzanzeige
        double dist = p.getLocation().distance(st.target);
        p.sendActionBar(Component.text(ChatColor.AQUA + "Ziel: " + (int)dist + "m  [" + st.mode + "]"));
    }

    // === Inkrementelles A*: von Spielerposition Richtung target bis segmentLen ===
    private void computeSegment(Player p, DeathPathState st) {
        Location start = surfaceStandable(p.getLocation());
        Location goal  = st.target;

        if (start == null || goal == null) return;

        if (!p.getWorld().isChunkLoaded(goal.getBlockX() >> 4, goal.getBlockZ() >> 4)) {
            // Zielchunk nicht geladen -> später erneut versuchen
            return;
        }

        List<Location> segment = aStarSegment(p.getWorld(), start, goal, st.mode, segmentLen, maxNodes);
        if (!segment.isEmpty()) {
            st.currentPath = segment;
        }
    }

    private Location surfaceStandable(Location base) {
        World w = base.getWorld();
        int x = base.getBlockX();
        int z = base.getBlockZ();
        int y = Math.min(255, base.getBlockY() + 2);

        // Suche kleine Korrektur nach unten für Stehhöhe
        for (int dy = 2; dy >= -3; dy--) {
            int yy = base.getBlockY() + dy;
            if (yy < w.getMinHeight() + 1 || yy > w.getMaxHeight() - 2) continue;
            if (isStandable(w, x, yy, z)) return new Location(w, x, yy, z);
        }
        return null;
    }

    private boolean isStandable(World w, int x, int y, int z) {
        Block below = w.getBlockAt(x, y - 1, z);
        Block feet  = w.getBlockAt(x, y, z);
        Block head  = w.getBlockAt(x, y + 1, z);
        return below.getType().isSolid() && feet.isPassable() && head.isPassable();
    }

    private boolean isBoatPass(World w, int x, int y, int z) {
        Block water = w.getBlockAt(x, y, z);
        Block air   = w.getBlockAt(x, y + 1, z);
        return water.getType() == Material.WATER && air.isPassable();
    }

    private double stepCost(World w, int x, int y, int z, PathMode mode) {
        Material mFeet = w.getBlockAt(x, y, z).getType();
        boolean isWater = (mFeet == Material.WATER);
        if (mode == PathMode.FOOT) {
            return isWater ? 6.0 : 1.0; // Wasser meiden
        } else {
            return isWater ? 1.0 : 8.0; // Land meiden
        }
    }

    private static final int[][] DIRS = {
            { 1, 0}, { -1, 0}, {0, 1}, {0, -1},
            { 1, 1}, { 1,-1}, {-1,1}, {-1,-1}
    };

    private List<Location> aStarSegment(World w, Location start, Location goal, PathMode mode, int maxLen, int nodeCap) {
        class N {
            final int x, y, z;
            final double g, f;
            final N prev;
            N(int x, int y, int z, double g, double f, N prev) { this.x=x; this.y=y; this.z=z; this.g=g; this.f=f; this.prev=prev; }
            long key() { return (((long)(x & 0x3FFFFF)) << 42) | (((long)(y & 0x3FF)) << 32) | ((long)(z & 0x3FFFFF)); }
        }

        int sx = start.getBlockX(), sy = start.getBlockY(), sz = start.getBlockZ();
        int gx = goal.getBlockX(),  gy = goal.getBlockY(),  gz = goal.getBlockZ();

        PriorityQueue<N> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        HashSet<Long> closed = new HashSet<>();

        open.add(new N(sx, sy, sz, 0.0, 0.0, null));

        int expanded = 0;
        N best = null;

        while (!open.isEmpty() && expanded < nodeCap) {
            N cur = open.poll();
            if (!w.isChunkLoaded(cur.x >> 4, cur.z >> 4)) continue;
            if (closed.contains(cur.key())) continue;
            closed.add(cur.key());
            expanded++;

            // Abbruch: Segmentlänge oder Nähe zum Ziel
            double distToGoal = Math.abs(cur.x - gx) + Math.abs(cur.z - gz) + Math.abs(cur.y - gy) * 0.5;
            if (distToGoal < 3) { best = cur; break; }
            if (cur.prev != null) {
                double seg = start.distance(new Location(w, cur.x, cur.y, cur.z));
                if (seg >= maxLen) { best = cur; break; }
            }

            for (int[] d : DIRS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                // y-Korrektur (kleine Höhenwechsel zulassen)
                for (int dy = 1; dy >= -1; dy--) {
                    int ny = cur.y + dy;
                    if (ny < w.getMinHeight() + 1 || ny > w.getMaxHeight() - 2) continue;

                    boolean pass;
                    if (mode == PathMode.FOOT) {
                        pass = isStandable(w, nx, ny, nz);
                    } else {
                        pass = isBoatPass(w, nx, ny, nz);
                    }
                    if (!pass) continue;

                    double step = (d[0] == 0 || d[1] == 0) ? 1.0 : 1.4142;
                    step += Math.abs(dy) * 0.25;
                    step += stepCost(w, nx, ny, nz, mode);

                    double ng = cur.g + step;
                    double h  = Math.abs(nx - gx) + Math.abs(nz - gz) + Math.abs(ny - gy) * 0.5;
                    double nf = ng + h * 0.9;

                    N n = new N(nx, ny, nz, ng, nf, cur);
                    if (!closed.contains(n.key())) open.add(n);
                }
            }
        }

        if (best == null) return Collections.emptyList();

        LinkedList<Location> path = new LinkedList<>();
        N t = best;
        while (t != null) {
            path.addFirst(new Location(w, t.x, t.y, t.z));
            t = t.prev;
        }
        return path;
    }
}
