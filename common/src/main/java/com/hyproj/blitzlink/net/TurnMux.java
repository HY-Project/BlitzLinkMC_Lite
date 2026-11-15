package com.hyproj.blitzlink.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.util.ReferenceCountUtil;

/**
 * Shares the Minecraft TCP port with the host sidecar's embedded TURN, splitting connections
 * by their first byte: 0x00–0x03 is a STUN header (TURN-over-TCP from a
 * client whose network blocks UDP), anything else is Minecraft (a handshake starts with a varint length ≥ 0x07, legacy
 * ping with 0xFE). TURN connections are piped to the sidecar's loopback TURN port; the TURN server only ever forwards
 * to this host's own WebRTC socket, so this is not an open relay.
 */
public final class TurnMux extends ChannelInboundHandlerAdapter {
    public static final String NAME = "blitzlink_turn_mux";
    private static volatile int turnPort;
    /** Sends {@code turnPeer{port, addr}} to the sidecar (HostController installs it). */
    private static volatile java.util.function.BiConsumer<Integer, String> peerReporter;

    public static void setPeerReporter(java.util.function.BiConsumer<Integer, String> reporter) {
        peerReporter = reporter;
    }

    private boolean decided;

    public static void setTurnPort(int port) {
        turnPort = port;
    }

    public static boolean active() {
        return turnPort > 0;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (decided) {
            ctx.fireChannelRead(msg);
            return;
        }
        decided = true;
        int port = turnPort;
        if (port > 0 && msg instanceof ByteBuf buf && buf.isReadable() && (buf.getUnsignedByte(buf.readerIndex()) & 0xFC) == 0) {
            pipeToTurn(ctx, buf, port);
            return;
        }
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(msg);
    }

    private void pipeToTurn(ChannelHandlerContext ctx, ByteBuf first, int port) {
        Channel inbound = ctx.channel();
        // Minecraft's own handlers stay in the pipeline but never see a byte; drop the read timeout so the 30 s
        // Minecraft idle limit does not cut a TURN allocation (the TURN server has its own lifetimes).
        if (inbound.pipeline().get("timeout") != null) inbound.pipeline().remove("timeout");
        inbound.config().setAutoRead(false);
        ctx.pipeline().replace(this, NAME, new Relay(null));
        Bootstrap b = new Bootstrap()
                .group(inbound.eventLoop())
                .channel(inbound.getClass().getSimpleName().startsWith("Epoll") ? io.netty.channel.epoll.EpollSocketChannel.class
                        : inbound.getClass().getSimpleName().startsWith("KQueue") ? io.netty.channel.kqueue.KQueueSocketChannel.class
                        : io.netty.channel.socket.nio.NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new Relay(inbound));
        ChannelFuture f = b.connect("127.0.0.1", port);
        f.addListener((ChannelFutureListener) done -> {
            if (!done.isSuccess()) {
                ReferenceCountUtil.release(first);
                inbound.close();
                return;
            }
            Channel outbound = done.channel();
            ((Relay) inbound.pipeline().get(NAME)).peer = outbound;
            // turnPeer before any byte is forwarded: the sidecar then reports this player's real address in `peer`.
            var reporter = peerReporter;
            if (reporter != null && outbound.localAddress() instanceof java.net.InetSocketAddress local
                    && inbound.remoteAddress() instanceof java.net.InetSocketAddress remote) {
                String ip = remote.getAddress().getHostAddress();
                reporter.accept(local.getPort(), (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + remote.getPort());
            }
            outbound.writeAndFlush(first);
            inbound.config().setAutoRead(true);
        });
    }

    /** Copies bytes to the other channel; closes both when either closes. */
    private static final class Relay extends ChannelInboundHandlerAdapter {
        volatile Channel peer;

        Relay(Channel peer) {
            this.peer = peer;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel p = peer;
            if (p != null && p.isActive()) {
                p.writeAndFlush(msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel p = peer;
            if (p != null) p.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
