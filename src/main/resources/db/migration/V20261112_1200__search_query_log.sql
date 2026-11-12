-- TCG-774: what people search, for "trending searches". No user id and no raw IP:
-- session_hash is a keyed hash of address + user agent + day, so it cannot be joined across days.
CREATE TABLE search_query_log (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    q_norm        varchar(60)  NOT NULL,
    result_count  bigint       NOT NULL,
    corrected_to  varchar(60),
    session_hash  char(64)     NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX ix_search_query_log_created_at ON search_query_log (created_at);
