package com.snehal.ims.scscf.ifc;

import com.snehal.ims.scscf.ifc.ServiceProfile.FilterCriterion;
import com.snehal.ims.scscf.ifc.ServiceProfile.SessionCase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses the service profile XML (3GPP TS 29.228) carried in a Cx Server-Assignment
 * answer.
 *
 * <p>The document arrives from the HSS over the network, so the parser is hardened
 * against XXE and entity-expansion attacks: DTDs are rejected outright and no external
 * resource is ever resolved.</p>
 */
@Component
public class ServiceProfileParser {

    private static final Logger log = LoggerFactory.getLogger(ServiceProfileParser.class);

    /** TS 29.228 SessionCase: 0 originating, 1/2 terminating, 3 originating-unregistered. */
    private static final int SESSION_CASE_ORIGINATING = 0;
    private static final int SESSION_CASE_ORIGINATING_UNREGISTERED = 3;

    /** TS 29.228 DefaultHandling: 0 SESSION_CONTINUED, 1 SESSION_TERMINATED. */
    private static final int DEFAULT_HANDLING_CONTINUED = 0;

    public ServiceProfile parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return ServiceProfile.EMPTY;
        }
        try {
            Document document = newSecureBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();
            return new ServiceProfile(publicIdentities(document), filterCriteria(document));
        } catch (Exception e) {
            // A malformed profile must not fail the registration: the user simply gets
            // no application services.
            log.warn("Unparseable service profile, continuing without filter criteria", e);
            return ServiceProfile.EMPTY;
        }
    }

    private static DocumentBuilder newSecureBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder();
    }

    private static List<String> publicIdentities(Document document) {
        List<String> identities = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("Identity");
        for (int i = 0; i < nodes.getLength(); i++) {
            String identity = nodes.item(i).getTextContent();
            if (identity != null && !identity.isBlank()) {
                identities.add(identity.trim());
            }
        }
        return identities;
    }

    private static List<FilterCriterion> filterCriteria(Document document) {
        List<FilterCriterion> criteria = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("InitialFilterCriteria");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element ifc) {
                FilterCriterion criterion = toCriterion(ifc);
                if (criterion != null) {
                    criteria.add(criterion);
                }
            }
        }
        criteria.sort(Comparator.comparingInt(FilterCriterion::priority));
        return criteria;
    }

    private static FilterCriterion toCriterion(Element ifc) {
        String serverName = firstChildText(ifc, "ServerName");
        if (serverName == null || serverName.isBlank()) {
            return null;
        }
        int priority = parseInt(firstChildText(ifc, "Priority"), 0);
        boolean continued = parseInt(firstChildText(ifc, "DefaultHandling"), DEFAULT_HANDLING_CONTINUED)
                == DEFAULT_HANDLING_CONTINUED;

        List<String> methods = new ArrayList<>();
        SessionCase sessionCase = null;
        NodeList spts = ifc.getElementsByTagName("SPT");
        for (int i = 0; i < spts.getLength(); i++) {
            if (!(spts.item(i) instanceof Element spt)) {
                continue;
            }
            String method = firstChildText(spt, "Method");
            if (method != null && !method.isBlank()) {
                methods.add(method.trim().toUpperCase());
            }
            String rawCase = firstChildText(spt, "SessionCase");
            if (rawCase != null && !rawCase.isBlank()) {
                int value = parseInt(rawCase, -1);
                if (value == SESSION_CASE_ORIGINATING || value == SESSION_CASE_ORIGINATING_UNREGISTERED) {
                    sessionCase = SessionCase.ORIGINATING;
                } else if (value >= 0) {
                    sessionCase = SessionCase.TERMINATING;
                }
            }
        }
        return new FilterCriterion(priority, methods, sessionCase, serverName.trim(), continued);
    }

    private static String firstChildText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        Node node = nodes.getLength() > 0 ? nodes.item(0) : null;
        return node == null ? null : node.getTextContent();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
