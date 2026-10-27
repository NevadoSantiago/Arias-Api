package com.arias.marketing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Marketing campaign settings.
 *
 * @param reviewCampaignEnabled switch of the daily discount + thank-you email campaign (off by default)
 * @param from                  optional sender address for marketing mail (blank = the global one)
 * @param fromName              sender display name for marketing mail
 */
@ConfigurationProperties(prefix = "arias.marketing")
public record MarketingProperties(boolean reviewCampaignEnabled, String from, String fromName) {
}
