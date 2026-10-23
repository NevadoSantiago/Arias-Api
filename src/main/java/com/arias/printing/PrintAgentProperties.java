package com.arias.printing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config of the local print agent, mapped from {@code arias.print-agent.*}.
 *
 * @param keySha256 hex SHA-256 of the agent key (env {@code PRINT_AGENT_KEY_SHA256}). Empty means
 *                  the agent API is closed: every request is 401. Revoking the key = rotating it.
 */
@ConfigurationProperties(prefix = "arias.print-agent")
public record PrintAgentProperties(String keySha256) {
}
