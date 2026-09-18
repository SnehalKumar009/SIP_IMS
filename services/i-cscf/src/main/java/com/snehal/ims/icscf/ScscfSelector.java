package com.snehal.ims.icscf;

import org.springframework.stereotype.Component;

/**
 * Resolves the next-hop S-CSCF for a request. When the HSS returns an explicit
 * server name (an assigned S-CSCF SIP URI) it is used verbatim; otherwise — the HSS
 * returned only a capability set — the I-CSCF falls back to its configured default
 * S-CSCF. Real capability-based selection across an S-CSCF farm arrives with Phase 4.
 */
@Component
public class ScscfSelector {

    private final IcscfProperties props;

    public ScscfSelector(IcscfProperties props) {
        this.props = props;
    }

    /**
     * @param serverName assigned S-CSCF SIP URI from the HSS, or {@code null}/blank when
     *                   the HSS returned only capabilities.
     */
    public ScscfTarget select(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            return parse(serverName);
        }
        IcscfProperties.Scscf s = props.getScscf();
        return new ScscfTarget(s.getHost(), s.getPort(), s.getTransport());
    }

    /** Parses a SIP URI such as {@code sip:s-cscf.example.com:5060;transport=tcp}. */
    ScscfTarget parse(String sipUri) {
        String work = sipUri.trim();
        int scheme = work.indexOf(':');
        if (scheme >= 0 && (work.startsWith("sip:") || work.startsWith("sips:"))) {
            work = work.substring(scheme + 1);
        }
        int at = work.indexOf('@');
        if (at >= 0) {
            work = work.substring(at + 1);
        }

        String transport = props.getScscf().getTransport();
        int semi = work.indexOf(';');
        if (semi >= 0) {
            String params = work.substring(semi + 1);
            work = work.substring(0, semi);
            String parsed = transportParam(params);
            if (parsed != null) {
                transport = parsed;
            }
        }

        String host = work;
        int port = props.getScscf().getPort();
        int colon = work.lastIndexOf(':');
        if (colon >= 0) {
            host = work.substring(0, colon);
            try {
                port = Integer.parseInt(work.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // Malformed port — keep the configured default.
            }
        }
        return new ScscfTarget(host, port, transport);
    }

    private static String transportParam(String params) {
        for (String p : params.split(";")) {
            String trimmed = p.trim();
            if (trimmed.regionMatches(true, 0, "transport=", 0, "transport=".length())) {
                return trimmed.substring("transport=".length());
            }
        }
        return null;
    }

    public record ScscfTarget(String host, int port, String transport) {
    }
}
