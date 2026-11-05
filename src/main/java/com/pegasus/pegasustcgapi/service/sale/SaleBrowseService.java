package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.listing.PublicListingResponse;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.Featured;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.FlashSale;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.SaleHeader;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.SalePage;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.SoldOutCard;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import com.pegasus.pegasustcgapi.repository.sale.SaleBrowseRepository;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository;
import com.pegasus.pegasustcgapi.repository.sale.SaleBrowseRepository.Campaign;
import com.pegasus.pegasustcgapi.repository.sale.SaleBrowseRepository.SoldOutRow;
import com.pegasus.pegasustcgapi.service.listing.ListingBrowseService;
import com.pegasus.pegasustcgapi.service.listing.ListingBrowseService.SaleFilter;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.ListingFacts;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.SaleListing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sale pages anyone can open, signed in or not [TCG-679]: the SALE page's
 * tabs, one sale's page, and the flash sale on the home page.
 */
@Service
public class SaleBrowseService {

    /** Cards the home page's flash sale strip shows. */
    static final int FEATURED_LISTINGS = 12;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SaleBrowseRepository sales;
    private final ListingBrowseService market;
    private final ListingSalePriceRepository prices;
    private final Clock clock;

    public SaleBrowseService(SaleBrowseRepository sales, ListingBrowseService market,
            ListingSalePriceRepository prices, Clock clock) {
        this.sales = sales;
        this.market = market;
        this.prices = prices;
        this.clock = clock;
    }

    /** Pegasus campaigns running now and flash sales that start within 24 hours. */
    @Transactional(readOnly = true)
    public List<SaleHeader> open() {
        return sales.findOpenCampaigns(OffsetDateTime.now(clock)).stream().map(SaleHeader::of).toList();
    }

    /**
     * A sale's header and a page of its cards, filtered like the market. A sale that
     * has ended still has a page, saying so, with no cards; one that never existed
     * is not found.
     */
    @Transactional(readOnly = true)
    public SalePage page(String slug, Long variantId, Long productId, Short gameId, CardCondition condition,
            BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minDiscount, String sort, int page, int size) {

        Campaign campaign = sales.findBySlug(slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT),
                        OffsetDateTime.now(clock))
                .orElseThrow(() -> new NotFoundException(ErrorCode.SALE_NOT_FOUND));
        SaleHeader header = SaleHeader.of(campaign);
        if (campaign.status() == SaleStatus.ENDED) {
            Paging paging = Paging.of(page, size);
            return new SalePage(header, PageResponse.of(List.of(), paging.page(), paging.size(), 0));
        }
        return new SalePage(header, market.market(variantId, productId, gameId, condition, minPrice, maxPrice,
                new SaleFilter(true, campaign.sale().id(), minDiscount), sort, page, size));
    }

    /**
     * The home page's strip: one flash sale — a featured one first, a running one
     * before one coming soon — with up to {@value #FEATURED_LISTINGS} of its cards,
     * and the featured category campaigns. Null when there is neither.
     */
    @Transactional(readOnly = true)
    public Featured featured() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Campaign> featured = sales.findFeatured(now);

        Optional<Campaign> flash = pickFlash(featured)
                .or(() -> pickFlash(sales.findOpenCampaigns(now)));
        List<SaleHeader> categories = featured.stream()
                .filter(c -> c.sale().saleType() == SaleType.CATEGORY_SALE)
                .filter(c -> c.status() == SaleStatus.ACTIVE)
                .map(SaleHeader::of)
                .toList();
        if (flash.isEmpty() && categories.isEmpty()) {
            return null;
        }
        return new Featured(flash.map(this::flashSale).orElse(null), categories);
    }

    /**
     * A running flash sale's cards, then — to fill the strip — the ones that have sold
     * their quota, faded. A sale coming soon, or sold out as a whole, shows its
     * sold-out cards only.
     */
    private FlashSale flashSale(Campaign c) {
        boolean running = c.status() == SaleStatus.ACTIVE;
        List<PublicListingResponse> cards = !running ? List.of()
                : market.market(null, null, null, null, null, null,
                        new SaleFilter(true, c.sale().id(), null), "discount_desc", 0, FEATURED_LISTINGS).items();
        boolean showSoldOut = running || c.status() == SaleStatus.SOLD_OUT;
        List<SoldOutCard> soldOut = showSoldOut ? soldOut(c.sale(), FEATURED_LISTINGS - cards.size()) : List.of();
        return new FlashSale(SaleHeader.of(c), cards, soldOut);
    }

    private List<SoldOutCard> soldOut(PriceSale sale, int room) {
        List<SoldOutRow> rows = sales.findSoldOut(sale, room);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(SoldOutRow::listingId).toList();
        Map<Long, ListingFacts> facts = prices.findFacts(ids).stream()
                .collect(Collectors.toMap(ListingFacts::listingId, Function.identity()));
        Map<Long, SoldOutRow> rowById = rows.stream()
                .collect(Collectors.toMap(SoldOutRow::listingId, Function.identity()));

        List<SoldOutCard> out = new ArrayList<>();
        for (PublicListingResponse card : market.byIds(ids)) {
            ListingFacts f = facts.get(card.id());
            SoldOutRow row = rowById.get(card.id());
            if (f == null || row == null) {
                continue;
            }
            // Priced as if nothing had sold yet: the flash price it went at.
            SalePriceResolver.price(sale, f, new SaleListing(row.rowSalePrice(), row.quota(), 0))
                    .ifPresent(r -> out.add(new SoldOutCard(card, r.salePrice(), f.price().subtract(r.salePrice())
                            .multiply(HUNDRED).divide(f.price(), 0, RoundingMode.HALF_UP).intValueExact())));
        }
        return out;
    }

    /** Running before coming soon; otherwise in the order given. */
    private static Optional<Campaign> pickFlash(List<Campaign> campaigns) {
        return campaigns.stream()
                .filter(c -> c.sale().saleType() == SaleType.FLASH_SALE)
                .min(Comparator.comparingInt(c -> c.status() == SaleStatus.ACTIVE ? 0 : 1));
    }
}
