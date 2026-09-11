package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.Address;
import com.pegasus.pegasustcgapi.repository.AddressRepository;
import com.pegasus.pegasustcgapi.repository.AddressRepository.AddressFields;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The buyer's address book [RQ-11]. Every method is scoped to one user, so an id from another account simply does not resolve. */
@Service
public class AddressService {

    private final AddressRepository addresses;

    public AddressService(AddressRepository addresses) {
        this.addresses = addresses;
    }

    public List<Address> list(long userId) {
        return addresses.findByUserId(userId);
    }

    public Address get(long userId, long addressId) {
        return addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ADDRESS_NOT_FOUND));
    }

    /** The first address a user saves becomes their default, so checkout always has one to offer. */
    @Transactional
    public Address create(long userId, AddressFields fields) {
        boolean first = !addresses.hasAny(userId);
        AddressFields toSave = first
                ? withDefaults(fields, true, true)
                : fields;

        long id = addresses.insert(userId, toSave);
        applyDefaultFlags(userId, id, toSave);
        return get(userId, id);
    }

    @Transactional
    public Address update(long userId, long addressId, AddressFields fields) {
        if (!addresses.update(addressId, userId, fields)) {
            throw new NotFoundException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        applyDefaultFlags(userId, addressId, fields);
        return get(userId, addressId);
    }

    /**
     * Soft delete. Orders point at these rows, and an order also keeps its own
     * snapshot, so removing one here never disturbs a past delivery.
     */
    @Transactional
    public void delete(long userId, long addressId) {
        if (!addresses.softDelete(addressId, userId)) {
            throw new NotFoundException(ErrorCode.ADDRESS_NOT_FOUND);
        }
    }

    /** Marking one address default has to unmark the previous one, or "default" means nothing. */
    private void applyDefaultFlags(long userId, long addressId, AddressFields fields) {
        if (fields.defaultShipping()) {
            addresses.clearDefaultShipping(userId, addressId);
        }
        if (fields.defaultBilling()) {
            addresses.clearDefaultBilling(userId, addressId);
        }
    }

    private static AddressFields withDefaults(AddressFields f, boolean shipping, boolean billing) {
        return new AddressFields(f.label(), f.recipientName(), f.phone(), f.line1(), f.line2(),
                f.subdistrict(), f.district(), f.province(), f.postalCode(), f.countryCode(),
                shipping, billing);
    }
}
