package com.arias.marketing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Marketing campaign settings.
 *
 * @param googleReviewUrl link of the restaurant's Google review page. Blank disables the
 *                        daily review/discount campaign entirely.
 * @param from            optional sender address for marketing mail (blank = the global one)
 * @param fromName        sender display name for marketing mail
 */
@ConfigurationProperties(prefix = "arias.marketing")
public record MarketingProperties(String googleReviewUrl, String from, String fromName) {

    public boolean isReviewCampaignEnabled() {
        return googleReviewUrl != null && !googleReviewUrl.isBlank();
    }
}
