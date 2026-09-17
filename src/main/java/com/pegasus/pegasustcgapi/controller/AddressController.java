package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.AddressRequest;
import com.pegasus.pegasustcgapi.model.Address;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.AddressService;
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
@RestController
@RequestMapping(ApiPaths.ADDRESSES)
public class AddressController {

    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    public ApiResult<List<Address>> list(AuthPrincipal principal) {
        return ApiResult.success(addresses.list(principal.userId()));
    }

    @GetMapping("/{addressId}")
    public ApiResult<Address> get(@PathVariable long addressId, AuthPrincipal principal) {
        return ApiResult.success(addresses.get(principal.userId(), addressId));
    }

    @PostMapping
    public ResponseEntity<ApiResult<Address>> create(
            @Valid @RequestBody AddressRequest request, AuthPrincipal principal) {

        Address created = addresses.create(principal.userId(), request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Address added", created));
    }

    @PutMapping("/{addressId}")
    public ApiResult<Address> update(
            @PathVariable long addressId,
            @Valid @RequestBody AddressRequest request,
            AuthPrincipal principal) {

        return ApiResult.success(
                "Address updated", addresses.update(principal.userId(), addressId, request.toFields()));
    }

    /** Removed from the book, kept in the database: past orders still point here. */
    @DeleteMapping("/{addressId}")
    public ApiResult<Void> delete(@PathVariable long addressId, AuthPrincipal principal) {
        addresses.delete(principal.userId(), addressId);
        return ApiResult.success("Address removed", null);
    }
}
