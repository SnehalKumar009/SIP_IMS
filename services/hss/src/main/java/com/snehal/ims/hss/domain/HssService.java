package com.snehal.ims.hss.domain;

import com.snehal.ims.hss.HssProperties;
import com.snehal.ims.hss.grpc.AuthorizationType;
import com.snehal.ims.hss.grpc.ServerAssignmentType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cx interface business logic over the subscriber store. Each method mirrors a
 * Diameter Cx command pair (UAR/UAA, MAR/MAA, SAR/SAA, LIR/LIA) and returns a
 * transport-neutral result the gRPC layer maps onto the wire.
 *
 * <p>The datastore boundary is guarded by a Resilience4j circuit breaker so a
 * database outage fails fast rather than stalling the calling CSCF.</p>
 */
@Service
public class HssService {

    private static final Logger log = LoggerFactory.getLogger(HssService.class);

    private static final String CB = "hssStore";

    private final SubscriberRepository subscribers;
    private final HssProperties props;

    public HssService(SubscriberRepository subscribers, HssProperties props) {
        this.subscribers = subscribers;
        this.props = props;
    }

    // ---- UAR / UAA ----
    @Transactional(readOnly = true)
    @CircuitBreaker(name = CB)
    public UarResult userAuthorization(String impu, String impi, String visitedNetwork, AuthorizationType type) {
        log.debug("TRACE Cx  -> UAR impu={} impi={} visited={} type={}", impu, impi, visitedNetwork, type);
        Optional<Subscriber> found = subscribers.findByImpu(impu);
        if (found.isEmpty()) {
            log.debug("TRACE Cx  <- UAA USER_UNKNOWN (5001): no subscriber with impu={}", impu);
            return UarResult.experimental(CxResultCodes.DIAMETER_ERROR_USER_UNKNOWN);
        }
        Subscriber s = found.get();
        log.debug("TRACE     · matched subscriber id={} impi={} state={} scscf={}",
                s.getId(), s.getImpi(), s.getState(), s.getScscfName());
        if (impi != null && !impi.isBlank() && !impi.equals(s.getImpi())) {
            log.debug("TRACE Cx  <- UAA IDENTITIES_DONT_MATCH (5002): request impi={} but stored impi={}",
                    impi, s.getImpi());
            return UarResult.experimental(CxResultCodes.DIAMETER_ERROR_IDENTITIES_DONT_MATCH);
        }

        if (type == AuthorizationType.AUTHORIZATION_TYPE_DEREGISTRATION) {
            if (s.getScscfName() == null || s.getState() == RegistrationState.NOT_REGISTERED) {
                return UarResult.experimental(CxResultCodes.DIAMETER_ERROR_IDENTITY_NOT_REGISTERED);
            }
            return UarResult.assigned(s.getScscfName());
        }

        // Registration: reuse the existing S-CSCF if one is assigned, else ask the
        // I-CSCF to select one from the returned capability set.
        if (s.getScscfName() != null) {
            log.debug("TRACE Cx  <- UAA SUBSEQUENT_REGISTRATION (2002) server={}", s.getScscfName());
            return UarResult.registered(CxResultCodes.DIAMETER_SUBSEQUENT_REGISTRATION, s.getScscfName());
        }
        log.debug("TRACE Cx  <- UAA FIRST_REGISTRATION (2001), returning capabilities for I-CSCF to select");
        return UarResult.firstRegistration(List.of(props.getServerCapabilities()));
    }

