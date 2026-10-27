package com.arias.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDiscountCronTest {

    @Test
    @DisplayName("the campaign runs every day at 11:30 Buenos Aires time")
    void runsAt1130BuenosAires() throws NoSuchMethodException {
        Scheduled scheduled = ReviewDiscountScheduler.class.getMethod("runDaily").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 30 11 * * *");
        assertThat(scheduled.zone()).isEqualTo("America/Argentina/Buenos_Aires");
    }
}
