package com.arias.credits;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Credit settings.
 *
 * @param welcomeGrantEnabled switch of the welcome lunch given to new accounts (on when the property is missing)
 */
@ConfigurationProperties(prefix = "arias.credits")
public record CreditsProperties(@DefaultValue("true") boolean welcomeGrantEnabled) {
}
