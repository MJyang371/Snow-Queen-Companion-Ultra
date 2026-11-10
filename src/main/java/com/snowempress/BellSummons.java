package com.snowempress;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** One active bell summon per player, shared by all dimensions. */
final class BellSummons extends SavedData {
    private final Map<UUID, UUID> active = new HashMap<>();

    static BellSummons get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(BellSummons::load,
                BellSummons::new, "snowempress_bell_summons");
    }

    private static BellSummons load(CompoundTag tag) {
        BellSummons result = new BellSummons();
        for (String key : tag.getAllKeys()) {
            if (tag.hasUUID(key)) {
                result.active.put(UUID.fromString(key), tag.getUUID(key));
            }
        }
        return result;
    }

    UUID activeFor(UUID owner) {
        return active.get(owner);
    }

    void bind(UUID owner, UUID empress) {
        active.put(owner, empress);
        setDirty();
    }

    void release(UUID owner, UUID empress) {
        if (active.remove(owner, empress)) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        active.forEach((owner, empress) -> tag.putUUID(owner.toString(), empress));
        return tag;
    }
}
