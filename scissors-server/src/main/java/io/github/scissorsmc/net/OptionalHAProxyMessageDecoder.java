package io.github.scissorsmc.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ProtocolDetectionResult;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import java.util.List;

public final class OptionalHAProxyMessageDecoder extends ByteToMessageDecoder {
    private final HAProxyMessageDecoder haProxyDecoder;
    private final String haProxyHandlerName;

    public OptionalHAProxyMessageDecoder(final HAProxyMessageDecoder haProxyDecoder, final String haProxyHandlerName) {
        this.haProxyDecoder = haProxyDecoder;
        this.haProxyHandlerName = haProxyHandlerName;
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) {
        final ProtocolDetectionResult<HAProxyProtocolVersion> detection = HAProxyMessageDecoder.detectProtocol(in);
        switch (detection.state()) {
            case NEEDS_MORE_DATA -> {
                return;
            }
            case INVALID -> {
                ctx.pipeline().remove(this.haProxyHandlerName);
                ctx.pipeline().remove(this.haProxyDecoder);
            }
            case DETECTED -> {
            }
        }

        // ByteToMessageDecoder forwards its unread cumulation when removed.
        ctx.pipeline().remove(this);
    }
}
