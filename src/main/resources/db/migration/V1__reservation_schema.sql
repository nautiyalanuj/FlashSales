CREATE TABLE sale (
    id integer PRIMARY KEY CHECK (id = 1),
    mode varchar(20) NOT NULL CHECK (mode IN ('SQL', 'REDIS_SQL')),
    initial_stock integer NOT NULL CHECK (initial_stock > 0)
);
CREATE TABLE reservation (
    id uuid PRIMARY KEY,
    customer varchar(100) NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    state varchar(30) NOT NULL CHECK (state IN ('PENDING','CLAIMED','RELEASED','EXPIRED','REFUND_REQUIRED')),
    expires_at timestamptz NOT NULL,
    payment_id uuid UNIQUE,
    payment_outcome varchar(10) CHECK (payment_outcome IN ('SUCCESS','FAILURE')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX reservation_expiry ON reservation(expires_at) WHERE state = 'PENDING';
CREATE TABLE inventory_unit (
    sale_id integer NOT NULL REFERENCES sale(id),
    unit_id integer NOT NULL,
    reservation_id uuid REFERENCES reservation(id),
    sold boolean NOT NULL DEFAULT false,
    PRIMARY KEY (sale_id, unit_id),
    CHECK (NOT sold OR reservation_id IS NOT NULL)
);
CREATE INDEX available_units ON inventory_unit(sale_id,unit_id) WHERE reservation_id IS NULL AND NOT sold;
CREATE TABLE redis_outbox (
    reservation_id uuid PRIMARY KEY REFERENCES reservation(id),
    action varchar(10) NOT NULL CHECK (action IN ('CLAIM','RELEASE')),
    delivered boolean NOT NULL DEFAULT false
);
