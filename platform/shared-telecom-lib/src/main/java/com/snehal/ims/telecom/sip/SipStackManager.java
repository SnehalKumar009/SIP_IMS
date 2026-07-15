package com.snehal.ims.telecom.sip;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.sip.AddressFactory;
import javax.sip.HeaderFactory;
import javax.sip.ListeningPoint;
import javax.sip.MessageFactory;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Owns the full Jain SIP lifecycle for a signaling pod: it instantiates the NIST
 * {@code SipStack}, binds a {@link ListeningPoint}, creates a {@link SipProvider},
 * and (if the pod supplies one) attaches the pod's {@link SipListener}.
 *
 * <p>Lifecycle is bound to the Spring context: {@link #start()} runs on
 * {@code @PostConstruct} and {@link #stop()} performs a graceful drain on
 * {@code @PreDestroy}, so a rolling pod restart never rips connections down abruptly.</p>
 *
 * <p>The class is intentionally framework-light (no {@code @Component}) — it is wired
 * as a bean by {@code TelecomAutoConfiguration}, guarded by {@code ims.sip.enabled}.</p>
 */
public class SipStackManager {

    private static final Logger log = LoggerFactory.getLogger(SipStackManager.class);

    private final SipStackProperties properties;
    private final ObjectProvider<SipListener> listenerProvider;

    private SipStack sipStack;
    private SipProvider sipProvider;
    private ListeningPoint listeningPoint;
    private AddressFactory addressFactory;
    private HeaderFactory headerFactory;
    private MessageFactory messageFactory;

    public SipStackManager(SipStackProperties properties,
                           ObjectProvider<SipListener> listenerProvider) {
        this.properties = properties;
        this.listenerProvider = listenerProvider;
    }

    @PostConstruct
    public void start() {
        try {
            SipFactory sipFactory = SipFactory.getInstance();
            sipFactory.setPathName("gov.nist");

            this.sipStack = sipFactory.createSipStack(NistSipPropertiesFactory.build(properties));
            this.addressFactory = sipFactory.createAddressFactory();
            this.headerFactory = sipFactory.createHeaderFactory();
            this.messageFactory = sipFactory.createMessageFactory();

            String transport = properties.getTransport();
            this.listeningPoint = sipStack.createListeningPoint(
                    properties.getHost(), properties.getPort(), transport);
            this.sipProvider = sipStack.createSipProvider(listeningPoint);

            // Attach the pod's listener if one is present in the context.
            SipListener listener = listenerProvider.getIfAvailable();
            if (listener != null) {
                sipProvider.addSipListener(listener);
                log.info("Attached SIP listener {} to provider", listener.getClass().getName());
            } else {
                log.warn("No SipListener bean found — SIP stack is up but will not process events");
            }

            log.info("Jain SIP stack '{}' started, listening on {}:{}/{}",
                    properties.getStackName(), properties.getHost(),
                    properties.getPort(), transport.toUpperCase());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Jain SIP stack", e);
        }
    }

    @PreDestroy
    public void stop() {
        // Graceful drain: detach listener first so no new events are dispatched,
        // then let the NIST stack quiesce in-flight transactions before teardown.
        try {
            if (sipProvider != null) {
                SipListener listener = listenerProvider.getIfAvailable();
                if (listener != null) {
                    sipProvider.removeSipListener(listener);
                }
            }
            if (sipStack != null) {
                sipStack.stop();
            }
            log.info("Jain SIP stack '{}' stopped gracefully", properties.getStackName());
        } catch (Exception e) {
            log.warn("Error during SIP stack shutdown", e);
        }
    }

    public SipStack getSipStack() { return sipStack; }
    public SipProvider getSipProvider() { return sipProvider; }
    public ListeningPoint getListeningPoint() { return listeningPoint; }
    public AddressFactory getAddressFactory() { return addressFactory; }
    public HeaderFactory getHeaderFactory() { return headerFactory; }
    public MessageFactory getMessageFactory() { return messageFactory; }
}
