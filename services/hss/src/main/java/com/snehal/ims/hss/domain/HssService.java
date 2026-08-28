package com.snehal.ims.hss.domain;

import com.snehal.ims.hss.HssProperties;
import com.snehal.ims.hss.grpc.AuthorizationType;
import com.snehal.ims.hss.grpc.ServerAssignmentType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
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
        Optional<Subscriber> found = subscribers.findByImpu(impu);
        if (found.isEmpty()) {
            return UarResult.experimental(CxResultCodes.DIAMETER_ERROR_USER_UNKNOWN);
        }
        Subscriber s = found.get();
        if (impi != null && !impi.isBlank() && !impi.equals(s.getImpi())) {
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
            return UarResult.registered(CxResultCodes.DIAMETER_SUBSEQUENT_REGISTRATION, s.getScscfName());
        }
        return UarResult.firstRegistration(List.of(props.getServerCapabilities()));
    }

    // ---- MAR / MAA ----
    @Transactional(readOnly = true)
    @CircuitBreaker(name = CB)
    public MaaResult multimediaAuth(String impu, String impi, String scheme) {
        Optional<Subscriber> found = subscribers.findByImpi(impi);
        if (found.isEmpty()) {
            return MaaResult.experimental(CxResultCodes.DIAMETER_ERROR_USER_UNKNOWN);
        }
        Subscriber s = found.get();
        if (impu != null && !impu.isBlank() && !impu.equals(s.getImpu())) {
            return MaaResult.experimental(CxResultCodes.DIAMETER_ERROR_IDENTITIES_DONT_MATCH);
        }
        if (scheme != null && !scheme.isBlank() && !isSupportedScheme(scheme)) {
            return MaaResult.experimental(CxResultCodes.DIAMETER_ERROR_AUTH_SCHEME_NOT_SUPPORTED);
        }

        String ha1 = digestHa1(s.getImpi(), s.getRealm(), s.getPassword());
        AuthVector vector = new AuthVector(props.getAuthScheme(), s.getRealm(), s.getRealm(), ha1, "auth");
        return MaaResult.success(s.getImpu(), s.getImpi(), List.of(vector));
    }

    // ---- SAR / SAA ----
    @Transactional
    @CircuitBreaker(name = CB)
    public SaaResult serverAssignment(String impu, String impi, String serverName, ServerAssignmentType type) {
        Optional<Subscriber> found = subscribers.findByImpi(impi);
        if (found.isEmpty()) {
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
        Optional<Subscriber> found = subscribers.findByImpu(impu);
        if (found.isEmpty()) {
            return LiaResult.experimental(CxResultCodes.DIAMETER_ERROR_USER_UNKNOWN);
        }
        Subscriber s = found.get();
        if (s.getState() == RegistrationState.REGISTERED && s.getScscfName() != null) {
            return LiaResult.assigned(s.getScscfName());
        }
        if (s.getState() == RegistrationState.UNREGISTERED && s.getScscfName() != null) {
            return LiaResult.unregisteredService(s.getScscfName());
        }
        return LiaResult.notRegistered(List.of(props.getServerCapabilities()));
    }

    // ---- helpers ----

    private boolean isSupportedScheme(String scheme) {
        return "unknown".equalsIgnoreCase(scheme)
                || props.getAuthScheme().equalsIgnoreCase(scheme)
                || "SIP Digest".equalsIgnoreCase(scheme);
    }

    private static String digestHa1(String impi, String realm, String password) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest((impi + ":" + realm + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
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
