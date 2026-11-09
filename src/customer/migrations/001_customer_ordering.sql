-- Apply to dev first. Existing customer rows are never linked by name or phone.
BEGIN;
CREATE TABLE IF NOT EXISTS customer (
    customer_id INTEGER PRIMARY KEY,
    phone VARCHAR(20) NOT NULL,
    points INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
    full_name VARCHAR(150) NOT NULL
);
CREATE TABLE IF NOT EXISTS customer_google_identity (
    google_subject TEXT PRIMARY KEY,
    customer_id INTEGER NOT NULL UNIQUE REFERENCES customer(customer_id)
);
CREATE TABLE IF NOT EXISTS customer_web_order (
    order_id INTEGER PRIMARY KEY REFERENCES Orders(OrderID),
    customer_id INTEGER REFERENCES customer(customer_id),
    checkout_key UUID NOT NULL UNIQUE,
    browser_key UUID NOT NULL,
    receipt JSONB NOT NULL
);
COMMIT;
