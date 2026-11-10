package com.snowempress;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

final class HungryCraftOwnership {
    private static final Method OWNER_OF = findOwnerLookup();
    private static final FactionApi FACTIONS = findFactionApi();
    private static boolean reportedLookupFailure;
    private static boolean reportedFactionFailure;

    enum Disposition { UNCLASSIFIED, HOSTILE, PROTECTED }

    private record FactionApi(Method faction, Method relation) {}

    private HungryCraftOwnership() {
    }

    static UUID ownerOf(Entity entity) {
        if (OWNER_OF != null) {
            try {
                return (UUID) OWNER_OF.invoke(null, entity);
            } catch (ReflectiveOperationException | LinkageError e) {
                if (!reportedLookupFailure) {
                    reportedLookupFailure = true;
                    SnowEmpress.LOGGER.warn("HungryCraft owner lookup failed; using vanilla ownership", e);
                }
            }
        }
        return entity instanceof OwnableEntity pet ? pet.getOwnerUUID() : null;
    }

    static Disposition disposition(Player owner, Entity unit) {
        if (FACTIONS == null || owner == null) {
            return Disposition.UNCLASSIFIED;
        }
        try {
            if (!"QUEEN".equals(enumName(FACTIONS.faction().invoke(null, owner)))
                    || "NONE".equals(enumName(FACTIONS.faction().invoke(null, unit)))) {
                return Disposition.UNCLASSIFIED;
            }
            // Ask how this unit sees the queen, including village exemptions and monster hostility tags.
            return "HOSTILE".equals(enumName(FACTIONS.relation().invoke(null, unit, owner)))
                    ? Disposition.HOSTILE : Disposition.PROTECTED;
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!reportedFactionFailure) {
                reportedFactionFailure = true;
                SnowEmpress.LOGGER.warn("HungryCraft faction lookup failed; using standard targeting", e);
            }
            return Disposition.UNCLASSIFIED;
        }
    }

    private static String enumName(Object value) {
        return ((Enum<?>) value).name();
    }

    private static FactionApi findFactionApi() {
        if (!ModList.get().isLoaded("spiderqueen_hungrycraft")) {
            return null;
        }
        try {
            ClassLoader loader = HungryCraftOwnership.class.getClassLoader();
            Class<?> resolver = Class.forName("spiderqueenhungrycraft.faction.FactionResolver", false, loader);
            Class<?> targeting = Class.forName("spiderqueenhungrycraft.faction.FactionTargeting", false, loader);
            return new FactionApi(resolver.getMethod("getFaction", Entity.class),
                    targeting.getMethod("getRelation", Entity.class, Entity.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            SnowEmpress.LOGGER.warn("HungryCraft faction API unavailable; using standard targeting", e);
            return null;
        }
    }

    private static Method findOwnerLookup() {
        if (!ModList.get().isLoaded("spiderqueen_hungrycraft")) {
            return null;
        }
        try {
            return Class.forName("spiderqueenhungrycraft.faction.QueenOwnerProtection", false,
                    HungryCraftOwnership.class.getClassLoader()).getMethod("ownerOf", Entity.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            SnowEmpress.LOGGER.warn("HungryCraft owner API unavailable; using vanilla ownership", e);
            return null;
        }
    }
}
