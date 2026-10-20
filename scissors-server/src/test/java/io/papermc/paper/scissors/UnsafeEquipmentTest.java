package io.papermc.paper.scissors;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.DummyServerHelper;
import org.bukkit.support.RegistryHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class UnsafeEquipmentTest {
    private static final double BASE_SPEED = 0.1;
    private static final Identifier REPORTED_SPEED = Identifier.withDefaultNamespace("b0b03ff6-3517-4042-8865-88837e21efc1");
    // A finite amount that stands out in the encoded bytes. Component constructors drop non-finite amounts,
    // so the test writes this marker and replaces it with NaN on the wire, as a modified client would.
    private static final double WIRE_MARKER = 1234.5;

    @BeforeAll
    static void initializePacketSanitizer() throws ClassNotFoundException {
        RegistryHelper.setup(FeatureFlags.REGISTRY.allFlags());
        // Supply real registries for Paper's static obfuscation setup without starting a server.
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.registryAccess()).thenReturn(RegistryHelper.registryAccess().freeze());
        try (var access = mockStatic(MinecraftServer.class)) {
            access.when(MinecraftServer::getServer).thenReturn(server);
            Class.forName("io.papermc.paper.util.sanitizer.ItemComponentSanitizer");
        }
        Bukkit.setServer(DummyServerHelper.setup());
        when(Bukkit.getServer().getPluginManager()).thenReturn(mock(PluginManager.class));
        CraftRegistry.setMinecraftRegistry(RegistryHelper.registryAccess());
    }

    @Test
    void storedItemLosesNonFiniteModifiersAndCustomOverlay() {
        assertReportedItemSanitized(parse(reportedItem()));
    }

    @Test
    void storedItemInsideAContainerIsSanitized() {
        final CompoundTag slot = new CompoundTag();
        slot.putInt("slot", 0);
        slot.put("item", reportedItem());
        final ListTag slots = new ListTag();
        slots.add(slot);
        final CompoundTag box = item("minecraft:shulker_box", 1);
        box.getCompoundOrEmpty("components").put("minecraft:container", slots);

        final ItemStack contained = parse(box).get(DataComponents.CONTAINER).nonEmptyItemCopyStream().findFirst().orElseThrow();
        assertReportedItemSanitized(contained);
    }

    @Test
    void vanillaEquipmentAndFiniteModifiersReachClientsUnchanged() {
        final ItemStack finite = new ItemStack(Items.STONE);
        finite.set(DataComponents.ATTRIBUTE_MODIFIERS, speedModifier(Identifier.withDefaultNamespace("finite"), 0.5, EquipmentSlotGroup.MAINHAND));
        for (final ItemStack item : List.of(new ItemStack(Items.CARVED_PUMPKIN), new ItemStack(Items.DIAMOND_HELMET),
            new ItemStack(Items.DIAMOND_HORSE_ARMOR), new ItemStack(Items.SADDLE), finite)) {
            assertTrue(ItemStack.matches(item, sendToClient(item)), item.toString());
        }
    }

    @Test
    void creativePacketWithNonFiniteModifierIsSanitizedAndCorrected() {
        final ItemStack stone = new ItemStack(Items.STONE);
        stone.set(DataComponents.ATTRIBUTE_MODIFIERS, speedModifier(Identifier.withDefaultNamespace("unsafe"), WIRE_MARKER, EquipmentSlotGroup.ANY));
        final ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(stone))));

        final ItemStack decodedStone = decodeCreativeSlotWithNaN(stone);
        assertTrue(decodedStone.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().isEmpty());
        assertTrue(decodedStone.wereComponentsSanitizedDuringDecode());

        final ItemStack decodedBundle = decodeCreativeSlotWithNaN(bundle);
        final ItemStack nested = decodedBundle.get(DataComponents.BUNDLE_CONTENTS).items().getFirst().create();
        assertTrue(nested.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().isEmpty());
        assertTrue(decodedBundle.wereComponentsSanitizedDuringDecode(), "Nested sanitation must still correct the client");
    }

    @Test
    void attributesDiscardNonFiniteValues() {
        for (final double amount : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (final AttributeModifier.Operation operation : AttributeModifier.Operation.values()) {
                final AttributeModifier modifier = new AttributeModifier(Identifier.withDefaultNamespace("unsafe"), amount, operation);
                final AttributeInstance added = speedAttribute();
                added.addPermanentModifier(modifier);
                added.addOrUpdateTransientModifier(modifier);
                assertTrue(added.getModifiers().isEmpty());
                assertEquals(BASE_SPEED, added.getValue());

                final AttributeInstance loaded = speedAttribute();
                loaded.apply(new AttributeInstance.Packed(Attributes.MOVEMENT_SPEED, amount, List.of(modifier)));
                assertTrue(loaded.getModifiers().isEmpty());
                assertTrue(Double.isFinite(loaded.getValue()));
            }
        }
    }

    @Test
    void replacingAModifierWithAnotherOperationLeavesNothingBehind() {
        final AttributeInstance speed = speedAttribute();
        final Identifier id = Identifier.withDefaultNamespace("same_id");
        speed.addOrUpdateTransientModifier(new AttributeModifier(id, 0.5, AttributeModifier.Operation.ADD_VALUE));
        speed.addOrUpdateTransientModifier(new AttributeModifier(id, 2, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        speed.removeModifier(id);
        assertEquals(BASE_SPEED, speed.getValue());
    }

    @Test
    void playersCannotEquipAnimalSlots() {
        final TestPlayer player = newPlayer();
        player.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.DIAMOND_HORSE_ARMOR));
        player.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        assertTrue(player.getItemBySlot(EquipmentSlot.BODY).isEmpty());
        assertTrue(player.getItemBySlot(EquipmentSlot.SADDLE).isEmpty());
        assertFalse(player.canUseSlot(EquipmentSlot.BODY));
        assertFalse(player.canUseSlot(EquipmentSlot.SADDLE));
    }

    @Test
    void savedAnimalSlotEquipmentReturnsToInventoryWithoutItsEffects() {
        final TestPlayer player = newPlayer();
        final CompoundTag saved = savedPlayer(List.of());
        saved.getCompoundOrEmpty("equipment").put("saddle", reportedItem());
        // Affected players were saved with the hidden item's speed modifier as a permanent attribute.
        final AttributeInstance speed = speedAttribute();
        speed.addPermanentModifier(new AttributeModifier(REPORTED_SPEED, 0.5, AttributeModifier.Operation.ADD_VALUE));
        saved.put("attributes", AttributeInstance.Packed.LIST_CODEC.encodeStart(ops(), List.of(speed.pack())).getOrThrow());

        player.loadTag(saved);

        assertTrue(player.getItemBySlot(EquipmentSlot.BODY).isEmpty());
        assertTrue(player.getItemBySlot(EquipmentSlot.SADDLE).isEmpty());
        assertEquals(BASE_SPEED, player.getAttribute(Attributes.MOVEMENT_SPEED).getValue(), 1.0e-6);
        assertReportedItemSanitized(player.getInventory().getItem(0));
        assertReportedItemSanitized(player.getInventory().getItem(1));
    }

    @Test
    void recoveredEquipmentWaitsInTheSaveUntilTheInventoryHasSpace() {
        final List<ItemStackWithSlot> fullInventory = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            fullInventory.add(new ItemStackWithSlot(slot, new ItemStack(Items.STONE, 64)));
        }
        final TestPlayer player = newPlayer();
        player.loadTag(savedPlayer(fullInventory));
        assertTrue(player.getItemBySlot(EquipmentSlot.BODY).isEmpty());

        player.getInventory().setItem(0, ItemStack.EMPTY);
        final TestPlayer restored = newPlayer();
        restored.loadTag(player.saveTag());

        assertReportedItemSanitized(restored.getInventory().getItem(0));
        assertTrue(restored.saveTag().getListOrEmpty("ScissorsRecoveredEquipment").isEmpty());
    }

    @Test
    void repeatedReloadsReturnTheRecoveredItemOnlyOnce() {
        // Creative players could equip one item from a held stack without consuming it.
        CompoundTag saved = savedPlayer(List.of(new ItemStackWithSlot(0, parse(reportedItem()))));
        saved.getCompoundOrEmpty("equipment").getCompoundOrEmpty("body").putInt("count", 1);
        for (int reload = 0; reload < 3; reload++) {
            final TestPlayer player = newPlayer();
            player.loadTag(saved);
            assertEquals(64, player.getInventory().getItem(0).getCount());
            assertEquals(1, player.getInventory().getItem(1).getCount());
            assertTrue(player.getInventory().getItem(2).isEmpty());
            saved = player.saveTag();
        }
    }

    @Test
    void recoveryKeepsTheEffectsOfRealEquipmentWithTheSameModifierId() {
        final TestPlayer player = newPlayer();
        final ItemStack held = new ItemStack(Items.STICK);
        held.set(DataComponents.ATTRIBUTE_MODIFIERS, speedModifier(REPORTED_SPEED, 0.25, EquipmentSlotGroup.MAINHAND));
        player.getInventory().setSelectedItem(held);
        player.detectEquipmentUpdates();

        // Reload the already tracked player, as CraftPlayer.loadData does.
        player.loadTag(savedPlayer(List.of(new ItemStackWithSlot(0, held))));
        player.detectEquipmentUpdates();

        assertEquals(BASE_SPEED + 0.25, player.getAttribute(Attributes.MOVEMENT_SPEED).getValue(), 1.0e-6);
    }

    private static ItemStack decodeCreativeSlotWithNaN(final ItemStack item) {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
        try {
            ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.encode(buf, new ServerboundSetCreativeModeSlotPacket(36, item));
            replaceDouble(buf, WIRE_MARKER, Double.NaN);
            final ServerboundSetCreativeModeSlotPacket packet = ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.decode(buf);
            assertEquals(0, buf.readableBytes());
            return packet.itemStack();
        } finally {
            buf.release();
        }
    }

    private static void replaceDouble(final ByteBuf buf, final double from, final double to) {
        final long bits = Double.doubleToRawLongBits(from);
        for (int i = buf.readerIndex(); i <= buf.writerIndex() - Long.BYTES; i++) {
            if (buf.getLong(i) == bits) {
                buf.setLong(i, Double.doubleToRawLongBits(to));
                return;
            }
        }
        fail("Encoded packet does not contain " + from);
    }

    private static ItemStack sendToClient(final ItemStack item) {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, item);
            return ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    // The item from the report: an iron ingot equippable in the animal body slot, with infinite negative modifiers
    // for the body slot, a movement speed bonus, and a resource-pack camera overlay.
    private static CompoundTag reportedItem() {
        final ListTag modifiers = new ListTag();
        for (final String attribute : List.of("armor", "attack_damage", "gravity", "jump_strength", "max_health")) {
            modifiers.add(bodyModifier(Identifier.withDefaultNamespace("reported_" + attribute), attribute, Double.NEGATIVE_INFINITY));
        }
        modifiers.add(bodyModifier(REPORTED_SPEED, "movement_speed", 0.5));
        final CompoundTag equippable = new CompoundTag();
        equippable.putString("slot", "body");
        equippable.putString("camera_overlay", "minecraft:n");

        final CompoundTag item = item("minecraft:iron_ingot", 64);
        item.getCompoundOrEmpty("components").put("minecraft:attribute_modifiers", modifiers);
        item.getCompoundOrEmpty("components").put("minecraft:equippable", equippable);
        return item;
    }

    private static void assertReportedItemSanitized(final ItemStack item) {
        assertTrue(item.is(Items.IRON_INGOT));
        assertEquals(64, item.getCount());
        final var modifiers = item.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers();
        assertEquals(1, modifiers.size());
        assertEquals(REPORTED_SPEED, modifiers.getFirst().modifier().id());
        final Equippable equipment = item.get(DataComponents.EQUIPPABLE);
        assertTrue(equipment.cameraOverlay().isEmpty());
        assertFalse(equipment.canBeEquippedBy(EntityTypes.PLAYER.builtInRegistryHolder()));
        assertTrue(equipment.canBeEquippedBy(EntityTypes.HORSE.builtInRegistryHolder()));
    }

    private static CompoundTag item(final String id, final int count) {
        final CompoundTag item = new CompoundTag();
        item.putString("id", id);
        item.putInt("count", count);
        item.put("components", new CompoundTag());
        return item;
    }

    private static CompoundTag bodyModifier(final Identifier id, final String attribute, final double amount) {
        final CompoundTag modifier = new CompoundTag();
        modifier.putString("type", "minecraft:" + attribute);
        modifier.putString("id", id.toString());
        modifier.putDouble("amount", amount);
        modifier.putString("operation", "add_value");
        modifier.putString("slot", "body");
        return modifier;
    }

    private static ItemStack parse(final CompoundTag tag) {
        return ItemStack.CODEC.parse(ops(), tag).getOrThrow();
    }

    private static ItemAttributeModifiers speedModifier(final Identifier id, final double amount, final EquipmentSlotGroup slot) {
        return ItemAttributeModifiers.builder()
            .add(Attributes.MOVEMENT_SPEED, new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE), slot)
            .build();
    }

    private static AttributeInstance speedAttribute() {
        final AttributeInstance speed = new AttributeInstance(Attributes.MOVEMENT_SPEED, ignored -> {});
        speed.setBaseValue(BASE_SPEED);
        return speed;
    }

    // A saved player wearing the reported item in the body slot.
    private static CompoundTag savedPlayer(final List<ItemStackWithSlot> inventory) {
        final CompoundTag equipment = new CompoundTag();
        equipment.put("body", reportedItem());
        final CompoundTag saved = new CompoundTag();
        saved.put("equipment", equipment);
        saved.put("Inventory", ItemStackWithSlot.CODEC.listOf().encodeStart(ops(), inventory).getOrThrow());
        return saved;
    }

    private static RegistryOps<Tag> ops() {
        return RegistryHelper.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    private static TestPlayer newPlayer() {
        final ServerLevel level = mock(ServerLevel.class);
        try {
            final var spigotConfig = Level.class.getDeclaredField("spigotConfig");
            spigotConfig.setAccessible(true);
            spigotConfig.set(level, mock(SpigotWorldConfig.class));
        } catch (final ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        when(level.registryAccess()).thenReturn(RegistryHelper.registryAccess());
        when(level.enabledFeatures()).thenReturn(FeatureFlags.VANILLA_SET);
        when(level.getChunkSource()).thenReturn(mock(ServerChunkCache.class));
        return new TestPlayer(level);
    }

    private static final class TestPlayer extends Player {
        private CraftHumanEntity bukkit;

        TestPlayer(final Level level) {
            super(level, new GameProfile(UUID.randomUUID(), "EquipmentTest"));
            this.setId(1);
        }

        @Override
        public GameType gameMode() {
            return GameType.SURVIVAL;
        }

        @Override
        public CraftHumanEntity getBukkitEntity() {
            if (this.bukkit == null) this.bukkit = mock(CraftHumanEntity.class);
            return this.bukkit;
        }

        void loadTag(final CompoundTag tag) {
            this.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
        }

        CompoundTag saveTag() {
            final TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, RegistryHelper.registryAccess());
            this.addAdditionalSaveData(output);
            return output.buildResult();
        }
    }
}
