package com.snehal.ims.icscf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.snehal.ims.icscf.ScscfSelector.ScscfTarget;
import org.junit.jupiter.api.Test;

class ScscfSelectorTest {

    private ScscfSelector selector() {
        IcscfProperties props = new IcscfProperties();
        props.getScscf().setHost("s-cscf.ims-core.svc.cluster.local");
        props.getScscf().setPort(5060);
        props.getScscf().setTransport("udp");
        return new ScscfSelector(props);
    }

    @Test
    void fallsBackToDefaultWhenServerNameBlank() {
        ScscfTarget target = selector().select(null);
        assertEquals("s-cscf.ims-core.svc.cluster.local", target.host());
        assertEquals(5060, target.port());
        assertEquals("udp", target.transport());
    }

    @Test
    void parsesHostAndExplicitPort() {
        ScscfTarget target = selector().select("sip:scscf1.ims.snehal.com:6060");
        assertEquals("scscf1.ims.snehal.com", target.host());
        assertEquals(6060, target.port());
    }

    @Test
    void parsesTransportParameter() {
        ScscfTarget target = selector().select("sip:scscf1.ims.snehal.com:5060;lr;transport=tcp");
        assertEquals("scscf1.ims.snehal.com", target.host());
        assertEquals(5060, target.port());
        assertEquals("tcp", target.transport());
    }

    @Test
    void usesDefaultPortWhenAbsent() {
        ScscfTarget target = selector().select("sip:scscf1.ims.snehal.com");
        assertEquals("scscf1.ims.snehal.com", target.host());
        assertEquals(5060, target.port());
    }

    @Test
    void stripsUserinfo() {
        ScscfTarget target = selector().select("sip:scscf@scscf1.ims.snehal.com:5080");
        assertEquals("scscf1.ims.snehal.com", target.host());
        assertEquals(5080, target.port());
    }
}
