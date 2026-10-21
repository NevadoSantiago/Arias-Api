package com.arias.email;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Guard: the test context must never hold a Resend credential, even if application-local.yml has one. */
@SpringBootTest
class NoRealEmailInTestsTest {

    @Autowired private EmailProperties props;

    @Test
    void resendIsNotConfiguredInTests() {
        assertThat(props.isConfigured()).isFalse();
    }
}
