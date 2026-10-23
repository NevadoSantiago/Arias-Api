package com.arias.printing;

import com.arias.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** What the local print agent can do: claim jobs (which doubles as its heartbeat) and acknowledge them. */
@Service
@RequiredArgsConstructor
public class PrintAgentService {

    public static final int CLAIM_LIMIT = 10;
    /** A claim not acknowledged within this time is offered again. */
    public static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration RETRY_BACKOFF = Duration.ofSeconds(15);
    public static final int MAX_ATTEMPTS = 5;
    static final int MAX_ERROR_LENGTH = 500;

    private final PrintJobRepository jobRepo;
    private final PrintAgentStatusRepository statusRepo;
    private final Clock clock;

    /** A job as the agent receives it. */
    public record AgentJobDto(Long id, String host, Integer port, String dataBase64) {
        static AgentJobDto from(PrintJob job) {
            return new AgentJobDto(job.getId(), job.getHost(), job.getPort(),
                Base64.getEncoder().encodeToString(job.getData()));
        }
    }

    public record AckResult(Long id, PrintJobStatus status) {}

    /**
     * Atomically claims up to {@link #CLAIM_LIMIT} available jobs and records the heartbeat. The rows
     * are locked with SKIP LOCKED, so concurrent claims get disjoint sets.
     */
    @Transactional
    public List<AgentJobDto> claim() {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(CLAIM_TIMEOUT);
        statusRepo.touch(now);
        jobRepo.failExhaustedStaleClaims(now, staleBefore, MAX_ATTEMPTS,
            "El agente no confirmó la impresión");
        List<PrintJob> jobs = jobRepo.lockClaimable(now, staleBefore, PageRequest.of(0, CLAIM_LIMIT));
        for (PrintJob job : jobs) {
            if (job.getStatus() == PrintJobStatus.CLAIMED) {
                job.setAttempts(job.getAttempts() + 1); // re-offer of an unacknowledged claim
            }
            job.setStatus(PrintJobStatus.CLAIMED);
            job.setClaimedAt(now);
        }
        return jobs.stream().map(AgentJobDto::from).toList();
    }

    /**
     * PRINTED sets {@code printedAt}. FAILED counts an attempt and reschedules the job as PENDING
     * after {@link #RETRY_BACKOFF}, until {@link #MAX_ATTEMPTS}, when it stays FAILED.
     */
    @Transactional
    public AckResult ack(Long id, AckStatus status, String error) {
        PrintJob job = jobRepo.findByIdForUpdate(id)
            .orElseThrow(() -> BusinessException.notFound("print-job-not-found", "Trabajo de impresión no encontrado"));
        Instant now = clock.instant();

        if (status == AckStatus.PRINTED && job.getStatus() == PrintJobStatus.PRINTED) {
            return new AckResult(job.getId(), job.getStatus()); // idempotent retry of a lost response
        }
        if (job.getStatus() != PrintJobStatus.CLAIMED) {
            throw BusinessException.conflict("print-job-not-claimed",
                "El trabajo de impresión no está reclamado (estado " + job.getStatus() + ")");
        }

        if (status == AckStatus.PRINTED) {
            job.setStatus(PrintJobStatus.PRINTED);
            job.setPrintedAt(now);
            job.setLastError(null);
        } else {
            job.setAttempts(job.getAttempts() + 1);
            job.setLastError(truncate(error));
            if (job.getAttempts() >= MAX_ATTEMPTS) {
                job.setStatus(PrintJobStatus.FAILED);
            } else {
                job.setStatus(PrintJobStatus.PENDING);
                job.setAvailableAt(now.plus(RETRY_BACKOFF));
                job.setClaimedAt(null);
            }
        }
        return new AckResult(job.getId(), job.getStatus());
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }
}
