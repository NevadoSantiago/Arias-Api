package com.arias.printing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SHA-256 key check of the print agent. No Spring. */
class PrintAgentKeyVerifierTest {

    // sha256("test-print-agent-key")
    private static final String HASH = "aedf6eff49b74979c69215396f6f88bda24f386977dad2b5ee3893768cc28b63";

    private static PrintAgentKeyVerifier verifier(String hash) {
        return new PrintAgentKeyVerifier(new PrintAgentProperties(hash));
    }

    @Test
    @DisplayName("accepts the key whose SHA-256 matches the configured hash")
    void acceptsRightKey() {
        assertThat(verifier(HASH).verify("test-print-agent-key")).isTrue();
    }

    @Test
    @DisplayName("the configured hash is compared case-insensitively and trimmed")
    void hashIsCaseInsensitiveAndTrimmed() {
        assertThat(verifier("  " + HASH.toUpperCase() + "\n").verify("test-print-agent-key")).isTrue();
    }

    @Test
    @DisplayName("rejects a wrong key, a near-miss and a hash passed as the key")
    void rejectsWrongKey() {
        PrintAgentKeyVerifier v = verifier(HASH);

        assertThat(v.verify("wrong")).isFalse();
        assertThat(v.verify("test-print-agent-key ")).isFalse();
        assertThat(v.verify(HASH)).isFalse();
    }

    @Test
    @DisplayName("rejects a missing or blank header")
    void rejectsMissingHeader() {
        PrintAgentKeyVerifier v = verifier(HASH);

        assertThat(v.verify(null)).isFalse();
        assertThat(v.verify("")).isFalse();
        assertThat(v.verify("   ")).isFalse();
    }

    @Test
    @DisplayName("an empty, blank or malformed configured hash rejects every request")
    void unconfiguredRejectsEverything() {
        assertThat(verifier(null).verify("test-print-agent-key")).isFalse();
        assertThat(verifier("").verify("test-print-agent-key")).isFalse();
        assertThat(verifier("   ").verify("test-print-agent-key")).isFalse();
        assertThat(verifier("not-hex").verify("test-print-agent-key")).isFalse();
        assertThat(verifier("abcd").verify("test-print-agent-key")).isFalse();
        assertThat(verifier("").verify("")).isFalse();
    }
}
