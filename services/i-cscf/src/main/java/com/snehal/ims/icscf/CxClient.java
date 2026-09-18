package com.snehal.ims.icscf;

import com.snehal.ims.hss.grpc.AuthorizationType;
import com.snehal.ims.hss.grpc.CxServiceGrpc;
import com.snehal.ims.hss.grpc.LocationInfoAnswer;
import com.snehal.ims.hss.grpc.LocationInfoRequest;
import com.snehal.ims.hss.grpc.UserAuthorizationAnswer;
import com.snehal.ims.hss.grpc.UserAuthorizationRequest;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cx interface client toward the HSS. Wraps the generated blocking gRPC stub and
 * exposes the two commands the I-CSCF needs — UAR (authorize a REGISTER + locate an
 * S-CSCF) and LIR (locate the serving S-CSCF for a terminating request).
 *
 * <p>Calls are issued from Jain SIP listener threads, so the boundary is defended on
 * three axes: a per-call gRPC deadline bounds latency, a bulkhead bounds how much of the
 * listener thread pool can be blocked in the HSS at once, and a circuit breaker stops
 * calling a persistently unhealthy HSS. All three yield a
 * {@code DIAMETER_UNABLE_TO_COMPLY} answer that the listener maps onto a SIP server
 * error rather than stalling the dialog.</p>
 */
@Component
public class CxClient {

    private static final Logger log = LoggerFactory.getLogger(CxClient.class);
    private static final String CB = "hssCx";

    /** Diameter base Result-Code AVP mirrored from the HSS contract. */
    static final int DIAMETER_UNABLE_TO_COMPLY = 5012;

    private final IcscfProperties props;

    @GrpcClient("hss")
    private CxServiceGrpc.CxServiceBlockingStub stub;

    public CxClient(IcscfProperties props) {
        this.props = props;
    }

    @CircuitBreaker(name = CB, fallbackMethod = "uarFallback")
    @Bulkhead(name = CB)
    public UserAuthorizationAnswer userAuthorization(String impu, String impi,
                                                     String visitedNetwork, AuthorizationType type) {
        UserAuthorizationRequest request = UserAuthorizationRequest.newBuilder()
                .setPublicIdentity(nullToEmpty(impu))
                .setPrivateIdentity(nullToEmpty(impi))
                .setVisitedNetwork(nullToEmpty(visitedNetwork))
                .setAuthorizationType(type)
                .build();
        return deadlined().userAuthorization(request);
    }

    @CircuitBreaker(name = CB, fallbackMethod = "liaFallback")
    @Bulkhead(name = CB)
    public LocationInfoAnswer locationInfo(String impu, boolean originating) {
        LocationInfoRequest request = LocationInfoRequest.newBuilder()
                .setPublicIdentity(nullToEmpty(impu))
                .setOriginating(originating)
                .build();
        return deadlined().locationInfo(request);
    }

    private CxServiceGrpc.CxServiceBlockingStub deadlined() {
        return stub.withDeadlineAfter(props.getCx().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unused")
    private UserAuthorizationAnswer uarFallback(String impu, String impi,
                                                String visitedNetwork, AuthorizationType type, Throwable t) {
        log.error("UAR to HSS failed for impu={} — circuit fallback", impu, t);
        return UserAuthorizationAnswer.newBuilder().setResultCode(DIAMETER_UNABLE_TO_COMPLY).build();
    }

    @SuppressWarnings("unused")
    private LocationInfoAnswer liaFallback(String impu, boolean originating, Throwable t) {
        log.error("LIR to HSS failed for impu={} — circuit fallback", impu, t);
        return LocationInfoAnswer.newBuilder().setResultCode(DIAMETER_UNABLE_TO_COMPLY).build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
