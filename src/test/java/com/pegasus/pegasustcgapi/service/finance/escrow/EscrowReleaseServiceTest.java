package com.pegasus.pegasustcgapi.service.finance.escrow;

import com.pegasus.pegasustcgapi.service.finance.wallet.WalletLedgerService;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.jooq.tables.records.EscrowHoldRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.finance.escrow.EscrowRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.payment.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.aftersales.ReturnRepository;
import java.time.Clock;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EscrowReleaseServiceTest {
    @Mock EscrowRepository escrow;
    @Mock OrderRepository orders;
    @Mock PaymentRepository payments;
    @Mock WalletLedgerService ledger;
    @Mock DSLContext dsl;
    @Mock ReturnRepository returns;
    EscrowReleaseService service;

    @BeforeEach void setUp() {
        service = new EscrowReleaseService(escrow, orders, payments, ledger, dsl,
                Clock.systemUTC(), returns);
    }

    @Test void repeatedReleaseCannotCreditSellerOrChargeCommissionTwice() {
        SellerOrderRecord order = new SellerOrderRecord();
        order.setSalesOrderId(2L);
        EscrowHoldRecord hold = new EscrowHoldRecord();
        hold.setStatus("RELEASED");
        when(orders.findSellerOrderById(3L)).thenReturn(Optional.of(order));
        when(escrow.lock(3L)).thenReturn(Optional.of(hold));

        service.release(3L);

        verify(payments).lockOrder(2L);
        verifyNoInteractions(ledger, dsl, returns);
    }
}
