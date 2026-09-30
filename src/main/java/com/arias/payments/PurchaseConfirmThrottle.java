package com.arias.payments;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Limita a una consulta a Mercado Pago cada {@link #MIN_INTERVAL} por compra,
 * en memoria. Protege la API de Mercado Pago del polling del cliente; si hay
 * varias instancias cada una aplica su propio límite, y la idempotencia de
 * {@code applySnapshot} cubre el resto.
 */
@Component
class PurchaseConfirmThrottle {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(5);
    static final Duration EVICT_AFTER = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<UUID, Instant> lastLookup = new ConcurrentHashMap<>();

    PurchaseConfirmThrottle(Clock clock) {
        this.clock = clock;
    }

    /** {@code true} si corresponde consultar ahora (y lo registra); {@code false} si hay que esperar. */
    boolean tryAcquire(UUID purchaseId) {
        Instant now = clock.instant();
        evictStale(now);
        AtomicBoolean acquired = new AtomicBoolean(false);
        lastLookup.compute(purchaseId, (id, last) -> {
            if (last == null || !now.isBefore(last.plus(MIN_INTERVAL))) {
                acquired.set(true);
                return now;
            }
            return last;
        });
        return acquired.get();
    }

    int size() {
        return lastLookup.size();
    }

    private void evictStale(Instant now) {
        Instant cutoff = now.minus(EVICT_AFTER);
        lastLookup.values().removeIf(last -> last.isBefore(cutoff));
    }
}
