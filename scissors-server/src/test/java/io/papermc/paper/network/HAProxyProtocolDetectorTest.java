package io.papermc.paper.network;

import io.github.scissorsmc.net.OptionalHAProxyMessageDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class HAProxyProtocolDetectorTest {
    private static final String HAPROXY_DETECTOR = "haproxy-detector";
    private static final String HAPROXY_DECODER = "haproxy-decoder";
    private static final String HAPROXY_HANDLER = "haproxy-handler";

    @Test
    void waitsForAndParsesFragmentedV1Header() {
        final Fixture fixture = new Fixture();
        final byte[] header = "PROXY TCP4 198.51.100.1 203.0.113.10 45678 25565\r\n".getBytes(StandardCharsets.US_ASCII);
        final byte[] payload = minecraftHandshake();

        assertFalse(fixture.channel.writeInbound(Unpooled.wrappedBuffer(header, 0, 11)));
        assertTrue(fixture.channel.pipeline().names().contains(HAPROXY_DETECTOR));
        assertTrue(fixture.channel.pipeline().names().contains(HAPROXY_DECODER));
        assertTrue(fixture.messages.isEmpty());

        assertTrue(fixture.channel.writeInbound(
            Unpooled.wrappedBuffer(header, 11, header.length - 11),
            Unpooled.wrappedBuffer(payload)
        ));

        final HAProxyMessage message = fixture.onlyMessage();
        try {
            assertEquals(HAProxyProtocolVersion.V1, message.protocolVersion());
            assertEquals(HAProxyCommand.PROXY, message.command());
            assertEquals("198.51.100.1", message.sourceAddress());
            assertEquals(45678, message.sourcePort());
            assertEquals("203.0.113.10", message.destinationAddress());
            assertEquals(25565, message.destinationPort());
            assertArrayEquals(payload, fixture.readPayload());
            assertFalse(fixture.channel.pipeline().names().contains(HAPROXY_DETECTOR));
            assertFalse(fixture.channel.pipeline().names().contains(HAPROXY_DECODER));
            assertTrue(fixture.channel.pipeline().names().contains(HAPROXY_HANDLER));
        } finally {
            fixture.close();
        }
    }

    @Test
    void waitsForAndParsesFragmentedV2Header() {
        final Fixture fixture = new Fixture();
        final byte[] messageBytes = v2Message(minecraftHandshake());

        assertFalse(fixture.channel.writeInbound(Unpooled.wrappedBuffer(messageBytes, 0, 11)));
        assertTrue(fixture.messages.isEmpty());
        assertTrue(fixture.channel.writeInbound(Unpooled.wrappedBuffer(messageBytes, 11, messageBytes.length - 11)));

        final HAProxyMessage message = fixture.onlyMessage();
        try {
            assertEquals(HAProxyProtocolVersion.V2, message.protocolVersion());
            assertEquals(HAProxyCommand.PROXY, message.command());
            assertEquals("198.51.100.1", message.sourceAddress());
            assertEquals(45678, message.sourcePort());
            assertEquals("203.0.113.10", message.destinationAddress());
            assertEquals(25565, message.destinationPort());
            assertArrayEquals(minecraftHandshake(), fixture.readPayload());
        } finally {
            fixture.close();
        }
    }

    @Test
    void passesFragmentedHttpThroughByteExactWithoutHAProxyHandlers() {
        final Fixture fixture = new Fixture();
        final byte[] request = (
            "GET / HTTP/1.1\r\n"
                + "Host: play.example.test\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);

        assertFalse(fixture.channel.writeInbound(Unpooled.wrappedBuffer(request, 0, 11)));
        assertNull(fixture.channel.readInbound());
        assertTrue(fixture.channel.writeInbound(Unpooled.wrappedBuffer(request, 11, request.length - 11)));

        try {
            assertArrayEquals(request, fixture.readPayload());
            assertTrue(fixture.messages.isEmpty());
            assertFalse(fixture.channel.pipeline().names().contains(HAPROXY_DETECTOR));
            assertFalse(fixture.channel.pipeline().names().contains(HAPROXY_DECODER));
            assertFalse(fixture.channel.pipeline().names().contains(HAPROXY_HANDLER));
        } finally {
            fixture.close();
        }
    }

    @Test
    void passesPlainMinecraftThroughByteExact() {
        final Fixture fixture = new Fixture();
        final byte[] handshake = minecraftHandshake();

        assertTrue(fixture.channel.writeInbound(Unpooled.wrappedBuffer(handshake)));

        try {
            assertArrayEquals(handshake, fixture.readPayload());
            assertTrue(fixture.messages.isEmpty());
            assertFalse(fixture.channel.pipeline().names().contains(HAPROXY_DETECTOR));
            assertFalse(fixture.channel.pipeline().names().contains(HAPROXY_DECODER));
            assertFalse(fixture.channel.pipeline().names().contains(HAPROXY_HANDLER));
        } finally {
            fixture.close();
        }
    }

    private static byte[] minecraftHandshake() {
        return new byte[] {
            0x10, 0x00, (byte) 0x80, 0x05, 0x09,
            'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't',
            0x63, (byte) 0xDD, 0x02
        };
    }

    private static byte[] v2Message(final byte[] payload) {
        final ByteBuf message = Unpooled.buffer(28 + payload.length);
        try {
            message.writeBytes(new byte[] {
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
            });
            message.writeByte(0x21); // Version 2, PROXY command
            message.writeByte(0x11); // TCP over IPv4
            message.writeShort(12);
            message.writeBytes(new byte[] {(byte) 198, 51, 100, 1});
            message.writeBytes(new byte[] {(byte) 203, 0, 113, 10});
            message.writeShort(45678);
            message.writeShort(25565);
            message.writeBytes(payload);
            return ByteBufUtil.getBytes(message);
        } finally {
            message.release();
        }
    }

    private static final class Fixture {
        private final List<HAProxyMessage> messages = new ArrayList<>();
        private final EmbeddedChannel channel = new EmbeddedChannel();

        private Fixture() {
            final HAProxyMessageDecoder decoder = new HAProxyMessageDecoder();
            this.channel.pipeline().addLast(
                HAPROXY_DETECTOR,
                new OptionalHAProxyMessageDecoder(decoder, HAPROXY_HANDLER)
            );
            this.channel.pipeline().addLast(HAPROXY_DECODER, decoder);
            this.channel.pipeline().addLast(HAPROXY_HANDLER, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                    if (msg instanceof HAProxyMessage message) {
                        Fixture.this.messages.add(message);
                    } else {
                        ctx.fireChannelRead(msg);
                    }
                }
            });
        }

        private HAProxyMessage onlyMessage() {
            assertEquals(1, this.messages.size());
            return this.messages.getFirst();
        }

        private byte[] readPayload() {
            final ByteBuf payload = this.channel.readInbound();
            try {
                final byte[] bytes = ByteBufUtil.getBytes(payload);
                assertNull(this.channel.readInbound());
                return bytes;
            } finally {
                payload.release();
            }
        }

        private void close() {
            this.messages.forEach(ReferenceCountUtil::release);
            this.messages.clear();
            this.channel.finishAndReleaseAll();
        }
    }
}
