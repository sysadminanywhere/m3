package com.sysadminanywhere.m3.messaging.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/** Byte preservation and explicit, strict text conversion. */
public final class PayloadCodec {
    private PayloadCodec() { }
    public static String charset(String name) {
        if (name == null || name.isBlank()) return null;
        String canonical = Charset.forName(name.trim()).name();
        if (canonical.length() > 64) throw new IllegalArgumentException("Charset name is too long");
        return canonical;
    }
    public static byte[] encode(String text, String name) {
        try {
            var buffer = Charset.forName(name).newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (CharacterCodingException error) { throw new IllegalArgumentException("Text cannot be encoded as " + name, error); }
    }
    public static String decode(byte[] bytes, String name) {
        if (name == null) throw new IllegalArgumentException("Text charset is unknown");
        try {
            return Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new IllegalArgumentException("Payload cannot be decoded as " + name, error); }
    }
    public static String bom(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == (byte)0xfe && bytes[3] == (byte)0xff) return "UTF-32BE";
        if (bytes.length >= 4 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe && bytes[2] == 0 && bytes[3] == 0) return "UTF-32LE";
        if (bytes.length >= 3 && bytes[0] == (byte)0xef && bytes[1] == (byte)0xbb && bytes[2] == (byte)0xbf) return "UTF-8";
        if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) return "UTF-16BE";
        if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) return "UTF-16LE";
        return null;
    }
    public static String mediaCharset(String type) {
        var matcher = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^;\"\\s]+)").matcher(type);
        return matcher.find() ? charset(matcher.group(1)) : null;
    }
    public static void applyRequest(Message message, String payload, String declaredCharset, Map<String, String> metadata) {
        String fromMetadata = charset(metadata.get("charset"));
        String requested = charset(declaredCharset);
        if (requested != null && fromMetadata != null && !requested.equals(fromMetadata))
            throw new IllegalArgumentException("Conflicting charset declarations");
        requested = requested == null ? fromMetadata : requested;
        String media = mediaCharset(message.getPayloadType());
        if (requested != null && media != null && !requested.equals(media)) throw new IllegalArgumentException("Conflicting media type charset");
        requested = requested == null ? media : requested;
        boolean base64 = "base64".equals(metadata.get("encoding"));
        if (metadata.containsKey("encoding") && !base64) throw new IllegalArgumentException("Only base64 byte transport encoding is supported");
        byte[] bytes = base64 ? Base64.getDecoder().decode(payload) : encode(payload, requested == null ? "UTF-8" : requested);
        String origin = requested == null ? (base64 ? "UNKNOWN" : "TEXT_UTF8") : "REQUEST";
        if (!base64 && requested == null) requested = "UTF-8";
        String bom = bom(bytes);
        if (bomConflicts(bytes, requested))
            throw new IllegalArgumentException("BOM conflicts with the declared charset");
        if (requested == null && bom != null) { requested = bom; origin = "BOM"; }
        message.setContent(bytes, requested, origin, base64 ? "BASE64" : "TEXT");
    }
    public static String outputType(String type, String charset) {
        if (charset == null) return type;
        return type.replaceAll("(?i)(;\\s*charset\\s*=\\s*)(?:\"[^\"]*\"|[^;\\s]*)", "$1" + charset);
    }
    public static boolean bomConflicts(byte[] bytes, String charset) {
        String bom = bom(bytes);
        return bom != null && charset != null && !charset.equals(bom)
                && !(charset.equals("UTF-16") && bom.startsWith("UTF-16"))
                && !(charset.equals("UTF-32") && bom.startsWith("UTF-32"));
    }
}
