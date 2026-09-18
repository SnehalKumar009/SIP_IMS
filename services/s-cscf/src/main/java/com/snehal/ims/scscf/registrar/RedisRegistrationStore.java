package com.snehal.ims.scscf.registrar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Binding store shared by every S-CSCF replica, so any pod can serve any subscriber
 * and bindings survive a pod restart.
 *
 * <p>Layout: one hash per AoR ({@code scscf:reg:<aor>}, field = binding key) plus a
 * sorted set scored by expiry ({@code scscf:reg:expiry}). The sorted set — rather than
 * a Redis TTL — is what lets the reaper observe expiries and de-register the subscriber
 * towards the HSS; a silently expiring key would leave the HSS believing the user is
 * still registered here.</p>
 */
public class RedisRegistrationStore implements RegistrationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRegistrationStore.class);

    private static final String AOR_PREFIX = "scscf:reg:";
    private static final String EXPIRY_ZSET = "scscf:reg:expiry";
    private static final char SEP = '\u0000';

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisRegistrationStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public List<Binding> find(String aor) {
        Map<Object, Object> entries = redis.opsForHash().entries(AOR_PREFIX + aor);
        if (entries.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        List<Binding> live = new ArrayList<>(entries.size());
        for (Object json : entries.values()) {
            Binding binding = deserialize((String) json);
            if (binding != null && !binding.isExpired(now)) {
                live.add(binding);
            }
        }
        live.sort(InMemoryRegistrationStore.PRIORITY);
        return List.copyOf(live);
    }

    @Override
    public void save(Binding binding) {
        String json = serialize(binding);
        if (json == null) {
            return;
        }
        redis.opsForHash().put(AOR_PREFIX + binding.aor(), binding.key(), json);
        redis.opsForZSet().add(EXPIRY_ZSET, member(binding.aor(), binding.key()),
                binding.expiresAt().toEpochMilli());
    }

    @Override
    public void remove(String aor, String bindingKey) {
        redis.opsForHash().delete(AOR_PREFIX + aor, bindingKey);
        redis.opsForZSet().remove(EXPIRY_ZSET, member(aor, bindingKey));
    }

    @Override
    public void removeAll(String aor) {
        Set<Object> keys = redis.opsForHash().keys(AOR_PREFIX + aor);
        redis.delete(AOR_PREFIX + aor);
        for (Object bindingKey : keys) {
            redis.opsForZSet().remove(EXPIRY_ZSET, member(aor, (String) bindingKey));
        }
    }

    @Override
    public List<Binding> removeExpired(Instant now) {
        Set<String> due = redis.opsForZSet().rangeByScore(EXPIRY_ZSET, 0, now.toEpochMilli());
        if (due == null || due.isEmpty()) {
            return List.of();
        }
        List<Binding> expired = new ArrayList<>(due.size());
        for (String member : due) {
            int sep = member.indexOf(SEP);
            if (sep < 0) {
                redis.opsForZSet().remove(EXPIRY_ZSET, member);
                continue;
            }
            String aor = member.substring(0, sep);
            String bindingKey = member.substring(sep + 1);
            Object json = redis.opsForHash().get(AOR_PREFIX + aor, bindingKey);
            if (json != null) {
                Binding binding = deserialize((String) json);
                if (binding != null) {
                    expired.add(binding);
                }
            }
            remove(aor, bindingKey);
        }
        return expired;
    }

    @Override
    public long bindingCount() {
        Long size = redis.opsForZSet().zCard(EXPIRY_ZSET);
        return size == null ? 0 : size;
    }

    private static String member(String aor, String bindingKey) {
        return aor + SEP + bindingKey;
    }

    private String serialize(Binding binding) {
        try {
            return mapper.writeValueAsString(binding);
        } catch (Exception e) {
            log.error("Failed to serialize binding for aor={}", binding.aor(), e);
            return null;
        }
    }

    private Binding deserialize(String json) {
        try {
            return mapper.readValue(json, Binding.class);
        } catch (Exception e) {
            log.error("Discarding unreadable binding payload", e);
            return null;
        }
    }
}
