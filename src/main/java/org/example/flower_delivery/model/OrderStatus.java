package org.example.flower_delivery.model;

/**
 * Статусы заказа — жизненный цикл доставки.
 */
public enum OrderStatus {
    NEW("Новый"),
    ACCEPTED("Принят курьером"),
    IN_SHOP("Курьер в магазине"),
    PICKED_UP("Забран"),
    ON_WAY("В пути"),
    DELIVERED("Доставлен"),
    RETURNED("Возврат"),
    CANCELLED("Отменён");

    private final String displayName;

    OrderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
