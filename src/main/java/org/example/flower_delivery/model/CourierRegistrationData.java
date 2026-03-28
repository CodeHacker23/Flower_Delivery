package org.example.flower_delivery.model;

import lombok.Data;

/**
 * Временные данные регистрации курьера.
 *
 * Мы не пишем в БД после каждого шага,
 * а держим всё это добро в памяти, пока не получим:
 * - ФИО
 * - телефон
 * - фото с паспортом
 */
@Data
public class CourierRegistrationData {

    /**
     * Текущее состояние диалога.
     */
    private CourierRegistrationState state = CourierRegistrationState.NONE;

    /**
     * ФИО, введённое курьером.
     */
    private String fullName;

    /**
     * Телефон курьера.
     */
    private String phone;

    /**
     * file_id селфи с паспортом из Telegram.
     */
    private String passportPhotoFileId;
}

