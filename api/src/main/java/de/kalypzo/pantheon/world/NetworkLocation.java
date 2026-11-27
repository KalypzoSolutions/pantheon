package de.kalypzo.pantheon.world;

public record NetworkLocation(
        String serverName, String worldName, double x, double y, double z, float yaw, float pitch) {}
