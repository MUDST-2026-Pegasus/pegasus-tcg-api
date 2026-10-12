package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.FinanceViews.Review;
import com.pegasus.pegasustcgapi.dto.FinanceViews.RatingSummary;
import com.pegasus.pegasustcgapi.repository.ReviewRepository.PublicReview;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.ReviewService;
import com.pegasus.pegasustcgapi.service.ReviewService.PublicReviews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller Reviews")
@RestController
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Review a seller after completing a sub-order")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping(ApiPaths.ORDERS + "/{sellerOrderId}/reviews")
    public ApiResult<Review> create(
            @Parameter(description = "seller_order.id (from sellerOrders[].id in GET /orders/{id}) only; does not accept sales_order.id")
            @PathVariable long sellerOrderId,
            @Valid @RequestBody CreateRequest body, AuthPrincipal principal) {
        return ApiResult.success(Review.from(reviews.create(principal.userId(), sellerOrderId,
                body.rating(), body.title(), body.comment())));
    }

    @Operation(summary = "View a seller's public reviews and rating")
    @GetMapping(ApiPaths.PROFILES + "/{username}/reviews")
    public ApiResult<PublicReviewsView> publicReviews(@PathVariable String username) {
        PublicReviews result = reviews.publicReviews(username);
        return ApiResult.success(new PublicReviewsView(result.reviews(), RatingSummary.from(result.summary())));
    }

    @Operation(summary = "Reply once to a review of my store")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping(ApiPaths.SELLERS_ME + "/reviews/{id}/reply")
    public ApiResult<Review> reply(@PathVariable long id,
            @Valid @RequestBody ReplyRequest body, AuthPrincipal principal) {
        return ApiResult.success(Review.from(reviews.reply(principal.userId(), id, body.comment())));
    }

    @Operation(summary = "Hide or show a seller review")
    @SecurityRequirement(name = "BearerAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping(ApiPaths.ADMIN + "/reviews/{id}/visibility")
    public ApiResult<Review> visibility(@PathVariable long id,
            @Valid @RequestBody VisibilityRequest body) {
        return ApiResult.success(Review.from(reviews.visibility(id, body.visible())));
    }

    public record CreateRequest(@Min(1) @Max(5) short rating,
            @Size(max = 150) String title, @NotBlank String comment) {
    }

    public record ReplyRequest(@NotBlank String comment) {
    }

    public record VisibilityRequest(@NotNull Boolean visible) {
    }

    public record PublicReviewsView(List<PublicReview> reviews, RatingSummary summary) {
    }
}
