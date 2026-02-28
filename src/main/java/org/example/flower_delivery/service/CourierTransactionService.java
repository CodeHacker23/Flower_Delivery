package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.CourierTransaction;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.repository.CourierTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CourierTransactionService {

    private final CourierTransactionRepository transactionRepository;

    @Transactional
    public void addCommissionCharge(Courier courier, Order order, BigDecimal amount) {
        if (courier == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        CourierTransaction tx = CourierTransaction.builder()
                .courier(courier)
                .type("COMMISSION_CHARGE")
                .amount(amount.negate())
                .order(order)
                .description("Комиссия за заказ " + (order != null ? order.getId() : ""))
                .build();
        transactionRepository.save(tx);
        log.info("Записана комиссия курьера {} по заказу {}: {}",
                courier.getId(), order != null ? order.getId() : null, amount);
    }

    @Transactional
    public void addCommissionRefund(Courier courier, Order order, BigDecimal amount) {
        if (courier == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        CourierTransaction tx = CourierTransaction.builder()
                .courier(courier)
                .type("COMMISSION_REFUND")
                .amount(amount)
                .order(order)
                .description("Возврат комиссии за заказ " + (order != null ? order.getId() : ""))
                .build();
        transactionRepository.save(tx);
        log.info("Возврат комиссии курьеру {} по заказу {}: {}",
                courier.getId(), order != null ? order.getId() : null, amount);
    }

    @Transactional(readOnly = true)
    public List<CourierTransaction> getLastTransactions(Courier courier, int limit) {
        if (courier == null) {
            return List.of();
        }
        // Пока просто берём топ-20, limit можно не использовать
        return transactionRepository.findTop20ByCourierOrderByCreatedAtDesc(courier);
    }
}

