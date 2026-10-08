package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sysadminanywhere.m3.messaging.domain.PayloadCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Node;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-side presentation policy. Original transport bytes are never modified. */
@Service
public class PayloadMasking {
    public static final String HIDDEN = "[REDACTED]";
    private final ObjectMapper json;
    private final Set<String> fields;
    private final List<String> jsonPaths;
    private final List<String> xmlPaths;
    private final String version;
    private static final com.google.re2j.Pattern CARD = com.google.re2j.Pattern.compile("\\b(?:[0-9][ -]?){13,19}\\b");
    private static final com.google.re2j.Pattern PHONE = com.google.re2j.Pattern.compile("(?:\\+?[0-9][ ()-]*){10,15}");

    public PayloadMasking(ObjectMapper json,
            @Value("${m3.masking.fields:password,secret,token,authorization,phone,mobile,cardNumber,pan,cvv,ssn}") String fields,
            @Value("${m3.masking.json-paths:}") String jsonPaths,
            @Value("${m3.masking.xml-paths:}") String xmlPaths) {
        this.json = json;
        this.fields = new HashSet<>(split(fields).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
        this.jsonPaths = paths(jsonPaths); this.xmlPaths = paths(xmlPaths);
        for (String path : this.jsonPaths) if (!path.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*(?:\\[\\*\\])?)+"))
            throw new IllegalArgumentException("Mask JSON paths must use $.field and [*]");
        try { for (String path : this.xmlPaths) XPathFactory.newInstance().newXPath().compile(path); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid masking XPath", e); }
        this.version = SourceDeliveryService.digest((fields + "|" + jsonPaths + "|" + xmlPaths + "|v1").getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split(";")).flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    private static List<String> paths(String value) { return Arrays.stream(value.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList(); }
    public String version() { return version; }
    public boolean sensitive(String name) { return fields.contains(name.toLowerCase(Locale.ROOT)); }
    public String text(String value) { return value == null ? "" : PHONE.matcher(CARD.matcher(value).replaceAll(HIDDEN)).replaceAll(HIDDEN); }
    public String metadata(String key, String value) { return sensitive(key) ? HIDDEN : text(value); }
    public String diagnostic(String value) {
        return value == null ? null : HIDDEN;
    }
    public String payload(byte[] bytes, String charset, String type) {
        if (charset == null) return HIDDEN;
        try { return payload(PayloadCodec.decode(bytes, charset), type); }
        catch (Exception error) { return HIDDEN; }
    }
    public String payload(String body, String type) {
        String value = body.stripLeading();
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        try {
            if (normalized.contains("json") || value.startsWith("{") || value.startsWith("[")) {
                JsonNode root = json.readTree(body);
                if (root == null) return HIDDEN;
                if(root.isValueNode()) return json.writeValueAsString(text(root.asText()));
                redactFields(root);
                for (String path : jsonPaths) redactPath(root, path.substring(2).split("\\."), 0);
                return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            }
            if (normalized.contains("xml") || value.startsWith("<")) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setExpandEntityReferences(false); factory.setXIncludeAware(false);
                var builder = factory.newDocumentBuilder();
                builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                    @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                });
                var document = builder.parse(new org.xml.sax.InputSource(new StringReader(body)));
                redactXml(document);
                for (String path : xmlPaths) {
                    var matches = (org.w3c.dom.NodeList) XPathFactory.newInstance().newXPath().evaluate(path, document, javax.xml.xpath.XPathConstants.NODESET);
                    for (int i = 0; i < matches.getLength(); i++) matches.item(i).setTextContent(HIDDEN);
                }
                var transformers = TransformerFactory.newInstance();
                transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                var transformer = transformers.newTransformer(); transformer.setOutputProperty("indent", "yes");
                var result = new StringWriter(); transformer.transform(new DOMSource(document), new StreamResult(result));
                return result.toString();
            }
            return text(body);
        } catch (Exception invalidStructuredPayload) { return HIDDEN; }
    }
    private void redactFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var names = new ArrayList<String>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                var child = object.get(name);
                if (sensitive(name)) object.put(name, HIDDEN);
                else if (child.isTextual()) object.put(name, text(child.asText()));
                else if (child.isNumber() && !text(child.asText()).equals(child.asText())) object.put(name, HIDDEN);
                else redactFields(child);
            }
        } else if (node.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                var child = array.get(i);
                if (child.isValueNode() && !text(child.asText()).equals(child.asText())) array.set(i, json.getNodeFactory().textNode(HIDDEN));
                else redactFields(child);
            }
        }
    }
    private void redactPath(JsonNode node, String[] parts, int position) {
        String part = parts[position]; boolean array = part.endsWith("[*]");
        String name = array ? part.substring(0, part.length()-3) : part;
        if (!(node instanceof ObjectNode object) || !object.has(name)) return;
        if (position == parts.length-1) { object.put(name,HIDDEN); return; }
        JsonNode child = object.get(name);
        if (array && child.isArray()) child.forEach(item -> redactPath(item,parts,position+1));
        else if (!array) redactPath(child,parts,position+1);
    }
    private void redactXml(Node node) {
        if (node.getNodeType() == Node.ELEMENT_NODE && sensitive(node.getNodeName().replaceFirst("^.*:",""))) {
            node.setTextContent(HIDDEN); return;
        }
        if (node.hasAttributes()) for (int i=0;i<node.getAttributes().getLength();i++) {
            Node attribute=node.getAttributes().item(i);
            attribute.setNodeValue(metadata(attribute.getNodeName().replaceFirst("^.*:",""),attribute.getNodeValue()));
        }
        if (node.getNodeType()==Node.TEXT_NODE || node.getNodeType()==Node.CDATA_SECTION_NODE) node.setNodeValue(text(node.getNodeValue()));
        if (node.getNodeType()==Node.COMMENT_NODE || node.getNodeType()==Node.PROCESSING_INSTRUCTION_NODE) node.setNodeValue(HIDDEN);
        for (int i=0;i<node.getChildNodes().getLength();i++) redactXml(node.getChildNodes().item(i));
    }
}
