package devp2p.networking;

import org.apache.tuweni.bytes.Bytes32;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Network-specific configuration for Ethereum chains.
 */
public record NetworkConfig(
        String name,
        long networkId,
        Bytes32 genesisHash,
        byte[] forkIdHash,
        long forkNext,
        List<InetSocketAddress> bootnodes
) {

    public static final NetworkConfig MAINNET = new NetworkConfig(
            "mainnet",
            1L,
            Bytes32.fromHexString("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"),
            new byte[]{(byte) 0x07, (byte) 0xc9, (byte) 0x46, (byte) 0x2e}, // BPO2
            0L,
            List.of(
                    new InetSocketAddress("18.138.108.67", 30303),
                    new InetSocketAddress("3.209.45.79", 30303),
                    new InetSocketAddress("18.188.214.86", 30303),
                    new InetSocketAddress("3.219.208.172", 30303)
            )
    );

    public static final NetworkConfig SEPOLIA = new NetworkConfig(
            "sepolia",
            11155111L,
            Bytes32.fromHexString("25a5cc106eea7138acab33231d7160d69cb777ee0c2c553fcddf5138993e6dd9"),
            new byte[]{(byte) 0x26, (byte) 0x89, (byte) 0x56, (byte) 0xb6}, // BPO2
            0L,
            List.of(
                    new InetSocketAddress("138.197.51.181", 30303),
                    new InetSocketAddress("146.190.1.103", 30303),
                    new InetSocketAddress("170.64.250.88", 30303),
                    new InetSocketAddress("139.59.49.206", 30303),
                    new InetSocketAddress("138.68.123.152", 30303)
            )
    );

    public static final NetworkConfig HOLESKY = new NetworkConfig(
            "holesky",
            17000L,
            Bytes32.fromHexString("b5f7f912443c940f21fd611f12828d75b534364ed9e95ca4e307729a4661bde4"),
            new byte[]{(byte) 0x9b, (byte) 0xc6, (byte) 0xcb, (byte) 0x31}, // BPO2
            0L,
            List.of(
                    new InetSocketAddress("146.190.13.128", 30303),
                    new InetSocketAddress("178.128.136.233", 30303)
            )
    );

    /** Look up a network by name (case-insensitive). */
    public static NetworkConfig byName(String name) {
        return switch (name.toLowerCase()) {
            case "mainnet" -> MAINNET;
            case "sepolia" -> SEPOLIA;
            case "holesky" -> HOLESKY;
            default -> throw new IllegalArgumentException(
                    "Unknown network: " + name + ". Supported: mainnet, sepolia, holesky");
        };
    }
}
