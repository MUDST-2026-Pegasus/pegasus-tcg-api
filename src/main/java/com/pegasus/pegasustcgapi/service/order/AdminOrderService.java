package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.common.csv.CsvExport;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Alerts;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Board;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Column;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Detail;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Payment;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Summary;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.order.AdminOrderColumn;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.repository.order.AdminOrderRepository;
import com.pegasus.pegasustcgapi.repository.order.AdminOrderRepository.AdminOrderQuery;
import com.pegasus.pegasustcgapi.repository.order.AdminOrderRepository.AdminOrderRow;
import com.pegasus.pegasustcgapi.repository.order.AdminOrderRepository.OrderContext;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every seller's orders, read-only, for the admin back office [CR-7].
 *
 * <p>Nothing here changes an order. Cancelling, refunding and settling disputes
 * stay with the services that own those transitions.
 */
@Service
public class AdminOrderService {

    public static final int DEFAULT_PER_COLUMN = 3;
    public static final int MAX_PER_COLUMN = 20;

    private static final Logger log = LoggerFactory.getLogger(AdminOrderService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] EXPORT_COLUMNS = {
            "seller_order_number", "order_number", "status", "seller", "buyer", "item_count",
            "grand_total", "created_at", "paid_at", "shipped_at", "completed_at", "cancelled_at",
            "tracking_number"};

    private final AdminOrderRepository orders;
    private final OrderLifecycleService lifecycle;
    private final PlatformSettingService settings;
    private final Clock clock;

    public AdminOrderService(
            AdminOrderRepository orders,
            OrderLifecycleService lifecycle,
            PlatformSettingService settings,
            Clock clock) {

        this.orders = orders;
        this.lifecycle = lifecycle;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * The five board columns with their full counts and the first few sub-orders of
     * each. The counts come from one GROUP BY, so a column's number is right even
     * though it shows only {@code perColumn} cards.
     */
    @Transactional(readOnly = true)
    public Board board(int perColumn) {
        int limit = Math.clamp(perColumn, 1, MAX_PER_COLUMN);
        Map<String, Long> counts = orders.countByStatus();

        List<Column> columns = new ArrayList<>();
        for (AdminOrderColumn column : AdminOrderColumn.values()) {
            List<String> statuses = column.statusNames();
            List<Summary> items = orders.findPage(new AdminOrderQuery(
                            statuses, null, null, null, null, null, column.sort(), limit, 0))
                    .stream()
                    .map(Summary::from)
                    .toList();
            columns.add(new Column(column.name(), statuses, total(counts, statuses), items));
        }

        Duration timeout = settings.getMinutes(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES);
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(timeout);
        Alerts alerts = new Alerts(
                total(counts, AdminOrderColumn.DISPUTED.statusNames()),
                orders.countUnpaidSince(cutoff),
                timeout.toMinutes());

        return new Board(List.copyOf(columns), alerts);
    }

    /**
     * @param statuses any number of {@link SellerOrderStatus} names; empty for all
     * @param sort     an {@link AdminOrderQuery.Sort} name; null for newest first
     */
    @Transactional(readOnly = true)
    public PageResponse<Summary> list(
            List<String> statuses,
            String search,
            Long sellerProfileId,
            Long buyerId,
            OffsetDateTime from,
            OffsetDateTime to,
            String sort,
            int page,
            int size) {

        Paging paging = Paging.of(page, size);
        AdminOrderQuery query = new AdminOrderQuery(
                parseStatuses(statuses),
                sellerProfileId,
                buyerId,
                from,
                to,
                search == null || search.isBlank() ? null : search.trim(),
                parseSort(sort),
                paging.size(),
                paging.offset());

        List<Summary> items = orders.findPage(query).stream().map(Summary::from).toList();
        return PageResponse.of(items, paging.page(), paging.size(), orders.count(query));
    }

    /**
     * Every sub-order placed in [from, to) that {@link #list} would show for the same
     * status and search, newest first, as one CSV. Times are in Asia/Bangkok.
     *
     * @throws BadRequestException EXPORT_TOO_LARGE above {@link CsvExport#MAX_ROWS} rows,
     *                             before any row is read
     */
    @Transactional(readOnly = true)
    public CsvExport export(
            List<String> statuses, String search, OffsetDateTime from, OffsetDateTime to, long adminId) {

        if (from == null || to == null || !to.isAfter(from)) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "from and to are required and to must be after from");
        }
        String text = search == null || search.isBlank() ? null : search.trim();
        List<String> parsed = parseStatuses(statuses);
        AdminOrderQuery query = new AdminOrderQuery(parsed, null, null, from, to, text,
                AdminOrderQuery.Sort.NEWEST, CsvExport.MAX_ROWS, 0);
        long rows = orders.count(query);
        CsvExport.checkRowCount(rows);

        CsvExport.Builder csv = CsvExport.builder(EXPORT_COLUMNS);
        for (AdminOrderRow row : orders.findPage(query)) {
            csv.row(row.sellerOrderNumber(), row.orderNumber(), row.status(), row.sellerUsername(),
                    row.buyerUsername(), row.itemCount(), row.grandTotal(), exportTime(row.createdAt()),
                    exportTime(row.paidAt()), exportTime(row.shippedAt()), exportTime(row.completedAt()),
                    exportTime(row.cancelledAt()), row.trackingNumber());
        }
        // The search text can be a username or an order number, so only whether there was one is logged.
        log.info("Admin {} exported {} sub-orders (from={}, to={}, status={}, search={})",
                adminId, rows, from, to, parsed, text != null);

        // to is exclusive, so the file is named after the last day it covers.
        String filename = "admin-orders-" + from.atZoneSameInstant(ZONE).toLocalDate()
                + "-" + to.minusNanos(1).atZoneSameInstant(ZONE).toLocalDate() + ".csv";
        return csv.build(filename);
    }

    private static String exportTime(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(ZONE).format(EXPORT_TIME);
    }

    @Transactional(readOnly = true)
    public Detail detail(long sellerOrderId) {
        AdminOrderRow row = orders.findById(sellerOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        OrderContext context = orders.findOrderContext(row.salesOrderId())
                .orElse(new OrderContext(null, null));
        Payment payment = orders.findLatestPayment(row.salesOrderId())
                .map(Payment::from)
                .orElse(null);

        return new Detail(
                Summary.from(row),
                lifecycle.toSellerOrderDetailsResponse(sellerOrderId),
                context.shippingAddressSnapshot(),
                context.buyerNote(),
                payment);
    }

    private static long total(Map<String, Long> counts, List<String> statuses) {
        return statuses.stream().mapToLong(status -> counts.getOrDefault(status, 0L)).sum();
    }

    /** Accepts repeated parameters and comma-separated values alike. Shared with {@link SellerOrderDeskService}. */
    static List<String> parseStatuses(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String value : requested) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                SellerOrderStatus status = SellerOrderStatus.parseOrNull(part);
                if (status == null) {
                    throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                            "status must be one of " + Arrays.toString(SellerOrderStatus.values()));
                }
                names.add(status.name());
            }
        }
        return List.copyOf(names);
    }

    static AdminOrderQuery.Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return AdminOrderQuery.Sort.NEWEST;
        }
        try {
            return AdminOrderQuery.Sort.valueOf(sort.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "sort must be one of " + Arrays.toString(AdminOrderQuery.Sort.values()));
        }
    }
}
