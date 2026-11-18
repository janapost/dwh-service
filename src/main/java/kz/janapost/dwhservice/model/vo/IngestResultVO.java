package kz.janapost.dwhservice.model.vo;

/**
 * Результат приёма пачки.
 *
 * @param accepted сколько записей подтвердила Kafka; {@code 0}, если приём выключен флагом
 */
public record IngestResultVO(int accepted) {
}
