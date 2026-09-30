package io.papermc.paper.item;

import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.WritableBookContent;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.support.DummyServerHelper;
import org.bukkit.support.RegistryHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CachedItemSerializationTest {
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
        CraftRegistry.setMinecraftRegistry(RegistryHelper.registryAccess());
    }

    @ParameterizedTest
    @ValueSource(strings = {"container", "bundle", "projectiles"})
    void savesAndSendsOversizedCachedContents(final String carrier) {
        final ItemStack item = carrier(carrier, 72);
        final DataComponentType<?> type = contentsType(carrier);
        final var ops = RegistryHelper.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        // Repeat against the same object to exercise both cache loads and reused nested encodings.
        for (int attempt = 0; attempt < 2; attempt++) {
            final var saved = ItemStack.CODEC.encodeStart(ops, item).getOrThrow();
            assertSafe(ItemStack.CODEC.parse(ops, saved).getOrThrow(), type);
            assertSafe(roundTripSlot(item), type);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"container", "bundle", "projectiles"})
    void preservesContentsBelowBudget(final String carrier) {
        final ItemStack item = carrier(carrier, 1);
        final var ops = RegistryHelper.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        final var saved = ItemStack.CODEC.encodeStart(ops, item).getOrThrow();
        assertTrue(ItemStack.matches(item, ItemStack.CODEC.parse(ops, saved).getOrThrow()));
        assertTrue(ItemStack.matches(item, roundTripSlot(item)));
    }

    private static void assertSafe(final ItemStack item, final DataComponentType<?> type) {
        assertFalse(item.isEmpty());
        assertEquals(7, item.get(DataComponents.REPAIR_COST));
        assertFalse(item.hasNonDefault(type));
        assertTrue(ItemStack.validateStrict(item).isSuccess());
    }

    private static ItemStack roundTripSlot(final ItemStack item) {
        final var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
        try {
            ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(buffer, new ClientboundContainerSetSlotPacket(0, 1, 4, item));
            final var decoded = ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded.getItem();
        } finally {
            buffer.release();
        }
    }

    private static DataComponentType<?> contentsType(final String carrier) {
        return switch (carrier) {
            case "container" -> DataComponents.CONTAINER;
            case "bundle" -> DataComponents.BUNDLE_CONTENTS;
            default -> DataComponents.CHARGED_PROJECTILES;
        };
    }

    private static ItemStack carrier(final String carrier, final int pages) {
        final ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(
            Collections.nCopies(pages, Filterable.passThrough("x".repeat(1000)))));
        final List<ItemStack> books = List.of(book, book.copy());
        final List<ItemStackTemplate> templates = books.stream().map(ItemStackTemplate::fromNonEmptyStack).toList();
        final ItemStack item;
        switch (carrier) {
            case "container" -> {
                item = new ItemStack(Items.SHULKER_BOX);
                item.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(books));
            }
            case "bundle" -> {
                item = new ItemStack(Items.BUNDLE);
                item.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(templates));
            }
            default -> {
                item = new ItemStack(Items.CROSSBOW);
                item.set(DataComponents.CHARGED_PROJECTILES, new ChargedProjectiles(templates));
            }
        }
        item.set(DataComponents.REPAIR_COST, 7);
        return item;
    }
}
