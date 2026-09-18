package com.snehal.ims.scscf.cx;

import com.snehal.ims.hss.grpc.CxServiceGrpc;
import com.snehal.ims.hss.grpc.MultimediaAuthAnswer;
import com.snehal.ims.hss.grpc.MultimediaAuthRequest;
import com.snehal.ims.hss.grpc.ServerAssignmentAnswer;
import com.snehal.ims.hss.grpc.ServerAssignmentRequest;
import com.snehal.ims.hss.grpc.ServerAssignmentType;
import com.snehal.ims.scscf.ScscfProperties;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cx interface client toward the HSS, exposing the two commands the S-CSCF owns —
 * MAR (pull authentication vectors) and SAR (store or clear the serving assignment
 * and fetch the user profile).
 *
 * <p>Calls are issued from Jain SIP listener threads, so the boundary is defended on
 * three axes: a per-call gRPC deadline bounds latency, a bulkhead bounds how much of
 * the listener thread pool can be blocked in the HSS at once, and a circuit breaker
 * stops calling a persistently unhealthy HSS. All three surface as a
 * {@code DIAMETER_UNABLE_TO_COMPLY} answer, which the caller maps onto a SIP 503
 * rather than stalling the transaction.</p>
 */
@Component
public class CxClient {

    private static final Logger log = LoggerFactory.getLogger(CxClient.class);
    private static final String CB = "hssCx";

    /** Diameter base Result-Code AVP mirrored from the HSS contract. */
    public static final int DIAMETER_UNABLE_TO_COMPLY = 5012;

    private final ScscfProperties props;

    @GrpcClient("hss")
    private CxServiceGrpc.CxServiceBlockingStub stub;

    public CxClient(ScscfProperties props) {
        this.props = props;
    }

    @CircuitBreaker(name = CB, fallbackMethod = "maaFallback")
    @Bulkhead(name = CB)
    public MultimediaAuthAnswer multimediaAuth(String impu, String impi, String scheme) {
        MultimediaAuthRequest request = MultimediaAuthRequest.newBuilder()
                .setPublicIdentity(nullToEmpty(impu))
                .setPrivateIdentity(nullToEmpty(impi))
                .setScscfName(props.resolvedServerName())
                .setAuthenticationScheme(nullToEmpty(scheme))
                .setNumberAuthItems(1)
                .build();
        return deadlined().multimediaAuth(request);
    }

    @CircuitBreaker(name = CB, fallbackMethod = "saaFallback")
    @Bulkhead(name = CB)
    public ServerAssignmentAnswer serverAssignment(String impu, String impi, ServerAssignmentType type) {
        ServerAssignmentRequest request = ServerAssignmentRequest.newBuilder()
                .setPublicIdentity(nullToEmpty(impu))
                .setPrivateIdentity(nullToEmpty(impi))
                .setServerName(props.resolvedServerName())
                .setAssignmentType(type)
                .build();
        return deadlined().serverAssignment(request);
    }

    private CxServiceGrpc.CxServiceBlockingStub deadlined() {
        return stub.withDeadlineAfter(props.getCx().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unused")
    private MultimediaAuthAnswer maaFallback(String impu, String impi, String scheme, Throwable t) {
        log.error("MAR to HSS failed for impu={} — circuit fallback", impu, t);
        return MultimediaAuthAnswer.newBuilder().setResultCode(DIAMETER_UNABLE_TO_COMPLY).build();
    }

    @SuppressWarnings("unused")
    private ServerAssignmentAnswer saaFallback(String impu, String impi, ServerAssignmentType type, Throwable t) {
        log.error("SAR({}) to HSS failed for impu={} — circuit fallback", type, impu, t);
        return ServerAssignmentAnswer.newBuilder().setResultCode(DIAMETER_UNABLE_TO_COMPLY).build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
