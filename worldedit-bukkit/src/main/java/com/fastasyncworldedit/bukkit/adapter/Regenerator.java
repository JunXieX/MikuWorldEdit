package com.fastasyncworldedit.bukkit.adapter;

import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.SingleThreadQueueExtent;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkHolder;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.RegenOptions;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Represents an abstract regeneration handler.
 */
public abstract class Regenerator {

    protected final org.bukkit.World originalBukkitWorld;
    protected final Region region;
    protected final Extent target;
    protected final RegenOptions options;

    //runtime
    protected long seed;
    protected SingleThreadQueueExtent source;

    /**
     * Initializes an abstract regeneration handler.
     *
     * @param originalBukkitWorld the Bukkit world containing all the information on how to regenerate the {code Region}
     * @param region              the selection to regenerate
     * @param target              the target {@code Extent} to paste the regenerated blocks into
     * @param options             the options to used while regenerating and pasting into the target {@code Extent}
     */
    public Regenerator(org.bukkit.World originalBukkitWorld, Region region, Extent target, RegenOptions options) {
        this.originalBukkitWorld = originalBukkitWorld;
        this.region = region;
        this.target = target;
        this.options = options;
    }

    /**
     * Regenerates the selected {@code Region}.
     *
     * @return whether the regeneration process was successful
     * @throws Exception when something goes terribly wrong
     */
    public boolean regenerate() throws Exception {
        if (!prepare()) {
            return false;
        }

        try {
            if (!initNewWorld()) {
                cleanup0();
                return false;
            }
        } catch (Exception e) {
            cleanup0();
            throw e;
        }

        try {
            copyToWorld();
        } catch (Exception e) {
            cleanup0();
            throw e;
        }

        cleanup0();
        return true;
    }

    /**
     * Execute tasks on the main thread during regen.
     */
    protected abstract void runTasks(BooleanSupplier shouldKeepTicking);

    private void createSource() {

        source = new SingleThreadQueueExtent(
                BukkitWorld.HAS_MIN_Y ? originalBukkitWorld.getMinHeight() : 0,
                BukkitWorld.HAS_MIN_Y ? originalBukkitWorld.getMaxHeight() : 256
        );
        source.init(target, initSourceQueueCache(), null);
    }

    private void copyToWorld() {
        createSource();
        if (TaskManager.taskManager().isFolia()) {
            copyToWorldRegionised();
            return;
        }
        final Pattern pattern = createPattern();
        final long timeoutPerTick = TimeUnit.MILLISECONDS.toNanos(10);
        int taskId = TaskManager.taskManager().repeat(() -> {
            final long startTime = System.nanoTime();
            runTasks(() -> System.nanoTime() - startTime < timeoutPerTick);
        }, 1);
        target.setBlocks(region, pattern);
        TaskManager.taskManager().cancel(taskId);
    }

    private Pattern createPattern() {
        final boolean genbiomes = options.shouldRegenBiomes();
        final boolean hasBiome = options.hasBiomeType();
        final BiomeType biome = options.getBiomeType();
        if (!genbiomes && !hasBiome) {
            return new PlacementPattern();
        } else if (hasBiome) {
            return new WithBiomePlacementPattern((ignored1, ignored2) -> biome);
        }
        return new WithBiomePlacementPattern((vec, chunk) -> {
            if (chunk != null) {
                return chunk.getBiomeType(vec.x() & 15, vec.y(), vec.z() & 15);
            }
            return source.getBiome(vec);
        });
    }

