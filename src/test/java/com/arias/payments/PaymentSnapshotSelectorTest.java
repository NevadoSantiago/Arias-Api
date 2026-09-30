package com.arias.payments;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSnapshotSelectorTest {

    private static PaymentSnapshot snap(String id, PaymentStatus status) {
        return new PaymentSnapshot(id, status, "d", 1_000L, "ARS", "ref", 0L);
    }

    @Test
    void nullOrEmptyYieldsNull() {
        assertThat(PaymentSnapshotSelector.best(null)).isNull();
        assertThat(PaymentSnapshotSelector.best(List.of())).isNull();
    }

    @Test
    void prefersTheFirstApprovedOverNewerOnes() {
        PaymentSnapshot newestRejected = snap("3", PaymentStatus.REJECTED);
        PaymentSnapshot approved = snap("2", PaymentStatus.APPROVED);
        PaymentSnapshot olderApproved = snap("1", PaymentStatus.APPROVED);

        assertThat(PaymentSnapshotSelector.best(List.of(newestRejected, approved, olderApproved)))
            .isSameAs(approved);
    }

    @Test
    void withoutApprovedTakesTheNewest() {
        PaymentSnapshot newest = snap("2", PaymentStatus.REJECTED);

        assertThat(PaymentSnapshotSelector.best(List.of(newest, snap("1", PaymentStatus.REJECTED))))
            .isSameAs(newest);
    }
}
