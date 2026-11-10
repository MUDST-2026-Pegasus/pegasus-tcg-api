package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.model.promotion.Promotion;
import com.pegasus.pegasustcgapi.service.promotion.PromotionCalculator;
import com.pegasus.pegasustcgapi.service.promotion.PromotionCalculator.Basket;
import com.pegasus.pegasustcgapi.service.promotion.PromotionCalculator.Line;
import com.pegasus.pegasustcgapi.support.PromotionFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutPricingGoldenCaptureTest {
    @Test void capture() {
        String[][] cases = {
            {"100.00", "0", "0", "0", "0", "0"},
            {"100.00", "0", "10", "0", "0", "0"},
            {"100.00", "0", "0", "10", "0", "0"},
            {"100.00", "0", "0", "0", "10", "0"},
            {"100.00", "0", "0", "0", "0", "10"},
            {"10.00", "0", "5", "0", "30", "0"},
            {"10.00", "0", "5", "0", "0", "30"},
            {"0.05", "0", "0", "0", "0", "10"},
            {"0.15", "0", "0", "0", "0", "10"},
            {"100.00", "200.00", "20", "30", "0", "10"},
            {"100.00", "200.00", "20", "30", "50", "10"},
            {"100.01", "200.02", "0", "0", "0", "7"},
            {"1.00", "1.00", "0", "0", "0", "1"},
            {"830.00", "2400.00", "45", "0", "50", "10"},
            {"99.99", "0.01", "10.01", "3.03", "0.01", "33"},
            {"5.00", "8.00", "2", "4", "999", "100"}
        };
        // Captured from the unmodified checkout promotion calculation before extraction.
        String[] expected = {
            "[100.00,0,0,0,100.00] total=100.00", "[100.00,10,0,0,110.00] total=110.00",
            "[100.00,0,0,0,100.00] total=100.00", "[100.00,0,10.00,0,90.00] total=90.00",
            "[100.00,0,0,10.00,90.00] total=90.00", "[10.00,5,10.00,0,5.00] total=5.00",
            "[10.00,5,0,3.00,12.00] total=12.00", "[0.05,0,0,0.01,0.04] total=0.04",
            "[0.15,0,0,0.02,0.13] total=0.13", "[100.00,20,0,10.00,110.00, 200.00,30,0,20.00,210.00] total=320.00",
            "[100.00,20,50.00,5.00,65.00, 200.00,30,0,20.00,210.00] total=275.00",
            "[100.01,0,0,7.00,93.01, 200.02,0,0,14.00,186.02] total=279.03",
            "[1.00,0,0,0.01,0.99, 1.00,0,0,0.01,0.99] total=1.98",
            "[830.00,45,50.00,78.00,747.00, 2400.00,0,0,240.00,2160.00] total=2907.00",
            "[99.99,10.01,0.01,33.00,76.99, 0.01,3.03,0,0.00,3.04] total=80.03",
            "[5.00,2,5.00,0.00,2.00, 8.00,4,0,8.00,4.00] total=6.00"
        };
        for (int i = 0; i < cases.length; i++) {
            String[] c = cases[i];
            var lines = new java.util.ArrayList<Line>();
            lines.add(new Line(1, 11, 1, 1, new BigDecimal(c[0])));
            if (new BigDecimal(c[1]).signum() != 0) lines.add(new Line(2, 22, 1, 1, new BigDecimal(c[1])));
            var shipping = lines.size() == 1 ? Map.of(11L, new BigDecimal(c[2]))
                    : Map.of(11L, new BigDecimal(c[2]), 22L, new BigDecimal(c[3]));
            var codes = new java.util.ArrayList<Promotion>();
            if (new BigDecimal(c[4]).signum() != 0) codes.add(PromotionFixture.seller("SHOP", 11).fixed(c[4]).build());
            if (new BigDecimal(c[5]).signum() != 0) codes.add(PromotionFixture.platform("PLATFORM").percent(c[5], null).build());
            var result = PromotionCalculator.calculate(new Basket(lines, shipping), codes);
            String actual = result.sellers().stream()
                    .map(s -> s.itemsSubtotal() + "," + s.shippingFee() + "," + s.sellerDiscount() + "," + s.platformDiscount() + "," + s.grandTotal())
                    .toList() + " total=" + result.grandTotal();
            assertThat(actual).as("case %s", i).isEqualTo(expected[i]);
        }
    }
}
