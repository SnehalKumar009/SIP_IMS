package com.snehal.ims.hss.grpc;

import com.snehal.ims.hss.domain.HssService;
import com.snehal.ims.hss.domain.HssService.AuthVector;
import com.snehal.ims.hss.domain.HssService.LiaResult;
import com.snehal.ims.hss.domain.HssService.MaaResult;
import com.snehal.ims.hss.domain.HssService.SaaResult;
import com.snehal.ims.hss.domain.HssService.UarResult;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC front end for the Cx interface. Translates each request to the
 * {@link HssService} and maps the transport-neutral result back onto the wire.
 */
@GrpcService
public class CxGrpcService extends CxServiceGrpc.CxServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(CxGrpcService.class);

    private final HssService hss;

    public CxGrpcService(HssService hss) {
        this.hss = hss;
    }

    @Override
    public void userAuthorization(UserAuthorizationRequest request,
                                  StreamObserver<UserAuthorizationAnswer> observer) {
        log.debug("UAR impu={} impi={} type={}", request.getPublicIdentity(),
                request.getPrivateIdentity(), request.getAuthorizationType());
        UarResult r = hss.userAuthorization(request.getPublicIdentity(), request.getPrivateIdentity(),
                request.getVisitedNetwork(), request.getAuthorizationType());
        UserAuthorizationAnswer.Builder answer = UserAuthorizationAnswer.newBuilder()
                .setResultCode(r.resultCode())
                .setExperimentalResultCode(r.experimentalResultCode())
                .addAllServerCapabilities(r.capabilities());
        if (r.serverName() != null) {
            answer.setServerName(r.serverName());
        }
        respond(observer, answer.build());
    }

    @Override
    public void multimediaAuth(MultimediaAuthRequest request,
                               StreamObserver<MultimediaAuthAnswer> observer) {
        log.debug("MAR impu={} impi={} scheme={}", request.getPublicIdentity(),
                request.getPrivateIdentity(), request.getAuthenticationScheme());
        MaaResult r = hss.multimediaAuth(request.getPublicIdentity(), request.getPrivateIdentity(),
                request.getAuthenticationScheme());
        MultimediaAuthAnswer.Builder answer = MultimediaAuthAnswer.newBuilder()
                .setResultCode(r.resultCode())
                .setExperimentalResultCode(r.experimentalResultCode());
        if (r.impu() != null) {
            answer.setPublicIdentity(r.impu());
        }
        if (r.impi() != null) {
            answer.setPrivateIdentity(r.impi());
        }
        for (AuthVector v : r.vectors()) {
            answer.addVectors(AuthenticationVector.newBuilder()
                    .setScheme(v.scheme())
                    .setRealm(v.realm())
                    .setAuthenticate(v.authenticate())
                    .setAuthorization(v.authorization())
                    .setQop(v.qop())
                    .build());
        }
        respond(observer, answer.build());
    }

    @Override
    public void serverAssignment(ServerAssignmentRequest request,
                                 StreamObserver<ServerAssignmentAnswer> observer) {
        log.debug("SAR impu={} impi={} server={} type={}", request.getPublicIdentity(),
                request.getPrivateIdentity(), request.getServerName(), request.getAssignmentType());
        SaaResult r = hss.serverAssignment(request.getPublicIdentity(), request.getPrivateIdentity(),
                request.getServerName(), request.getAssignmentType());
        ServerAssignmentAnswer.Builder answer = ServerAssignmentAnswer.newBuilder()
                .setResultCode(r.resultCode())
                .setExperimentalResultCode(r.experimentalResultCode());
        if (r.userProfile() != null) {
            answer.setUserProfile(r.userProfile());
        }
        respond(observer, answer.build());
    }

    @Override
    public void locationInfo(LocationInfoRequest request,
                             StreamObserver<LocationInfoAnswer> observer) {
        log.debug("LIR impu={} originating={}", request.getPublicIdentity(), request.getOriginating());
        LiaResult r = hss.locationInfo(request.getPublicIdentity());
        LocationInfoAnswer.Builder answer = LocationInfoAnswer.newBuilder()
                .setResultCode(r.resultCode())
                .setExperimentalResultCode(r.experimentalResultCode())
                .addAllServerCapabilities(r.capabilities());
        if (r.serverName() != null) {
            answer.setServerName(r.serverName());
        }
        respond(observer, answer.build());
    }

    private static <T> void respond(StreamObserver<T> observer, T message) {
        observer.onNext(message);
        observer.onCompleted();
    }
}
