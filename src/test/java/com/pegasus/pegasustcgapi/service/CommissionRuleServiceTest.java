package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.dto.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommissionRuleServiceTest {
    @Mock CommissionRuleRepository rules;

    @Test void feeRangeCannotBeInvertedOrSaved() {
        var request = new CommissionRuleRequest("GLOBAL", null, null, null,
                new BigDecimal("5.0"), BigDecimal.ZERO, new BigDecimal("10.00"),
                new BigDecimal("5.00"), (short) 0, OffsetDateTime.now(), null, true);

        assertThatThrownBy(() -> new CommissionRuleService(rules).create(request, 1L))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(rules);
    }
}
