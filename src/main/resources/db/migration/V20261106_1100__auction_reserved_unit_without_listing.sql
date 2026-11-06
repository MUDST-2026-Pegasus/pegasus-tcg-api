-- A card in an open auction is on no listing (ck_listing_unit_on_auction_unlisted).
-- Auction checkout reserves that exact card for the winner's order, so a RESERVED
-- card may now have no listing. LISTED still requires one. Releasing such a card
-- sends it back to IN_STOCK, as ListingUnitRepository.release already does for any
-- held card whose listing is gone.
ALTER TABLE listing_unit DROP CONSTRAINT ck_listing_unit_listed_has_listing;
ALTER TABLE listing_unit ADD CONSTRAINT ck_listing_unit_listed_has_listing CHECK (
    status <> 'LISTED' OR listing_id IS NOT NULL);
