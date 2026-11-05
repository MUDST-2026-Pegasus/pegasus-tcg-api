-- Auctions without the runner-up offer [TCG-614]. When a winner does not pay, the
-- order is cancelled, the winner gets a strike, the auction ends UNSOLD and the card
-- goes straight back to stock. A seller who still wants to sell opens a new auction.
--
-- Status codes match model/AuctionStatus and BidStatus.

-- Auctions still waiting on a seller choice or a runner-up answer end UNSOLD, and
-- their cards go back to stock, before the old statuses are dropped.
UPDATE listing_unit lu
   SET status = 'IN_STOCK'
  FROM auction a
 WHERE a.listing_unit_id = lu.id
   AND a.status IN ('AWAITING_SELLER_DECISION', 'OFFERED_TO_SECOND')
   AND lu.status = 'ON_AUCTION';

UPDATE auction
   SET status     = 'UNSOLD',
       closed_at  = COALESCE(closed_at, now()),
       version    = version + 1,
       updated_at = now()
 WHERE status IN ('AWAITING_SELLER_DECISION', 'OFFERED_TO_SECOND');

DROP TABLE auction_offer;

DROP INDEX ix_auction_status_decision_due;
ALTER TABLE auction DROP CONSTRAINT ck_auction_decision_due;
ALTER TABLE auction DROP COLUMN decision_due_at;

ALTER TABLE auction DROP CONSTRAINT ck_auction_status;
ALTER TABLE auction ADD CONSTRAINT ck_auction_status CHECK (status IN
    ('LIVE', 'AWAITING_PAYMENT', 'SOLD', 'UNSOLD', 'CANCELLED', 'SUSPENDED'));

-- A card can sit in only one open auction at a time.
DROP INDEX uq_auction_open_unit;
CREATE UNIQUE INDEX uq_auction_open_unit ON auction (listing_unit_id)
    WHERE status IN ('LIVE', 'AWAITING_PAYMENT');

COMMENT ON COLUMN auction.winner_id IS
    'Whoever must pay now: the top bidder or a buy-now buyer.';
COMMENT ON COLUMN auction.relisted_from_id IS
    'The auction this one was reopened from, when the seller opens a new auction for the same card.';
