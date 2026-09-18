package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.AddressRequest;
import com.pegasus.pegasustcgapi.model.Address;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's address book [RQ-11]. Everything is scoped to the caller,
 * so an id belonging to someone else reads as not found rather than forbidden.
 */
@Tag(name = "Addresses", description = "User address book management")
@RestController
@RequestMapping(ApiPaths.ADDRESSES)
public class AddressController {

    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @Operation(summary = "List saved addresses", description = "Retrieves all saved delivery addresses for the authenticated user.")
    @ApiResponse(responseCode = "200", description = "Addresses listed")
    @GetMapping
    public ApiResult<List<Address>> list(AuthPrincipal principal) {
        return ApiResult.success(addresses.list(principal.userId()));
    }

    @Operation(summary = "Get address by ID", description = "Retrieves a specific delivery address by ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Address retrieved"),
            @ApiResponse(responseCode = "404", description = "Address not found")
    })
    @GetMapping("/{addressId}")
    public ApiResult<Address> get(@PathVariable long addressId, AuthPrincipal principal) {
        return ApiResult.success(addresses.get(principal.userId(), addressId));
    }

    @Operation(summary = "Create new address", description = "Adds a new delivery address to the user's address book.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Address added successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    @PostMapping
    public ResponseEntity<ApiResult<Address>> create(
            @Valid @RequestBody AddressRequest request, AuthPrincipal principal) {

        Address created = addresses.create(principal.userId(), request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Address added", created));
    }

    @Operation(summary = "Update address", description = "Updates an existing delivery address in the user's address book.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Address updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "404", description = "Address not found")
    })
    @PutMapping("/{addressId}")
    public ApiResult<Address> update(
            @PathVariable long addressId,
            @Valid @RequestBody AddressRequest request,
            AuthPrincipal principal) {

        return ApiResult.success(
                "Address updated", addresses.update(principal.userId(), addressId, request.toFields()));
    }

    /** Removed from the book, kept in the database: past orders still point here. */
    @Operation(summary = "Delete address", description = "Soft-deletes a delivery address from the address book.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Address removed"),
            @ApiResponse(responseCode = "404", description = "Address not found")
    })
    @DeleteMapping("/{addressId}")
    public ApiResult<Void> delete(@PathVariable long addressId, AuthPrincipal principal) {
        addresses.delete(principal.userId(), addressId);
        return ApiResult.success("Address removed", null);
    }
}
