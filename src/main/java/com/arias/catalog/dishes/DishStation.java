package com.arias.catalog.dishes;

/**
 * Kitchen station that prepares a dish. Independent of the menu section: two dishes of the
 * same section can go to different stations. Drives which comanda tickets are printed.
 */
public enum DishStation {
    COCINA,
    PARRILLA
}
