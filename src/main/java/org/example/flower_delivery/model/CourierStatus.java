package org.example.flower_delivery.model;

/**
 * Статус курьера.
 *
 * PENDING — только что зарегистрировался, ждёт активации админом.
 * ACTIVE  — одобрен, может брать заказы.
 * BLOCKED — забанен (например, накосячил или уволился).
 */
public enum CourierStatus {
    PENDING,
    ACTIVE,
    BLOCKED
}
