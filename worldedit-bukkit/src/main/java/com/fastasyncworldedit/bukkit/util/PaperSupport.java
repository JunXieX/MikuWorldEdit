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

    /**
     * Whether the current thread is the one that runs work not tied to a location.
     * <p>
     * {@code Bukkit.isGlobalTickThread()} only exists on newer API versions than the one this project compiles against,
     * so it is looked up once. When it is missing the answer falls back to "not the global thread", which makes callers
     * hop instead of running inline - slower, never wrong.
     * </p>
     *
     * @return true when the current thread may run global work without a hop
     */
    public static boolean isGlobalTickThread() {
        if (!FOLIA) {
            return org.bukkit.Bukkit.isPrimaryThread();
        }
        if (GLOBAL_TICK_THREAD != null) {
            return GLOBAL_TICK_THREAD.getAsBoolean();
        }
        // Older regionised builds do not expose the predicate. The thread that runs a global task is remembered
        // instead, so a caller can still tell whether hopping is needed - guessing "no" here would deadlock.
        return Thread.currentThread() == GLOBAL_THREAD;
    }

    /**
     * Records the thread a task submitted to the global region scheduler runs on. Only used when the platform does not
     * expose {@code Bukkit.isGlobalTickThread()}.
     */
    public static void recordGlobalThread() {
        GLOBAL_THREAD = Thread.currentThread();
    }

    /**
     * @return true when callers have to determine the global thread themselves
     */
    public static boolean needsGlobalThreadTracking() {
        return FOLIA && GLOBAL_TICK_THREAD == null;
    }

    private static volatile Thread GLOBAL_THREAD;

    private static final java.util.function.BooleanSupplier GLOBAL_TICK_THREAD = lookupGlobalTickThread();

    private static java.util.function.BooleanSupplier lookupGlobalTickThread() {
        try {
            final java.lang.reflect.Method method = org.bukkit.Bukkit.class.getMethod("isGlobalTickThread");
            return () -> {
                try {
                    return (boolean) method.invoke(null);
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            };
        } catch (NoSuchMethodException e) {
            return null;
        }
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
