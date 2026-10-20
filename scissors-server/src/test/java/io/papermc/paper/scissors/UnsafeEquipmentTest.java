package io.papermc.paper.scissors;

import java.io.DataInputStream;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@AllFeatures
class UnsafeEquipmentTest {
    private static final class TestPlayer extends net.minecraft.world.entity.player.Player {
        private org.bukkit.craftbukkit.entity.CraftHumanEntity bukkit;
        TestPlayer(net.minecraft.world.level.Level level) {
            super(level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "EquipmentRegression"));
            this.setId(1);
        }
        @Override public net.minecraft.world.level.GameType gameMode() { return net.minecraft.world.level.GameType.SURVIVAL; }
        @Override public org.bukkit.craftbukkit.entity.CraftHumanEntity getBukkitEntity() {
            if (this.bukkit == null) this.bukkit = org.mockito.Mockito.mock(org.bukkit.craftbukkit.entity.CraftHumanEntity.class);
            return this.bukkit;
        }
        void loadTag(CompoundTag tag) {
            this.readAdditionalSaveData(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
        }
        CompoundTag saveTag() {
            var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, RegistryHelper.registryAccess());
            this.addAdditionalSaveData(output);
            return output.buildResult();
        }
    }

    @Test
    void trackedEquipmentKeepsItsSafeEffectsAfterRepair() throws Exception {
        var level = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        var config = net.minecraft.world.level.Level.class.getDeclaredField("spigotConfig");
        config.setAccessible(true);
        config.set(level, org.mockito.Mockito.mock(org.spigotmc.SpigotWorldConfig.class));
        org.mockito.Mockito.when(level.registryAccess()).thenReturn(RegistryHelper.registryAccess());
        org.mockito.Mockito.when(level.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
        org.mockito.Mockito.when(level.getChunkSource()).thenReturn(org.mockito.Mockito.mock(net.minecraft.server.level.ServerChunkCache.class));
        org.mockito.Mockito.when(org.bukkit.Bukkit.getServer().getPluginManager()).thenReturn(org.mockito.Mockito.mock(org.bukkit.plugin.PluginManager.class));
        TestPlayer player = new TestPlayer(level);
        ItemStack original = new ItemStack(Items.STICK);
        var id = Identifier.withDefaultNamespace("safe_equipment");
        original.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.MOVEMENT_SPEED,
            new AttributeModifier(id, 0.5, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build());
        player.getInventory().setSelectedItem(original);
        player.detectEquipmentUpdates();
        double expected = player.getAttribute(Attributes.MOVEMENT_SPEED).getValue();
        assertEquals(0.6, expected, 1.0e-6);
        ItemStack poisoned = original.copy();
        poisoned.set(DataComponents.ATTRIBUTE_MODIFIERS, original.get(DataComponents.ATTRIBUTE_MODIFIERS).withModifierAdded(Attributes.MOVEMENT_SPEED,
            new AttributeModifier(Identifier.withDefaultNamespace("unsafe_extra"), Double.NaN, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND));
        player.getInventory().setSelectedItem(poisoned);
        player.detectEquipmentUpdates();
        assertEquals(expected, player.getAttribute(Attributes.MOVEMENT_SPEED).getValue(), 1.0e-9);
        ItemStack hidden = new ItemStack(Items.STONE);
        hidden.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.MOVEMENT_SPEED,
            new AttributeModifier(id, 2, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.BODY).build());
        player.getInventory().equipment.set(EquipmentSlot.BODY, hidden);
        player.detectEquipmentUpdates();
        assertEquals(expected, player.getAttribute(Attributes.MOVEMENT_SPEED).getValue(), 1.0e-9);
        player.getInventory().setSelectedItem(ItemStack.EMPTY);
        player.detectEquipmentUpdates();
        assertEquals(0.1, player.getAttribute(Attributes.MOVEMENT_SPEED).getValue(), 1.0e-6);

        // Reload an already tracked player, as CraftPlayer.loadData does.
        player.getInventory().setSelectedItem(original);
        player.detectEquipmentUpdates();
        ItemStack replacement = new ItemStack(Items.STICK);
        replacement.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.MOVEMENT_SPEED,
            new AttributeModifier(Identifier.withDefaultNamespace("replacement"), 0.25, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build());
        CompoundTag saved = new CompoundTag();
        saved.put("Inventory", net.minecraft.world.ItemStackWithSlot.CODEC.listOf().encodeStart(ops(),
            List.of(new net.minecraft.world.ItemStackWithSlot(0, replacement))).getOrThrow());
        CompoundTag equipment = new CompoundTag();
        equipment.put("body", reportedTag());
        saved.put("equipment", equipment);
        player.loadTag(saved);
        player.detectEquipmentUpdates();
        assertEquals(0.35, player.getAttribute(Attributes.MOVEMENT_SPEED).getValue(), 1.0e-6);
        assertFalse(player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(id));

        ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
        var enchantment = RegistryHelper.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
            .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING);
        boots.enchant(enchantment, 1);
        player.getInventory().equipment.set(EquipmentSlot.FEET, boots);
        player.detectEquipmentUpdates();
        var attributeEffect = new net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect(
            Identifier.withDefaultNamespace("recovery_effect"), Attributes.MOVEMENT_SPEED,
            net.minecraft.world.item.enchantment.LevelBasedValue.constant(0.4F), AttributeModifier.Operation.ADD_VALUE);
        var otherEffect = org.mockito.Mockito.mock(net.minecraft.world.item.enchantment.effects.EnchantmentLocationBasedEffect.class);
        var group = net.minecraft.world.item.enchantment.effects.AllOf.locationBasedEffects(attributeEffect, otherEffect);
        player.activeLocationDependentEnchantments(EquipmentSlot.FEET).put(enchantment.value(), java.util.Set.of(group));
        player.getAttribute(Attributes.MOVEMENT_SPEED).addOrUpdateTransientModifier(attributeEffect.getModifier(1, EquipmentSlot.FEET));
        ItemStack collision = new ItemStack(Items.IRON_INGOT);
        collision.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.MOVEMENT_SPEED,
            attributeEffect.getModifier(1, EquipmentSlot.FEET), EquipmentSlotGroup.BODY).build());
        saved = player.saveTag();
        equipment = saved.getCompoundOrEmpty("equipment");
        equipment.put("body", ItemStack.CODEC.encodeStart(ops(), collision).getOrThrow());
        saved.put("equipment", equipment);
        player.loadTag(saved);
        player.detectEquipmentUpdates();
        assertEquals(0.75, player.getAttribute(Attributes.MOVEMENT_SPEED).getValue(), 1.0e-6);
        org.mockito.Mockito.verifyNoInteractions(otherEffect);
        for (int attempt = 0; attempt < 1000; attempt++) player.getInventory().equipment.set(EquipmentSlot.BODY, hidden);
        assertTrue(player.getItemBySlot(EquipmentSlot.BODY).isEmpty());
        assertTrue(player.saveTag().getListOrEmpty("ScissorsRecoveredEquipment").isEmpty());
    }

    @Test
    void fullInventoryRecoverySurvivesASaveAndReturnsItemsWhenSpaceIsAvailable() throws Exception {
        var level = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        var config = net.minecraft.world.level.Level.class.getDeclaredField("spigotConfig");
        config.setAccessible(true);
        config.set(level, org.mockito.Mockito.mock(org.spigotmc.SpigotWorldConfig.class));
        org.mockito.Mockito.when(level.registryAccess()).thenReturn(RegistryHelper.registryAccess());
        org.mockito.Mockito.when(level.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
        TestPlayer player = new TestPlayer(level);
        CompoundTag saved = new CompoundTag();
        CompoundTag equipment = new CompoundTag();
        equipment.put("body", reportedTag());
        saved.put("equipment", equipment);
        var inventory = new java.util.ArrayList<net.minecraft.world.ItemStackWithSlot>();
        for (int slot = 0; slot < 36; slot++) inventory.add(new net.minecraft.world.ItemStackWithSlot(slot, new ItemStack(Items.STONE, 64)));
        saved.put("Inventory", net.minecraft.world.ItemStackWithSlot.CODEC.listOf().encodeStart(ops(), inventory).getOrThrow());
        player.loadTag(saved);
        assertTrue(player.getItemBySlot(EquipmentSlot.BODY).isEmpty());
        CompoundTag persisted = player.saveTag();
        assertEquals(1, persisted.getListOrEmpty("ScissorsRecoveredEquipment").size());
        player.getInventory().setItem(0, ItemStack.EMPTY);
        persisted = player.saveTag();
        TestPlayer restored = new TestPlayer(level);
        restored.loadTag(persisted);
        assertReportedItemSafe(restored.getInventory().getItem(0));
        assertTrue(restored.saveTag().getListOrEmpty("ScissorsRecoveredEquipment").isEmpty());
        for (int slot = 1; slot < 36; slot++) assertEquals(64, restored.getInventory().getItem(slot).getCount());
    }

    @Test
    void ordinaryVanillaEquipmentAndFiniteCustomAttributesSurvive() {
        for (var item : List.of(Items.CARVED_PUMPKIN, Items.DIAMOND_HELMET, Items.DIAMOND_HORSE_ARMOR, Items.SADDLE)) {
            ItemStack original = new ItemStack(item);
            assertTrue(ItemStack.matches(original, wireRoundTrip(original)));
        }
        ItemStack original = new ItemStack(Items.STONE);
        original.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().add(Attributes.MOVEMENT_SPEED,
            new AttributeModifier(Identifier.withDefaultNamespace("finite"), 0.5, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build());
        assertTrue(ItemStack.matches(original, wireRoundTrip(original)));
    }

    @Test
    void storedHiddenEquipmentIsAutomaticallyRecoveredWithoutResidualSpeed() throws Exception {
        var level = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        var config = net.minecraft.world.level.Level.class.getDeclaredField("spigotConfig");
        config.setAccessible(true);
        config.set(level, org.mockito.Mockito.mock(org.spigotmc.SpigotWorldConfig.class));
        org.mockito.Mockito.when(level.registryAccess()).thenReturn(RegistryHelper.registryAccess());
        org.mockito.Mockito.when(level.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
        TestPlayer player = new TestPlayer(level);
        CompoundTag saved = new CompoundTag();
        CompoundTag equipment = new CompoundTag();
        equipment.put("body", reportedTag());
        equipment.put("saddle", reportedTag());
        saved.put("equipment", equipment);
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        speed.addPermanentModifier(new AttributeModifier(Identifier.withDefaultNamespace("b0b03ff6-3517-4042-8865-88837e21efc1"), 0.5, AttributeModifier.Operation.ADD_VALUE));
        saved.put("attributes", AttributeInstance.Packed.LIST_CODEC.encodeStart(ops(), List.of(speed.pack())).getOrThrow());
        player.loadTag(saved);
        assertTrue(player.getItemBySlot(EquipmentSlot.BODY).isEmpty());
        assertTrue(player.getItemBySlot(EquipmentSlot.SADDLE).isEmpty());
        assertFalse(player.canUseSlot(EquipmentSlot.BODY));
        assertFalse(player.canUseSlot(EquipmentSlot.SADDLE));
        assertEquals(0.1, speed.getValue(), 1.0e-6);
        int recovered = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!item.isEmpty()) {
                assertReportedItemSafe(item);
                recovered += item.getCount();
            }
        }
        assertEquals(128, recovered);
    }
    private static RegistryOps<net.minecraft.nbt.Tag> ops() {
        return RegistryHelper.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    private static CompoundTag reportedTag() throws Exception {
        try (var input = new DataInputStream(UnsafeEquipmentTest.class.getResourceAsStream("/io/github/scissorsmc/unsafe-equipment.nbt"))) {
            return NbtIo.read(input);
        }
    }

    private static ItemStack rawReportedItem() throws Exception {
        CompoundTag components = reportedTag().getCompoundOrEmpty("components");
        ItemStack item = new ItemStack(Items.IRON_INGOT, 64);
        item.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.CODEC.parse(ops(), components.get("minecraft:attribute_modifiers")).getOrThrow());
        item.set(DataComponents.EQUIPPABLE, Equippable.CODEC.parse(ops(), components.get("minecraft:equippable")).getOrThrow());
        return item;
    }

    private static ItemStack wireRoundTrip(ItemStack item) {
        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryHelper.registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buf, item);
            int count = buf.readVarInt();
            var type = net.minecraft.world.item.Item.STREAM_CODEC.decode(buf);
            return new ItemStack(type, count, net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }

    private static void assertReportedItemSafe(ItemStack item) {
        assertTrue(item.is(Items.IRON_INGOT));
        assertEquals(64, item.getCount());
        var modifiers = item.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers();
        assertEquals(1, modifiers.size());
        assertEquals(0.5, modifiers.getFirst().modifier().amount());
        var equipment = item.get(DataComponents.EQUIPPABLE);
        assertTrue(equipment.cameraOverlay().isEmpty());
        assertFalse(equipment.canBeEquippedBy(EntityTypes.PLAYER.builtInRegistryHolder()));
        assertTrue(equipment.canBeEquippedBy(EntityTypes.HORSE.builtInRegistryHolder()));
    }

    @Test
    void reportedStoredAndPluginItemsCannotReachClientsUnchanged() throws Exception {
        assertReportedItemSafe(wireRoundTrip(ItemStack.CODEC.parse(ops(), reportedTag()).getOrThrow()));
        assertReportedItemSafe(wireRoundTrip(rawReportedItem()));
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(rawReportedItem()))));
        assertReportedItemSafe(wireRoundTrip(bundle).get(DataComponents.BUNDLE_CONTENTS).items().getFirst().create());
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(rawReportedItem())));
        assertReportedItemSafe(wireRoundTrip(box).get(DataComponents.CONTAINER).nonEmptyItemCopyStream().findFirst().orElseThrow());
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, net.minecraft.world.item.component.ChargedProjectiles.ofNonEmpty(List.of(rawReportedItem())));
        assertReportedItemSafe(wireRoundTrip(crossbow).get(DataComponents.CHARGED_PROJECTILES).items().getFirst().create());
        ItemStack food = new ItemStack(Items.APPLE);
        food.set(DataComponents.USE_REMAINDER, new net.minecraft.world.item.component.UseRemainder(net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(rawReportedItem())));
        assertReportedItemSafe(wireRoundTrip(food).get(DataComponents.USE_REMAINDER).convertInto().create());
        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryHelper.registryAccess());
        try {
            net.minecraft.world.item.ItemStackTemplate.STREAM_CODEC.encode(buf, net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(rawReportedItem()));
            var type = net.minecraft.world.item.Item.STREAM_CODEC.decode(buf);
            int count = buf.readVarInt();
            assertReportedItemSafe(new ItemStack(type, count, net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.decode(buf)));
        } finally {
            buf.release();
        }
    }

    @Test
    void rawCreativeIngressStripsThePayloadAndRequestsClientCorrection() throws Exception {
        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryHelper.registryAccess());
        try {
            buf.writeShort(36);
            buf.writeVarInt(64);
            net.minecraft.world.item.Item.STREAM_CODEC.encode(buf, Items.IRON_INGOT.builtInRegistryHolder());
            writeOriginalPatch(buf, true);
            var packet = net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.decode(buf);
            assertReportedItemSafe(packet.itemStack());
            assertTrue(packet.itemStack().wereComponentsSanitizedDuringDecode());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void nestedCreativeIngressKeepsTheClientCorrectionMarker() throws Exception {
        for (var type : List.of(DataComponents.BUNDLE_CONTENTS, DataComponents.CONTAINER, DataComponents.CHARGED_PROJECTILES, DataComponents.USE_REMAINDER)) {
            var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryHelper.registryAccess());
            var payload = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryHelper.registryAccess());
            try {
                buf.writeShort(36);
                buf.writeVarInt(1);
                net.minecraft.world.item.Item.STREAM_CODEC.encode(buf, Items.STONE.builtInRegistryHolder());
                buf.writeVarInt(1);
                buf.writeVarInt(0);
                net.minecraft.core.component.DataComponentType.STREAM_CODEC.encode(buf, type);
                if (type != DataComponents.USE_REMAINDER) payload.writeVarInt(1);
                if (type == DataComponents.CONTAINER) payload.writeBoolean(true);
                net.minecraft.world.item.Item.STREAM_CODEC.encode(payload, Items.IRON_INGOT.builtInRegistryHolder());
                payload.writeVarInt(64);
                writeOriginalPatch(payload, false);
                buf.writeVarInt(payload.readableBytes());
                buf.writeBytes(payload);
                var packet = net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.decode(buf);
                assertFalse(packet.itemStack().isEmpty());
                assertTrue(packet.itemStack().wereComponentsSanitizedDuringDecode(), type.toString());
                assertEquals(0, buf.readableBytes());
            } finally {
                payload.release();
                buf.release();
            }
        }
    }

    // Encode the original primitives directly so constructor sanitation cannot hide a decode failure.
    private static void writeOriginalPatch(RegistryFriendlyByteBuf buf, boolean delimited) throws Exception {
        buf.writeVarInt(2);
        buf.writeVarInt(0);
        for (var type : List.of(DataComponents.ATTRIBUTE_MODIFIERS, DataComponents.EQUIPPABLE)) {
            net.minecraft.core.component.DataComponentType.STREAM_CODEC.encode(buf, type);
            var payload = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryHelper.registryAccess());
            try {
                if (type == DataComponents.ATTRIBUTE_MODIFIERS) {
                    var entries = ItemAttributeModifiers.Entry.CODEC.listOf().parse(ops(),
                        reportedTag().getCompoundOrEmpty("components").get("minecraft:attribute_modifiers")).getOrThrow();
                    payload.writeVarInt(entries.size());
                    for (var entry : entries) ItemAttributeModifiers.Entry.STREAM_CODEC.encode(payload, entry);
                } else {
                    var defaults = Equippable.builder(EquipmentSlot.BODY).build();
                    EquipmentSlot.STREAM_CODEC.encode(payload, EquipmentSlot.BODY);
                    net.minecraft.sounds.SoundEvent.STREAM_CODEC.encode(payload, defaults.equipSound());
                    payload.writeBoolean(false); // No equipment asset.
                    payload.writeBoolean(true);
                    Identifier.STREAM_CODEC.encode(payload, Identifier.withDefaultNamespace("n"));
                    payload.writeBoolean(false); // All entity types in the original payload.
                    payload.writeBoolean(true);
                    payload.writeBoolean(true);
                    payload.writeBoolean(true);
                    payload.writeBoolean(false);
                    payload.writeBoolean(false);
                    net.minecraft.sounds.SoundEvent.STREAM_CODEC.encode(payload, defaults.shearingSound());
                }
                if (delimited) buf.writeVarInt(payload.readableBytes());
                buf.writeBytes(payload);
            } finally {
                payload.release();
            }
        }
    }

    @Test
    void attributeVariantsCannotPoisonEquipmentOrAttributeSynchronization() {
        for (double amount : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (var operation : AttributeModifier.Operation.values()) {
                ItemStack item = new ItemStack(Items.STONE);
                var modifier = new AttributeModifier(Identifier.withDefaultNamespace("unsafe"), amount, operation);
                item.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                    .add(Attributes.MOVEMENT_SPEED, modifier, EquipmentSlotGroup.ANY).build());
                assertTrue(wireRoundTrip(item).get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().isEmpty());
                AttributeInstance attribute = new AttributeInstance(Attributes.MOVEMENT_SPEED, ignored -> {});
                attribute.addPermanentModifier(modifier);
                var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryHelper.registryAccess());
                try {
                    ClientboundUpdateAttributesPacket.STREAM_CODEC.encode(buf, new ClientboundUpdateAttributesPacket(1, List.of(attribute)));
                    var packet = ClientboundUpdateAttributesPacket.STREAM_CODEC.decode(buf);
                    assertTrue(packet.getValues().getFirst().modifiers().isEmpty());
                    assertTrue(Double.isFinite(attribute.getValue()));
                } finally {
                    buf.release();
                }
            }
        }
    }

    @Test
    void changedModifierOperationCannotSurviveRemovalOrStorage() {
        AttributeInstance attribute = new AttributeInstance(Attributes.MOVEMENT_SPEED, ignored -> {});
        attribute.setBaseValue(0.1);
        var id = Identifier.withDefaultNamespace("same_id");
        attribute.addOrUpdateTransientModifier(new AttributeModifier(id, 0.5, AttributeModifier.Operation.ADD_VALUE));
        attribute.addOrUpdateTransientModifier(new AttributeModifier(id, 2, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        attribute.removeModifier(id);
        assertEquals(0.1, attribute.getValue(), 1.0e-9);
        AttributeInstance restored = new AttributeInstance(Attributes.MOVEMENT_SPEED, ignored -> {});
        restored.apply(AttributeInstance.Packed.CODEC.parse(ops(), AttributeInstance.Packed.CODEC.encodeStart(ops(), attribute.pack()).getOrThrow()).getOrThrow());
        assertEquals(attribute.getValue(), restored.getValue());
    }
}
