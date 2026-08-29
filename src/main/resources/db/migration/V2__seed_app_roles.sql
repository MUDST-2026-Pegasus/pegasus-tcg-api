-- Reference data. Codes are part of the API contract (they appear in the JWT
-- "roles" claim), so they are seeded rather than created at runtime.
INSERT INTO app_role (id, code, name, description) VALUES
    (1, 'BUYER',   'Buyer',         'Browse listings, place and track orders.'),
    (2, 'SELLER',  'Seller',        'Publish listings and manage sales from the same profile.'),
    (3, 'ADMIN',   'Administrator', 'Full access, including account moderation and role grants.'),
    (4, 'SUPPORT', 'Support',       'Read-only access for handling customer enquiries.');

SELECT setval(pg_get_serial_sequence('app_role', 'id'), (SELECT max(id) FROM app_role));