    /**
     * Copies the regenerated blocks on a regionised server.
     * <p>
     * The temporary level is not part of the regioniser, so its chunks are read on this (worker) thread, one chunk at a
     * time. Each chunk is immediately reduced to plain data - block ids, block entity NBT and biomes - and written
     * before the next one is touched. That keeps the memory cost at one chunk regardless of the region size, and the
     * write side still goes through the regular queue, so the actual blocks are placed by the region owning them.
     * </p>
     *
     */
    private void copyToWorldRegionised() {
        final boolean captureBiomes = options.shouldRegenBiomes();
        final BiomeType singleBiome = options.hasBiomeType() ? options.getBiomeType() : null;
        final BlockVector3 min = region.getMinimumPoint();
        final BlockVector3 max = region.getMaximumPoint();
        for (BlockVector2 chunkPos : region.getChunks()) {
            final SourceChunk snapshot = snapshotChunk(chunkPos, captureBiomes);
            if (snapshot == SourceChunk.EMPTY) {
                // the source queue refused the chunk (outside the safe range), nothing to copy
                continue;
            }
            target.setBlocks(chunkSlice(chunkPos, min, max), new SnapshotPattern(snapshot, singleBiome));
        }
    }

    private Region chunkSlice(final BlockVector2 chunkPos, final BlockVector3 min, final BlockVector3 max) {
        final int fromX = Math.max(min.x(), chunkPos.x() << 4);
        final int fromZ = Math.max(min.z(), chunkPos.z() << 4);
        final int toX = Math.min(max.x(), (chunkPos.x() << 4) + 15);
        final int toZ = Math.min(max.z(), (chunkPos.z() << 4) + 15);
        return new CuboidRegion(
                region.getWorld(),
                BlockVector3.at(fromX, min.y(), fromZ),
                BlockVector3.at(toX, max.y(), toZ)
        );
    }

    /**
     * Reads one generated chunk into plain data. Runs on the worker thread; the temporary level allows asynchronous
     * loading from there, and none of the accessors used here need a ticking thread.
     */
    private SourceChunk snapshotChunk(final BlockVector2 chunkPos, final boolean captureBiomes) {
        final IChunk chunk = source.getOrCreateChunk(chunkPos.x(), chunkPos.z());
        if (!(chunk instanceof ChunkHolder<?> holder)) {
            return SourceChunk.EMPTY;
        }
        final IChunkGet get = holder.getOrCreateGet();
        final int minSection = get.getMinSectionPosition();
        final int maxSection = get.getMaxSectionPosition();
        final char[][] sections = new char[maxSection - minSection + 1][];
        for (int layer = minSection; layer <= maxSection; layer++) {
            final char[] data = get.load(layer);
            sections[layer - minSection] = data == null ? null : data.clone();
        }
        final Map<BlockVector3, FaweCompoundTag> tiles = new HashMap<>(get.tiles());
        BiomeType[][] biomes = null;
        if (captureBiomes) {
            biomes = new BiomeType[sections.length][];
            for (int index = 0; index < sections.length; index++) {
                if (sections[index] == null) {
                    continue;
                }
                final int y = (index + minSection) << 4;
                final BiomeType[] sectionBiomes = new BiomeType[64];
                for (int cellY = 0; cellY < 4; cellY++) {
                    for (int cellZ = 0; cellZ < 4; cellZ++) {
                        for (int cellX = 0; cellX < 4; cellX++) {
                            sectionBiomes[(cellY << 4) | (cellZ << 2) | cellX] =
                                    get.getBiomeType(cellX << 2, y + (cellY << 2), cellZ << 2);
                        }
                    }
                }
                biomes[index] = sectionBiomes;
            }
        }
        return new SourceChunk(chunkPos.x(), chunkPos.z(), sections, minSection, tiles, biomes);
    }

    /**
     * A generated chunk reduced to plain data: block ids per section, block entity NBT and (optionally) biomes.
     */
    private static final class SourceChunk {

        private static final SourceChunk EMPTY = new SourceChunk(0, 0, new char[0][], 0, Map.of(), null);

        private final int chunkX;
        private final int chunkZ;
        private final char[][] sections;
        private final int minSection;
        private final Map<BlockVector3, FaweCompoundTag> tiles;
        private final BiomeType[][] biomes;

