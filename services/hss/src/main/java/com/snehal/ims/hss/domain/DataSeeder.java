package com.snehal.ims.hss.domain;

import com.snehal.ims.hss.HssProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Provisions a couple of demo subscribers on first startup so the Cx interface
 * can be exercised end-to-end before real provisioning exists. Idempotent: does
 * nothing when the store already holds data or when seeding is disabled.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SubscriberRepository subscribers;
    private final HssProperties props;

    public DataSeeder(SubscriberRepository subscribers, HssProperties props) {
        this.subscribers = subscribers;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        if (!props.isSeedDemoData() || subscribers.count() > 0) {
            return;
        }
        String realm = props.getRealm();
        subscribers.save(new Subscriber(
                "alice@" + realm, "sip:alice@" + realm, realm, "alice-secret", defaultProfile("alice", realm)));
        subscribers.save(new Subscriber(
                "bob@" + realm, "sip:bob@" + realm, realm, "bob-secret", defaultProfile("bob", realm)));
        log.info("Seeded {} demo subscriber(s) in realm {}", subscribers.count(), realm);
    }

    private static String defaultProfile(String user, String realm) {
        return "<ServiceProfile><PublicIdentity><Identity>sip:" + user + "@" + realm
                + "</Identity></PublicIdentity></ServiceProfile>";
    }
}
