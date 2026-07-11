package com.vgc.tms.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 content hash over a synced field-set with FIXED key order (B4).
 * Used by the reconciler (S2) and drift detection: stored TMS hash vs freshly-fetched Polarion
 * hash catches sub-field mutations the row-count delta misses.
 */
public final class ContentHash {
    private ContentHash() {}

    /** Deterministic hash: keys sorted, "k=v;" joined, SHA-256 hex. */
    public static String of(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new TreeMap<>(fields).entrySet()) {
            sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue())).append(';');
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
