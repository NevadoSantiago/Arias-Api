package com.arias.printing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config of the local print agent, mapped from {@code arias.print-agent.*}.
 *
 * @param keySha256 hex SHA-256 of the agent key (env {@code PRINT_AGENT_KEY_SHA256}). Empty means
 *                  the agent API is closed: every request is 401. Revoking the key = rotating it.
 * @param jobTtl    how long after being enqueued a job may still be printed (default 30 minutes).
 *                  Older jobs expire and are never printed; the admin can reprint them.
 */
@ConfigurationProperties(prefix = "arias.print-agent")
public record PrintAgentProperties(String keySha256, Duration jobTtl) {

    public static final Duration DEFAULT_JOB_TTL = Duration.ofMinutes(30);

    public PrintAgentProperties {
        if (jobTtl == null || jobTtl.isNegative() || jobTtl.isZero()) {
            jobTtl = DEFAULT_JOB_TTL;
        }
    }
}
