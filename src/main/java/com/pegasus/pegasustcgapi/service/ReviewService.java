package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerRatingSummaryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerReviewRecord;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.ReviewRepository;
import com.pegasus.pegasustcgapi.repository.ReviewRepository.AdminReview;
import com.pegasus.pegasustcgapi.repository.ReviewRepository.PublicReview;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private final ReviewRepository reviews;
    private final PaymentRepository orders;
    private final Clock clock;

    public ReviewService(ReviewRepository reviews, PaymentRepository orders, Clock clock) {
        this.reviews = reviews;
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional
    public SellerReviewRecord create(long buyerId, long sellerOrderId, short rating,
            String title, String comment) {
        var order = reviews.order(sellerOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        var parent = orders.findOrder(order.getSalesOrderId()).orElseThrow();
        if (parent.getBuyerId() != buyerId) throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
        if (!"COMPLETED".equals(order.getStatus())) {
            throw new ConflictException(ErrorCode.REVIEW_ORDER_NOT_COMPLETED);
        }
        if (reviews.byOrder(sellerOrderId).isPresent()) {
            throw new ConflictException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        var review = reviews.create(sellerOrderId, order.getSellerProfileId(), buyerId,
                rating, title, comment);
        reviews.refreshSummary(order.getSellerProfileId());
        return review;
    }

    @Transactional(readOnly = true)
    public Optional<SellerReviewRecord> mine(long buyerId, long sellerOrderId) {
        var order = reviews.order(sellerOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        var parent = orders.findOrder(order.getSalesOrderId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        if (parent.getBuyerId() != buyerId) throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
        return reviews.byOrder(sellerOrderId);
    }

    @Transactional(readOnly = true)
    public PublicReviews publicReviews(String username) {
        long sellerId = reviews.publicSellerId(username)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SELLER_NOT_FOUND));
        return new PublicReviews(reviews.visible(sellerId), reviews.summary(sellerId).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<AdminReview> adminReviews(String username) {
        long sellerId = reviews.sellerIdByUsername(username)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SELLER_NOT_FOUND));
        return reviews.allForSeller(sellerId);
    }

    @Transactional
    public SellerReviewRecord reply(long sellerUserId, long reviewId, String comment) {
        var review = reviews.lock(reviewId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REVIEW_NOT_FOUND));
        if (!reviews.sellerOwns(review.getSellerProfileId(), sellerUserId)) {
            throw new NotFoundException(ErrorCode.REVIEW_NOT_FOUND);
        }
        if (review.getReplyComment() != null) throw new ConflictException(ErrorCode.REVIEW_ALREADY_REPLIED);
        reviews.reply(reviewId, comment, OffsetDateTime.now(clock));
        return reviews.lock(reviewId).orElseThrow();
    }

    @Transactional
    public SellerReviewRecord visibility(long reviewId, boolean visible) {
        var review = reviews.lock(reviewId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REVIEW_NOT_FOUND));
        if (review.getIsVisible() != visible) {
            reviews.visibility(reviewId, visible);
            reviews.refreshSummary(review.getSellerProfileId());
        }
        return reviews.lock(reviewId).orElseThrow();
    }

    public record PublicReviews(List<PublicReview> reviews, SellerRatingSummaryRecord summary) {
    }
}
