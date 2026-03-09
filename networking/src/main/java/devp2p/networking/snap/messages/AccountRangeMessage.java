package devp2p.networking.snap.messages;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.rlp.RLP;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * snap/1 AccountRange response (absolute message code 0x22).
 *
 * Wire format:
 *   [reqId, [[accountHash(32B), accountRlpBytes], ...], [proofNode, ...]]
 *
 * accountRlpBytes is a raw-bytes value containing the RLP encoding of:
 *   [nonce, balance, storageRoot(32B), codeHash(32B)]
 *
 * The proof list is parsed but discarded — sufficient for balance/nonce lookups.
 */
public final class AccountRangeMessage {

    private AccountRangeMessage() {}

    public record AccountData(
        Bytes32 accountHash,
        long nonce,
        BigInteger balance,
        Bytes32 storageRoot,
        Bytes32 codeHash
    ) {}

    public record DecodeResult(long requestId, List<AccountData> accounts) {}

    public static DecodeResult decode(byte[] rlp) {
        List<AccountData> accounts = new ArrayList<>();
        long[] reqIdHolder = {0L};

        RLP.decodeList(Bytes.wrap(rlp), outerReader -> {
            reqIdHolder[0] = outerReader.readLong();

            // accounts list: [[accountHash, accountRlpBytes], ...]
            outerReader.readList(accountsReader -> {
                while (!accountsReader.isComplete()) {
                    accountsReader.readList(pairReader -> {
                        Bytes32 hash = Bytes32.wrap(pairReader.readValue());
                        // accountRlpBytes: a value containing the RLP of [nonce, balance, storageRoot, codeHash]
                        Bytes accountRlpBytes = pairReader.readValue();

                        long[] nonce = {0L};
                        BigInteger[] balance = {BigInteger.ZERO};
                        Bytes32[] storageRoot = {Bytes32.ZERO};
                        Bytes32[] codeHash = {Bytes32.ZERO};

                        RLP.decodeList(accountRlpBytes, ar -> {
                            if (!ar.isComplete()) nonce[0] = ar.readLong();
                            if (!ar.isComplete()) balance[0] = ar.readBigInteger();
                            if (!ar.isComplete()) storageRoot[0] = Bytes32.wrap(ar.readValue());
                            if (!ar.isComplete()) codeHash[0] = Bytes32.wrap(ar.readValue());
                            return null;
                        });

                        accounts.add(new AccountData(hash, nonce[0], balance[0], storageRoot[0], codeHash[0]));
                        return null;
                    });
                }
                return null;
            });

            // Skip proof list
            if (!outerReader.isComplete()) {
                outerReader.readList(p -> { p.readRemaining(); return null; });
            }
            return null;
        });

        return new DecodeResult(reqIdHolder[0], accounts);
    }
}
