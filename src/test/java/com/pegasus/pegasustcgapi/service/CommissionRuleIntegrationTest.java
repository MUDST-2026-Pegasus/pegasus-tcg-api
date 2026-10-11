package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommissionRuleIntegrationTest extends PostgresIntegrationTest {

    @Autowired CommissionRuleService rules;

    @Test
    void activePriorityAndNewestRuleWinWithoutChangingOldQuotes() {
        var seller = data.seller();
        var admin = data.admin();
        var card = data.card(data.game("Pokemon"), "Commission " + data.tag(), "AR");
        OffsetDateTime now = OffsetDateTime.now(clock);
        long older = create(seller.profileId(), admin.userId(), "5", (short) 1,
                now.minusDays(2), null, true);
        create(seller.profileId(), admin.userId(), "99", (short) 99,
                now.minusDays(2), null, false);
        create(seller.profileId(), admin.userId(), "98", (short) 99,
                now.minusDays(3), now.minusDays(1), true);
        assertThat(ledgerPort.quoteCommission(seller.profileId(), new BigDecimal("100.00"),
                List.of(card.variantId())).amount()).isEqualByComparingTo("5.00");

        create(seller.profileId(), admin.userId(), "7", (short) 2,
                now.minusDays(1), null, true);
        LedgerPort.CommissionQuote frozen = ledgerPort.quoteCommission(seller.profileId(),
                new BigDecimal("100.00"), List.of(card.variantId()));
        assertThat(frozen.amount()).isEqualByComparingTo("7.00");
        create(seller.profileId(), admin.userId(), "9", (short) 2,
                now.minusDays(1), null, true);
        assertThat(ledgerPort.quoteCommission(seller.profileId(), new BigDecimal("100.00"),
                List.of(card.variantId())).amount()).isEqualByComparingTo("9.00");
        rules.deactivate(older);
        assertThat(frozen.amount()).isEqualByComparingTo("7.00");
    }

    private long create(long sellerId, long adminId, String rate, short priority,
            OffsetDateTime from, OffsetDateTime to, boolean active) {
        return rules.create(new CommissionRuleRequest("SELLER", null, null, sellerId,
                new BigDecimal(rate), BigDecimal.ZERO, null, null, priority, from, to, active), adminId).id();
    }
}
