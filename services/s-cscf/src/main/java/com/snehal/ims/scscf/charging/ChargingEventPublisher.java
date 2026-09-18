package com.snehal.ims.scscf.charging;

/**
 * Seam for the charging plane.
 *
 * <p>Phase 6 supplies a Kafka-backed implementation feeding the CDF/billing worker.
 * Until then {@link LoggingChargingEventPublisher} writes the records to the structured
 * log, so the emission points are exercised and observable from day one.</p>
 *
 * <p>Implementations must never block or throw into the signaling path: a charging
 * outage must not become a call failure.</p>
 */
public interface ChargingEventPublisher {

    void publish(ChargingEvent event);
}
