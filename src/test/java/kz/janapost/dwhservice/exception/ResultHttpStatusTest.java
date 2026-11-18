package kz.janapost.dwhservice.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ResultHttpStatusTest {

    @ParameterizedTest(name = "status {0} → HTTP {1}")
    @DisplayName("каждый статус, который отдаёт сервис, получает свой HTTP-код")
    @CsvSource({
            "0, 200",
            "-1, 400",
            "-2, 400",
            "-101, 400",
            "-401, 401",
            "-403, 403",
            "-405, 405",
            "-413, 413",
            "-415, 415",
            "-429, 429",
            "-3, 503",
            "-90, 500",
            "-7, 500",
            "-600, 500",
            "5, 500"
    })
    void httpStatus(int resultStatus, int expectedHttp) {
        assertThat(ResultHttpStatus.httpStatus(resultStatus)).isEqualTo(expectedHttp);
    }
}
