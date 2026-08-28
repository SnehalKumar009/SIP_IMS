package com.snehal.ims.hss.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {

    Optional<Subscriber> findByImpu(String impu);

    Optional<Subscriber> findByImpi(String impi);
}
