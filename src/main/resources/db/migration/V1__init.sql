-- Schema for ts-ticketing-system (BRD section 10).
-- The constraints here, not application code, make double-sells and duplicate
-- reservations impossible.
--
-- Identifier columns use utf8mb4_bin so comparison and ORDER BY are byte-wise and
-- case-sensitive: "a1" and "A1" are different seats, and the lock order used for
-- multi-seat requests (ORDER BY seat_label) matches a plain sort in Java.

CREATE TABLE shows (
    id             CHAR(36)     NOT NULL,
    name           VARCHAR(100) NOT NULL,
    price_paise    BIGINT       NOT NULL,
    per_user_limit INT          NOT NULL DEFAULT 4,
    total_seats    INT          NOT NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_shows_price_paise CHECK (price_paise >= 0),        -- BR-13
    CONSTRAINT chk_shows_per_user_limit CHECK (per_user_limit >= 1),
    CONSTRAINT chk_shows_total_seats CHECK (total_seats >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- One row per seat, one status column per seat: a seat is always in exactly one
-- state, so available + held + confirmed == total_seats holds structurally (BR-12).
CREATE TABLE seats (
    show_id        CHAR(36)                                 NOT NULL,
    seat_label     VARCHAR(8) COLLATE utf8mb4_bin           NOT NULL,
    status         ENUM ('available', 'held', 'confirmed')  NOT NULL DEFAULT 'available',
    holder_user_id VARCHAR(64) COLLATE utf8mb4_bin          NULL,
    reservation_id CHAR(36)                                 NULL,
    held_until     DATETIME(6)                              NULL,
    PRIMARY KEY (show_id, seat_label),                                 -- BR-01
    KEY idx_seats_reservation_id (reservation_id),                     -- BR-10
    CONSTRAINT fk_seats_show FOREIGN KEY (show_id) REFERENCES shows (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Declined requests are never stored: their transaction rolls back.
CREATE TABLE reservations (
    id              CHAR(36)                          NOT NULL,
    show_id         CHAR(36)                          NOT NULL,
    user_id         VARCHAR(64) COLLATE utf8mb4_bin   NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin  NOT NULL,
    request_hash    CHAR(64)                          NOT NULL,
    seats           JSON                              NOT NULL,
    amount_paise    BIGINT                            NOT NULL,
    status          ENUM ('confirmed', 'cancelled')   NOT NULL,
    created_at      DATETIME(6)                       NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservations_user_key (user_id, idempotency_key),   -- BR-05, BR-06
    KEY idx_reservations_show_id (show_id),
    CONSTRAINT chk_reservations_amount_paise CHECK (amount_paise >= 0),
    CONSTRAINT fk_reservations_show FOREIGN KEY (show_id) REFERENCES shows (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Seats each user currently holds per show; guarded updates keep it within the limit.
CREATE TABLE user_show_counts (
    show_id CHAR(36)                         NOT NULL,
    user_id VARCHAR(64) COLLATE utf8mb4_bin  NOT NULL,
    held    INT                              NOT NULL DEFAULT 0,
    PRIMARY KEY (show_id, user_id),
    CONSTRAINT chk_user_show_counts_held CHECK (held >= 0),           -- BR-03
    CONSTRAINT fk_user_show_counts_show FOREIGN KEY (show_id) REFERENCES shows (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
