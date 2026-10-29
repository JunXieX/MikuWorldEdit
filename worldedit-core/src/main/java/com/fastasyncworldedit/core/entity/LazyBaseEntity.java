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
            saveTag = null;
            if (TaskManager.taskManager().isTickingThread()) {
                setNbt(tmp.get());
            } else if (location != null) {
                setNbt(TaskManager.taskManager().submitAt(
                        location.getWorld(),
                        location.getBlockX() >> 4,
                        location.getBlockZ() >> 4,
                        tmp
                ).join());
            } else {
                setNbt(TaskManager.taskManager().sync(tmp));
            }
        }
        return super.getNbt();
    }

}
