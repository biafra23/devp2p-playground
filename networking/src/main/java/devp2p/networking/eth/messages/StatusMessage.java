package devp2p.networking.eth.messages;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.rlp.RLP;

/**
 * eth/Status (message code 0x10, offset 0x10 from p2p base = 0x00).
 *
 * After eth capability is negotiated (offset 0x10 for eth messages):
 *   Status code = 0x10 + 0x00 = 0x10
 *
 * RLP: [protocolVersion, networkId, td, bestHash, genesisHash, forkId([hash, next])]
 *
 * For eth/68, forkId is required.
 */
public final class StatusMessage {

    // eth/68 code within the eth namespace = 0x00 (first message)
    // Over the wire: 0x10 (base offset for eth capability after p2p hello)
    public static final int CODE = 0x10;
    public static final int MIN_ETH_VERSION = 67;
    public static final int MAX_ETH_VERSION = 68;

    // Genesis block difficulty (honest — we haven't synced the chain)
    private static final Bytes DEFAULT_TOTAL_DIFFICULTY = Bytes.fromHexString("0x0400000000");

    public final int protocolVersion;
    public final long networkId;
    public final Bytes totalDifficulty;
    public final Bytes32 bestHash;
    public final Bytes32 genesisHash;

    private StatusMessage(int protoVer, long networkId, Bytes td,
                          Bytes32 best, Bytes32 genesis) {
        this.protocolVersion = protoVer;
        this.networkId = networkId;
        this.totalDifficulty = td;
        this.bestHash = best;
        this.genesisHash = genesis;
    }

    /**
     * Encode a Status message for any network.
     *
     * @param networkId    chain network ID
     * @param genesisHash  genesis block hash
     * @param bestHash     best known block hash (recent block to avoid being deprioritized)
     * @param forkIdHash   4-byte EIP-2124 fork ID hash
     * @param forkNext     next fork timestamp (0 if none known)
     */
    public static byte[] encode(int ethVersion, long networkId, Bytes32 genesisHash,
                                Bytes32 bestHash, byte[] forkIdHash, long forkNext) {
        return RLP.encodeList(writer -> {
            writer.writeInt(ethVersion);
            writer.writeLong(networkId);
            writer.writeValue(DEFAULT_TOTAL_DIFFICULTY);
            writer.writeValue(bestHash);
            writer.writeValue(genesisHash);
            writer.writeList(forkWriter -> {
                forkWriter.writeValue(Bytes.wrap(forkIdHash));
                forkWriter.writeLong(forkNext);
            });
        }).toArrayUnsafe();
    }

    public static StatusMessage decode(byte[] rlp) {
        return RLP.decodeList(Bytes.wrap(rlp), reader -> {
            int version = reader.readInt();
            long netId = reader.readLong();
            Bytes td = reader.readValue();
            Bytes32 best = Bytes32.wrap(reader.readValue());
            Bytes32 genesis = Bytes32.wrap(reader.readValue());
            // forkId is an RLP list [hash(4), next(uint64)] — must readList(), not readValue()
            if (!reader.isComplete()) {
                reader.readList(fr -> null); // consume the forkId list; we only need genesis+networkId
            }
            return new StatusMessage(version, netId, td, best, genesis);
        });
    }

    public boolean isCompatible(long expectedNetworkId, Bytes32 expectedGenesis) {
        return networkId == expectedNetworkId && genesisHash.equals(expectedGenesis);
    }

    @Override
    public String toString() {
        return "Status{version=" + protocolVersion + ", networkId=" + networkId +
               ", genesis=" + genesisHash.toShortHexString() +
               ", best=" + bestHash.toShortHexString() + "}";
    }
}
