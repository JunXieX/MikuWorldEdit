package com.fastasyncworldedit.bukkit.util;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Not for external use. May change at any point.
 */
@ApiStatus.Internal
public enum PaperSupport {
    ;
    public static final boolean PAPER;
    public static final boolean FOLIA;

    static {
        PAPER = hasClass("com.destroystokyo.paper.PaperConfig")
            || hasClass("io.papermc.paper.configuration.Configuration");
        // The regionised server classes only exist on Folia (and Folia derived forks)
        FOLIA = hasClass("io.papermc.paper.threadedregions.RegionizedServer")
                || hasClass("io.papermc.paper.threadedregions.TickRegionScheduler");
    }

    public static boolean isPaper() {
        return PAPER;
    }

    /**
     * Whether the server ticks worlds on regionised threads instead of a single main thread.
     *
     * @return true when running on Folia (or a Folia derived fork)
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        if (isPaper()) {
            return entity.teleportAsync(location);
        }
        return CompletableFuture.completedFuture(entity.teleport(location));
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location, PlayerTeleportEvent.TeleportCause cause) {
        if (isPaper()) {
            return entity.teleportAsync(location, cause);
        }
        return CompletableFuture.completedFuture(entity.teleport(location, cause));
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(Location location) {
        return getChunkAtAsync(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, true);
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int cx, int cz, boolean gen) {
        return getChunkAtAsync(world, cx, cz, gen, false);
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int cx, int cz, boolean gen, boolean urgent) {
        if (isPaper()) {
            return world.getChunkAtAsync(cx, cz, gen, urgent);
        } else {
            return CompletableFuture.completedFuture(world.getChunkAt(cx, cz));
        }
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
