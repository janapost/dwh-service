package kz.janapost.dwhservice.model.s2s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Заказ в событии оплаты.
 *
 * @param id       ID заказа
 * @param usdValue стоимость доставки в USD ×10000, как {@code RoadPrice.usdValue}; {@code null} — у заказа нет цены
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record S2SOrder(String id, Long usdValue) {
}
