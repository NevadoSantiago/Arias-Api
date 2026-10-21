package com.arias.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The repository is public, so no secret may have a default in the base
 * application.yml. Outside dev/test, a missing env var must fail startup.
 *
 * <p>Only the main application.yml is loaded (explicit config location), so
 * the values under src/test/resources/config cannot mask a missing secret.
 */
class RequiredSecretsConfigTest {

    private static final String[] REQUIRED = {"arias.jwt.secret", "arias.bootstrap.admin.password"};

    private ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                "spring.config.location=classpath:/application.yml",
                "spring.profiles.active=" + profile);
    }

    @Test
    void prodProfileWithoutEnvVarsFailsToResolveRequiredSecrets() {
        runner("prod").run(ctx -> {
            for (String key : REQUIRED) {
                assertThatThrownBy(() -> ctx.getEnvironment().getProperty(key))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Could not resolve placeholder");
            }
        });
    }

    @Test
    void prodProfileWithEnvVarsResolvesThem() {
        runner("prod")
            .withPropertyValues(
                "JWT_SECRET=0123456789abcdef0123456789abcdef",
                "BOOTSTRAP_ADMIN_PASSWORD=some-real-password")
            .run(ctx -> {
                assertThat(ctx.getEnvironment().getProperty("arias.jwt.secret"))
                    .isEqualTo("0123456789abcdef0123456789abcdef");
                assertThat(ctx.getEnvironment().getProperty("arias.bootstrap.admin.password"))
                    .isEqualTo("some-real-password");
            });
    }

    @Test
    void devProfileProvidesLocalOnlyValues() {
        runner("dev").run(ctx -> {
            assertThat(ctx.getEnvironment().getProperty("arias.jwt.secret")).hasSizeGreaterThanOrEqualTo(32);
            assertThat(ctx.getEnvironment().getProperty("arias.bootstrap.admin.password")).isNotBlank();
        });
    }
}