    // ---- MAR / MAA ----
    @Transactional(readOnly = true)
    @CircuitBreaker(name = CB)
    public MaaResult multimediaAuth(String impu, String impi, String scheme) {
        log.debug("TRACE Cx  -> MAR impu={} impi={} scheme={}", impu, impi, scheme);
        Optional<Subscriber> found = subscribers.findByImpi(impi);
        if (found.isEmpty()) {
            log.debug("TRACE Cx  <- MAA USER_UNKNOWN (5001): no subscriber with impi={}", impi);
            return MaaResult.experimental(CxResultCodes.DIAMETER_ERROR_USER_UNKNOWN);
        }
        Subscriber s = found.get();
        if (impu != null && !impu.isBlank() && !impu.equals(s.getImpu())) {
            return MaaResult.experimental(CxResultCodes.DIAMETER_ERROR_IDENTITIES_DONT_MATCH);
        }
        if (scheme != null && !scheme.isBlank() && !isSupportedScheme(scheme)) {
            return MaaResult.experimental(CxResultCodes.DIAMETER_ERROR_AUTH_SCHEME_NOT_SUPPORTED);
        }

        // Both flavours are returned; the S-CSCF picks the one matching its configured algorithm.
        List<AuthVector> vectors = new ArrayList<>(2);
        vectors.add(new AuthVector(DigestCredentials.SCHEME_MD5, s.getRealm(), s.getRealm(), s.getHa1(), "auth"));
        if (s.getHa1Sha256() != null && !s.getHa1Sha256().isBlank()) {
            vectors.add(new AuthVector(DigestCredentials.SCHEME_SHA256, s.getRealm(), s.getRealm(),
                    s.getHa1Sha256(), "auth"));
        }
        log.debug("TRACE Cx  <- MAA SUCCESS (2001) impu={} vectors={} (schemes: {})",
                s.getImpu(), vectors.size(), vectors.stream().map(AuthVector::scheme).toList());
        return MaaResult.success(s.getImpu(), s.getImpi(), List.copyOf(vectors));
    }

    // ---- SAR / SAA ----
    @Transactional
    @CircuitBreaker(name = CB)
    public SaaResult serverAssignment(String impu, String impi, String serverName, ServerAssignmentType type) {
        log.debug("TRACE Cx  -> SAR impu={} impi={} server={} type={}", impu, impi, serverName, type);
        Optional<Subscriber> found = subscribers.findByImpi(impi);
        if (found.isEmpty()) {
            log.debug("TRACE Cx  <- SAA USER_UNKNOWN (5001): no subscriber with impi={}", impi);
            return SaaResult.experimental(CxResultCodes.DIAMETER_ERROR_USER_UNKNOWN);
        }
        Subscriber s = found.get();
        if (impu != null && !impu.isBlank() && !impu.equals(s.getImpu())) {
            return SaaResult.experimental(CxResultCodes.DIAMETER_ERROR_IDENTITIES_DONT_MATCH);
        }

        switch (type) {
            case SERVER_ASSIGNMENT_REGISTRATION, SERVER_ASSIGNMENT_RE_REGISTRATION -> {
                s.setScscfName(serverName);
                s.setState(RegistrationState.REGISTERED);
                subscribers.save(s);
                log.debug("TRACE     · stored assignment: impu={} -> REGISTERED at {}", s.getImpu(), serverName);
                log.debug("TRACE Cx  <- SAA SUCCESS (2001), returning service profile ({} chars)",
                        s.getServiceProfile() == null ? 0 : s.getServiceProfile().length());
                return SaaResult.success(s.getServiceProfile());
            }
            case SERVER_ASSIGNMENT_UNREGISTERED_USER -> {
                s.setScscfName(serverName);
                s.setState(RegistrationState.UNREGISTERED);
                subscribers.save(s);
                return SaaResult.success(s.getServiceProfile());
            }
            case SERVER_ASSIGNMENT_USER_DEREGISTRATION,
                 SERVER_ASSIGNMENT_TIMEOUT_DEREGISTRATION,
                 SERVER_ASSIGNMENT_AUTHENTICATION_FAILURE -> {
                s.setScscfName(null);
                s.setState(RegistrationState.NOT_REGISTERED);
                subscribers.save(s);
                log.debug("TRACE     · cleared assignment: impu={} -> NOT_REGISTERED (type={})", s.getImpu(), type);
                log.debug("TRACE Cx  <- SAA SUCCESS (2001), no profile returned");
                return SaaResult.success(null);
            }
            default -> {
                return SaaResult.experimental(CxResultCodes.DIAMETER_ERROR_IN_ASSIGNMENT_TYPE);
            }
        }
    }

