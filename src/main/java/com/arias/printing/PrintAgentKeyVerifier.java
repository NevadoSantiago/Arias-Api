package com.arias.printing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Checks the {@code X-Print-Agent-Key} header: SHA-256 of the received key against the configured
 * hex hash, in constant time ({@link MessageDigest#isEqual}), like the Mercado Pago signature check.
 * An empty or malformed configured hash rejects everything.
 */
@Component
@RequiredArgsConstructor
public class PrintAgentKeyVerifier {

    private static final int SHA_256_BYTES = 32;

    private final PrintAgentProperties props;

    public boolean verify(String key) {
        String configured = props.keySha256() == null ? "" : props.keySha256().strip();
        if (configured.isEmpty() || key == null || key.isBlank()) {
            return false;
        }
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(configured);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (expected.length != SHA_256_BYTES) {
            return false;
        }
        return MessageDigest.isEqual(sha256(key.getBytes(StandardCharsets.UTF_8)), expected);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
