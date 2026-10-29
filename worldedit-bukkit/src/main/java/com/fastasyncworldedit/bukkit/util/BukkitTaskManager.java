package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.wrappers.WorldWrapper;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bukkit side task handling.
 * <p>
 * On a single threaded server everything that has to run "on the server" is queued on the Bukkit scheduler. On a
 * regionised server (Folia) there is no such thread: work is either location bound (region scheduler), entity bound
 * (entity scheduler) or global (global region scheduler), and the region schedulers are the only place world state may
 * be touched.
 * </p>
 */
public class BukkitTaskManager extends TaskManager {

    private static final AtomicInteger TASK_ID = new AtomicInteger();
    private static final Map<Integer, ScheduledTask> SCHEDULED_TASKS = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final boolean folia;

    public BukkitTaskManager(final Plugin plugin) {
        this.plugin = plugin;
        this.folia = PaperSupport.isFolia();
    }

    private int track(@Nullable ScheduledTask task) {
        if (task == null) {
            return -1;
        }
        final int id = TASK_ID.incrementAndGet();
        SCHEDULED_TASKS.put(id, task);
        return id;
    }

    @Override
    public boolean isFolia() {
        return this.folia;
    }

    @Override
    public boolean isTickingThread() {
        if (!this.folia) {
            return Fawe.isMainThread();
        }
        // Folia reports true for every tick thread here (the global region thread and all region threads), while the
        // async pools report false. Region ownership still has to be checked with ownsRegion.
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isGlobalTickThread() {
        if (!this.folia) {
            return Fawe.isMainThread();
        }
        return ThreadingContextHolder.isGlobalThread();
    }

    @Override
    public boolean ownsRegion(@Nullable com.sk89q.worldedit.world.World world, int chunkX, int chunkZ) {
        if (!this.folia) {
            return Fawe.isMainThread();
        }
        final org.bukkit.World bukkitWorld = toBukkitWorld(world);
        return bukkitWorld != null && Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ);
    }

