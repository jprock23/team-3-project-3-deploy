# Customer ordering

The customer self-ordering part of the combined Flask web app (see the root README; cashier and manager pages live in `src/staff`), using the existing PostgreSQL POS tables and the photos in `pandaimages/`. Run commands below from the repository root.

## Start locally

```bash
python3 -m venv .venv
.venv/bin/pip install -r src/customer/requirements.txt
cp .env.example .env  # only if you do not already have .env
```

Fill in the existing **dev** database settings in `.env`. The app accepts the existing `DB_URL` (including the `jdbc:` prefix), `DB_USER`, and `DB_PASSWORD`, or a standard PostgreSQL `DATABASE_URL`. Set `CUSTOMER_SECRET_KEY` to a persistent random value generated with:

```bash
python3 -c "import secrets; print(secrets.token_hex(32))"
```

Apply `src/customer/migrations/001_customer_ordering.sql` using the dev database SQL editor, or `psql` with your dev connection. The migration adds Google identity and customer order association tables. It preserves existing customer rows. The original POS tables must already exist, including `MenuItem.EntreeCount`, `MenuItemInventory`, `Inventory.Active`, and `OrderItemSelection` from the existing meal ordering implementation. The app never creates or modifies schema during a request.

```bash
.venv/bin/flask --app src.app run
```

Open **http://127.0.0.1:5000**. Guests can order without Google credentials. Database configuration and the migration are required for both guests and signed-in orders. The page reports database outages; it does not silently substitute sample data.

## Google login

Create a Google OAuth web application with the `openid`, `email`, and `profile` scopes. Set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `.env`, and authorize this exact redirect URI:

```text
http://127.0.0.1:5000/auth/google/callback
```

Set `GOOGLE_REDIRECT_URI` to the same URI. In deployment, use your HTTPS origin, set `CUSTOMER_SECURE_COOKIE=true`, and keep the secret consistent across server instances. The login uses Authlib's OpenID Connect flow, including state/nonce verification and PKCE. No Google tokens or credentials are sent to the browser application.

Accounts are linked by verified Google subject, never by matching a name or phone number. First login creates a customer row with an empty phone number; this app does not collect phone numbers. Existing sample customer rows have no identity proof and are not automatically claimed. Points are displayed but earning/redemption rules are outside this interface.

## Previous orders

Signed-in customers can open **Previous orders** to see their account's web orders from the rolling past calendar month, newest first. Guest orders and other customers' orders are excluded. Each order shows its date, quantities, meal choices, and original total. Reorder restores the order into the cart (with confirmation before replacing an existing cart), validates current availability and prices, and requires normal checkout review. Unavailable orders leave the existing cart untouched. No additional migration is required.

## Ordering behavior

- Active menu items, categories, prices, and meal sizes come from PostgreSQL.
- Photos match database names exactly, with shared photos for drink sizes and cub portions. Missing photos show a placeholder.
- Bowl, Plate, and Bigger Plate use the database's `EntreeCount`: one side and the included entrees. Repeated entree selections are supported. Drink sizes retain separate database IDs and prices.
- The server calculates prices and 8.25% tax, validates combined ingredient demand, and checks the displayed total again at checkout.
- Checkout stores `Orders`, `OrderItem`, and `OrderItemSelection`, deducts inventory, and associates the order with the account (or a null customer for guests) in one transaction. Lock modes match the Java cashier so their manually assigned IDs do not collide.
- Browser checkout request IDs prevent duplicate orders after lost responses. An uncertain request stays pending until retried. The cart survives Google redirects and page reloads when browser storage is available.
- Orders are placed for payment at the counter. No online payment processor is integrated.

The customer app connects directly with Psycopg, as allowed in `docs/DATABASE.md`. It is registered as a blueprint in `src/app.py` alongside the web cashier and manager (`src/staff`); `flask --app src.customer.app run` still works and starts the same combined app. Each connection sets the session timezone to `POS_TIMEZONE` (default `America/Chicago`) so order dates match the Java app.

## Tests

Install `pytest` in the environment and point `CUSTOMER_TEST_DATABASE_URL` at a **disposable local database**, then run:

```bash
.venv/bin/python -m pytest src -q
node --check src/customer/static/customer.js
```

The tests create and drop an isolated schema. They cover guest sessions, mocked Google callbacks, account association, menu photos, invalid meals, stock changes, server pricing, concurrency, retry behavior, and receipt isolation. Without the test database setting, integration tests skip. Actual Google authorization requires configured credentials and a browser.

Authentication integration follows [Authlib's Flask documentation](https://docs.authlib.org/en/latest/oauth2/client/web/flask.html); database transactions follow [Psycopg's transaction documentation](https://www.psycopg.org/psycopg3/docs/basic/transactions.html).
