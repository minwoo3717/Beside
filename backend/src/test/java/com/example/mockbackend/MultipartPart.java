package com.example.mockbackend;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One part of a multipart/form-data body, as the fake inference services read POST /infer/upload
 * (InferenceClientTest, RealJobWorkerUploadTest). Strict on purpose: a body the real service could not parse fails here.
 */
record MultipartPart(String name, String filename, String contentType, byte[] bytes) {
    private static final Pattern NAME = Pattern.compile("(?:^|;)\\s*name=\"([^\"]*)\"");
    private static final Pattern FILENAME = Pattern.compile(";\\s*filename=\"([^\"]*)\"");
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Parses the body of a request whose Content-Type is {@code multipart/form-data; boundary=...}. */
    static List<MultipartPart> parse(String contentType, byte[] body) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data;")) {
            throw new IllegalArgumentException("not multipart/form-data: " + contentType);
        }
        int at = contentType.indexOf("boundary=");
        if (at < 0) {
            throw new IllegalArgumentException("no boundary in " + contentType);
        }
        String boundary = contentType.substring(at + "boundary=".length()).split(";")[0].strip().replace("\"", "");
        byte[] first = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] next = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        if (indexOf(body, first, 0) != 0) {
            throw new IllegalArgumentException("body does not start with the boundary");
        }
        List<MultipartPart> parts = new ArrayList<>();
        int cursor = first.length;
        while (true) {
            if (startsWith(body, cursor, new byte[]{'-', '-'})) {
                if (!startsWith(body, cursor + 2, CRLF) || cursor + 4 != body.length) {
                    throw new IllegalArgumentException("closing boundary must end the body with CRLF");
                }
                return parts;
            }
            if (!startsWith(body, cursor, CRLF)) {
                throw new IllegalArgumentException("boundary not followed by CRLF at " + cursor);
            }
            cursor += 2;
            int end = indexOf(body, next, cursor);
            int headerEnd = indexOf(body, HEADER_END, cursor);
            if (end < 0 || headerEnd < 0 || headerEnd > end) {
                throw new IllegalArgumentException("unterminated part at " + cursor);
            }
            String headers = new String(body, cursor, headerEnd - cursor, StandardCharsets.UTF_8);
            parts.add(part(headers, Arrays.copyOfRange(body, headerEnd + HEADER_END.length, end)));
            cursor = end + next.length;
        }
    }

    private static MultipartPart part(String headers, byte[] content) {
        String disposition = null;
        String type = null;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            String header = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (header.equals("content-disposition")) {
                disposition = value;
            } else if (header.equals("content-type")) {
                type = value;
            }
        }
        if (disposition == null || !disposition.startsWith("form-data")) {
            throw new IllegalArgumentException("part without Content-Disposition: form-data: " + headers);
        }
        Matcher name = NAME.matcher(disposition);
        Matcher filename = FILENAME.matcher(disposition);
        return new MultipartPart(name.find() ? name.group(1) : null, filename.find() ? filename.group(1) : null, type, content);
    }

    private static boolean startsWith(byte[] body, int from, byte[] prefix) {
        return from + prefix.length <= body.length && Arrays.equals(body, from, from + prefix.length, prefix, 0, prefix.length);
    }

    private static int indexOf(byte[] body, byte[] needle, int from) {
        for (int i = from; i + needle.length <= body.length; i++) {
            if (startsWith(body, i, needle)) {
                return i;
            }
        }
        return -1;
    }
}
