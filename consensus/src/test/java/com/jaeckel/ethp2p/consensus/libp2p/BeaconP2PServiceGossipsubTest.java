package com.jaeckel.ethp2p.consensus.libp2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The Lighthouse-ban regression: a host with gossipsub enabled must negotiate a
 * {@code /meshsub/} stream opened toward it — that negotiation is what
 * Lighthouse's {@code does_not_support_gossipsub} Fatal report keys on — while
 * joining no light-client topic unless the separate topic switch is on.
 */
class BeaconP2PServiceGossipsubTest {

    private static final String MESHSUB = "/meshsub/1.1.0";

    private static String loopbackAddress(BeaconP2PService target) {
        return target.listenAddresses().stream()
                // jvm-libp2p reports the wildcard bind as /ip4/0.0.0.0/ or /ip6/::/;
                // either one is reachable on loopback.
                .map(a -> a.replace("/ip4/0.0.0.0/", "/ip4/127.0.0.1/").replace("/ip6/::/", "/ip4/127.0.0.1/"))
                .filter(a -> a.startsWith("/ip4/127.0.0.1/"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void gossipsubNegotiatesMeshsubWithoutJoiningTopics() throws Exception {
        BeaconP2PService withGossip = new BeaconP2PService(null);
        withGossip.setGossipsubEnabled(true);
        BeaconP2PService without = new BeaconP2PService(null);
        BeaconP2PService observer = new BeaconP2PService(null);
        withGossip.start();
        without.start();
        observer.start();
        try {
            String negotiated = observer.probeProtocol(loopbackAddress(withGossip), MESHSUB)
                    .get(20, TimeUnit.SECONDS);
            assertEquals(MESHSUB, negotiated);
            assertTrue(withGossip.subscribedGossipTopics().isEmpty(),
                    "protocol registration must not join any topic");

            // Control: the same probe against a host without gossipsub must fail
            // negotiation — exactly the outcome that used to earn the Fatal report.
            ExecutionException refused = assertThrows(ExecutionException.class, () ->
                    observer.probeProtocol(loopbackAddress(without), MESHSUB).get(20, TimeUnit.SECONDS));
            assertTrue(refused.getCause() != null, "negotiation failure must carry a cause");
        } finally {
            observer.close();
            without.close();
            withGossip.close();
        }
    }

    @Test
    void topicSubscriptionWithoutGossipsubIsRefused() {
        BeaconP2PService svc = new BeaconP2PService(null);
        svc.setGossipTopicSubscriptionEnabled(true);
        assertThrows(IllegalStateException.class, svc::start);
    }
}
