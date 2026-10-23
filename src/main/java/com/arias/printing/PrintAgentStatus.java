package com.arias.printing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Singleton row (id = 1) with the last time the local print agent called the API. */
@Entity
@Table(name = "print_agent_status")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PrintAgentStatus {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}
