package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий для работы с таблицей couriers.
 * Позволяет искать курьеров без ручного SQL.
 */
@Repository
public interface CourierRepository extends JpaRepository<Courier, UUID> {

    /**
     * Найти курьера по пользователю (1:1).
     */
    Optional<Courier> findByUser(User user);

}
