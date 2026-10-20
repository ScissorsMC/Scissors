package io.github.scissorsmc.util;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.equipment.Equippable;

public final class UnsafeEquipment {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong NEXT_WARNING = new AtomicLong();
    private static final ThreadLocal<long[]> SANITATION_REVISION = ThreadLocal.withInitial(() -> new long[1]);
    private static final Identifier PUMPKIN_OVERLAY = Identifier.withDefaultNamespace("misc/pumpkinblur");

    private UnsafeEquipment() {}

    public static boolean isPlayerSlot(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET -> true;
            default -> false;
        };
    }

    public static boolean isSafeOverlay(EquipmentSlot slot, Identifier overlay) {
        return slot == EquipmentSlot.HEAD && PUMPKIN_OVERLAY.equals(overlay);
    }

    public static long sanitationRevision() {
        return SANITATION_REVISION.get()[0];
    }

    public static void removed(String reason) {
        ++SANITATION_REVISION.get()[0];
        warn(reason);
    }

    public static Equippable restrictPlayerTargets(Equippable equipment) {
        if (isPlayerSlot(equipment.slot())) return equipment;
        var allowed = equipment.allowedEntities();
        // Bootstrap tags cannot be enumerated. Both codecs repeat this restriction when they write bound data.
        if (allowed.isPresent() && (!allowed.get().isBound() || !allowed.get().contains(EntityTypes.PLAYER.builtInRegistryHolder()))) return equipment;
        var entities = allowed.isPresent() ? allowed.get().stream() : BuiltInRegistries.ENTITY_TYPE.listElements();
        Optional<HolderSet<net.minecraft.world.entity.EntityType<?>>> safe = Optional.of(HolderSet.direct(entities.filter(type -> type.value() != EntityTypes.PLAYER).toList()));
        removed("Removed player target from animal equipment slot " + equipment.slot().getSerializedName());
        return new Equippable(equipment.slot(), equipment.equipSound(), equipment.assetId(), equipment.cameraOverlay(), safe,
            equipment.dispensable(), equipment.swappable(), equipment.damageOnHurt(), equipment.equipOnInteract(), equipment.canBeSheared(), equipment.shearingSound());
    }

    public static void warn(String message) {
        long now = net.minecraft.util.Util.getMillis();
        long next = NEXT_WARNING.get();
        if (now >= next && NEXT_WARNING.compareAndSet(next, now + 300_000L)) {
            LOGGER.warn("{}; suppressing further unsafe-equipment warnings for five minutes", message);
        }
    }

    public static void restoreAttributeEffect(net.minecraft.world.item.enchantment.effects.EnchantmentLocationBasedEffect effect,
                                             int level, EquipmentSlot slot, net.minecraft.world.entity.LivingEntity entity) {
        if (effect instanceof net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect attribute) {
            var instance = entity.getAttributes().getInstance(attribute.attribute());
            if (instance != null) instance.addOrUpdateTransientModifier(attribute.getModifier(level, slot));
        } else if (effect instanceof net.minecraft.world.item.enchantment.effects.AllOf.LocationBasedEffects group) {
            for (var child : group.effects()) restoreAttributeEffect(child, level, slot, entity);
        }
    }
}
