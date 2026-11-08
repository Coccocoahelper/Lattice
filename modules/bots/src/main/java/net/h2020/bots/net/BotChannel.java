package net.h2020.bots.net;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/**
 * Dummy channel for a bot connection. It exposes the same handler names as a real 1.8 pipeline
 * (splitter, decoder, prepender, encoder, packet_handler) so packet-injection code written for real players can call
 * {@code pipeline().addBefore("packet_handler", ...)} without failing. Anything written directly to the channel is
 * routed back to the bot as if it had been sent through the NetworkManager.
 */
final class BotChannel {
    private BotChannel() {}

    static EmbeddedChannel create(final FakeNetworkManager owner) {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                try {
                    owner.captureFromChannel(msg);
                } finally {
                    ReferenceCountUtil.release(msg);
                    promise.trySuccess();
                }
            }
        });
        ch.pipeline().addLast("timeout", new ChannelDuplexHandler());
        ch.pipeline().addLast("splitter", new ChannelDuplexHandler());
        ch.pipeline().addLast("decoder", new ChannelDuplexHandler());
        ch.pipeline().addLast("prepender", new ChannelDuplexHandler());
        ch.pipeline().addLast("encoder", new ChannelDuplexHandler());
        ch.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
        return ch;
    }
}
