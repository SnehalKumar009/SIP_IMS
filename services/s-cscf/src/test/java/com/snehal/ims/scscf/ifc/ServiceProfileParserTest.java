package com.snehal.ims.scscf.ifc;

import static org.assertj.core.api.Assertions.assertThat;

import com.snehal.ims.scscf.ifc.ServiceProfile.FilterCriterion;
import com.snehal.ims.scscf.ifc.ServiceProfile.SessionCase;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceProfileParserTest {

    private final ServiceProfileParser parser = new ServiceProfileParser();

    @Test
    void parsesPublicIdentities() {
        ServiceProfile profile = parser.parse("""
                <ServiceProfile>
                  <PublicIdentity><Identity>sip:alice@ims.snehal.com</Identity></PublicIdentity>
                  <PublicIdentity><Identity>tel:+15550100</Identity></PublicIdentity>
                </ServiceProfile>
                """);

        assertThat(profile.publicIdentities())
                .containsExactly("sip:alice@ims.snehal.com", "tel:+15550100");
    }

    @Test
    void parsesFilterCriteriaInPriorityOrder() {
        ServiceProfile profile = parser.parse("""
                <ServiceProfile>
                  <InitialFilterCriteria>
                    <Priority>2</Priority>
                    <TriggerPoint><SPT><Method>MESSAGE</Method></SPT></TriggerPoint>
                    <ApplicationServer>
                      <ServerName>sip:msg-as@ims.snehal.com</ServerName>
                      <DefaultHandling>1</DefaultHandling>
                    </ApplicationServer>
                  </InitialFilterCriteria>
                  <InitialFilterCriteria>
                    <Priority>1</Priority>
                    <TriggerPoint>
                      <SPT><Method>INVITE</Method></SPT>
                      <SPT><SessionCase>0</SessionCase></SPT>
                    </TriggerPoint>
                    <ApplicationServer>
                      <ServerName>sip:tel-as@ims.snehal.com</ServerName>
                      <DefaultHandling>0</DefaultHandling>
                    </ApplicationServer>
                  </InitialFilterCriteria>
                </ServiceProfile>
                """);

        List<FilterCriterion> criteria = profile.filterCriteria();
        assertThat(criteria).hasSize(2);
        assertThat(criteria.get(0).applicationServer()).isEqualTo("sip:tel-as@ims.snehal.com");
        assertThat(criteria.get(0).sessionCase()).isEqualTo(SessionCase.ORIGINATING);
        assertThat(criteria.get(0).sessionContinued()).isTrue();
        assertThat(criteria.get(1).applicationServer()).isEqualTo("sip:msg-as@ims.snehal.com");
        assertThat(criteria.get(1).sessionContinued()).isFalse();
    }

    @Test
    void terminatingSessionCaseIsRecognised() {
        ServiceProfile profile = parser.parse("""
                <ServiceProfile>
                  <InitialFilterCriteria>
                    <Priority>0</Priority>
                    <TriggerPoint><SPT><SessionCase>1</SessionCase></SPT></TriggerPoint>
                    <ApplicationServer><ServerName>sip:as@ims.snehal.com</ServerName></ApplicationServer>
                  </InitialFilterCriteria>
                </ServiceProfile>
                """);

        assertThat(profile.filterCriteria().get(0).sessionCase()).isEqualTo(SessionCase.TERMINATING);
    }

    @Test
    void criterionWithoutServerNameIsIgnored() {
        ServiceProfile profile = parser.parse("""
                <ServiceProfile>
                  <InitialFilterCriteria>
                    <Priority>0</Priority>
                    <TriggerPoint><SPT><Method>INVITE</Method></SPT></TriggerPoint>
                  </InitialFilterCriteria>
                </ServiceProfile>
                """);

        assertThat(profile.filterCriteria()).isEmpty();
    }

    @Test
    void malformedProfileYieldsAnEmptyProfileRatherThanThrowing() {
        assertThat(parser.parse("<ServiceProfile><unclosed>")).isEqualTo(ServiceProfile.EMPTY);
        assertThat(parser.parse(null)).isEqualTo(ServiceProfile.EMPTY);
        assertThat(parser.parse("  ")).isEqualTo(ServiceProfile.EMPTY);
    }

    @Test
    void externalEntitiesAreNotResolved() {
        // The profile arrives from the HSS over the network; a DTD must never be honoured.
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE ServiceProfile [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <ServiceProfile>
                  <PublicIdentity><Identity>&xxe;</Identity></PublicIdentity>
                </ServiceProfile>
                """;

        assertThat(parser.parse(xxe)).isEqualTo(ServiceProfile.EMPTY);
    }
}
