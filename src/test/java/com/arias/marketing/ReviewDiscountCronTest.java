package com.arias.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDiscountCronTest {

    @Test
    @DisplayName("the campaign runs every day at 12:06 Buenos Aires time")
    void runsAt1206BuenosAires() throws NoSuchMethodException {
        Scheduled scheduled = ReviewDiscountScheduler.class.getMethod("runDaily").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 6 12 * * *");
        assertThat(scheduled.zone()).isEqualTo("America/Argentina/Buenos_Aires");
    }
}
