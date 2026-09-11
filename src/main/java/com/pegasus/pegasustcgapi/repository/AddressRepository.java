package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Address.ADDRESS;

import com.pegasus.pegasustcgapi.jooq.tables.records.AddressRecord;
import com.pegasus.pegasustcgapi.model.Address;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code address}. Deletes are soft, because orders reference
 * these rows and a removed address must not take a delivery record with it.
 */
@Repository
public class AddressRepository {

    private final DSLContext dsl;

    public AddressRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<Address> findByUserId(long userId) {
        return dsl.selectFrom(ADDRESS)
                .where(ADDRESS.USER_ID.eq(userId))
                .and(ADDRESS.DELETED_AT.isNull())
                .orderBy(ADDRESS.IS_DEFAULT_SHIPPING.desc(), ADDRESS.ID.asc())
                .fetch(AddressRepository::toAddress);
    }

    public Optional<Address> findByIdAndUserId(long id, long userId) {
        return dsl.selectFrom(ADDRESS)
                .where(ADDRESS.ID.eq(id))
                .and(ADDRESS.USER_ID.eq(userId))
                .and(ADDRESS.DELETED_AT.isNull())
                .fetchOptional()
                .map(AddressRepository::toAddress);
    }

    public long insert(long userId, AddressFields fields) {
        return dsl.insertInto(ADDRESS)
                .set(ADDRESS.USER_ID, userId)
                .set(apply(dsl.newRecord(ADDRESS), fields))
                .returningResult(ADDRESS.ID)
                .fetchSingle(ADDRESS.ID);
    }

    /** @return true when a row belonging to this user was actually updated. */
    public boolean update(long id, long userId, AddressFields fields) {
        return dsl.update(ADDRESS)
                .set(apply(dsl.newRecord(ADDRESS), fields))
                .where(ADDRESS.ID.eq(id))
                .and(ADDRESS.USER_ID.eq(userId))
                .and(ADDRESS.DELETED_AT.isNull())
                .execute() > 0;
    }

    public boolean softDelete(long id, long userId) {
        return dsl.update(ADDRESS)
                .set(ADDRESS.DELETED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(ADDRESS.ID.eq(id))
                .and(ADDRESS.USER_ID.eq(userId))
                .and(ADDRESS.DELETED_AT.isNull())
                .execute() > 0;
    }

    /**
     * Clears the default flag on every other address of this user, so "default"
     * stays a single row rather than whichever one was written last.
     */
    public void clearDefaultShipping(long userId, long exceptId) {
        dsl.update(ADDRESS)
                .set(ADDRESS.IS_DEFAULT_SHIPPING, false)
                .where(ADDRESS.USER_ID.eq(userId))
                .and(ADDRESS.ID.ne(exceptId))
                .and(ADDRESS.IS_DEFAULT_SHIPPING.isTrue())
                .execute();
    }

    public void clearDefaultBilling(long userId, long exceptId) {
        dsl.update(ADDRESS)
                .set(ADDRESS.IS_DEFAULT_BILLING, false)
                .where(ADDRESS.USER_ID.eq(userId))
                .and(ADDRESS.ID.ne(exceptId))
                .and(ADDRESS.IS_DEFAULT_BILLING.isTrue())
                .execute();
    }

    public boolean hasAny(long userId) {
        return dsl.fetchExists(dsl.selectOne().from(ADDRESS)
                .where(ADDRESS.USER_ID.eq(userId))
                .and(ADDRESS.DELETED_AT.isNull()));
    }

    private static AddressRecord apply(AddressRecord record, AddressFields f) {
        record.setLabel(f.label());
        record.setRecipientName(f.recipientName());
        record.setPhone(f.phone());
        record.setLine1(f.line1());
        record.setLine2(f.line2());
        record.setSubdistrict(f.subdistrict());
        record.setDistrict(f.district());
        record.setProvince(f.province());
        record.setPostalCode(f.postalCode());
        record.setCountryCode(f.countryCode());
        record.setIsDefaultShipping(f.defaultShipping());
        record.setIsDefaultBilling(f.defaultBilling());
        return record;
    }

    private static Address toAddress(AddressRecord r) {
        return new Address(
                r.getId(),
                r.getUserId(),
                r.getLabel(),
                r.getRecipientName(),
                r.getPhone(),
                r.getLine1(),
                r.getLine2(),
                r.getSubdistrict(),
                r.getDistrict(),
                r.getProvince(),
                r.getPostalCode(),
                r.getCountryCode(),
                r.getIsDefaultShipping(),
                r.getIsDefaultBilling(),
                r.getCreatedAt());
    }

    /** The writable columns, shared by insert and update so the two cannot drift. */
    public record AddressFields(
            String label,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String subdistrict,
            String district,
            String province,
            String postalCode,
            String countryCode,
            boolean defaultShipping,
            boolean defaultBilling) {
    }
}
