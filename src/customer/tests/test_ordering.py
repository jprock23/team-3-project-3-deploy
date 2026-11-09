"""Use a disposable database only: CUSTOMER_TEST_DATABASE_URL=... pytest src/customer/tests."""

import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from authlib.integrations.base_client.errors import OAuthError

from src.customer import db
from src.customer.app import create_app


@pytest.fixture
def database(monkeypatch):
    dsn = os.environ.get('CUSTOMER_TEST_DATABASE_URL')
    if not dsn:
        pytest.skip('Set CUSTOMER_TEST_DATABASE_URL to an empty, disposable PostgreSQL database.')
    schema = 'customer_test_' + uuid4().hex
    with psycopg.connect(dsn, autocommit=True) as conn:
        conn.execute(f'CREATE SCHEMA {schema}')
    real_connect = psycopg.connect

    def isolated_connection():
        return real_connect(dsn, options=f'-c search_path={schema}', row_factory=db.dict_row)

    monkeypatch.setattr(db, 'connect', isolated_connection)
    with db.connect() as conn:
        conn.execute('''
            CREATE TABLE MenuItem (MenuItemID INTEGER PRIMARY KEY, MenuItemName TEXT NOT NULL,
                Price NUMERIC(10,2) NOT NULL, Category TEXT NOT NULL,
                EntreeCount INTEGER DEFAULT 0, Active BOOLEAN DEFAULT TRUE);
            CREATE TABLE Inventory (InventoryID INTEGER PRIMARY KEY, ItemName TEXT,
                Quantity NUMERIC NOT NULL CHECK(Quantity>=0), Active BOOLEAN DEFAULT TRUE);
            CREATE TABLE MenuItemInventory (MenuItemID INTEGER REFERENCES MenuItem,
                InventoryID INTEGER REFERENCES Inventory, QuantityRequired NUMERIC);
            CREATE TABLE Orders (OrderID INTEGER PRIMARY KEY, OrderDate DATE, OrderTime TIME, Total NUMERIC);
            CREATE TABLE OrderItem (OrderItemID INTEGER PRIMARY KEY, OrderID INTEGER REFERENCES Orders,
                MenuItemID INTEGER REFERENCES MenuItem, Qty INTEGER);
            CREATE TABLE OrderItemSelection (OrderItemID INTEGER REFERENCES OrderItem,
                MenuItemID INTEGER REFERENCES MenuItem, Qty INTEGER, PRIMARY KEY(OrderItemID,MenuItemID));
            INSERT INTO MenuItem VALUES
                (1,'PLATE',11,'Meals',2,TRUE), (2,'FRIED RICE',5,'Sides',0,TRUE),
                (3,'ORANGE CHICKEN',6.5,'Entrees',0,TRUE),
                (4,'MANGO GUAVA TEA',3.49,'Drinks',0,TRUE),
                (5,'MUSHROOM CHICKEN',6.5,'Entrees',0,FALSE),
                (6,'UNMAPPED',1,'Extras',0,TRUE),
                (7,'HOT & SOUR SOUP - CUP',3.5,'Sides',0,TRUE);
            INSERT INTO Inventory VALUES (1,'Containers',10,TRUE), (2,'Rice',10,TRUE),
                (3,'Chicken',10,TRUE), (4,'Tea',10,TRUE);
            INSERT INTO MenuItemInventory VALUES (1,1,1),(2,2,1),(3,3,1),(4,4,1),(7,2,1);
        ''')
    with db.connect() as conn:
        # Exercise the real migration, including reapplication.
        migration = (Path(__file__).parents[1] / 'migrations/001_customer_ordering.sql').read_text()
        conn.execute(migration)
        conn.execute(migration)
    yield
    with real_connect(dsn, autocommit=True) as conn:
        conn.execute(f'DROP SCHEMA {schema} CASCADE')


@pytest.fixture
def client(database):
    app = create_app({'TESTING': True, 'SECRET_KEY': 'testing-only',
                      'GOOGLE_CLIENT_ID': 'test-client', 'GOOGLE_CLIENT_SECRET': 'test-secret'})
    return app.test_client()


