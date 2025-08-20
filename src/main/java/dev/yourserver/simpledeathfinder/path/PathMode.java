package dev.yourserver.simpledeathfinder.path;

public enum PathMode {
    FOOT, BOAT;

    public static PathMode fromString(String s) {
        if (s == null) return FOOT;
        switch (s.toLowerCase()) {
            case "boat": return BOAT;
            default:     return FOOT;
        }
    }
}
