package com.arias.credits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreditsPropertiesTest {

    @Test
    @DisplayName("the welcome lunch stays on when the property is missing")
    void welcomeGrantDefaultsToEnabled() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));

        CreditsProperties props = binder.bindOrCreate("arias.credits", CreditsProperties.class);

        assertThat(props.welcomeGrantEnabled()).isTrue();
    }

    @Test
    @DisplayName("the welcome lunch can be switched off")
    void welcomeGrantCanBeDisabled() {
        Binder binder = new Binder(new MapConfigurationPropertySource(
            Map.of("arias.credits.welcome-grant-enabled", "false")));

        CreditsProperties props = binder.bindOrCreate("arias.credits", CreditsProperties.class);

        assertThat(props.welcomeGrantEnabled()).isFalse();
    }
}
