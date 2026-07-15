package com.snehal.ims.telecom.hep;

import jakarta.annotation.PreDestroy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ships pre-encoded HEPv3 frames to Homer over UDP.
 *
 * <p>Fire-and-forget by design: a capture failure must NEVER propagate into the SIP
 * signaling path, so {@link #send(byte[])} swallows and logs errors. A single
 * {@link DatagramSocket} is reused; {@code send} on a {@code DatagramSocket} is
 * atomic per-datagram, and the destination is resolved once and cached.</p>
 */
public class HepV3Sender {

    private static final Logger log = LoggerFactory.getLogger(HepV3Sender.class);

    private final HepCaptureProperties properties;
    private final AtomicReference<DatagramSocket> socketRef = new AtomicReference<>();
    private volatile InetSocketAddress destination;

    public HepV3Sender(HepCaptureProperties properties) {
        this.properties = properties;
    }

    /**
     * Sends a HEPv3 frame. Returns {@code true} on a successful socket write, {@code false}
     * if capture is disabled or the send failed (never throws).
     */
    public boolean send(byte[] frame) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            DatagramSocket socket = socket();
            InetSocketAddress dst = destination();
            socket.send(new DatagramPacket(frame, frame.length, dst));
            return true;
        } catch (Exception e) {
            // Downgrade to debug to avoid log floods when Homer is briefly unavailable.
            log.debug("HEPv3 capture send failed to {}:{} — {}",
                    properties.getHost(), properties.getPort(), e.toString());
            return false;
        }
    }

    private DatagramSocket socket() throws SocketException {
        DatagramSocket existing = socketRef.get();
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        synchronized (this) {
            existing = socketRef.get();
            if (existing == null || existing.isClosed()) {
                existing = new DatagramSocket(); // ephemeral local port, connectionless
                socketRef.set(existing);
            }
            return existing;
        }
    }

    private InetSocketAddress destination() throws UnknownHostException {
        InetSocketAddress dst = destination;
        if (dst == null) {
            synchronized (this) {
                if (destination == null) {
                    destination = new InetSocketAddress(
                            InetAddress.getByName(properties.getHost()), properties.getPort());
                }
                dst = destination;
            }
        }
        return dst;
    }

    @PreDestroy
    public void close() {
        DatagramSocket socket = socketRef.getAndSet(null);
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
