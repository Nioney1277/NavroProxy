package ac.navro.proxy.nethernet;

import ac.navro.proxy.SupportedCodecs;
import java.io.IOException;
import java.net.InetAddress;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;


final class VersionedBatchSession implements AutoCloseable {
    private final Supplier<NetherNetBatchSession> detectionFactory;
    private final PublicKey transportKey;
    private final KeyPair backendKey;
    private final boolean raknetBackend, raknetFrontend;
    private final InetAddress forwardedPeer;
    private final PrivateKey signer;
    private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private NetherNetBatchSession detection;
    private ConnectionBatchSession connection;

    VersionedBatchSession(Supplier<NetherNetBatchSession> detectionFactory, PublicKey transportKey, KeyPair backendKey,
                          boolean raknetBackend, boolean raknetFrontend, InetAddress forwardedPeer, PrivateKey signer) {
        this.detectionFactory = detectionFactory; this.transportKey = transportKey; this.backendKey = backendKey;
        this.raknetBackend = raknetBackend; this.raknetFrontend = raknetFrontend;
        this.forwardedPeer = forwardedPeer; this.signer = signer;
    }
    NetherNetBatchSession.Result process(boolean backend, byte[] bytes) throws Exception {
        if (detection == null && connection == null) {
            if (backend) throw new IOException("Backend packet before client protocol declaration");
            var initial = ConnectionBatchSession.initial(bytes);
            if (SupportedCodecs.forProtocol(initial.protocol()) != null) detection = detectionFactory.get();
            else connection = new ConnectionBatchSession(initial, transportKey, backendKey,
                    raknetBackend, raknetFrontend, forwardedPeer, signer);
        }
        return detection != null ? detection.process(backend, bytes) : connection.process(backend, bytes);
    }
    NetherNetBatchSession.Result tick() throws Exception {
        if (detection != null) return detection.tick();
        if (connection != null) return connection.tick();
        if (System.nanoTime() > deadline) throw new IOException("Client protocol declaration timed out");
        return new NetherNetBatchSession.Result(null, List.of(), List.of(), List.of(), false);
    }
    Map<String,Long> counts() { return detection != null ? detection.counts() : connection != null ? connection.counts() : Map.of(); }
    String mode() { return detection != null ? detection.mode() : "FORWARD"; }
    String backendDisconnect() { return detection != null ? detection.backendDisconnect() : "Backend disconnect"; }
    @Override public void close() {
        if (detection != null) detection.close();
        if (connection != null) connection.close();
    }
}
