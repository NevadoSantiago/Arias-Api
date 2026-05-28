package com.arias.metrics;

public record ParticipationMetrics(
    long activeEmployees,
    long orderedToday,
    double todayRate,
    long weekOrders,
    double weekRate
) {}
