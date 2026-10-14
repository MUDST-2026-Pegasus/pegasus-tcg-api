package com.pegasus.pegasustcgapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.ShippingQuoteOptionResponse;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ShippingOptionTest {
    @ParameterizedTest
    @CsvSource({
            "1, 100.00, NULL, 40.00, false, NULL",
            "3, 250.00, 300.00, 50.00, false, 50.00",
            "3, 300.00, 300.00, 0.00, true, NULL",
            "4, 350.00, 300.00, 0.00, true, NULL",
            "2, 0.01, 0.02, 45.00, false, 0.01",
            "0, 0.00, NULL, 40.00, false, NULL"
    })
    void pricesPhysicalCardsAndFreeThresholdExactly(int count, String subtotal, String threshold,
            String fee, boolean free, String remaining) {
        var option = new ShippingOption(1, 2, "Kerry", "KERRY", new BigDecimal("40.00"),
                new BigDecimal("5.00"), decimal(threshold), (short) 1, (short) 3, true, (short) 0);
        BigDecimal itemsSubtotal = decimal(subtotal);
        assertThat(option.feeFor(count, itemsSubtotal)).isEqualByComparingTo(fee);
        var quote = ShippingQuoteOptionResponse.from(option, count, itemsSubtotal);
        assertThat(quote.fee()).isEqualByComparingTo(option.feeFor(count, itemsSubtotal));
        assertThat(quote.free()).isEqualTo(free);
        if (remaining.equals("NULL")) assertThat(quote.amountToFree()).isNull();
        else assertThat(quote.amountToFree()).isEqualByComparingTo(remaining);
    }

    private static BigDecimal decimal(String value) {
        return value.equals("NULL") ? null : new BigDecimal(value);
    }
}
