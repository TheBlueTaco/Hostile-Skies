package dev.thebluetaco.hostileskies.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class HelmChainsEntity extends Mob {

    /** Wheel's yaw rotation in degrees (from Direction.toYRot) */
    private static final EntityDataAccessor<Float> WHEEL_YAW =
            SynchedEntityData.defineId(HelmChainsEntity.class, EntityDataSerializers.FLOAT);
    /** Whether the wheel is upright (true) or upside down (false) */
    private static final EntityDataAccessor<Boolean> WHEEL_ON_FLOOR =
            SynchedEntityData.defineId(HelmChainsEntity.class, EntityDataSerializers.BOOLEAN);

    public HelmChainsEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
        setInvisible(true);
        setSilent(true);
        setPersistenceRequired();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(WHEEL_YAW, 0f);
        builder.define(WHEEL_ON_FLOOR, false);
    }

    @Override
    protected void registerGoals() {}

    @Override
    public void travel(Vec3 travelVector) {}

    // Wheel orientation accessors

    public void setWheelYaw(float yaw) { this.entityData.set(WHEEL_YAW, yaw); }
    public float getWheelYaw() { return this.entityData.get(WHEEL_YAW); }

    public void setWheelOnFloor(boolean floor) { this.entityData.set(WHEEL_ON_FLOOR, floor); }
    public boolean isWheelOnFloor() { return this.entityData.get(WHEEL_ON_FLOOR); }

    @Override public boolean hurt(DamageSource s, float a) { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isInvulnerable() { return true; }
    @Override protected boolean shouldDropLoot() { return false; }

    /** Never persisted, derived state owned by RaidManager. The reconciler
     *  re-creates it (with correct Sable attachment) within one tick of the ship being
     *  loaded and tracked. Saving it would only produce detached ghosts on reload */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 1.0);
    }
}
