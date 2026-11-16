package com.example.ticketing.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.example.ticketing.exception.ErrorCode;

import jakarta.servlet.http.HttpServletResponse;

public final class JsonErrorWriter {

    private JsonErrorWriter() {
    }

    public static void write(HttpServletResponse response, ErrorCode code, String message, String path)
            throws IOException {
        response.setStatus(code.status());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"timestamp\":\"" + Instant.now() + "\","
                + "\"status\":" + code.status() + ","
                + "\"code\":\"" + code.name() + "\","
                + "\"message\":\"" + escape(message) + "\","
                + "\"path\":\"" + escape(path) + "\","
                + "\"errors\":[]}";
        response.getWriter().write(body);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
