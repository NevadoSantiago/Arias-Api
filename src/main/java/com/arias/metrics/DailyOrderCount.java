package com.arias.metrics;

import java.time.LocalDate;

public record DailyOrderCount(LocalDate fecha, long count) {}
