package com.snehal.ims.hss.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.snehal.ims.hss.HssProperties;
import com.snehal.ims.hss.grpc.AuthorizationType;
import com.snehal.ims.hss.grpc.ServerAssignmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(HssService.class)
@EnableConfigurationProperties(HssProperties.class)
class HssServiceTest {

    @Autowired
    private SubscriberRepository subscribers;

    @Autowired
    private HssService hss;

    private static final String REALM = "ims.snehal.com";
    private static final String IMPI = "alice@" + REALM;
    private static final String IMPU = "sip:alice@" + REALM;
    private static final String SCSCF = "sip:scscf1." + REALM;

    @BeforeEach
    void seed() {
        subscribers.deleteAll();
        subscribers.save(new Subscriber(IMPI, IMPU, REALM, "alice-secret", "<ServiceProfile/>"));
    }

    @Test
    void unknownUserReturnsUserUnknown() {
        HssService.UarResult r = hss.userAuthorization("sip:nobody@" + REALM, null, "visited",
                AuthorizationType.AUTHORIZATION_TYPE_REGISTRATION);
        assertThat(r.experimentalResultCode()).isEqualTo(CxResultCodes.DIAMETER_ERROR_USER_UNKNOWN);
    }

    @Test
    void firstRegistrationReturnsCapabilities() {
        HssService.UarResult r = hss.userAuthorization(IMPU, IMPI, "visited",
                AuthorizationType.AUTHORIZATION_TYPE_REGISTRATION);
        assertThat(r.experimentalResultCode()).isEqualTo(CxResultCodes.DIAMETER_FIRST_REGISTRATION);
        assertThat(r.serverName()).isNull();
        assertThat(r.capabilities()).isNotEmpty();
    }

    @Test
    void mismatchedIdentitiesRejected() {
        HssService.UarResult r = hss.userAuthorization(IMPU, "mallory@" + REALM, "visited",
                AuthorizationType.AUTHORIZATION_TYPE_REGISTRATION);
        assertThat(r.experimentalResultCode()).isEqualTo(CxResultCodes.DIAMETER_ERROR_IDENTITIES_DONT_MATCH);
    }

    @Test
    void multimediaAuthReturnsDigestVector() {
        HssService.MaaResult r = hss.multimediaAuth(IMPU, IMPI, "SIP Digest");
        assertThat(r.resultCode()).isEqualTo(CxResultCodes.DIAMETER_SUCCESS);
        assertThat(r.vectors()).hasSize(1);
        assertThat(r.vectors().get(0).authorization()).hasSize(32); // MD5 HA1 hex
    }

    @Test
    void assignmentThenLocationInfoRoundTrip() {
        HssService.SaaResult sar = hss.serverAssignment(IMPU, IMPI, SCSCF,
                ServerAssignmentType.SERVER_ASSIGNMENT_REGISTRATION);
        assertThat(sar.resultCode()).isEqualTo(CxResultCodes.DIAMETER_SUCCESS);

        HssService.LiaResult lir = hss.locationInfo(IMPU);
        assertThat(lir.resultCode()).isEqualTo(CxResultCodes.DIAMETER_SUCCESS);
        assertThat(lir.serverName()).isEqualTo(SCSCF);

        // A subsequent REGISTER now reuses the assigned S-CSCF.
        HssService.UarResult uar = hss.userAuthorization(IMPU, IMPI, "visited",
                AuthorizationType.AUTHORIZATION_TYPE_REGISTRATION);
        assertThat(uar.experimentalResultCode()).isEqualTo(CxResultCodes.DIAMETER_SUBSEQUENT_REGISTRATION);
        assertThat(uar.serverName()).isEqualTo(SCSCF);
    }

    @Test
    void deregistrationClearsAssignment() {
        hss.serverAssignment(IMPU, IMPI, SCSCF, ServerAssignmentType.SERVER_ASSIGNMENT_REGISTRATION);
        hss.serverAssignment(IMPU, IMPI, SCSCF, ServerAssignmentType.SERVER_ASSIGNMENT_USER_DEREGISTRATION);

        HssService.LiaResult lir = hss.locationInfo(IMPU);
        assertThat(lir.experimentalResultCode()).isEqualTo(CxResultCodes.DIAMETER_ERROR_IDENTITY_NOT_REGISTERED);
    }
}
