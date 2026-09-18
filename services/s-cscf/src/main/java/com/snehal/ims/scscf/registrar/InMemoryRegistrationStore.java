package com.snehal.ims.scscf.registrar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-pod binding store. Paired with the StatefulSet assignment model, where the HSS
 * pins each subscriber to the exact pod that registered them, so a local map is
 * authoritative for every subscriber this pod serves.
 *
 * <p>Bindings are lost on restart; UEs recover on their own re-registration timer
 * (see docs/TECH_DEBT.md TD-021).</p>
 */
public class InMemoryRegistrationStore implements RegistrationStore {

    private final Map<String, Map<String, Binding>> bindingsByAor = new ConcurrentHashMap<>();
    private final AtomicLong count = new AtomicLong();

    @Override
    public List<Binding> find(String aor) {
        Map<String, Binding> bindings = bindingsByAor.get(aor);
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        return bindings.values().stream()
                .filter(b -> !b.isExpired(now))
                .sorted(PRIORITY)
                .toList();
    }

    @Override
    public void save(Binding binding) {
        bindingsByAor.compute(binding.aor(), (aor, existing) -> {
            Map<String, Binding> bindings = existing != null ? existing : new ConcurrentHashMap<>();
            if (bindings.put(binding.key(), binding) == null) {
                count.incrementAndGet();
            }
            return bindings;
        });
    }

    @Override
    public void remove(String aor, String bindingKey) {
        bindingsByAor.computeIfPresent(aor, (key, bindings) -> {
            if (bindings.remove(bindingKey) != null) {
                count.decrementAndGet();
            }
            return bindings.isEmpty() ? null : bindings;
        });
    }

    @Override
    public void removeAll(String aor) {
        Map<String, Binding> removed = bindingsByAor.remove(aor);
        if (removed != null) {
            count.addAndGet(-removed.size());
        }
    }

    @Override
    public List<Binding> removeExpired(Instant now) {
        List<Binding> expired = new ArrayList<>();
        for (String aor : List.copyOf(bindingsByAor.keySet())) {
            bindingsByAor.computeIfPresent(aor, (key, bindings) -> {
                bindings.values().removeIf(binding -> {
                    if (binding.isExpired(now)) {
                        expired.add(binding);
                        count.decrementAndGet();
                        return true;
                    }
                    return false;
                });
                return bindings.isEmpty() ? null : bindings;
            });
        }
        return expired;
    }

    @Override
    public long bindingCount() {
        return Math.max(0, count.get());
    }

    /** Highest q-value first, then the longest-lived binding. */
    static final Comparator<Binding> PRIORITY =
            Comparator.comparingInt(Binding::qValue).reversed()
                    .thenComparing(Binding::expiresAt, Comparator.reverseOrder());
}
