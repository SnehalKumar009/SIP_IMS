package com.snehal.ims.scscf.registrar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract of the binding store, exercised against the in-memory implementation. The
 * Redis implementation is expected to satisfy the same behaviour.
 */
class InMemoryRegistrationStoreTest {

    private static final String AOR = "sip:alice@ims.snehal.com";
    private static final String IMPI = "alice@ims.snehal.com";

    private RegistrationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRegistrationStore();
    }

    @Test
    void savesAndFindsBinding() {
        store.save(binding("sip:alice@10.0.0.1:5060", 300));

        List<Binding> found = store.find(AOR);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).contactUri()).isEqualTo("sip:alice@10.0.0.1:5060");
        assertThat(store.bindingCount()).isEqualTo(1);
    }

    @Test
    void refreshReplacesRatherThanDuplicates() {
        store.save(binding("sip:alice@10.0.0.1:5060", 300));
        store.save(binding("sip:alice@10.0.0.1:5060", 600));

        assertThat(store.find(AOR)).hasSize(1);
        assertThat(store.bindingCount()).isEqualTo(1);
    }

    @Test
    void instanceIdKeepsOneBindingWhenContactChanges() {
        store.save(bindingWithInstance("sip:alice@10.0.0.1:5060", "urn:uuid:alice-1", 1));
        store.save(bindingWithInstance("sip:alice@10.0.0.9:5060", "urn:uuid:alice-1", 1));

        // Same instance and reg-id is the same flow, so the address change must not
        // leave a stale binding behind.
        assertThat(store.find(AOR)).hasSize(1);
        assertThat(store.find(AOR).get(0).contactUri()).isEqualTo("sip:alice@10.0.0.9:5060");
    }

    @Test
    void differentFlowsOfSameInstanceCoexist() {
        store.save(bindingWithInstance("sip:alice@10.0.0.1:5060", "urn:uuid:alice-1", 1));
        store.save(bindingWithInstance("sip:alice@10.0.0.2:5060", "urn:uuid:alice-1", 2));

        assertThat(store.find(AOR)).hasSize(2);
    }

    @Test
    void expiredBindingsAreHiddenFromLookup() {
        store.save(binding("sip:alice@10.0.0.1:5060", -1));

        assertThat(store.find(AOR)).isEmpty();
    }

    @Test
    void removeExpiredReturnsTheLapsedBindings() {
        store.save(binding("sip:alice@10.0.0.1:5060", -1));
        store.save(binding("sip:alice@10.0.0.2:5060", 600));

        List<Binding> expired = store.removeExpired(Instant.now());

        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).contactUri()).isEqualTo("sip:alice@10.0.0.1:5060");
        assertThat(store.find(AOR)).hasSize(1);
        assertThat(store.bindingCount()).isEqualTo(1);
    }

    @Test
    void removeAllClearsTheAor() {
        store.save(binding("sip:alice@10.0.0.1:5060", 300));
        store.save(binding("sip:alice@10.0.0.2:5060", 300));

        store.removeAll(AOR);

        assertThat(store.find(AOR)).isEmpty();
        assertThat(store.bindingCount()).isZero();
    }

    @Test
    void highestQValueIsReturnedFirst() {
        store.save(new Binding(AOR, IMPI, "sip:alice@10.0.0.1:5060", "call-1", 1, List.of(),
                null, null, 200, Instant.now().plusSeconds(300)));
        store.save(new Binding(AOR, IMPI, "sip:alice@10.0.0.2:5060", "call-1", 1, List.of(),
                null, null, 900, Instant.now().plusSeconds(300)));

        assertThat(store.find(AOR).get(0).contactUri()).isEqualTo("sip:alice@10.0.0.2:5060");
    }

    private static Binding binding(String contactUri, int expiresInSeconds) {
        return new Binding(AOR, IMPI, contactUri, "call-1", 1, List.of("<sip:p-cscf:5060;lr>"),
                null, null, 1000, Instant.now().plusSeconds(expiresInSeconds));
    }

    private static Binding bindingWithInstance(String contactUri, String instanceId, int regId) {
        return new Binding(AOR, IMPI, contactUri, "call-1", 1, List.of(), instanceId, regId, 1000,
                Instant.now().plusSeconds(300));
    }
}
