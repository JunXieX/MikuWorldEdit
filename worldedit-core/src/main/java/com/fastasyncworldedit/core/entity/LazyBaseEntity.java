package com.fastasyncworldedit.core.entity;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.world.entity.EntityType;
import org.enginehub.linbus.tree.LinCompoundTag;

import javax.annotation.Nullable;
import java.util.function.Supplier;

public class LazyBaseEntity extends BaseEntity {

    private Supplier<LinCompoundTag> saveTag;
    private final com.sk89q.worldedit.util.Location location;

    public LazyBaseEntity(EntityType type, Supplier<LinCompoundTag> saveTag) {
        this(type, saveTag, null);
    }

    /**
     * @param location location of the entity, used to read the data on the thread owning its region
     */
    public LazyBaseEntity(EntityType type, Supplier<LinCompoundTag> saveTag, @Nullable com.sk89q.worldedit.util.Location location) {
        super(type);
        this.saveTag = saveTag;
        this.location = location;
    }

    @Nullable
    @Override
    public LinCompoundTag getNbt() {
        Supplier<LinCompoundTag> tmp = saveTag;
        if (tmp != null) {
            // the entity data may only be read on the thread owning its region; the supplier is kept until the read
            // succeeded so a failed hop does not silently drop the entity data
            if (location == null) {
                setNbt(TaskManager.taskManager().sync(tmp));
            } else {
                final com.sk89q.worldedit.math.BlockVector3 position = location.toVector().toBlockPoint();
                final com.sk89q.worldedit.world.World world =
                        com.fastasyncworldedit.core.wrappers.WorldWrapper.unwrap(location.getExtent());
                final int chunkX = position.x() >> 4;
                final int chunkZ = position.z() >> 4;
                if (TaskManager.taskManager().ownsRegion(world, chunkX, chunkZ)) {
                    setNbt(tmp.get());
                } else {
                    setNbt(TaskManager.taskManager().submitAt(world, chunkX, chunkZ, tmp).join());
                }
            }
            saveTag = null;
        }
        return super.getNbt();
    }

}
