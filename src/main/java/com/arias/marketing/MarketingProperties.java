package com.arias.marketing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Marketing campaign settings.
 *
 * @param googleReviewUrl link of the restaurant's Google review page. Blank disables the
 *                        daily review/discount campaign entirely.
 */
@ConfigurationProperties(prefix = "arias.marketing")
public record MarketingProperties(String googleReviewUrl) {

    public boolean isReviewCampaignEnabled() {
        return googleReviewUrl != null && !googleReviewUrl.isBlank();
    }
}
