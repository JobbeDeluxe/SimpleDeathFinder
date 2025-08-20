package dev.yourserver.simpledeathfinder.path;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DeathPathState {
    public boolean visible = false;
    public PathMode mode = PathMode.FOOT;
    public UUID lastDeathWorldId = null;
    public Location target = null;
    public List<Location> currentPath = new ArrayList<>();
    public long lastComputeMs = 0L;

    public void clearPath() {
        currentPath.clear();
    }
}
