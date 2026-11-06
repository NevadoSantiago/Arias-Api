package com.arias.loyalty;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Loyalty line settings.
 *
 * @param enabled master switch: off (the default) means nothing accrues and the endpoint reports the feature as disabled
 */
@ConfigurationProperties(prefix = "arias.loyalty")
public record LoyaltyProperties(boolean enabled) {
}
