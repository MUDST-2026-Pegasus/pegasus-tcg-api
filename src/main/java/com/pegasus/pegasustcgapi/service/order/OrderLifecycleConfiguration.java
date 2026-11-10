package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.port.collection.CollectionPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.service.promotion.CheckoutPromotionService;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrderLifecycleConfiguration {
    @Bean
    OrderTransitionSupport orderTransitionSupport(OrderRepository orders, SellerPort sellers,
            PlatformSettingService settings, Clock clock) {
        return new OrderTransitionSupport(orders, sellers, settings, clock);
    }

    @Bean
    OrderSettlementService orderSettlementService(OrderRepository orders, Clock clock,
            ObjectProvider<SaleCheckoutService> saleCheckout,
            ObjectProvider<CheckoutPromotionService> checkoutPromotions) {
        return new OrderSettlementService(orders, clock,
                saleCheckout.getIfAvailable(), checkoutPromotions.getIfAvailable());
    }

    @Bean
    OrderResponseMapper orderResponseMapper(OrderRepository orders, PlatformSettingService settings,
            @Autowired(required = false) StorageService storage,
            ObjectProvider<CheckoutPromotionService> checkoutPromotions) {
        return new OrderResponseMapper(orders, settings, storage, checkoutPromotions.getIfAvailable());
    }

    @Bean
    OrderQueryService orderQueryService(OrderTransitionSupport support, OrderResponseMapper mapper,
            @Autowired(required = false) StorageService storage) {
        return new OrderQueryService(support, mapper, storage);
    }

    @Bean
    OrderCancellationService orderCancellationService(OrderTransitionSupport support, InventoryPort inventory,
            LedgerPort ledger, OrderSettlementService settlement, OrderResponseMapper mapper) {
        return new OrderCancellationService(support, inventory, ledger, settlement, mapper);
    }

    @Bean
    OrderFulfillmentService orderFulfillmentService(OrderTransitionSupport support, InventoryPort inventory,
            LedgerPort ledger, CollectionPort collection, OrderSettlementService settlement, OrderResponseMapper mapper) {
        return new OrderFulfillmentService(support, inventory, ledger, collection, settlement, mapper);
    }

    @Bean
    OrderPaymentLifecycleService orderPaymentLifecycleService(OrderTransitionSupport support, AuctionRepository auctions,
            OrderSettlementService settlement, OrderResponseMapper mapper) {
        return new OrderPaymentLifecycleService(support, auctions, settlement, mapper);
    }
}
