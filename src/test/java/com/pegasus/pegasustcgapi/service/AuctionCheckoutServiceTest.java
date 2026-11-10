package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.AuctionCheckout;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.shipping.ShippingOption;
import com.pegasus.pegasustcgapi.model.user.Address;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort.CommissionQuote;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderTransitionRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository.ListingSnapshotDetails;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.auction.AuctionCheckoutService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.service.shipping.ShippingOptionService;
import com.pegasus.pegasustcgapi.service.user.AddressService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuctionCheckoutServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
    private static final AuthPrincipal BUYER = new AuthPrincipal(42L, "buyer@example.test", "buyer", Set.of());
    private static final BigDecimal PRICE = new BigDecimal("8500.00");
    private static final BigDecimal FEE = new BigDecimal("50.00");

    @Mock AuctionRepository auctions;
    @Mock ListingUnitRepository units;
    @Mock OrderRepository orders;
    @Mock OrderTransitionRepository orderTransitions;
    @Mock OrderReferenceQueryRepository orderReferences;
    @Mock CheckoutService checkout;
    @Mock AddressService addresses;
    @Mock ShippingOptionService shipping;
    @Mock InventoryPort inventory;
    @Mock LedgerPort ledger;
    AuctionCheckoutService service;

    @BeforeEach void setUp() {
        service = new AuctionCheckoutService(auctions, units, orders, orderTransitions, orderReferences, checkout, addresses,
                shipping, inventory, ledger, CLOCK);
    }

    private Auction auction(long winner, AuctionStatus status, Long orderId) {
        Auction a = mock(Auction.class);
        lenient().when(a.winnerId()).thenReturn(winner);
        lenient().when(a.salesOrderId()).thenReturn(orderId);
        lenient().when(a.status()).thenReturn(status);
        lenient().when(a.paymentDueAt()).thenReturn(OffsetDateTime.now(CLOCK).plusHours(1));
        return a;
    }

    @Test void winnerPlacesOrderAtLockedPriceWithShippingCommissionAndCardSnapshot() {
        Auction a = auction(42L, AuctionStatus.AWAITING_PAYMENT, null);
        when(a.winningAmount()).thenReturn(PRICE);
        when(a.sellerProfileId()).thenReturn(7L);
        when(a.listingUnitId()).thenReturn(9L);
        when(a.catalogVariantId()).thenReturn(11L);
        when(a.condition()).thenReturn(CardCondition.NM);
        when(auctions.lock(5L)).thenReturn(Optional.of(a));
        Address address = mock(Address.class);
        when(address.id()).thenReturn(3L);
        when(addresses.get(42L, 3L)).thenReturn(address);
        ShippingOption option = new ShippingOption(4L, 7L, "Delivery", "THP", FEE,
                BigDecimal.ZERO, null, (short) 1, (short) 3, true, (short) 0);
        when(shipping.resolveForCheckout(7L, 4L)).thenReturn(option);
        when(units.changeStatus(List.of(9L), ListingUnitStatus.ON_AUCTION,
                ListingUnitStatus.RESERVED)).thenReturn(1);
        when(orderReferences.findAuctionCardDetails(9L)).thenReturn(Optional.of(new ListingSnapshotDetails(
                null, 11L, "NM", "Card", "EN", "Game", "image")));
        when(ledger.quoteCommission(7L, PRICE, List.of(11L)))
                .thenReturn(new CommissionQuote(new BigDecimal("5.00"), new BigDecimal("425.00")));
        when(inventory.averageUnitCost(7L, 11L, CardCondition.NM)).thenReturn(new BigDecimal("100.00"));
        when(orders.nextOrderNumber()).thenReturn("PGS-1");
        when(checkout.createAddressSnapshot(address)).thenReturn(JSONB.valueOf("{}"));
        SalesOrderRecord sale = mock(SalesOrderRecord.class);
        SellerOrderRecord seller = mock(SellerOrderRecord.class);
        OrderItemRecord item = mock(OrderItemRecord.class);
        when(sale.getId()).thenReturn(100L);
        when(seller.getId()).thenReturn(101L);
        when(item.getId()).thenReturn(102L);
        when(orders.insertSalesOrder(eq("PGS-1"), eq(42L), eq("THB"), eq(PRICE), eq(FEE),
                eq(BigDecimal.ZERO), eq(new BigDecimal("8550.00")), eq(3L), any(), eq(null), eq("key")))
                .thenReturn(sale);
        when(orders.insertSellerOrder(eq(100L), eq(7L), eq("PGS-1-S7"), eq(PRICE), eq(FEE),
                eq(BigDecimal.ZERO), eq(new BigDecimal("8550.00")), eq(new BigDecimal("5.00")),
                eq(new BigDecimal("425.00")), eq(new BigDecimal("8125.00")), eq(4L)))
                .thenReturn(seller);
        when(orders.insertOrderItem(eq(101L), eq(null), eq(11L), eq(1), eq(PRICE), eq(PRICE),
                eq(new BigDecimal("100.00")), eq("Card"), eq("EN"), eq("NM"), eq("Game"), eq("image")))
                .thenReturn(item);
        CheckoutResponse result = mock(CheckoutResponse.class);
        when(checkout.getOrderDetails(100L, false)).thenReturn(result);

        assertThat(service.checkout(5L, BUYER, "key", new AuctionCheckout(3L, 4L, null,
                new BigDecimal("8550.00")))).isSameAs(result);
        verify(orders).markAuctionSource(100L, 5L);
        verify(orders).insertOrderItemUnits(102L, List.of(9L));
        verify(auctions).attachSalesOrder(5L, 100L);
    }

    @Test void otherBuyerCannotCheckout() {
        Auction a = auction(99L, AuctionStatus.AWAITING_PAYMENT, null);
        when(auctions.lock(5L)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.checkout(5L, BUYER, "key", new AuctionCheckout(3L, null, null, null)))
                .isInstanceOf(ForbiddenException.class);
        verify(orders, never()).nextOrderNumber();
    }

    @Test void repeatedCheckoutReturnsTheSameOrder() {
        Auction a = auction(42L, AuctionStatus.AWAITING_PAYMENT, 100L);
        when(auctions.lock(5L)).thenReturn(Optional.of(a));
        SalesOrderRecord sale = mock(SalesOrderRecord.class);
        when(sale.getIdempotencyKey()).thenReturn("key");
        when(sale.getId()).thenReturn(100L);
        when(orders.findSalesOrderById(100L)).thenReturn(Optional.of(sale));
        CheckoutResponse response = mock(CheckoutResponse.class);
        when(checkout.getOrderDetails(100L, true)).thenReturn(response);
        assertThat(service.checkout(5L, BUYER, "key", new AuctionCheckout(3L, null, null, null)))
                .isSameAs(response);
        verify(orders, never()).nextOrderNumber();
    }

    @Test void discountCodeIsRejectedBeforeWriting() {
        assertThatThrownBy(() -> service.checkout(5L, BUYER, "key",
                new AuctionCheckout(3L, null, null, null, "SALE", Map.of())))
                .isInstanceOf(BadRequestException.class);
        verify(auctions, never()).lock(anyLong());
    }

    @Test void expiredPaymentWindowIsRejected() {
        Auction a = auction(42L, AuctionStatus.AWAITING_PAYMENT, null);
        lenient().when(a.paymentDueAt()).thenReturn(OffsetDateTime.now(CLOCK).minusSeconds(1));
        when(auctions.lock(5L)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.checkout(5L, BUYER, "key", new AuctionCheckout(3L, null, null, null)))
                .isInstanceOf(ConflictException.class);
        verify(orders, never()).nextOrderNumber();
    }
}
