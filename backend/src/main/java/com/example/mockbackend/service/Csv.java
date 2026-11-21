package com.example.mockbackend.service;

import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * RFC 4180 CSV for every admin export (job_runs, quality ratings, app measurements, e2e) and the metrics.csv import
 * (docs/METRICS.md §1.1, §3.1, §4.1): header line, CRLF, null as an empty cell, only cells with a comma, quote or line
 * break quoted. A text cell that starts with = + - @ tab or CR gets a leading apostrophe, so Excel and Google Sheets
 * show it as text instead of running it as a formula (OWASP "CSV Injection"; free text such as a rater's comment or a
 * phone's device name ends up in these files). Numbers, booleans and times are never changed.
 */
public final class Csv {
    static final String CRLF = "\r\n";
    private static final char BOM = '﻿';
    /** First characters a spreadsheet reads as the start of a formula (or that hide one behind a control character). */
    private static final String FORMULA_START = "=+-@\t\r";

    private Csv() {
    }

    /** {@code format} query parameter of the admin exports: json (default, also blank) or csv, any case. @throws ApiException 400 */
    public static boolean isCsvFormat(String format) {
        String value = format == null ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if (value.equals("csv")) {
            return true;
        }
        if (value.equals("json") || value.isEmpty()) {
            return false;
        }
        throw ApiException.badRequest(ErrorCode.INVALID_REQUEST, "format must be json or csv, got '" + format + "'");
    }

    /** Header line and one line per row, CRLF line ends, UTF-8. Every row must have the header's number of cells. */
    public static byte[] write(List<String> header, List<? extends List<?>> rows) {
        StringBuilder csv = new StringBuilder(String.join(",", header)).append(CRLF);
        for (List<?> row : rows) {
            if (row.size() != header.size()) {
                throw new IllegalStateException("CSV row has " + row.size() + " cells, header " + header.size());
            }
            csv.append(row.stream().map(Csv::cell).collect(Collectors.joining(","))).append(CRLF);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (value instanceof CharSequence && startsLikeFormula(text)) {
            text = "'" + text;
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    static boolean startsLikeFormula(String text) {
        return !text.isEmpty() && FORMULA_START.indexOf(text.charAt(0)) >= 0;
    }

    /**
     * A text cell as it was before {@link #cell} guarded it: an apostrophe followed by a formula character is dropped,
     * so an exported file imported again (metrics.csv) reads the same value and is skipped as a duplicate.
     */
    public static String unguard(String text) {
        return text.length() >= 2 && text.charAt(0) == '\'' && startsLikeFormula(text.substring(1)) ? text.substring(1) : text;
    }

    /**
     * One record of a parsed file: the physical line it starts on (1-based) and its cells, unquoted but not trimmed.
     * {@code unterminated} means a quoted cell was still open at the end of the file.
     */
    public record Record(int line, List<String> cells, boolean unterminated) {
        /** An empty line (one empty cell). */
        public boolean isBlank() {
            return cells.size() == 1 && cells.get(0).isEmpty() && !unterminated;
        }
    }

    /**
     * Parses CSV text: a leading BOM is dropped; LF, CRLF or a lone CR ends a record outside quotes, and a line end
     * at the very end does not start another record. A quote opens a quoted cell only at the start of a cell
     * ({@code ""} inside it is one quote); a stray quote elsewhere is kept as text. Empty text has no records.
     */
    public static List<Record> parse(String text) {
        String input = !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
        List<Record> records = new ArrayList<>();
        int length = input.length();
        int i = 0;
        int line = 1;
        while (i < length) {
            int startLine = line;
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean inQuotes = false;
            boolean cellWasQuoted = false;
            boolean endOfRecord = false;
            while (i < length && !endOfRecord) {
                char c = input.charAt(i);
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < length && input.charAt(i + 1) == '"') {
                            cell.append('"');
                            i += 2;
                        } else {
                            inQuotes = false;
                            i++;
                        }
                        continue;
                    }
                    if (c == '\n' || (c == '\r' && !(i + 1 < length && input.charAt(i + 1) == '\n'))) {
                        line++;
                    }
                    cell.append(c);
                    i++;
                } else if (c == ',') {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    cellWasQuoted = false;
                    i++;
                } else if (c == '\r' || c == '\n') {
                    i += c == '\r' && i + 1 < length && input.charAt(i + 1) == '\n' ? 2 : 1;
                    line++;
                    endOfRecord = true;
                } else if (c == '"' && cell.length() == 0 && !cellWasQuoted) {
                    inQuotes = true;
                    cellWasQuoted = true;
                    i++;
                } else {
                    cell.append(c);
                    i++;
                }
            }
            cells.add(cell.toString());
            records.add(new Record(startLine, List.copyOf(cells), inQuotes));
        }
        return records;
    }
}