        private SourceChunk(
                final int chunkX,
                final int chunkZ,
                final char[][] sections,
                final int minSection,
                final Map<BlockVector3, FaweCompoundTag> tiles,
                final BiomeType[][] biomes
        ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sections = sections;
            this.minSection = minSection;
            this.tiles = tiles;
            this.biomes = biomes;
        }

        private char ordinal(final int x, final int y, final int z) {
            final int index = (y >> 4) - minSection;
            if (index < 0 || index >= sections.length) {
                return BlockTypesCache.ReservedIDs.AIR;
            }
            final char[] section = sections[index];
            if (section == null) {
                return BlockTypesCache.ReservedIDs.AIR;
            }
            return section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        }

        private BlockState state(final int x, final int y, final int z) {
            return BlockTypesCache.states[ordinal(x, y, z)];
        }

        private BaseBlock block(final int x, final int y, final int z) {
            final BlockState state = state(x, y, z);
            if (tiles.isEmpty()) {
                return state.toBaseBlock();
            }
            final FaweCompoundTag tile = tiles.get(BlockVector3.at((chunkX << 4) + (x & 15), y, (chunkZ << 4) + (z & 15)));
            return tile == null ? state.toBaseBlock() : state.toBaseBlock(tile.linTag());
        }

        private BiomeType biome(final int x, final int y, final int z) {
            if (biomes == null) {
                return null;
            }
            final int index = (y >> 4) - minSection;
            if (index < 0 || index >= biomes.length || biomes[index] == null) {
                return null;
            }
            return biomes[index][(((y & 15) >> 2) << 4) | ((z & 15) >> 2) << 2 | ((x & 15) >> 2)];
        }

    }

    /**
     * Serves the snapshotted blocks and biomes. Only reads plain data, so it is safe on any thread.
     */
    private static final class SnapshotPattern implements Pattern {

        private final SourceChunk snapshot;
        private final BiomeType singleBiome;

        private SnapshotPattern(final SourceChunk snapshot, final BiomeType singleBiome) {
            this.snapshot = snapshot;
            this.singleBiome = singleBiome;
        }

        @Override
        public <T extends IChunk> T applyChunk(final T chunk, final Region region) {
            return chunk;
        }

        @Override
        public void finishChunk(final IChunk chunk) {
            // nothing to reset, the snapshot is stateless
        }

        @Override
        public BaseBlock applyBlock(final BlockVector3 position) {
            return snapshot.block(position.x() & 15, position.y(), position.z() & 15);
        }

        @Override
        public boolean apply(final Extent extent, final BlockVector3 get, final BlockVector3 set) throws WorldEditException {
            final boolean placed = extent.setBlock(
                    set.x(),
                    set.y(),
                    set.z(),
                    snapshot.block(get.x() & 15, get.y(), get.z() & 15)
            );
            final BiomeType biome = singleBiome != null ? singleBiome : snapshot.biome(get.x() & 15, get.y(), get.z() & 15);
            if (biome != null) {
                extent.setBiome(set.x(), set.y(), set.z(), biome);
            }
            return placed;
        }

        @Override
        public Pattern fork() {
            return this;
        }

    }

    private abstract class ChunkwisePattern implements Pattern {
        // the chunk we're currently operating on, if any.
        // allows faster access to chunk data than source.getFullBlock(...)
        protected @Nullable IChunk chunk;

        @Override
        public @NotNull <T extends IChunk> T applyChunk(final T chunk, @Nullable final Region region) {
            this.chunk = source.getOrCreateChunk(chunk.getX(), chunk.getZ());
            return chunk;
        }

        @Override
        public void finishChunk(final IChunk chunk) {
            this.chunk = null;
        }

        @Override
        public abstract Pattern fork();

    }

    private class PlacementPattern extends ChunkwisePattern {

        @Override
        public BaseBlock applyBlock(final BlockVector3 position) {
            return source.getFullBlock(position);
        }

