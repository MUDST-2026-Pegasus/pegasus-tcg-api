package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.CollectionItemRequest;
import com.pegasus.pegasustcgapi.dto.CollectionItemResponse;
import com.pegasus.pegasustcgapi.dto.CollectionSummaryResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CollectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own collection [RQ-3, RQ-9]. Any account has one — buying
 * and selling are not required to keep cards.
 *
 * <p>Everything is scoped to the caller, so an id belonging to someone else reads
 * as not found rather than forbidden.
 */
@RestController
@RequestMapping(ApiPaths.COLLECTION)
public class CollectionController {

    private final CollectionService collection;

    public CollectionController(CollectionService collection) {
        this.collection = collection;
    }

    /** Newest first. {@code publicItem} narrows to the cards shown, or the ones kept private. */
    @GetMapping
    public ApiResponse<PageResponse<CollectionItemResponse>> list(
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) Boolean publicItem,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {

        return ApiResponse.success(
                collection.mine(principal.userId(), gameId, variantId, publicItem, page, size));
    }

    @GetMapping("/summary")
    public ApiResponse<CollectionSummaryResponse> summary(AuthPrincipal principal) {
        return ApiResponse.success(collection.summary(principal.userId()));
    }

    @GetMapping("/{itemId}")
    public ApiResponse<CollectionItemResponse> get(@PathVariable long itemId, AuthPrincipal principal) {
        return ApiResponse.success(collection.get(principal.userId(), itemId));
    }

    /** Adding the same printing again makes a new row, each with its own price and note. */
    @PostMapping
    public ResponseEntity<ApiResponse<CollectionItemResponse>> add(
            @Valid @RequestBody CollectionItemRequest request, AuthPrincipal principal) {

        CollectionItemResponse created = collection.add(principal.userId(), request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Card added to collection", created));
    }

    @PutMapping("/{itemId}")
    public ApiResponse<CollectionItemResponse> update(
            @PathVariable long itemId,
            @Valid @RequestBody CollectionItemRequest request,
            AuthPrincipal principal) {

        return ApiResponse.success("Collection card updated",
                collection.update(principal.userId(), itemId, request.toFields()));
    }

    /** Gone from the collection; the row is kept, see {@link CollectionService#remove}. */
    @DeleteMapping("/{itemId}")
    public ApiResponse<Void> remove(@PathVariable long itemId, AuthPrincipal principal) {
        collection.remove(principal.userId(), itemId);
        return ApiResponse.success("Card removed from collection", null);
    }
}
