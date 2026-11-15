package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * One local TCP connection ↔ one {@code mc-N} DataChannel (reliable, ordered) — native {@code tunnel.rs} in threads.
 *
 * <ul>
 * <li>TCP → channel in messages of at most 16 KB. Reading pauses while more than 1 MB is buffered in the channel and
 *     resumes below 256 KB.</li>
 * <li>Channel → TCP through a writer thread, so libwebrtc's callback thread never blocks on a socket.</li>
 * <li>Close propagation: TCP EOF closes the channel once the backlog is sent; a closed channel closes the TCP socket
 *     after everything received has been written; a failed TCP write closes the channel at once.</li>
 * </ul>
 * The host side may receive channel data before its TCP connection to the server is up: it waits in the writer queue.
 */
final class Pipe {
    static final int CHUNK = 16 * 1024;
    static final long HIGH_WATER = 1024 * 1024;
    static final long LOW_WATER = 256 * 1024;
    private static final byte[] END = new byte[0];

    final Rtc.Channel channel;
    private final LinkedBlockingQueue<byte[]> toTcp = new LinkedBlockingQueue<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final IntConsumer onFinished;
    private volatile Socket socket;
    private volatile int localPort;

    /** {@code onFinished} gets the TCP socket's local port (0 if it never connected) once both directions are done. */
    Pipe(Rtc.Channel channel, IntConsumer onFinished) {
        this.channel = channel;
        this.onFinished = onFinished;
    }

    /** Channel → TCP (called on libwebrtc's thread, never blocks). */
    void inbound(byte[] b) {
        if (b.length > 0) toTcp.add(b);
    }

    /** The channel closed: finish writing what arrived, then close the socket. */
    void channelClosed() {
        toTcp.add(END);
        if (socket == null) finish();
    }

    int localPort() {
        return localPort;
    }

    /**
     * Starts both directions on {@code s}. {@code beforeFirstWrite} runs before any byte reaches the socket (the host
     * emits {@code peer} there, and Bukkit writes the PROXY header).
     */
    void attach(Socket s, Runnable beforeFirstWrite) {
        socket = s;
        localPort = s.getLocalPort();
        try {
            s.setTcpNoDelay(true);
        } catch (IOException ignored) {
        }
        Thread.ofVirtual().name("blitzlink-" + channel.label + "-w").start(() -> writer(s, beforeFirstWrite));
        Thread.ofVirtual().name("blitzlink-" + channel.label + "-r").start(() -> reader(s));
    }

    private void writer(Socket s, Runnable beforeFirstWrite) {
        try {
            if (beforeFirstWrite != null) beforeFirstWrite.run();
            OutputStream out = s.getOutputStream();
            while (true) {
                byte[] b = toTcp.take();
                if (b == END) break;
                out.write(b);
                // Coalesce what is already queued into one flush.
                byte[] next;
                while ((next = toTcp.peek()) != null && next != END) {
                    out.write(toTcp.poll());
                }
                out.flush();
            }
            try {
                s.shutdownOutput();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            channel.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSocket(s);
            finish();
        }
    }

    private void reader(Socket s) {
        byte[] buf = new byte[CHUNK];
        try {
            if (!channel.awaitOpen(15_000)) return;
            InputStream in = s.getInputStream();
            while (true) {
                if (channel.buffered() > HIGH_WATER && !channel.awaitBelow(LOW_WATER, 0)) return;
                int n = in.read(buf);
                if (n < 0) {
                    // EOF: close the channel once everything queued has gone out.
                    channel.awaitBelow(0, 10_000);
                    channel.close();
                    return;
                }
                if (n > 0) channel.send(buf, 0, n);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // socket closed by the writer, or the channel is gone
            channel.close();
        }
    }

    /** Closes both sides now (session teardown). */
    void closeAll() {
        channel.close();
        toTcp.clear();
        toTcp.add(END);
        Socket s = socket;
        if (s != null) closeSocket(s);
        else finish();
    }

    private void finish() {
        if (finished.compareAndSet(false, true)) onFinished.accept(localPort);
    }

    private static void closeSocket(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