def post(client, path, body):
    csrf = client.get('/api/session').json['csrf']
    return client.post(path, json=body, headers={'X-CSRF-Token': csrf})


def plate(quantity=1):
    return [{'id': 1, 'quantity': quantity, 'selections': [2, 3, 3]}]


def counts():
    with db.connect() as conn:
        orders = conn.execute('SELECT COUNT(*) AS count FROM Orders').fetchone()['count']
        chicken = conn.execute('SELECT Quantity FROM Inventory WHERE InventoryID=3').fetchone()['quantity']
        return orders, chicken


def test_photo_menu_and_guest_session(client):
    assert client.get('/').status_code == 200
    menu = client.get('/api/menu').json['items']
    assert next(i for i in menu if i['id'] == 4)['image'] == 'MANGO GUAVA TEA.png'
    assert not next(i for i in menu if i['id'] == 6)['available']
    assert client.get('/images/MANGO%20GUAVA%20TEA.png').status_code == 200
    assert client.get('/images/../../.env.example').status_code == 404
    assert client.post('/auth/guest', json={}).status_code == 403
    assert post(client, '/auth/guest', {}).status_code == 200
    assert client.get('/api/session').json['guest']


def test_legacy_food_categories_are_corrected(client):
    with db.connect() as conn:
        conn.execute('''INSERT INTO MenuItem VALUES
            (8,'BLACK PEPPER SIRLOIN STEAK',6.5,'Drinks',0,TRUE),
            (9,'STEAMED GINGER FISH',6.5,'Drinks',0,TRUE),
            (10,'BLACK TEA - SMALL',2.49,'Drinks',0,TRUE)''')
    menu = {item['id']: item for item in client.get('/api/menu').json['items']}
    assert menu[8]['category'] == 'Entrees'
    assert menu[9]['category'] == 'Entrees'
    assert menu[10]['category'] == 'Drinks'
    assert menu[4]['category'] == 'Drinks'


def test_checkout_persists_components_stock_and_retries(client):
    post(client, '/auth/guest', {})
    quote = post(client, '/api/quote', {'cart': plate(2)}).json
    assert quote['subtotal'] == '22.00'
    assert quote['tax'] == '1.82'
    payload = {'cart': plate(2), 'expected_total': quote['total'], 'checkout_key': str(uuid4())}
    response = post(client, '/api/orders', payload)
    assert response.status_code == 201
    assert response.json['total'] == '23.82'
    assert counts() == (1, 6)
    retry = post(client, '/api/orders', payload)
    assert retry.json == response.json
    assert counts() == (1, 6)
    with db.connect() as conn:
        assert conn.execute('SELECT Qty FROM OrderItemSelection WHERE MenuItemID=3').fetchone()['qty'] == 2
        assert conn.execute('SELECT customer_id FROM customer_web_order').fetchone()['customer_id'] is None


@pytest.mark.parametrize('cart', [[], plate(0), plate(21),
    [{'id': True, 'quantity': 1}], [{'id': 1, 'quantity': 1, 'selections': [2, 3]}],
    [{'id': 1, 'quantity': 1, 'selections': [3, 3, 3]}],
    [{'id': 1, 'quantity': 1, 'selections': [7, 3, 3]}],
    [{'id': 5, 'quantity': 1}], [{'id': 6, 'quantity': 1}], plate(6)])
def test_invalid_or_unavailable_orders_cannot_change_stock(client, cart):
    post(client, '/auth/guest', {})
    result = post(client, '/api/orders', {'cart': cart, 'expected_total': '0.00', 'checkout_key': str(uuid4())})
    assert result.status_code == 409
    assert counts() == (0, 10)


def test_login_required_and_price_tampering(client):
    payload = {'cart': plate(), 'expected_total': '0.01', 'checkout_key': str(uuid4())}
    assert post(client, '/api/orders', payload).status_code == 401
    post(client, '/auth/guest', {})
    assert post(client, '/api/orders', payload).status_code == 409
    assert counts() == (0, 10)


