package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.listing.PublicListingResponse;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.Featured;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.FlashSale;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.SaleHeader;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.SalePage;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import com.pegasus.pegasustcgapi.repository.sale.SaleBrowseRepository;
import com.pegasus.pegasustcgapi.repository.sale.SaleBrowseRepository.Campaign;
import com.pegasus.pegasustcgapi.service.listing.ListingBrowseService;
import com.pegasus.pegasustcgapi.service.listing.ListingBrowseService.SaleFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    private final SaleBrowseRepository sales;
    private final ListingBrowseService market;
    private final Clock clock;

    public SaleBrowseService(SaleBrowseRepository sales, ListingBrowseService market, Clock clock) {
        this.sales = sales;
        this.market = market;
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

    private FlashSale flashSale(Campaign c) {
        List<PublicListingResponse> cards = c.status() != SaleStatus.ACTIVE ? List.of()
                : market.market(null, null, null, null, null, null,
                        new SaleFilter(true, c.sale().id(), null), "discount_desc", 0, FEATURED_LISTINGS).items();
        return new FlashSale(SaleHeader.of(c), cards);
    }

    /** Running before coming soon; otherwise in the order given. */
    private static Optional<Campaign> pickFlash(List<Campaign> campaigns) {
        return campaigns.stream()
                .filter(c -> c.sale().saleType() == SaleType.FLASH_SALE)
                .min(Comparator.comparingInt(c -> c.status() == SaleStatus.ACTIVE ? 0 : 1));
    }
}
