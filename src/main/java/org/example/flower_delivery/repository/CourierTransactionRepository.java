package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.CourierTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CourierTransactionRepository extends JpaRepository<CourierTransaction, UUID> {

    List<CourierTransaction> findTop20ByCourierOrderByCreatedAtDesc(Courier courier);
}