def test_account_identity_and_order_association(client):
    customer_id = db.google_customer('verified-google-subject', 'A Customer')
    assert db.google_customer('verified-google-subject', 'Changed name') == customer_id
    assert db.google_customer('another-google-subject', 'A Customer') != customer_id
    with client.session_transaction() as session:
        session['customer_id'] = customer_id
    assert client.get('/api/session').json['customer']['full_name'] == 'A Customer'
    quote = post(client, '/api/quote', {'cart': plate()}).json
    result = post(client, '/api/orders', {'cart': plate(), 'expected_total': quote['total'], 'checkout_key': str(uuid4())})
    assert result.status_code == 201
    with db.connect() as conn:
        assert conn.execute('SELECT customer_id FROM customer_web_order').fetchone()['customer_id'] == customer_id
    assert post(client, '/auth/logout', {}).status_code == 200
    assert client.get('/api/session').json['customer'] is None


def test_google_callback_and_failure(client, monkeypatch):
    google = client.application.extensions['authlib.integrations.flask_client'].google
    monkeypatch.setattr(google, 'authorize_access_token', lambda: {'userinfo': {
        'sub': 'google-sub', 'name': 'Google Customer', 'email_verified': True}})
    assert client.get('/auth/google/callback').status_code == 302
    assert client.get('/api/session').json['customer']['full_name'] == 'Google Customer'
    post(client, '/auth/logout', {})
    monkeypatch.setattr(google, 'authorize_access_token', lambda: {'userinfo': {
        'sub': 'unverified-sub', 'email_verified': False}})
    assert 'auth_error=failed' in client.get('/auth/google/callback').location
    assert client.get('/api/session').json['customer'] is None
    def fail():
        raise OAuthError('invalid_state')
    monkeypatch.setattr(google, 'authorize_access_token', fail)
    assert 'auth_error=failed' in client.get('/auth/google/callback').location


def test_stock_changes_after_quote_roll_back(client):
    post(client, '/auth/guest', {})
    quote = post(client, '/api/quote', {'cart': plate(2)}).json
    with db.connect() as conn:
        conn.execute('UPDATE Inventory SET Quantity=3 WHERE InventoryID=3')
    result = post(client, '/api/orders', {'cart': plate(2), 'expected_total': quote['total'], 'checkout_key': str(uuid4())})
    assert result.status_code == 409
    assert counts() == (0, 3)


def test_concurrent_checkouts_cannot_oversell(database):
    with db.connect() as conn:
        conn.execute('UPDATE Inventory SET Quantity=2 WHERE InventoryID=3')
    def submit():
        try:
            return db.checkout(plate(), None, uuid4(), uuid4(), '11.91')
        except db.OrderError:
            return None
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: submit(), range(2)))
    assert sum(result is not None for result in results) == 1
    assert counts() == (1, 0)


def test_receipt_cannot_be_retrieved_by_another_browser(client):
    post(client, '/auth/guest', {})
    payload = {'cart': plate(), 'expected_total': '11.91', 'checkout_key': str(uuid4())}
    assert post(client, '/api/orders', payload).status_code == 201
    stranger = client.application.test_client()
    post(stranger, '/auth/guest', {})
    assert post(stranger, '/api/orders', payload).status_code == 409
    assert counts() == (1, 8)


def test_failure_after_inventory_deduction_rolls_back_everything(client):
    post(client, '/auth/guest', {})
    with db.connect() as conn:
        conn.execute('''CREATE FUNCTION reject_web_order() RETURNS trigger LANGUAGE plpgsql AS
            $$ BEGIN RAISE EXCEPTION 'Simulated write failure'; END $$;
            CREATE TRIGGER fail_receipt BEFORE INSERT ON customer_web_order
            FOR EACH ROW EXECUTE FUNCTION reject_web_order();''')
    result = post(client, '/api/orders', {
        'cart': plate(), 'expected_total': '11.91', 'checkout_key': str(uuid4())})
    assert result.status_code == 503
    assert counts() == (0, 10)
    with db.connect() as conn:
        assert conn.execute('SELECT COUNT(*) AS count FROM OrderItem').fetchone()['count'] == 0
        assert conn.execute('SELECT COUNT(*) AS count FROM OrderItemSelection').fetchone()['count'] == 0


