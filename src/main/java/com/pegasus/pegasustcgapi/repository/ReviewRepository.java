package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerReview.SELLER_REVIEW;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerRatingSummaryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerReviewRecord;
import com.pegasus.pegasustcgapi.model.UserStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {

    private final DSLContext dsl;

    public ReviewRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SellerOrderRecord> order(long id) {
        return dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.ID.eq(id)).fetchOptional();
    }

    public Optional<SellerReviewRecord> byOrder(long sellerOrderId) {
        return dsl.selectFrom(SELLER_REVIEW)
                .where(SELLER_REVIEW.SELLER_ORDER_ID.eq(sellerOrderId)).fetchOptional();
    }

    public Optional<SellerReviewRecord> lock(long id) {
        return dsl.selectFrom(SELLER_REVIEW).where(SELLER_REVIEW.ID.eq(id))
                .forUpdate().fetchOptional();
    }

    public SellerReviewRecord create(long sellerOrderId, long sellerId, long buyerId,
            short rating, String title, String comment) {
        return dsl.insertInto(SELLER_REVIEW)
                .set(SELLER_REVIEW.SELLER_ORDER_ID, sellerOrderId)
                .set(SELLER_REVIEW.SELLER_PROFILE_ID, sellerId)
                .set(SELLER_REVIEW.REVIEWER_ID, buyerId)
                .set(SELLER_REVIEW.RATING, rating)
                .set(SELLER_REVIEW.TITLE, title)
                .set(SELLER_REVIEW.COMMENT, comment)
                .returning().fetchSingle();
    }

    public void reply(long id, String comment, OffsetDateTime now) {
        dsl.update(SELLER_REVIEW).set(SELLER_REVIEW.REPLY_COMMENT, comment)
                .set(SELLER_REVIEW.REPLIED_AT, now)
                .where(SELLER_REVIEW.ID.eq(id)).execute();
    }

    public void visibility(long id, boolean visible) {
        dsl.update(SELLER_REVIEW).set(SELLER_REVIEW.IS_VISIBLE, visible)
                .where(SELLER_REVIEW.ID.eq(id)).execute();
    }

    public boolean sellerOwns(long sellerId, long userId) {
        return dsl.fetchExists(dsl.selectOne().from(SELLER_PROFILE)
                .where(SELLER_PROFILE.ID.eq(sellerId)).and(SELLER_PROFILE.USER_ID.eq(userId)));
    }

    public Optional<Long> publicSellerId(String username) {
        return dsl.select(SELLER_PROFILE.ID).from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(USER_ACCOUNT.USERNAME.eq(username)).and(USER_ACCOUNT.STATUS.eq(UserStatus.ACTIVE))
                .fetchOptional(SELLER_PROFILE.ID);
    }

    public List<PublicReview> visible(long sellerId) {
        return dsl.select(SELLER_REVIEW.ID, SELLER_REVIEW.RATING, SELLER_REVIEW.TITLE,
                        SELLER_REVIEW.COMMENT, SELLER_REVIEW.REPLY_COMMENT,
                        SELLER_REVIEW.CREATED_AT, USER_ACCOUNT.USERNAME)
                .from(SELLER_REVIEW).join(USER_ACCOUNT)
                .on(USER_ACCOUNT.ID.eq(SELLER_REVIEW.REVIEWER_ID))
                .where(SELLER_REVIEW.SELLER_PROFILE_ID.eq(sellerId))
                .and(SELLER_REVIEW.IS_VISIBLE.isTrue())
                .orderBy(SELLER_REVIEW.CREATED_AT.desc(), SELLER_REVIEW.ID.desc())
                .fetch(r -> new PublicReview(r.value1(), r.value2(), r.value3(), r.value4(),
                        r.value5(), r.value6(), r.value7()));
    }

    public void refreshSummary(long sellerId) {
        dsl.insertInto(SELLER_RATING_SUMMARY)
                .set(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID, sellerId)
                .onConflict(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID).doNothing().execute();
        dsl.selectFrom(SELLER_RATING_SUMMARY)
                .where(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(sellerId))
                .forUpdate().fetchSingle();
        List<Short> ratings = dsl.select(SELLER_REVIEW.RATING).from(SELLER_REVIEW)
                .where(SELLER_REVIEW.SELLER_PROFILE_ID.eq(sellerId))
                .and(SELLER_REVIEW.IS_VISIBLE.isTrue())
                .fetch(SELLER_REVIEW.RATING);
        int sum = ratings.stream().mapToInt(Short::intValue).sum();
        BigDecimal average = ratings.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(ratings.size()), 2, RoundingMode.HALF_UP);
        dsl.update(SELLER_RATING_SUMMARY)
                .set(SELLER_RATING_SUMMARY.RATING_COUNT, ratings.size())
                .set(SELLER_RATING_SUMMARY.RATING_SUM, sum)
                .set(SELLER_RATING_SUMMARY.RATING_AVG, average)
                .set(SELLER_RATING_SUMMARY.COUNT_1, count(ratings, 1))
                .set(SELLER_RATING_SUMMARY.COUNT_2, count(ratings, 2))
                .set(SELLER_RATING_SUMMARY.COUNT_3, count(ratings, 3))
                .set(SELLER_RATING_SUMMARY.COUNT_4, count(ratings, 4))
                .set(SELLER_RATING_SUMMARY.COUNT_5, count(ratings, 5))
                .where(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(sellerId)).execute();
    }

    public Optional<SellerRatingSummaryRecord> summary(long sellerId) {
        return dsl.selectFrom(SELLER_RATING_SUMMARY)
                .where(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(sellerId)).fetchOptional();
    }

    private static int count(List<Short> ratings, int value) {
        return (int) ratings.stream().filter(r -> r == value).count();
    }

    public record PublicReview(long id, short rating, String title, String comment,
            String replyComment, OffsetDateTime createdAt, String reviewerUsername) {
    }
}
