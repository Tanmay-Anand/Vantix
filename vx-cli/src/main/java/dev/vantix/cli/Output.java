/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vantix.core.diagnostic.Diagnostic;
import dev.vantix.core.diagnostic.DiagnosticRenderer;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the CLI prints goes through here, keeping three channels apart: human output (summary
 * lines on stdout, diagnostics on stderr), {@code --verbose} detail, and {@code --json} machine
 * output (stdout only, nothing else printed).
 */
final class Output {

    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final PrintWriter out;
    private final PrintWriter err;
    private final boolean json;
    private final boolean verbose;
    private final boolean color;
    private final boolean unicode;

    Output(PrintWriter out, PrintWriter err, CommonOptions options) {
        this.out = out;
        this.err = err;
        this.json = options.json;
        this.verbose = options.verbose;
        this.color = !options.noColor && System.getenv("VANTIX_NO_COLOR") == null && System.console() != null;
        this.unicode = supportsUnicode();
    }

    boolean json() {
        return json;
    }

    /** ✓/✗ only where the console can show them; Windows code pages get ASCII. */
    private static boolean supportsUnicode() {
        String encoding = System.getProperty("stdout.encoding");
        Charset charset =
                encoding != null ? Charset.forName(encoding, StandardCharsets.US_ASCII) : Charset.defaultCharset();
        return charset.equals(StandardCharsets.UTF_8);
    }

    void success(String message) {
        if (!json) {
            out.println(paint("\u001B[32m", unicode ? "✓" : "OK") + " " + message);
            out.flush();
        }
    }

    void failure(String message) {
        if (!json) {
            err.println(paint("\u001B[31m", unicode ? "✗" : "ERROR") + " " + message);
            err.flush();
        }
    }

    void info(String message) {
        if (!json) {
            out.println(message);
            out.flush();
        }
    }

    void detail(String message) {
        if (verbose && !json) {
            out.println(paint("\u001B[2m", message));
            out.flush();
        }
    }

    void diagnostics(Workflow.Validation v, Path displayPath) {
        List<Diagnostic> diagnostics = v.result().diagnostics();
        if (json || diagnostics.isEmpty()) {
            return;
        }
        err.println(new DiagnosticRenderer(displayPath.toString().replace('\\', '/'), v.source(), color)
                .render(diagnostics));
        err.flush();
    }

    void json(Map<String, Object> document) {
        if (json) {
            try {
                out.println(JSON.writeValueAsString(document));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            out.flush();
        }
    }

    static List<Map<String, Object>> diagnosticsJson(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("severity", d.severity().name().toLowerCase(Locale.ROOT));
                    m.put("message", d.message());
                    m.put("line", d.position().line());
                    m.put("column", d.position().column());
                    m.put("length", d.position().length());
                    if (d.suggestion() != null) {
                        m.put("suggestion", d.suggestion());
                    }
                    if (!d.related().isEmpty()) {
                        m.put(
                                "related",
                                d.related().stream()
                                        .map(l -> Map.of(
                                                "line", l.position().line(),
                                                "column", l.position().column(),
                                                "length", l.position().length(),
                                                "note", l.note()))
                                        .toList());
                    }
                    return m;
                })
                .toList();
    }

    static String plural(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private String paint(String code, String text) {
        return color ? code + text + "\u001B[0m" : text;
    }
}