    @Override
    public void executeAt(
            @Nullable com.sk89q.worldedit.world.World world,
            int chunkX,
            int chunkZ,
            @Nonnull Runnable runnable
    ) {
        if (!this.folia) {
            task(runnable);
            return;
        }
        final org.bukkit.World bukkitWorld = toBukkitWorld(world);
        if (bukkitWorld == null) {
            task(runnable);
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ)) {
            runnable.run();
            return;
        }
        Bukkit.getRegionScheduler().execute(this.plugin, bukkitWorld, chunkX, chunkZ, runnable);
    }

    @Override
    public <T> CompletableFuture<T> submitAt(
            @Nullable com.sk89q.worldedit.world.World world,
            int chunkX,
            int chunkZ,
            @Nonnull Supplier<T> supplier
    ) {
        if (!this.folia) {
            return super.submitAt(world, chunkX, chunkZ, supplier);
        }
        final org.bukkit.World bukkitWorld = toBukkitWorld(world);
        final CompletableFuture<T> result = new CompletableFuture<>();
        final Runnable call = () -> {
            try {
                result.complete(supplier.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };
        if (bukkitWorld == null) {
            task(call);
        } else if (Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ)) {
            call.run();
        } else {
            Bukkit.getRegionScheduler().execute(this.plugin, bukkitWorld, chunkX, chunkZ, call);
        }
        return result;
    }

    @Override
    public int repeat(@Nonnull final Runnable runnable, final int interval) {
        if (!this.folia) {
            return this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(this.plugin, runnable, interval, interval);
        }
        return track(Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                this.plugin,
                task -> runInGlobalContext(runnable),
                interval,
                interval
        ));
    }

    @Override
    public int repeatAsync(@Nonnull final Runnable runnable, final int interval) {
        if (!this.folia) {
            return this.plugin.getServer().getScheduler().scheduleAsyncRepeatingTask(this.plugin, runnable, interval, interval);
        }
        return track(Bukkit.getAsyncScheduler().runAtFixedRate(
                this.plugin,
                task -> runnable.run(),
                Math.max(1L, interval) * 50L,
                Math.max(1L, interval) * 50L,
                TimeUnit.MILLISECONDS
        ));
    }

    @Override
    public void async(@Nonnull final Runnable runnable) {
        if (!this.folia) {
            this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, runnable);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> runnable.run());
    }

    @Override
    public void task(@Nonnull final Runnable runnable) {
        if (!this.folia) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, runnable);
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> runInGlobalContext(runnable));
    }

    @Override
    public void later(@Nonnull final Runnable runnable, final int delay) {
        if (!this.folia) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, runnable, delay);
            return;
        }
        track(Bukkit.getGlobalRegionScheduler().runDelayed(
                this.plugin,
                task -> runInGlobalContext(runnable),
                Math.max(1L, delay)
        ));
    }

    @Override
    public void laterAsync(@Nonnull final Runnable runnable, final int delay) {
        if (!this.folia) {
            this.plugin.getServer().getScheduler().runTaskLaterAsynchronously(this.plugin, runnable, delay);
            return;
        }
        track(Bukkit.getAsyncScheduler().runDelayed(
                this.plugin,
                task -> runnable.run(),
                Math.max(1L, delay) * 50L,
                TimeUnit.MILLISECONDS
        ));
    }

    @Override
    public void cancel(final int task) {
        if (task == -1) {
            return;
        }
        final ScheduledTask scheduledTask = SCHEDULED_TASKS.remove(task);
        if (scheduledTask != null) {
            scheduledTask.cancel();
        } else if (!this.folia) {
            Bukkit.getScheduler().cancelTask(task);
        }
    }

    @Override
    public void taskWhenFree(@Nonnull Runnable run) {
        if (isTickingThread()) {
            run.run();
            return;
        }
        if (!this.folia) {
            Fawe.instance().getQueueHandler().sync(run);
            return;
        }
        executeGlobal(run);
    }

    @Override
    public <T> T syncWhenFree(@Nonnull final com.fastasyncworldedit.core.util.task.RunnableVal<T> function) {
        return sync(function);
    }

    @Override
    public <T> T syncWhenFree(@Nonnull final Supplier<T> supplier) {
        return sync(supplier);
    }

    @Override
    public <T> T sync(final Supplier<T> function) {
        if (!this.folia) {
            return super.sync(function);
        }
        if (isGlobalTickThread()) {
            return function.get();
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        async(() -> {
            try {
                future.complete(function.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void runInGlobalContext(Runnable runnable) {
        final boolean previous = ThreadingContextHolder.isGlobalThread();
        ThreadingContextHolder.setGlobalThread(true);
        try {
            runnable.run();
        } finally {
            ThreadingContextHolder.setGlobalThread(previous);
        }
    }

    private org.bukkit.World toBukkitWorld(@Nullable com.sk89q.worldedit.world.World world) {
        if (world == null) {
            return null;
        }
        final com.sk89q.worldedit.world.World unwrapped = WorldWrapper.unwrap(world);
        return BukkitAdapter.adapt(unwrapped);
    }

    /**
     * Tracks whether the current thread is executing one of this class' region/global callbacks. Region threads are not
     * identifiable through the API, so callbacks are flagged as they enter.
     */
    static final class ThreadingContextHolder {

        private static final ThreadLocal<Boolean> GLOBAL = ThreadLocal.withInitial(() -> Boolean.FALSE);
        private static final ThreadLocal<Boolean> TICK = ThreadLocal.withInitial(() -> Boolean.FALSE);

        private ThreadingContextHolder() {
        }

        static boolean isGlobalThread() {
            return GLOBAL.get();
        }

        static void setGlobalThread(boolean value) {
            GLOBAL.set(value);
        }

        static boolean isTickThread() {
            return TICK.get();
        }
    }

}
