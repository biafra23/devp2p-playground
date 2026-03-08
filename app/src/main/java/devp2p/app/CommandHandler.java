package devp2p.app;

import devp2p.core.types.BlockHeader;
import devp2p.networking.discv4.DiscV4Service;
import devp2p.networking.discv4.KademliaTable;
import devp2p.networking.eth.messages.BlockHeadersMessage;
import devp2p.networking.rlpx.RLPxConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Handles JSON-Lines IPC commands dispatched by DaemonServer.
 * Holds references to live P2P services and produces JSON responses.
 */
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private final DiscV4Service discV4;
    private final RLPxConnector connector;
    private final long startTimeMs;
    private final CountDownLatch stopLatch;

    public CommandHandler(DiscV4Service discV4, RLPxConnector connector, CountDownLatch stopLatch) {
        this.discV4 = discV4;
        this.connector = connector;
        this.startTimeMs = System.currentTimeMillis();
        this.stopLatch = stopLatch;
    }

    /** Parse and dispatch one JSON-Lines request; returns a JSON-Lines response. */
    public String handle(String jsonLine) {
        try {
            String cmd = extractString(jsonLine, "cmd");
            return switch (cmd) {
                case "status"      -> handleStatus();
                case "peers"       -> handlePeers();
                case "get-headers" -> handleGetHeaders(jsonLine);
                case "stop"        -> handleStop();
                default            -> jsonError("Unknown command: " + cmd);
            };
        } catch (Exception e) {
            log.warn("[ipc] Error handling command '{}': {}", jsonLine, e.getMessage());
            return jsonError(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Command implementations
    // -------------------------------------------------------------------------

    private String handleStatus() {
        long uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000;
        int discovered = discV4.table().size();
        return "{\"ok\":true,\"state\":\"RUNNING\",\"uptimeSeconds\":" + uptimeSec
                + ",\"discoveredPeers\":" + discovered + "}";
    }

    private String handlePeers() {
        List<KademliaTable.Entry> peers = discV4.table().allPeers();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"count\":").append(peers.size()).append(",\"peers\":[");
        boolean first = true;
        for (KademliaTable.Entry e : peers) {
            if (!first) sb.append(",");
            first = false;
            String shortId = e.nodeId().size() >= 8
                    ? e.nodeId().slice(0, 8).toHexString() + "..."
                    : e.nodeId().toHexString();
            sb.append("{\"ip\":\"").append(e.udpAddr().getAddress().getHostAddress()).append("\"")
              .append(",\"udpPort\":").append(e.udpAddr().getPort())
              .append(",\"tcpPort\":").append(e.tcpPort())
              .append(",\"nodeId\":\"").append(shortId).append("\"")
              .append("}");
        }
        sb.append("],\"connected\":[");
        List<RLPxConnector.PeerInfo> connected = connector.getActivePeers();
        first = true;
        for (RLPxConnector.PeerInfo p : connected) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"remoteAddress\":\"").append(escapeJson(p.remoteAddress())).append("\"")
              .append(",\"state\":\"").append(p.state()).append("\"")
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String handleGetHeaders(String jsonLine) {
        long blockNumber = extractLong(jsonLine, "blockNumber");
        int count = (int) extractLong(jsonLine, "count");

        try {
            List<BlockHeadersMessage.VerifiedHeader> headers =
                    connector.requestBlockHeaders(blockNumber, count)
                             .get(30, TimeUnit.SECONDS);

            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"count\":").append(headers.size()).append(",\"headers\":[");
            boolean first = true;
            for (BlockHeadersMessage.VerifiedHeader vh : headers) {
                if (!first) sb.append(",");
                first = false;
                BlockHeader h = vh.header();
                sb.append("{\"number\":").append(h.number)
                  .append(",\"hash\":\"0x").append(vh.hash().toHexString()).append("\"")
                  .append(",\"parentHash\":\"0x").append(h.parentHash.toHexString()).append("\"")
                  .append(",\"stateRoot\":\"0x").append(h.stateRoot.toHexString()).append("\"")
                  .append(",\"transactionsRoot\":\"0x").append(h.transactionsRoot.toHexString()).append("\"")
                  .append(",\"timestamp\":").append(h.timestamp)
                  .append(",\"gasUsed\":").append(h.gasUsed)
                  .append(",\"gasLimit\":").append(h.gasLimit);
                if (h.baseFeePerGas != null) {
                    sb.append(",\"baseFeePerGas\":\"").append(h.baseFeePerGas).append("\"");
                }
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return jsonError(msg);
        }
    }

    private String handleStop() {
        log.info("[ipc] Stop command received — initiating graceful shutdown");
        stopLatch.countDown();
        return "{\"ok\":true,\"message\":\"Daemon shutting down\"}";
    }

    // -------------------------------------------------------------------------
    // JSON helpers (no external library needed for simple commands)
    // -------------------------------------------------------------------------

    private static String jsonError(String message) {
        return "{\"ok\":false,\"error\":\"" + escapeJson(message) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /** Minimal field extractor for string values: finds {@code "field":"value"} in flat JSON. */
    static String extractString(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) throw new IllegalArgumentException("Missing field: " + field);
        int colon = json.indexOf(':', keyIdx + key.length());
        if (colon < 0) throw new IllegalArgumentException("Malformed JSON near field: " + field);
        int open = json.indexOf('"', colon + 1);
        if (open < 0) throw new IllegalArgumentException("Field '" + field + "' value is not a string");
        int close = json.indexOf('"', open + 1);
        if (close < 0) throw new IllegalArgumentException("Unterminated string for field: " + field);
        return json.substring(open + 1, close);
    }

    /** Minimal field extractor for long/integer values. */
    static long extractLong(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) throw new IllegalArgumentException("Missing field: " + field);
        int colon = json.indexOf(':', keyIdx + key.length());
        if (colon < 0) throw new IllegalArgumentException("Malformed JSON near field: " + field);
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (start == end) throw new IllegalArgumentException("Field '" + field + "' is not a number");
        return Long.parseLong(json.substring(start, end));
    }
}
