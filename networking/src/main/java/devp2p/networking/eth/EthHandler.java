package devp2p.networking.eth;

import devp2p.core.crypto.NodeKey;
import devp2p.networking.NetworkConfig;
import devp2p.networking.eth.messages.*;
import devp2p.networking.rlpx.RLPxHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * eth/68 protocol handler.
 *
 * Sits above the RLPxHandler in the pipeline. Receives decoded RLPxMessages
 * and implements the eth sub-protocol state machine:
 *
 *   AWAITING_HELLO → AWAITING_STATUS → READY
 *
 * Message code offsets (after p2p base):
 *   p2p:  0x00 Hello, 0x01 Disconnect, 0x02 Ping, 0x03 Pong
 *   eth:  0x10 Status, 0x11 NewBlockHashes, 0x13 GetBlockHeaders, 0x14 BlockHeaders, ...
 */
public final class EthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(EthHandler.class);

    // p2p sub-protocol message codes
    private static final int P2P_HELLO = 0x00;
    private static final int P2P_DISCONNECT = 0x01;
    private static final int P2P_PING = 0x02;
    private static final int P2P_PONG = 0x03;

    // eth/68 offsets from capability base (0x10)
    private static final int ETH_STATUS = 0x10;
    private static final int ETH_GET_BLOCK_HEADERS = 0x13;
    private static final int ETH_BLOCK_HEADERS = 0x14;

    public enum State { AWAITING_HELLO, AWAITING_STATUS, READY }
    private volatile State state = State.AWAITING_HELLO;
    private volatile String remoteAddress;

    private final NodeKey nodeKey;
    private final int tcpPort;
    private final NetworkConfig network;
    private final Consumer<List<BlockHeadersMessage.VerifiedHeader>> onHeaders;
    private final Runnable onReady;
    private final AtomicLong requestId = new AtomicLong(1);
    private final ConcurrentMap<Long, CompletableFuture<List<BlockHeadersMessage.VerifiedHeader>>>
            pendingRequests = new ConcurrentHashMap<>();

    private RLPxHandler rlpxHandler; // reference to the RLPx layer for sending
    private volatile ChannelHandlerContext readyCtx; // stored when state reaches READY
    private int negotiatedEthVersion = StatusMessage.MAX_ETH_VERSION;

    public EthHandler(NodeKey nodeKey, int tcpPort, NetworkConfig network,
                      Consumer<List<BlockHeadersMessage.VerifiedHeader>> onHeaders,
                      Runnable onReady) {
        this.nodeKey = nodeKey;
        this.tcpPort = tcpPort;
        this.network = network;
        this.onHeaders = onHeaders;
        this.onReady = onReady;
    }

    /** Set the remote address early (at connect time), before the handshake completes. */
    public void setRemoteAddress(String address) {
        this.remoteAddress = address;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if ("RLPX_READY".equals(evt)) {
            // Store remote address early so it's available before READY state
            var addr = ctx.channel().remoteAddress();
            if (addr != null) {
                remoteAddress = addr.toString().replaceFirst("^/", "");
            }
            // Retrieve the RLPx handler from the pipeline
            rlpxHandler = (RLPxHandler) ctx.pipeline().get("rlpx");
            sendHello(ctx);
        }
    }

    /** Called by RLPxHandler when a decoded message arrives. */
    public void onMessage(ChannelHandlerContext ctx, RLPxHandler.RLPxMessage msg) {
        log.info("[eth] Received message code=0x{} state={}", Integer.toHexString(msg.code()), state);
        switch (state) {
            case AWAITING_HELLO -> handleHello(ctx, msg);
            case AWAITING_STATUS -> handleStatus(ctx, msg);
            case READY -> handleReady(ctx, msg);
        }
    }

    // -------------------------------------------------------------------------
    // State: AWAITING_HELLO
    // -------------------------------------------------------------------------
    private void handleHello(ChannelHandlerContext ctx, RLPxHandler.RLPxMessage msg) {
        if (msg.code() == P2P_HELLO) {
            HelloMessage hello = HelloMessage.decode(msg.payload());
            log.info("[eth] Hello from peer: {}", hello);

            // Negotiate highest common eth version (67 or 68)
            int bestEthVersion = hello.capabilities.stream()
                .filter(c -> c.name().equals("eth") && c.version() >= StatusMessage.MIN_ETH_VERSION
                        && c.version() <= StatusMessage.MAX_ETH_VERSION)
                .mapToInt(HelloMessage.Capability::version)
                .max().orElse(-1);
            if (bestEthVersion < 0) {
                log.warn("[eth] Peer does not support eth/67+, disconnecting (caps={})", hello.capabilities);
                ctx.close();
                return;
            }
            negotiatedEthVersion = bestEthVersion;
            log.info("[eth] Negotiated eth/{}", negotiatedEthVersion);
            state = State.AWAITING_STATUS;
            sendStatus(ctx);
        } else if (msg.code() == P2P_DISCONNECT) {
            log.info("[eth] Peer disconnected during Hello (reason={})", decodeDisconnectReason(msg.payload()));
            ctx.close();
        }
    }

    // -------------------------------------------------------------------------
    // State: AWAITING_STATUS
    // -------------------------------------------------------------------------
    private void handleStatus(ChannelHandlerContext ctx, RLPxHandler.RLPxMessage msg) {
        if (msg.code() == ETH_STATUS) {
            StatusMessage status;
            try {
                status = StatusMessage.decode(msg.payload());
            } catch (Exception e) {
                log.error("[eth] Failed to decode Status from peer: {} | payload[{}]={}", e.getMessage(),
                    msg.payload().length,
                    bytesToHex(msg.payload(), msg.payload().length));
                ctx.close();
                return;
            }
            log.info("[eth] Status from peer: {}", status);
            if (!status.isCompatible(network.networkId(), network.genesisHash())) {
                log.warn("[eth] Incompatible network: chainId={}, genesis={}",
                    status.networkId, status.genesisHash);
                ctx.close();
                return;
            }
            state = State.READY;
            readyCtx = ctx;
            log.info("[eth] Peer ready! Requesting recent block headers...");
            if (onReady != null) onReady.run();
            requestBlockHeaders(ctx, 21_000_000L, 1);
        } else if (msg.code() == P2P_PING) {
            sendPong(ctx);
        } else if (msg.code() == P2P_DISCONNECT) {
            log.info("[eth] Peer disconnected during Status exchange (reason={})", decodeDisconnectReason(msg.payload()));
            ctx.close();
        } else {
            log.info("[eth] Unexpected msg during Status: code=0x{}", Integer.toHexString(msg.code()));
        }
    }

    // -------------------------------------------------------------------------
    // State: READY
    // -------------------------------------------------------------------------
    private void handleReady(ChannelHandlerContext ctx, RLPxHandler.RLPxMessage msg) {
        switch (msg.code()) {
            case ETH_BLOCK_HEADERS -> {
                try {
                    BlockHeadersMessage.DecodeResult decoded =
                        BlockHeadersMessage.decodeWithRequestId(msg.payload());
                    log.info("[eth] Received {} block headers (reqId={})",
                            decoded.headers().size(), decoded.requestId());
                    // Complete pending future if this was an IPC-originated request
                    CompletableFuture<List<BlockHeadersMessage.VerifiedHeader>> future =
                            pendingRequests.remove(decoded.requestId());
                    if (future != null) {
                        future.complete(decoded.headers());
                    }
                    onHeaders.accept(decoded.headers());
                } catch (Exception e) {
                    log.error("[eth] Failed to decode BlockHeaders", e);
                }
            }
            case P2P_PING -> sendPong(ctx);
            case P2P_DISCONNECT -> {
                log.info("[eth] Peer disconnected (reason={})", decodeDisconnectReason(msg.payload()));
                ctx.close();
            }
            default -> log.trace("[eth] Unhandled message 0x{}", Integer.toHexString(msg.code()));
        }
    }

    // -------------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------------
    private void sendHello(ChannelHandlerContext ctx) {
        log.debug("[eth] Sending Hello");
        byte[] payload = HelloMessage.encode(nodeKey.publicKeyBytes(), tcpPort);
        rlpxHandler.sendMessage(ctx, P2P_HELLO, payload);
    }

    private void sendStatus(ChannelHandlerContext ctx) {
        byte[] payload = StatusMessage.encode(
            negotiatedEthVersion, network.networkId(), network.genesisHash(),
            network.forkIdHash(), network.forkNext());
        log.info("[eth] Sending Status ({} bytes): {}", payload.length, bytesToHex(payload, payload.length));
        rlpxHandler.sendMessage(ctx, ETH_STATUS, payload);
    }

    public void requestBlockHeaders(ChannelHandlerContext ctx, long blockNumber, int count) {
        long reqId = requestId.getAndIncrement();
        log.debug("[eth] GetBlockHeaders block={} count={} reqId={}", blockNumber, count, reqId);
        byte[] payload = GetBlockHeadersMessage.encodeByNumber(reqId, blockNumber, count, 0, false);
        rlpxHandler.sendMessage(ctx, ETH_GET_BLOCK_HEADERS, payload);
    }

    /**
     * Request block headers and return a future that completes when the response arrives.
     * Uses the stored ChannelHandlerContext from the READY state.
     *
     * @return a future, or null if this handler is not in READY state
     */
    public CompletableFuture<List<BlockHeadersMessage.VerifiedHeader>> requestBlockHeadersAsync(
            long blockNumber, int count) {
        ChannelHandlerContext ctx = readyCtx;
        if (ctx == null || state != State.READY) return null;

        long reqId = requestId.getAndIncrement();
        CompletableFuture<List<BlockHeadersMessage.VerifiedHeader>> future = new CompletableFuture<>();
        pendingRequests.put(reqId, future);

        log.debug("[eth] GetBlockHeaders (async) block={} count={} reqId={}", blockNumber, count, reqId);
        byte[] payload = GetBlockHeadersMessage.encodeByNumber(reqId, blockNumber, count, 0, false);
        rlpxHandler.sendMessage(ctx, ETH_GET_BLOCK_HEADERS, payload);
        return future;
    }

    public State getState() {
        return state;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    /** Returns true if this handler has completed the eth handshake. */
    public boolean isReady() {
        return state == State.READY && readyCtx != null;
    }

    private void sendPong(ChannelHandlerContext ctx) {
        rlpxHandler.sendMessage(ctx, P2P_PONG, new byte[0]);
    }

    private static String bytesToHex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    /**
     * Decode disconnect reason from RLP payload.
     * Disconnect payload: RLP([reason]) = [0xC1, reason_byte] or [0xC0] (empty)
     */
    private static int decodeDisconnectReason(byte[] payload) {
        if (payload.length == 0) return -1;
        int first = payload[0] & 0xFF;
        if (first < 0x80) return first;          // raw byte (non-standard)
        if (first == 0xC0) return 0;             // empty list
        if (first >= 0xC1 && payload.length >= 2) return payload[1] & 0xFF; // list[reason]
        return -1;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[eth] Exception", cause);
        ctx.close();
    }
}
