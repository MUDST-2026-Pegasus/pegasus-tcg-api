package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.InventoryMovementResponse;
import com.pegasus.pegasustcgapi.dto.ListingUnitResponse;
import com.pegasus.pegasustcgapi.dto.StockInRequest;
import com.pegasus.pegasustcgapi.dto.UnitMoveRequest;
import com.pegasus.pegasustcgapi.dto.UnitUpdateRequest;
import com.pegasus.pegasustcgapi.dto.UnitWriteOffRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.MovementType;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.InventoryService;
import com.pegasus.pegasustcgapi.service.ListingUnitService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in seller's stock as physical cards, wherever they sit — on a
 * listing or in hand — and the ledger of every card in and out.
 */
@RestController
@RequestMapping(ApiPaths.SELLERS_ME)
public class SellerInventoryController {

    private final ListingUnitService units;
    private final InventoryService inventory;

    public SellerInventoryController(ListingUnitService units, InventoryService inventory) {
        this.units = units;
        this.inventory = inventory;
    }

    /** Every card; {@code status=IN_STOCK} is the ones in hand and on no listing. */
    @GetMapping("/units")
    public ApiResponse<PageResponse<ListingUnitResponse>> list(
            @RequestParam(required = false) Long listingId,
            @RequestParam(required = false) ListingUnitStatus status,
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) CardCondition condition,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {

        return ApiResponse.success(units.mine(principal.userId(), listingId, status, variantId, condition, page, size));
    }

    /** Cards coming in — kept in hand, or straight onto a listing when one is named. */
    @PostMapping("/units")
    public ResponseEntity<ApiResponse<List<ListingUnitResponse>>> stockIn(
            @Valid @RequestBody StockInRequest request, AuthPrincipal principal) {

        List<ListingUnitResponse> created = units.stockIn(principal.userId(), request.toStockIn());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created.size() + " card(s) stocked", created));
    }

    @GetMapping("/units/{unitUid}")
    public ApiResponse<ListingUnitResponse> get(@PathVariable UUID unitUid, AuthPrincipal principal) {
        return ApiResponse.success(units.get(principal.userId(), unitUid));
    }

    @PutMapping("/units/{unitUid}")
    public ApiResponse<ListingUnitResponse> update(
            @PathVariable UUID unitUid, @Valid @RequestBody UnitUpdateRequest request, AuthPrincipal principal) {

        return ApiResponse.success("Card updated", units.updateNotes(principal.userId(), null, unitUid, request.toNotes()));
    }

    /**
     * Onto another listing of the same card, condition and seller, or back into
     * hand with {@code listingId} null. The UUID, cost and history go with it.
     */
    @PostMapping("/units/{unitUid}/move")
    public ApiResponse<ListingUnitResponse> move(
            @PathVariable UUID unitUid, @Valid @RequestBody UnitMoveRequest request, AuthPrincipal principal) {

        ListingUnitResponse moved = units.move(principal.userId(), unitUid, request.listingId());
        String message = moved.listingId() == null ? "Card is in hand" : "Card is on listing #" + moved.listingId();
        return ApiResponse.success(message, moved);
    }

    /** Lost or damaged; out of stock for good, booked as a LOSS. */
    @PostMapping("/units/{unitUid}/write-off")
    public ApiResponse<ListingUnitResponse> writeOff(
            @PathVariable UUID unitUid,
            @Valid @RequestBody(required = false) UnitWriteOffRequest request,
            AuthPrincipal principal) {

        String reason = request == null ? null : request.reasonOrNull();
        return ApiResponse.success("Card written off", units.writeOff(principal.userId(), unitUid, reason));
    }

    /** Newest first. {@code unitUid} gives one card's whole history. */
    @GetMapping("/inventory/movements")
    public ApiResponse<PageResponse<InventoryMovementResponse>> movements(
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) CardCondition condition,
            @RequestParam(required = false) MovementType type,
            @RequestParam(required = false) Long listingId,
            @RequestParam(required = false) UUID unitUid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {

        return ApiResponse.success(inventory.movements(
                principal.userId(), variantId, condition, type, listingId, unitUid, page, size));
    }
}
