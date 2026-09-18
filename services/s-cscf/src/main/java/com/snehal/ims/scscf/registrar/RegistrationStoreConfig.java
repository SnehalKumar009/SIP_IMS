package com.snehal.ims.scscf.registrar;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Selects the binding store implementation from {@code ims.scscf.store} and exports
 * the live binding count as a gauge.
 */
@Configuration(proxyBeanMethods = false)
public class RegistrationStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(RegistrationStoreConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "ims.scscf", name = "store", havingValue = "memory", matchIfMissing = true)
    public RegistrationStore inMemoryRegistrationStore() {
        log.info("Registration store: in-memory (bindings are lost on restart)");
        return new InMemoryRegistrationStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ims.scscf", name = "store", havingValue = "redis")
    public RegistrationStore redisRegistrationStore(StringRedisTemplate redis) {
        log.info("Registration store: Redis (shared across replicas)");
        return new RedisRegistrationStore(redis);
    }

    @Bean
    public Gauge registeredBindingsGauge(MeterRegistry registry, RegistrationStore store) {
        return Gauge.builder("ims_scscf_registered_bindings", store, RegistrationStore::bindingCount)
                .description("Currently registered contact bindings")
                .register(registry);
    }
}
