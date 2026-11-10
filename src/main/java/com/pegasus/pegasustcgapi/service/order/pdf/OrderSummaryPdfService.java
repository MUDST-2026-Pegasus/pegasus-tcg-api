package com.pegasus.pegasustcgapi.service.order.pdf;

import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.OrderItemDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.OrderStatusHistoryResponse;
import com.pegasus.pegasustcgapi.dto.order.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.shipping.ShipmentResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.repository.order.OrderQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository;
import com.pegasus.pegasustcgapi.repository.user.UserRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.order.AdminOrderService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.order.SellerOrderDeskService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Maps each already-authorized role view into the shared PDF document. */
@Service
public class OrderSummaryPdfService {

    private final OrderLifecycleService buyerOrders;
    private final SellerOrderDeskService sellerOrders;
    private final AdminOrderService adminOrders;
    private final OrderQueryRepository orderQueries;
    private final OrderReferenceQueryRepository orderReferences;
    private final UserRepository users;
    private final OrderSummaryPdfRenderer renderer;
    private final ObjectMapper json;
    private final Clock clock;

    public OrderSummaryPdfService(
            OrderLifecycleService buyerOrders,
            SellerOrderDeskService sellerOrders,
            AdminOrderService adminOrders,
            OrderQueryRepository orderQueries,
            OrderReferenceQueryRepository orderReferences,
            UserRepository users,
            OrderSummaryPdfRenderer renderer,
            ObjectMapper json,
            Clock clock) {
        this.buyerOrders = buyerOrders;
        this.sellerOrders = sellerOrders;
        this.adminOrders = adminOrders;
        this.orderQueries = orderQueries;
        this.orderReferences = orderReferences;
        this.users = users;
        this.renderer = renderer;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrderSummaryPdfRenderer.RenderedPdf buyer(AuthPrincipal principal, long orderId) {
        OrderDetailsResponse order = buyerOrders.getBuyerOrder(principal, orderId);
        UserRepository.UserIdentity identity = users.findIdentityById(order.buyerId())
                .orElse(new UserRepository.UserIdentity(principal.username(), principal.username()));
        AddressSnapshot address = address(order.shippingAddressSnapshot());
        Map<Long, OrderReferenceQueryRepository.SellerIdentity> sellerIdentities = orderReferences.findSellerIdentitiesByProfileIds(order.sellerOrders().stream()
                .map(SellerOrderDetailsResponse::sellerProfileId).distinct().toList());
        List<OrderSummaryPdfDocument.SellerSection> sections = order.sellerOrders().stream()
                .map(sellerOrder -> buyerSection(sellerOrder,
                        sellerIdentities.get(sellerOrder.sellerProfileId())))
                .toList();

        List<OrderSummaryPdfDocument.StatusEvent> history = order.sellerOrders().stream()
                .flatMap(sellerOrder -> statusEvents(sellerOrder.statusHistory()).stream())
                .sorted(Comparator.comparing(OrderSummaryPdfDocument.StatusEvent::occurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        OrderSummaryPdfDocument document = new OrderSummaryPdfDocument(
                OrderSummaryPdfRole.BUYER,
                order.orderNumber(),
                null,
                order.status(),
                order.currency(),
                order.placedAt(),
                order.paidAt(),
                new OrderSummaryPdfDocument.Party(identity.displayName(), identity.username()),
                sections,
                shipping(address, order.sellerOrders().stream()
                        .flatMap(sellerOrder -> safe(sellerOrder.shipments()).stream()).toList()),
                null,
                new OrderSummaryPdfDocument.Totals(
                        order.itemsSubtotal(), order.shippingTotal(), order.discountTotal(), order.grandTotal(),
                        BigDecimal.ZERO, BigDecimal.ZERO),
                history,
                OffsetDateTime.now(clock));
        return renderer.render(document);
    }

    @Transactional(readOnly = true)
    public OrderSummaryPdfRenderer.RenderedPdf seller(AuthPrincipal principal, long sellerOrderId) {
        com.pegasus.pegasustcgapi.dto.order.SellerOrderViews.Detail detail =
                sellerOrders.detail(principal, sellerOrderId);
        SellerOrderDetailsResponse order = detail.summary().order();
        String currency = currencyForSalesOrder(order.salesOrderId());
        AddressSnapshot address = address(detail.summary().shippingAddressSnapshot());
        List<OrderSummaryPdfDocument.LineItem> items = lineItems(order.items());

        OrderSummaryPdfDocument.SellerSection section = new OrderSummaryPdfDocument.SellerSection(
                accountLabel(order.sellerName(), principal.username()),
                order.sellerOrderNumber(),
                items,
                new OrderSummaryPdfDocument.Totals(
                        order.itemsSubtotal(), order.shippingFee(), order.discountAmount(), order.grandTotal(),
                        order.commissionAmount(), order.sellerNetAmount()));
        OrderSummaryPdfDocument document = new OrderSummaryPdfDocument(
                OrderSummaryPdfRole.SELLER,
                detail.summary().orderNumber(),
                order.sellerOrderNumber(),
                order.status(),
                currency,
                order.createdAt(),
                detail.summary().paidAt(),
                new OrderSummaryPdfDocument.Party(
                        detail.summary().buyer().displayName(), detail.summary().buyer().username()),
                List.of(section),
                shipping(address, order.shipments()),
                detail.payment() == null
                        ? null
                        : new OrderSummaryPdfDocument.Payment(null, detail.payment().status(), null,
                                detail.payment().paidAt()),
                new OrderSummaryPdfDocument.Totals(
                        order.itemsSubtotal(), order.shippingFee(), BigDecimal.ZERO, BigDecimal.ZERO,
                        order.commissionAmount(), order.sellerNetAmount()),
                statusEvents(order.statusHistory()),
                OffsetDateTime.now(clock));
        return renderer.render(document);
    }

    @Transactional(readOnly = true)
    public OrderSummaryPdfRenderer.RenderedPdf admin(long sellerOrderId) {
        com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Detail detail = adminOrders.detail(sellerOrderId);
        SellerOrderDetailsResponse order = detail.order();
        String currency = currencyForSalesOrder(order.salesOrderId());
        AddressSnapshot address = address(detail.shippingAddressSnapshot());
        ShipmentResponse shipment = latestShipment(order.shipments());
        List<OrderSummaryPdfDocument.LineItem> items = lineItems(order.items());
        com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Payment payment = detail.payment();
        BigDecimal paid = payment != null && "SUCCEEDED".equalsIgnoreCase(payment.status())
                ? payment.amount() : BigDecimal.ZERO;
        List<OrderSummaryPdfDocument.StatusEvent> history = new ArrayList<>(statusEvents(order.statusHistory()));
        if (present(detail.summary().returnReasonCode())) {
            history.add(new OrderSummaryPdfDocument.StatusEvent(
                    "RETURN_REQUESTED", null, detail.summary().returnReasonCode()));
        }
        if (payment != null && present(payment.failureReason())) {
            history.add(new OrderSummaryPdfDocument.StatusEvent(
                    "PAYMENT_ISSUE", payment.paidAt(), payment.failureReason()));
        }

        OrderSummaryPdfDocument.SellerSection section = new OrderSummaryPdfDocument.SellerSection(
                accountLabel(detail.summary().seller().displayName(), detail.summary().seller().username()),
                order.sellerOrderNumber(),
                items,
                new OrderSummaryPdfDocument.Totals(
                        order.itemsSubtotal(), order.shippingFee(), order.discountAmount(), order.grandTotal(),
                        order.commissionAmount(), order.sellerNetAmount()));
        OrderSummaryPdfDocument document = new OrderSummaryPdfDocument(
                OrderSummaryPdfRole.ADMIN,
                detail.summary().orderNumber(),
                order.sellerOrderNumber(),
                order.status(),
                currency,
                order.createdAt(),
                detail.summary().paidAt(),
                new OrderSummaryPdfDocument.Party(
                        detail.summary().buyer().displayName(), detail.summary().buyer().username()),
                List.of(section),
                shipping(address, shipment == null ? List.of() : List.of(shipment)),
                payment == null
                        ? null
                        : new OrderSummaryPdfDocument.Payment(
                                payment.method(), payment.status(), payment.amount(), payment.paidAt()),
                new OrderSummaryPdfDocument.Totals(
                        order.itemsSubtotal(), order.shippingFee(), order.discountAmount(), paid,
                        order.commissionAmount(), order.sellerNetAmount()),
                history,
                OffsetDateTime.now(clock));
        return renderer.render(document);
    }

    private OrderSummaryPdfDocument.SellerSection buyerSection(
            SellerOrderDetailsResponse order, OrderReferenceQueryRepository.SellerIdentity identity) {
        String displayName = identity == null ? order.sellerName() : identity.displayName();
        String username = identity == null ? null : identity.username();
        return new OrderSummaryPdfDocument.SellerSection(
                accountLabel(displayName, username), order.sellerOrderNumber(), lineItems(order.items()),
                new OrderSummaryPdfDocument.Totals(
                        order.itemsSubtotal(), order.shippingFee(), order.discountAmount(), order.grandTotal(),
                        BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private static List<OrderSummaryPdfDocument.LineItem> lineItems(List<OrderItemDetailsResponse> items) {
        return safe(items).stream()
                .map(item -> new OrderSummaryPdfDocument.LineItem(
                        item.productName(), item.variantLabel(), item.condition(), item.gameName(), item.quantity(),
                        item.unitPrice(), item.discountAllocated(), item.lineTotal()))
                .toList();
    }

    private static List<OrderSummaryPdfDocument.StatusEvent> statusEvents(
            List<OrderStatusHistoryResponse> history) {
        return safe(history).stream()
                .map(event -> new OrderSummaryPdfDocument.StatusEvent(
                        event.toStatus(), event.createdAt(), event.note()))
                .toList();
    }

    private OrderSummaryPdfDocument.Shipping shipping(AddressSnapshot address, List<ShipmentResponse> shipments) {
        List<ShipmentResponse> rows = safe(shipments);
        String carriers = rows.stream().map(ShipmentResponse::carrierName).filter(OrderSummaryPdfService::present)
                .distinct().collect(Collectors.joining(" / "));
        String trackingNumbers = rows.stream().map(ShipmentResponse::trackingNumber)
                .filter(OrderSummaryPdfService::present).distinct().collect(Collectors.joining(" / "));
        String estimatedDelivery = rows.stream().map(ShipmentResponse::estimatedDeliveryDate)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).map(Object::toString).orElse(null);
        return new OrderSummaryPdfDocument.Shipping(
                address.recipientName(), address.formattedAddress(), blankToNull(carriers),
                blankToNull(trackingNumbers), estimatedDelivery);
    }

    private AddressSnapshot address(String snapshot) {
        if (!present(snapshot)) {
            return new AddressSnapshot(null, null);
        }
        try {
            JsonNode root = json.readTree(snapshot);
            return new AddressSnapshot(
                    value(root, "recipientName"),
                    joinAddress(root));
        } catch (JacksonException invalidSnapshot) {
            // Do not print the raw snapshot: it contains the buyer's phone number.
            return new AddressSnapshot(null, null);
        }
    }

    private static String joinAddress(JsonNode root) {
        List<String> fields = List.of("line1", "line2", "subdistrict", "district", "province", "postalCode", "countryCode");
        List<String> values = new ArrayList<>();
        for (String field : fields) {
            String value = value(root, field);
            if (present(value)) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static String value(JsonNode root, String field) {
        if (root == null || root.get(field) == null || root.get(field).isNull()) {
            return null;
        }
        return root.get(field).asString(null);
    }

    private static ShipmentResponse latestShipment(List<ShipmentResponse> shipments) {
        return safe(shipments).stream()
                .max(Comparator.comparing(ShipmentResponse::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return present(value) ? value : null;
    }

    private static String accountLabel(String displayName, String username) {
        String name = blankToNull(displayName);
        String handle = blankToNull(username);
        if (name == null) {
            return handle == null ? "Seller" : "@" + handle;
        }
        if (handle == null || handle.equalsIgnoreCase(name)) {
            return name;
        }
        return name + " (@" + handle + ")";
    }

    private String currencyForSalesOrder(long salesOrderId) {
        return orderQueries.findCurrencyBySalesOrderId(salesOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record AddressSnapshot(String recipientName, String formattedAddress) {
    }
}