def test_concurrent_retry_creates_only_one_order(database):
    checkout_key, browser_key = uuid4(), uuid4()
    def submit():
        return db.checkout(plate(), None, checkout_key, browser_key, '11.91')
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: submit(), range(2)))
    assert results[0] == results[1]
    assert counts() == (1, 8)


def test_database_outage_does_not_expose_credentials(client, monkeypatch):
    def unavailable():
        raise psycopg.OperationalError('secret connection details')
    monkeypatch.setattr(db, 'connect', unavailable)
    response = client.get('/api/menu')
    assert response.status_code == 503
    assert 'secret' not in response.get_data(as_text=True)


def test_history_requires_account(client):
    assert client.get('/api/orders').status_code == 401
    assert post(client, '/api/orders/1/reorder', {}).status_code == 401
    post(client, '/auth/guest', {})
    assert client.get('/api/orders').status_code == 401
    assert post(client, '/api/orders/1/reorder', {}).status_code == 401


def test_recent_history_is_private_and_reorder_uses_current_menu(client):
    owner = db.google_customer('history-owner', 'Owner')
    other = db.google_customer('history-other', 'Other')
    old = db.checkout(plate(), owner, uuid4(), uuid4(), '11.91')
    recent = db.checkout(plate(2), owner, uuid4(), uuid4(), '23.82')
    foreign = db.checkout(plate(), other, uuid4(), uuid4(), '11.91')
    guest = db.checkout([{'id': 4, 'quantity': 1, 'selections': []}], None,
                        uuid4(), uuid4(), '3.78')
    with db.connect() as conn:
        conn.execute("UPDATE Orders SET OrderDate=CURRENT_DATE - INTERVAL '1 month' - INTERVAL '1 day' WHERE OrderID=%s", (old['order_id'],))
    with client.session_transaction() as session:
        session['customer_id'] = owner
    orders = client.get('/api/orders').json['orders']
    assert [order['order_id'] for order in orders] == [recent['order_id']]
    assert orders[0]['receipt'] == recent
    for excluded in [old, foreign, guest]:
        assert post(client, f"/api/orders/{excluded['order_id']}/reorder", {}).status_code == 404
    with db.connect() as conn:
        conn.execute('UPDATE Inventory SET Quantity=20')
        conn.execute('UPDATE MenuItem SET Price=12 WHERE MenuItemID=1')
    before = counts()
    result = post(client, f"/api/orders/{recent['order_id']}/reorder", {})
    assert result.status_code == 200
    assert result.json['cart'] == plate(2)
    assert result.json['quote']['total'] == '25.98'
    assert counts() == before  # Reorder prepares a cart; it never places an order.
    with db.connect() as conn:
        conn.execute('UPDATE MenuItem SET Active=FALSE WHERE MenuItemID=3')
    assert post(client, f"/api/orders/{recent['order_id']}/reorder", {}).status_code == 409
    assert counts() == before
    with client.session_transaction() as session:
        session['customer_id'] = other
    assert post(client, f"/api/orders/{recent['order_id']}/reorder", {}).status_code == 404


def test_history_empty_and_newest_first(client):
    customer = db.google_customer('history-sort', 'Customer')
    with client.session_transaction() as session:
        session['customer_id'] = customer
    assert client.get('/api/orders').json == {'orders': []}
    first = db.checkout(plate(), customer, uuid4(), uuid4(), '11.91')
    second = db.checkout(plate(), customer, uuid4(), uuid4(), '11.91')
    with db.connect() as conn:
        conn.execute("UPDATE Orders SET OrderDate=CURRENT_DATE - 1 WHERE OrderID=%s", (first['order_id'],))
    assert [o['order_id'] for o in client.get('/api/orders').json['orders']] == [second['order_id'], first['order_id']]
