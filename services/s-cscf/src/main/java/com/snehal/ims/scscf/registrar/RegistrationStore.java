package com.snehal.ims.scscf.registrar;

import java.time.Instant;
import java.util.List;

/**
 * Where the S-CSCF keeps its registered contacts.
 *
 * <p>Two implementations exist: {@code memory} (per-pod, paired with the StatefulSet
 * assignment model) and {@code redis} (shared, so any replica can serve any subscriber).
 * Everything above this interface is agnostic to which is in use.</p>
 */
public interface RegistrationStore {

    /** Live bindings for an AoR, highest priority first. Never null. */
    List<Binding> find(String aor);

    /** Inserts or replaces a binding, keyed by {@link Binding#key()}. */
    void save(Binding binding);

    void remove(String aor, String bindingKey);

    void removeAll(String aor);

    /**
     * Drops every binding whose expiry has passed and returns them, so the caller can
     * de-register the now-unreachable subscribers towards the HSS.
     */
    List<Binding> removeExpired(Instant now);

    /** Total live bindings, exported as a gauge. */
    long bindingCount();
}