    // ---- LIR / LIA ----
    @Transactional(readOnly = true)
    @CircuitBreaker(name = CB)
    public LiaResult locationInfo(String impu) {
        log.debug("TRACE Cx  -> LIR impu={}", impu);
        Optional<Subscriber> found = subscribers.findByImpu(impu);
        if (found.isEmpty()) {
            log.debug("TRACE Cx  <- LIA USER_UNKNOWN (5001): no subscriber with impu={}", impu);
            return LiaResult.experimental(CxResultCodes.DIAMETER_ERROR_USER_UNKNOWN);
        }
        Subscriber s = found.get();
        log.debug("TRACE     · subscriber state={} scscf={}", s.getState(), s.getScscfName());
        if (s.getState() == RegistrationState.REGISTERED && s.getScscfName() != null) {
            log.debug("TRACE Cx  <- LIA SUCCESS (2001) server={}", s.getScscfName());
            return LiaResult.assigned(s.getScscfName());
        }
        if (s.getState() == RegistrationState.UNREGISTERED && s.getScscfName() != null) {
            log.debug("TRACE Cx  <- LIA UNREGISTERED_SERVICE (2003) server={}", s.getScscfName());
            return LiaResult.unregisteredService(s.getScscfName());
        }
        log.debug("TRACE Cx  <- LIA IDENTITY_NOT_REGISTERED (5003), returning capabilities");
        return LiaResult.notRegistered(List.of(props.getServerCapabilities()));
    }

    // ---- helpers ----

    private boolean isSupportedScheme(String scheme) {
        return "unknown".equalsIgnoreCase(scheme)
                || props.getAuthScheme().equalsIgnoreCase(scheme)
                || DigestCredentials.SCHEME_MD5.equalsIgnoreCase(scheme)
                || DigestCredentials.SCHEME_SHA256.equalsIgnoreCase(scheme);
    }

    // ---- transport-neutral result carriers ----

    public record AuthVector(String scheme, String realm, String authenticate, String authorization, String qop) {
    }

    public record UarResult(int resultCode, int experimentalResultCode, String serverName, List<String> capabilities) {
        static UarResult experimental(int code) {
            return new UarResult(0, code, null, List.of());
        }
        static UarResult assigned(String serverName) {
            return new UarResult(CxResultCodes.DIAMETER_SUCCESS, 0, serverName, List.of());
        }
        static UarResult registered(int experimental, String serverName) {
            return new UarResult(0, experimental, serverName, List.of());
        }
        static UarResult firstRegistration(List<String> capabilities) {
            return new UarResult(0, CxResultCodes.DIAMETER_FIRST_REGISTRATION, null, capabilities);
        }
    }

    public record MaaResult(int resultCode, int experimentalResultCode, String impu, String impi, List<AuthVector> vectors) {
        static MaaResult experimental(int code) {
            return new MaaResult(0, code, null, null, List.of());
        }
        static MaaResult success(String impu, String impi, List<AuthVector> vectors) {
            return new MaaResult(CxResultCodes.DIAMETER_SUCCESS, 0, impu, impi, vectors);
        }
    }

    public record SaaResult(int resultCode, int experimentalResultCode, String userProfile) {
        static SaaResult experimental(int code) {
            return new SaaResult(0, code, null);
        }
        static SaaResult success(String userProfile) {
            return new SaaResult(CxResultCodes.DIAMETER_SUCCESS, 0, userProfile);
        }
    }

    public record LiaResult(int resultCode, int experimentalResultCode, String serverName, List<String> capabilities) {
        static LiaResult experimental(int code) {
            return new LiaResult(0, code, null, List.of());
        }
        static LiaResult assigned(String serverName) {
            return new LiaResult(CxResultCodes.DIAMETER_SUCCESS, 0, serverName, List.of());
        }
        static LiaResult unregisteredService(String serverName) {
            return new LiaResult(0, CxResultCodes.DIAMETER_UNREGISTERED_SERVICE, serverName, List.of());
        }
        static LiaResult notRegistered(List<String> capabilities) {
            return new LiaResult(0, CxResultCodes.DIAMETER_ERROR_IDENTITY_NOT_REGISTERED, null, capabilities);
        }
    }
}
