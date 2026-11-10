package com.snowempress;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.common.util.ITeleporter;
import twilightforest.init.TFSounds;
import twilightforest.init.TFParticleType;
import twilightforest.util.EntityUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public final class SnowEmpressEntity extends TamableAnimal {
    private static final EntityDataAccessor<Float> SPIN_YAW_OFFSET = SynchedEntityData.defineId(
            SnowEmpressEntity.class, EntityDataSerializers.FLOAT);
    private static final float NO_SPIN = -1.0F;
    private static final EntityDataAccessor<Boolean> AIMED_FLIGHT = SynchedEntityData.defineId(
            SnowEmpressEntity.class, EntityDataSerializers.BOOLEAN);
    private static final double RECALL_DISTANCE_SQUARED = 64.0 * 64.0;
    private static final String TICKET_TAG = "LoadedChunk";
    private static final String BELL_SUMMON_TAG = "BellSummon";
    private static final String HUD_PET_TAG = "SnowEmpressLatestPet";
    private static final boolean COMBAT_HUD_ENABLED = false;
    private static final int MAX_SUMMONED_CRYSTALS = 6;
    private final List<SummonedIceCrystal> summonedCrystals = new ArrayList<>();
    private final MovementCoordinator movement = new MovementCoordinator(this);
    private final CarryFlight carryFlight = new CarryFlight(this, movement);
    private final SnowEmpressCombat combat = new SnowEmpressCombat(this, movement, new UtilityCombatPolicy(this));
    static final int SHIELD_COUNT = 7;
    private final SnowEmpressIceShield[] iceShields = new SnowEmpressIceShield[SHIELD_COUNT];
    private ChunkPos ticketChunk;
    private boolean ticketChecked;
    private boolean bellSummon;
    private boolean accompaniedSleep;
    private ServerPlayer aiOwner;

    public SnowEmpressEntity(EntityType<? extends SnowEmpressEntity> type, Level level) {
        super(type, level);
        for (int i = 0; i < iceShields.length; i++) {
            iceShields[i] = new SnowEmpressIceShield(this);
        }
        setId(ENTITY_COUNTER.getAndAdd(iceShields.length + 1) + 1);
        moveControl = new MovementCoordinator.FlightControl(this);
        setNoGravity(true);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 200.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.75)
                .add(Attributes.ATTACK_DAMAGE, 7.0)
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.FLYING_SPEED, 0.35);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(SPIN_YAW_OFFSET, NO_SPIN);
        entityData.define(AIMED_FLIGHT, false);
    }

    public void setGunshipSettings(GunshipSettings settings) {
        combat.setGunshipSettings(java.util.Objects.requireNonNull(settings));
    }

    /** Sets the flight limits for the next retreat. */
    public void setRetreatSettings(RetreatSettings settings) {
        combat.setRetreatSettings(java.util.Objects.requireNonNull(settings));
    }

    /** Sets the duration of the next standalone spinning breath, in game ticks. */
    public void setSpinBreathDurationTicks(int ticks) {
        combat.setSpinDurationTicks(ticks, false);
    }

    /** Sets the duration of the next spinning breath that follows a slam, in game ticks. */
    public void setSlamSpinDurationTicks(int ticks) {
        combat.setSpinDurationTicks(ticks, true);
    }

    boolean isInAimedFlight() {
        return entityData.get(AIMED_FLIGHT);
    }

    void setAimedFlight(boolean flying) {
        entityData.set(AIMED_FLIGHT, flying);
    }

    boolean isSpinning() {
        return isAlive() && entityData.get(SPIN_YAW_OFFSET) != NO_SPIN;
    }

    void startSpin(float facingYaw) {
        entityData.set(SPIN_YAW_OFFSET, Mth.positiveModulo(facingYaw - spinPhase(0.0F), 360.0F));
    }

    void stopSpin() {
        entityData.set(SPIN_YAW_OFFSET, NO_SPIN);
    }

    float spinYaw(float tickOffset) {
        return entityData.get(SPIN_YAW_OFFSET) + spinPhase(tickOffset);
    }

    private float spinPhase(float tickOffset) {
        return (level().getGameTime() % 15 + tickOffset) * 24.0F;
    }

    @Override
    public void knockback(double strength, double x, double z) {
        if (!isSpinning()) {
            super.knockback(strength, x, z);
        }
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
        navigation.setCanFloat(true);
        return navigation;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        ServerLevel server = (ServerLevel) level();
        ServerPlayer owner = getOwnerUUID() == null ? null
                : server.getServer().getPlayerList().getPlayer(getOwnerUUID());
        boolean remote = owner != null && owner.serverLevel() != server;
        if (owner == null || remote || owner.isRemoved() || !owner.isAlive() || owner.isSpectator()) {
            ejectPassengers();
            carryFlight.resetOwner();
            accompaniedSleep = false;
            aiOwner = null;
            stopCombat(remote && owner.isAlive() && !owner.isSpectator()
                    ? SnowEmpressCombat.InterruptReason.CROSS_DIMENSION
                    : SnowEmpressCombat.InterruptReason.OWNER_UNAVAILABLE);
            return;
        }
        if (aiOwner != owner) {
            accompaniedSleep = false;
            combat.reconnectOwner(owner);
            aiOwner = owner;
        }
        if (carryFlight.tick(owner)) {
            return;
        }
        boolean sleepingHere = owner.isSleeping() && owner.getSleepingPos()
                .filter(bed -> bed.distToCenterSqr(owner.getX(), owner.getY(), owner.getZ()) <= 4).isPresent();
        if (sleepingHere) {
            stopCombat(SnowEmpressCombat.InterruptReason.OWNER_SLEEPING);
            accompanySleepingOwner(owner);
            if (distanceToSqr(owner) > RECALL_DISTANCE_SQUARED && tickCount % 10 == 0) {
                recall(owner);
            }
            faceSleepingOwner(owner);
        } else {
            accompaniedSleep = false;
            updateAwakeBehavior(owner);
        }
        if (COMBAT_HUD_ENABLED && tickCount % 10 == 0) {
            updateCombatHud(owner);
        }
    }

    private void updateAwakeBehavior(ServerPlayer owner) {
        double distance = distanceToSqr(owner);
        if (distance > RECALL_DISTANCE_SQUARED) {
            stopCombat(SnowEmpressCombat.InterruptReason.RECALL);
            if (tickCount % 10 == 0) {
                recall(owner);
            }
        } else if (!combat.tick(owner) && tickCount % 10 == 0) {
            if (distance > 16.0) {
                movement.follow(owner.position());
            } else {
                movement.stopFollowing();
            }
        }
    }

    SnowEmpressCombat combat() {
        return combat;
    }

    private void accompanySleepingOwner(Player owner) {
        if (accompaniedSleep) {
            return;
        }
        CombatSpace space = new CombatSpace(this);
        Vec3 bed = owner.getSleepingPos().map(Vec3::atBottomCenterOf).orElse(owner.position());
        var bedDirection = owner.getBedOrientation();
        if (bedDirection != null) {
            var footward = bedDirection.getOpposite();
            // Leave enough distance for the face to fit in the horizontal sleep camera.
            for (int distance = 3; distance >= 2; distance--) {
                Vec3 point = bed.add(footward.getStepX() * distance, 0, footward.getStepZ() * distance);
                if (space.fit(getBoundingBox().move(point.subtract(position())), true) == CombatSpace.Fit.CLEAR) {
                    accompaniedSleep = teleportNearOwner(point);
                    return;
                }
            }
        }
        Vec3 nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (int radius = 1; radius <= 2 && nearest == null; radius++) {
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                Vec3 point = bed.add(side.getStepX() * radius, 0, side.getStepZ() * radius);
                double distance = position().distanceToSqr(point);
                if (distance < best && space.fit(getBoundingBox().move(point.subtract(position())), true)
                        == CombatSpace.Fit.CLEAR) {
                    best = distance;
                    nearest = point;
                }
            }
        }
        if (nearest != null) {
            accompaniedSleep = teleportNearOwner(nearest);
        }
    }

    private void faceSleepingOwner(Player owner) {
        getLookControl().setLookAt(owner, 90.0F, 90.0F);
        Vec3 aim = owner.getEyePosition().subtract(getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, aim.horizontalDistance()));
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        setXRot(pitch);
    }

    void stopCombat(SnowEmpressCombat.InterruptReason reason) {
        combat.clearTarget(reason);
        movement.stopFollowing();
    }

    @Override
    public void tame(Player player) {
        super.tame(player);
        if (player instanceof ServerPlayer owner) {
            rememberHudPet(owner);
        }
    }

    void bindBellSummon(Player player) {
        bellSummon = true;
        tame(player);
    }

    private void releaseBellSummon(ServerLevel server) {
        if (bellSummon && getOwnerUUID() != null) {
            BellSummons.get(server.getServer()).release(getOwnerUUID(), getUUID());
        }
    }

    private void rememberHudPet(ServerPlayer owner) {
        CompoundTag data = owner.getPersistentData();
        CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putUUID(HUD_PET_TAG, getUUID());
        data.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private void updateCombatHud(ServerPlayer owner) {
        CompoundTag persisted = owner.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (!persisted.hasUUID(HUD_PET_TAG)) {
            // Existing saves do not record which pet was tamed most recently.
            rememberHudPet(owner);
        } else if (!getUUID().equals(persisted.getUUID(HUD_PET_TAG))) {
            return;
        }
        LivingEntity target = getTarget();
        owner.displayClientMessage(Component.translatable("hud.snowempress.combat",
                Math.round(getHealth()), Math.round(getMaxHealth()),
                target == null ? Component.translatable("hud.snowempress.no_target") : target.getDisplayName(),
                Component.translatable(combat.nextSkillKey()), summonedCrystalCount(), MAX_SUMMONED_CRYSTALS), true);
    }

    int summonedCrystalCount() {
        summonedCrystals.removeIf(crystal -> !crystal.isAlive());
        return summonedCrystals.size();
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (dead && level() instanceof ServerLevel server) {
            ejectPassengers();
            carryFlight.stop();
            releaseBellSummon(server);
            stopCombat(SnowEmpressCombat.InterruptReason.DEATH);
            dismissCrystals();
        }
    }

    private void dismissCrystals() {
        summonedCrystals.forEach(SummonedIceCrystal::vanish);
        summonedCrystals.clear();
    }

    String summonIceCrystals(LivingEntity target) {
        int available = MAX_SUMMONED_CRYSTALS - summonedCrystalCount();
        if (available == 0) {
            summonedCrystals.forEach(crystal -> crystal.retarget(target));
            return "CRYSTALS_RETARGETED";
        }
        boolean spawned = false;
        for (int i = 0; i < Math.min(2, available); i++) {
            SummonedIceCrystal crystal = AddonContent.SUMMONED_ICE_CRYSTAL.get().create(level());
            if (crystal == null) {
                break;
            }
            for (int attempt = 0; attempt < 20; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double radius = 1.7 + random.nextDouble() * 1.8;
                Vec3 pos = position().add(Math.cos(angle) * radius, 0.3 + random.nextDouble() * 2,
                        Math.sin(angle) * radius);
                if (!level().hasChunkAt(BlockPos.containing(pos))) {
                    continue;
                }
                crystal.setPos(pos.x, pos.y, pos.z);
                AABB box = crystal.getBoundingBox();
                if (box.minY < level().getMinBuildHeight() || box.maxY > level().getMaxBuildHeight()
                        || !level().getWorldBorder().isWithinBounds(box)
                        || !level().noCollision(crystal, box) || level().containsAnyLiquid(box)
                        || !level().getEntitiesOfClass(LivingEntity.class, box).isEmpty()) {
                    continue;
                }
                crystal.bindSummoner(this, target);
                if (level().addFreshEntity(crystal)) {
                    if (!isAlive() || isRemoved()) {
                        crystal.vanish();
                        return null;
                    }
                    summonedCrystals.add(crystal);
                    spawned = true;
                }
                break;
            }
        }
        return spawned ? "CRYSTALS_SPAWNED" : null;
    }

    @Override
    public void tick() {
        if (bellSummon && isAlive() && level() instanceof ServerLevel server
                && !getUUID().equals(BellSummons.get(server.getServer()).activeFor(getOwnerUUID()))) {
            discard();
            return;
        }
        if (isSpinning()) {
            if (level().isClientSide()) {
                setDeltaMovement(Vec3.ZERO);
            } else {
                movement.maintainSpinHold();
            }
        }
        super.tick();
        if (isAlive()) {
            updateIceShields();
            if (level().isClientSide()) {
                spawnSnowParticles();
            }
        }
        if (isAlive() && getOwnerUUID() != null && level() instanceof ServerLevel server) {
            if (tickCount % 10 == 0) {
                ServerPlayer owner = server.getServer().getPlayerList().getPlayer(getOwnerUUID());
                if (owner != null && owner.isAlive() && !owner.isSpectator()
                        && owner.serverLevel() != server && followAcrossDimensions(server, owner)) {
                    return;
                }
            }
            ChunkPos current = chunkPosition();
            if (!ticketChecked || !current.equals(ticketChunk)) {
                forceChunk(server, current, true);
                if (ticketChunk != null && !current.equals(ticketChunk)) {
                    forceChunk(server, ticketChunk, false);
                }
                ticketChunk = current;
                ticketChecked = true;
            }
        }
    }

    private void spawnSnowParticles() {
        for (int i = 0; i < 3; i++) {
            float x = (random.nextFloat() - random.nextFloat()) * 0.3F;
            float y = getEyeHeight() + (random.nextFloat() - random.nextFloat()) * 0.5F;
            float z = (random.nextFloat() - random.nextFloat()) * 0.3F;
            level().addParticle(TFParticleType.SNOW_GUARDIAN.get(), xOld + x, yOld + y, zOld + z,
                    0.0, 0.0, 0.0);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public SnowEmpressIceShield[] getParts() {
        return iceShields;
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (iceShields != null) {
            for (int i = 0; i < iceShields.length; i++) {
                iceShields[i].setId(id + i + 1);
            }
        }
    }

    Vec3 shieldOffset(int index, float tickOffset) {
        if (index == SHIELD_COUNT - 1) {
            return new Vec3(0.0, -1.0, 0.0);
        }
        // World time keeps the orbit in phase when a client starts tracking an existing empress.
        double angle = Math.toRadians(60.0 * index + 5.0 * (level().getGameTime() % 72 + tickOffset));
        return new Vec3(Math.cos(angle), 0.1, Math.sin(angle));
    }

    private void updateIceShields() {
        for (int i = 0; i < iceShields.length; i++) {
            SnowEmpressIceShield shield = iceShields[i];
            Vec3 position = position().add(shieldOffset(i, 0.0F));
            shield.setPos(position.x, position.y, position.z);
            if (level().isClientSide()) {
                continue;
            }
            AABB contact = shield.getBoundingBox().deflate(0.2);
            for (Entity collided : level().getEntities(shield, contact, Entity::isPushable)) {
                if (!canDamage(collided)) {
                    continue;
                }
                collided.push(shield);
                if (collided instanceof LivingEntity
                        && EntityUtil.properlyApplyCustomDamageSource(this, collided, freezeDamage(this, this))) {
                    collided.setDeltaMovement(collided.getDeltaMovement().add(0.0, 0.4, 0.0));
                    playSound(TFSounds.SNOW_QUEEN_ATTACK.get(), 1.0F, 1.0F);
                }
            }
        }
    }

    private boolean isProtectedByOwner(Entity entity, UUID owner) {
        if (owner == null) {
            return false;
        }
        return owner.equals(entity.getUUID()) || owner.equals(HungryCraftOwnership.ownerOf(entity));
    }

    boolean canDamage(Entity entity) {
        Entity unit = entity instanceof net.minecraftforge.entity.PartEntity<?> part ? part.getParent() : entity;
        return unit != this && !isProtectedByOwner(unit, getOwnerUUID())
                && factionDisposition(unit) != HungryCraftOwnership.Disposition.PROTECTED;
    }

    HungryCraftOwnership.Disposition factionDisposition(Entity unit) {
        return unit instanceof LivingEntity && getOwner() instanceof Player owner
                ? HungryCraftOwnership.disposition(owner, unit)
                : HungryCraftOwnership.Disposition.UNCLASSIFIED;
    }

    static DamageSource freezeDamage(Entity direct, Entity attacker) {
        return new DamageSource(attacker.damageSources().freeze().typeHolder(), direct, attacker);
    }

    private void recall(Player owner) {
        Vec3 destination = findRecallPosition(owner.level(), owner);
        if (destination != null) {
            teleportNearOwner(destination);
        }
    }

    private boolean teleportNearOwner(Vec3 destination) {
        var lease = movement.acquire(MovementCoordinator.Owner.LIFECYCLE, 0);
        if (lease == null) {
            return false;
        }
        try {
            movement.drive(lease, Vec3.ZERO);
            movement.teleport(lease, () -> teleportTo(destination.x, destination.y, destination.z));
            return true;
        } finally {
            movement.release(lease);
        }
    }

    Vec3 findRecallPosition(Level destinationLevel, Player owner) {
        BlockPos center = owner.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-3, 0, -3), center.offset(3, 3, 3))) {
            if (pos.distSqr(center) < 4 || !destinationLevel.hasChunkAt(pos)) {
                continue;
            }
            Vec3 destination = Vec3.atBottomCenterOf(pos);
            AABB box = getBoundingBox().move(destination.subtract(position()));
            if (box.minY >= destinationLevel.getMinBuildHeight() && box.maxY <= destinationLevel.getMaxBuildHeight()
                    && destinationLevel.getWorldBorder().isWithinBounds(box)
                    && destinationLevel.noCollision(this, box) && !destinationLevel.containsAnyLiquid(box)) {
                return destination;
            }
        }
        return null;
    }

    private boolean followAcrossDimensions(ServerLevel source, ServerPlayer owner) {
        ServerLevel destinationLevel = owner.serverLevel();
        Vec3 destination = findRecallPosition(destinationLevel, owner);
        if (destination == null) {
            return false;
        }

        stopCombat(SnowEmpressCombat.InterruptReason.CROSS_DIMENSION);
        var lease = movement.acquire(MovementCoordinator.Owner.LIFECYCLE, 0);
        Entity transferred;
        try {
            transferred = movement.changeDimension(lease, () -> transferToOwner(destinationLevel, destination));
        } finally {
            movement.release(lease);
        }
        if (transferred == null) {
            return false;
        }
        dismissCrystals();
        if (ticketChunk != null) {
            forceChunk(source, ticketChunk, false);
            ticketChunk = null;
            ticketChecked = false;
        }
        return true;
    }

    private Entity transferToOwner(ServerLevel destinationLevel, Vec3 destination) {
        return changeDimension(destinationLevel, new ITeleporter() {
            @Override
            public PortalInfo getPortalInfo(Entity entity, ServerLevel level,
                                            Function<ServerLevel, PortalInfo> defaultPortalInfo) {
                return new PortalInfo(destination, Vec3.ZERO, entity.getYRot(), entity.getXRot());
            }

            @Override
            public Entity placeEntity(Entity entity, ServerLevel current, ServerLevel target,
                                      float yaw, Function<Boolean, Entity> repositionEntity) {
                Entity result = repositionEntity.apply(false);
                if (result instanceof SnowEmpressEntity empress) {
                    // The copied NBT contains a ticket belonging to the source dimension.
                    empress.ticketChunk = null;
                    empress.ticketChecked = false;
                }
                return result;
            }
        });
    }

    private void forceChunk(ServerLevel level, ChunkPos chunk, boolean add) {
        ForgeChunkManager.forceChunk(level, SnowEmpress.MOD_ID, this, chunk.x, chunk.z, add, true);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide() && (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION)) {
            ejectPassengers();
            carryFlight.stop();
        }
        if (reason.shouldDestroy() && level() instanceof ServerLevel server) {
            releaseBellSummon(server);
            dismissCrystals();
        }
        if (!level().isClientSide()) {
            stopCombat(reason == RemovalReason.CHANGED_DIMENSION
                    ? SnowEmpressCombat.InterruptReason.CROSS_DIMENSION : SnowEmpressCombat.InterruptReason.REMOVED);
        }
        if ((reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION)
                && ticketChunk != null && level() instanceof ServerLevel server) {
            forceChunk(server, ticketChunk, false);
            ticketChunk = null;
            ticketChecked = false;
        }
        super.remove(reason);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(BELL_SUMMON_TAG, bellSummon);
        if (ticketChunk != null) {
            tag.putLong(TICKET_TAG, ticketChunk.toLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        bellSummon = tag.getBoolean(BELL_SUMMON_TAG);
        ticketChunk = tag.contains(TICKET_TAG) ? new ChunkPos(tag.getLong(TICKET_TAG)) : null;
        ticketChecked = false;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(player);
        if (isAlive() && getHealth() < getMaxHealth() && food != null && food.getNutrition() > 0) {
            if (!level().isClientSide()) {
                heal(food.getNutrition());
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
            }
            return InteractionResult.sidedSuccess(level().isClientSide());
        }
        if (isAlive() && isOwnedBy(player) && !isVehicle() && !player.isPassenger() && !player.isSleeping()) {
            if (level().isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            if (player instanceof ServerPlayer rider && carryFlight.mount(rider)) {
                return InteractionResult.CONSUME;
            }
        }
        return super.mobInteract(player, hand);
    }

    void acceptCarryInput(ServerPlayer player, int keys) {
        carryFlight.acceptInput(player, keys);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return passenger instanceof Player player && isOwnedBy(player) && !isVehicle();
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (hasPassenger(passenger)) {
            Vec3 seat = carryFlight.seat(getYRot());
            move.accept(passenger, seat.x, seat.y, seat.z);
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide()) {
            carryFlight.dismounted();
        }
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return passenger.position();
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }
}