        @Override
        public boolean apply(final Extent extent, final BlockVector3 get, final BlockVector3 set) throws WorldEditException {
            BaseBlock fullBlock;
            if (chunk != null) {
                fullBlock = chunk.getFullBlock(get.x() & 15, get.y(), get.z() & 15);
            } else {
                fullBlock = source.getFullBlock(get.x(), get.y(), get.z());
            }
            return set.setFullBlock(extent, fullBlock);
        }

        @Override
        public Pattern fork() {
            return new PlacementPattern();
        }
    }

    private class WithBiomePlacementPattern extends ChunkwisePattern {

        private final BiFunction<BlockVector3, @Nullable IChunk, BiomeType> biomeGetter;

        private WithBiomePlacementPattern(final BiFunction<BlockVector3, @Nullable IChunk, BiomeType> biomeGetter) {
            this.biomeGetter = biomeGetter;
        }

        @Override
        public BaseBlock applyBlock(final BlockVector3 position) {
            return source.getFullBlock(position);
        }

        @Override
        public boolean apply(final Extent extent, final BlockVector3 get, final BlockVector3 set) throws WorldEditException {
            final BaseBlock fullBlock;
            if (chunk != null) {
                fullBlock = chunk.getFullBlock(get.x() & 15, get.y(), get.z() & 15);
            } else {
                fullBlock = source.getFullBlock(get.x(), get.y(), get.z());
            }
            return extent.setBlock(set.x(), set.y(), set.z(), fullBlock)
                    && extent.setBiome(set.x(), set.y(), set.z(), biomeGetter.apply(get, chunk));
        }

        @Override
        public Pattern fork() {
            return new WithBiomePlacementPattern(this.biomeGetter);
        }
    }

    //functions to be implemented by sub class
    private void cleanup0() {
        cleanup();
    }

    /**
     * <p>Implement the preparation process in here. DO NOT instanciate any variable here that require the cleanup function. This function is for gathering further information before initializing a new
     * world.</p>
     *
     * <p>Fields required to be initialized: chunkStati, seed</p>
     * <p>For chunkStati also see {code ChunkStatusWrapper}.</p>
     *
     * @return whether or not the preparation process was successful
     */
    protected abstract boolean prepare();

    /**
     * Implement the creation of the seperate world in here.
     * <p>
     * Fields required to be initialized: generateConcurrent
     *
     * @return true if everything went fine, otherwise false. When false is returned the Regenerator halts the regeneration process and calls the cleanup function.
     * @throws java.lang.Exception When the implementation of this method throws and exception the Regenerator halts the regeneration process and calls the cleanup function.
     */
    protected abstract boolean initNewWorld() throws Exception;

    //functions to implement by sub class - regenate related

    /**
     * Implement the cleanup of all the mess that is created during the regeneration process (initNewWorld() and generate()).This function must not throw any exceptions.
     */
    protected abstract void cleanup();

    /**
     * Implement the initialization an {@code IChunkCache<IChunkGet>} here. Use will need the {@code getChunkAt} function
     *
     * @return an initialized {@code IChunkCache<IChunkGet>}
     */
    protected abstract IChunkCache<IChunkGet> initSourceQueueCache();

    protected BiomeProvider getBiomeProvider() {
        if (options.hasBiomeType()) {
            return new SingleBiomeProvider();
        }
        return originalBukkitWorld.getBiomeProvider();
    }

    //classes

    public enum Concurrency {
        FULL,
        RADIUS,
        NONE
    }

    public class SingleBiomeProvider extends BiomeProvider {

        private final org.bukkit.block.Biome biome = BukkitAdapter.adapt(options.getBiomeType());

        @Override
        public org.bukkit.block.Biome getBiome(final WorldInfo worldInfo, final int x, final int y, final int z) {
            return biome;
        }

        @Override
        public List<org.bukkit.block.Biome> getBiomes(final WorldInfo worldInfo) {
            return Collections.singletonList(biome);
        }

    }

}
