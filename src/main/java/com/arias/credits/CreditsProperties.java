package com.arias.credits;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credit settings.
 *
 * @param welcomeGrantEnabled switch of the welcome lunch given to new accounts (on by default)
 */
@ConfigurationProperties(prefix = "arias.credits")
public record CreditsProperties(boolean welcomeGrantEnabled) {
}
