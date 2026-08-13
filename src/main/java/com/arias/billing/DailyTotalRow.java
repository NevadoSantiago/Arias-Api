package com.arias.billing;

import java.time.LocalDate;

/**
 * Fila cruda de la agregación diaria — una por día CON pedidos servidos.
 * Los días sin actividad no aparecen.
 */
public record DailyTotalRow(LocalDate fecha, Long pedidos, Long total) {}
