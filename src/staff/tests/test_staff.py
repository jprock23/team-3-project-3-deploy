"""Use a disposable database only: CUSTOMER_TEST_DATABASE_URL=... pytest src/staff/tests."""

import os
import re
from uuid import uuid4

import psycopg
import pytest

from src.app import create_app
from src.customer import db


@pytest.fixture
def database(monkeypatch):
    dsn = os.environ.get('CUSTOMER_TEST_DATABASE_URL')
    if not dsn:
        pytest.skip('Set CUSTOMER_TEST_DATABASE_URL to an empty, disposable PostgreSQL database.')
    schema = 'staff_test_' + uuid4().hex
    with psycopg.connect(dsn, autocommit=True) as conn:
        conn.execute(f'CREATE SCHEMA {schema}')
    real_connect = psycopg.connect

    def isolated_connection():
        return real_connect(dsn, options=f'-c search_path={schema}', row_factory=db.dict_row)

    monkeypatch.setattr(db, 'connect', isolated_connection)
    with db.connect() as conn:
        conn.execute('''
            CREATE TABLE MenuItem (MenuItemID INTEGER PRIMARY KEY, MenuItemName TEXT NOT NULL,
                Price NUMERIC(10,2) NOT NULL, Category TEXT, EntreeCount INTEGER DEFAULT 0,
                Active BOOLEAN DEFAULT TRUE);
            CREATE TABLE Inventory (InventoryID INTEGER PRIMARY KEY, ItemName TEXT, Unit TEXT,
                Minimum_Threshold NUMERIC, Quantity NUMERIC NOT NULL CHECK (Quantity >= 0),
                ShelfLifeDays INTEGER, Active BOOLEAN DEFAULT TRUE, UnitCost NUMERIC DEFAULT 0);
            CREATE TABLE MenuItemInventory (MenuItemID INTEGER REFERENCES MenuItem,
                InventoryID INTEGER REFERENCES Inventory, QuantityRequired NUMERIC);
            CREATE TABLE Orders (OrderID INTEGER PRIMARY KEY, OrderDate DATE, OrderTime TIME, Total NUMERIC);
            CREATE TABLE OrderItem (OrderItemID INTEGER PRIMARY KEY, OrderID INTEGER REFERENCES Orders,
                MenuItemID INTEGER REFERENCES MenuItem, Qty INTEGER);
            CREATE TABLE OrderItemSelection (OrderItemID INTEGER REFERENCES OrderItem,
                MenuItemID INTEGER REFERENCES MenuItem, Qty INTEGER, PRIMARY KEY (OrderItemID, MenuItemID));
            INSERT INTO MenuItem VALUES
                (1,'PLATE',11,'Meals',2,TRUE), (2,'FRIED RICE',5,'Sides',0,TRUE),
                (3,'ORANGE CHICKEN',6.5,'Entrees',0,TRUE), (4,'BLACK TEA - SMALL',2.1,'Drinks',0,TRUE),
                (5,'BLACK TEA - LARGE',2.9,'Drinks',0,TRUE), (6,'EGG ROLL',2,'Appetizers',0,TRUE),
                (7,'HOT & SOUR SOUP - CUP',3.5,'Sides',0,TRUE);
            INSERT INTO Inventory VALUES
                (1,'Plate Containers','each',2,10,365,TRUE,0.12), (2,'White Rice','lb',2,10,30,TRUE,0.5),
                (3,'Chicken','lb',2,10,5,TRUE,2.5), (4,'Tea Concentrate','oz',5,6,90,TRUE,4),
                (5,'Old Sauce','oz',1,0,30,FALSE,1);
            INSERT INTO MenuItemInventory VALUES (1,1,1),(2,2,1),(3,3,1),(4,4,1),(5,4,2),(6,5,1),(7,2,1);
        ''')
        migration = (db.ROOT / 'src/customer/migrations/001_customer_ordering.sql').read_text()
        conn.execute(migration)
    yield
    with real_connect(dsn, autocommit=True) as conn:
        conn.execute(f'DROP SCHEMA {schema} CASCADE')


@pytest.fixture
def client(database):
    app = create_app({'TESTING': True, 'SECRET_KEY': 'testing-only', 'MANAGER_PIN': '4321'})
    return app.test_client()


def csrf(client):
    return client.get('/api/session').json['csrf']


def form(client, path, data=None):
    return client.post(path, data={**(data or {}), 'csrf': csrf(client)}, follow_redirects=True)


def flashes(response):
    return re.findall(r'class="flash flash-\w+" role="\w+">([^<]*)<', response.get_data(as_text=True))


def manager(client):
    assert 'Inventory' in form(client, '/manager/login', {'pin': '4321'}).get_data(as_text=True)


def one(sql):
    with db.connect() as conn:
        return conn.execute(sql).fetchall()


def test_mode_select_links_all_three_interfaces(client):
    page = client.get('/staff').get_data(as_text=True)
    assert 'href="/cashier"' in page and 'href="/manager/login"' in page and 'href="/"' in page
    assert 'href="/staff"' in client.get('/').get_data(as_text=True)


def test_manager_pages_require_pin(client):
    for path in ('/manager/inventory', '/manager/menu', '/manager/orders', '/manager/reports'):
        assert client.get(path).location.endswith('/manager/login')
    assert flashes(form(client, '/manager/login', {'pin': '1234'})) == ['Incorrect PIN.']
    manager(client)
    assert client.get('/manager/reports').status_code == 200
    client.get('/manager/logout')
    assert client.get('/manager/inventory').status_code == 302


def test_staff_posts_require_csrf(client):
    response = client.post('/cashier/add', data={'menu_item_id': 6, 'category': 'Appetizers'})
    assert response.status_code == 403
    assert 'Session expired' in response.get_data(as_text=True)
    assert client.post('/manager/login', data={'pin': '4321'}).status_code == 403


def test_cashier_menu_hides_items_with_discontinued_ingredients_and_groups_drinks(client):
    assert 'EGG ROLL' not in client.get('/cashier?category=Appetizers').get_data(as_text=True)
    drinks = client.get('/cashier?category=Drinks').get_data(as_text=True)
    assert 'drink=BLACK+TEA' in drinks
    sizes = client.get('/cashier?category=Drinks&drink=BLACK TEA').get_data(as_text=True)
    assert sizes.index('Small') < sizes.index('Large')
    meal = client.get('/cashier/meal/1').get_data(as_text=True)
    assert 'ORANGE CHICKEN' in meal and 'SOUP' not in meal


def test_cashier_checkout_records_selections_and_deducts_stock(client):
    page = form(client, '/cashier/add', {'menu_item_id': 1, 'selection': ['2', '3', '3']}).get_data(as_text=True)
    assert 'PLATE / FRIED RICE / ORANGE CHICKEN / ORANGE CHICKEN' in page
    form(client, '/cashier/add', {'menu_item_id': 5, 'category': 'Drinks'})
    done = form(client, '/cashier/checkout')
    assert flashes(done) == ['Order #1 submitted. Total: $15.05. Thank you, have a nice day!']
    assert float(one('SELECT Total FROM Orders')[0]['total']) == 15.05
    assert one('SELECT Qty FROM OrderItemSelection WHERE MenuItemID = 3')[0]['qty'] == 2
    stock = {r['inventoryid']: r['quantity'] for r in one('SELECT InventoryID, Quantity FROM Inventory')}
    assert [float(stock[i]) for i in (1, 2, 3, 4)] == [9, 9, 8, 4]
    assert 'No items yet' in client.get('/cashier').get_data(as_text=True)


def test_combined_cart_stock_is_checked_for_new_and_duplicated_lines(client):
    form(client, '/cashier/add', {'menu_item_id': 5})  # 2 oz of 6
    form(client, '/cashier/add', {'menu_item_id': 5})  # 4 oz
    key = re.findall(r'/cashier/duplicate/(\d+)', client.get('/cashier').get_data(as_text=True))[0]
    form(client, f'/cashier/duplicate/{key}')  # 6 oz: exactly enough
    assert flashes(form(client, f'/cashier/duplicate/{key}')) == [
        'Cannot duplicate item: Not enough Tea Concentrate for this order.']
    assert flashes(form(client, '/cashier/add', {'menu_item_id': 4})) == [
        'Cannot add item: Not enough Tea Concentrate for this order.']
    assert client.get('/cashier').get_data(as_text=True).count('/cashier/duplicate/') == 3


def test_edit_meal_prices_extras_and_keeps_one_side_and_entree(client):
    form(client, '/cashier/add', {'menu_item_id': 1, 'selection': ['2', '3', '3']})
    key = re.findall(r'/cashier/edit/(\d+)', client.get('/cashier').get_data(as_text=True))[0]
    url = f'/cashier/edit/{key}'
    page = form(client, url, {'comp': ['2', '3', '3'], 'op': 'add-Entrees', 'pick-Entrees': '3'})
    text = page.get_data(as_text=True)
    assert 'Meal price: $17.50' in text and '+$6.50' in text
    text = form(client, url, {'comp': ['2', '3'], 'op': 'remove-0'}).get_data(as_text=True)
    assert 'A meal must keep at least one side and one entree.' in text
    form(client, url, {'comp': ['2', '3', '3', '3'], 'op': 'save'})
    ticket = client.get('/cashier').get_data(as_text=True)
    assert 'PLATE / FRIED RICE / ORANGE CHICKEN / ORANGE CHICKEN / ORANGE CHICKEN' in ticket
    assert '$17.50' in ticket
    form(client, '/cashier/checkout')
    assert one('SELECT Qty FROM OrderItemSelection WHERE MenuItemID = 3')[0]['qty'] == 3
    assert float(one('SELECT Quantity FROM Inventory WHERE InventoryID = 3')[0]['quantity']) == 7


def test_customer_and_cashier_orders_share_order_ids_and_history(client):
    quote = client.post('/api/quote', json={'cart': [{'id': 6, 'quantity': 1}]}, headers={'X-CSRF-Token': csrf(client)})
    assert quote.status_code == 409  # egg roll uses a discontinued ingredient
    client.post('/auth/guest', json={}, headers={'X-CSRF-Token': csrf(client)})
    cart = [{'id': 4, 'quantity': 1, 'selections': []}]
    total = client.post('/api/quote', json={'cart': cart}, headers={'X-CSRF-Token': csrf(client)}).json['total']
    web = client.post('/api/orders', json={'cart': cart, 'expected_total': total, 'checkout_key': str(uuid4())},
                      headers={'X-CSRF-Token': csrf(client)})
    assert web.status_code == 201 and web.json['order_id'] == 1
    form(client, '/cashier/add', {'menu_item_id': 4})
    assert 'Order #2 submitted' in flashes(form(client, '/cashier/checkout'))[0]
    manager(client)
    history = client.get('/manager/orders').get_data(as_text=True)
    assert 'data-order="1"' in history and 'data-order="2"' in history and '2 orders today' in history
    assert client.get('/manager/orders/2.json').json['items'][0]['name'] == 'BLACK TEA - SMALL'


def test_inventory_management(client):
    manager(client)
    assert flashes(form(client, '/manager/inventory', {'value_3': '12.5', 'orig_3': '10.00'})) == ['Saved 1 change(s).']
    assert flashes(form(client, '/manager/inventory', {'value_3': '-1', 'orig_3': '12.50'})) == [
        'Values must be non-negative numbers.']
    item = {'unit': 'lb', 'threshold': '1', 'quantity': '4', 'shelf_life': '3'}
    assert 'already exists' in form(client, '/manager/inventory/add', {**item, 'name': 'chicken'}).get_data(as_text=True)
    assert 'is discontinued' in form(client, '/manager/inventory/add', {**item, 'name': 'old sauce'}).get_data(as_text=True)
    assert flashes(form(client, '/manager/inventory/add', {**item, 'name': 'Broccoli'})) == [
        '&#34;Broccoli&#34; was added to inventory.']
    assert flashes(form(client, '/manager/inventory/reactivate', {'item_id': '5', 'quantity': '8'})) == [
        '&#34;Old Sauce&#34; was reactivated.']
    assert flashes(form(client, '/manager/inventory/6/discontinue', {'name': 'Broccoli'})) == [
        '&#34;Broccoli&#34; was discontinued from inventory.']
    rows = {r['itemname']: (float(r['quantity']), r['active']) for r in one('SELECT ItemName, Quantity, Active FROM Inventory')}
    assert rows['Chicken'] == (12.5, True) and rows['Old Sauce'] == (8, True) and rows['Broccoli'] == (4, False)


def test_menu_management(client):
    manager(client)
    assert flashes(form(client, '/manager/menu', {'value_4': '2.25', 'orig_4': '2.10'})) == ['Saved 1 change(s).']
    bad = form(client, '/manager/menu/add', {'name': 'Beef Bowl', 'price': '9', 'category': 'Meals', 'qty_3': '0.1234'})
    assert 'at most three decimal places' in bad.get_data(as_text=True)
    form(client, '/manager/menu/add', {'name': 'Beef Bowl', 'price': '9', 'category': 'Meals', 'qty_1': '1', 'qty_3': '0.5'})
    assert len(one('SELECT * FROM MenuItemInventory WHERE MenuItemID = 8')) == 2
    form(client, '/manager/menu/8/discontinue', {'name': 'Beef Bowl'})
    assert flashes(form(client, '/manager/menu/add', {'name': 'beef bowl', 'price': '9.5', 'category': 'Meals'})) == [
        '&#34;beef bowl&#34; was reactivated on the menu.']
    row = one('SELECT Price, Active FROM MenuItem WHERE MenuItemID = 8')[0]
    assert (float(row['price']), row['active']) == (9.5, True)


def test_reports_show_profit_margin_and_restock_at_threshold(client):
    form(client, '/cashier/add', {'menu_item_id': 1, 'selection': ['2', '3', '3']})
    form(client, '/cashier/checkout')  # plate $11 + tax; cost 0.12 + 0.5 + 2 * 2.5 = 5.62
    with db.connect() as conn:
        conn.execute('UPDATE Inventory SET Quantity = Minimum_Threshold WHERE InventoryID = 2')
    manager(client)
    page = ' '.join(client.get('/manager/reports').get_data(as_text=True).split())
    assert '$11.00 revenue, $5.38 est. profit' in page and '-$0.50' in page   # sales: 11.00 - 5.62 recipe cost
    assert '1 days, $11.91 revenue, $6.29 estimated profit' in page    # daily margin (Orders.Total)
    assert '1 items need restocking' in page and 'White Rice' in page
    assert "Today&#39;s Sales: $11.91" in page and '<svg' in page
    bad = client.get('/manager/reports?sales_from=2026-11-05T00:00&sales_to=2026-11-01T00:00')
    assert 'Start must be on or before end' in bad.get_data(as_text=True)


def test_database_outage_shows_error_page_without_details(client, monkeypatch):
    def unavailable():
        raise psycopg.OperationalError('secret connection details')
    monkeypatch.setattr(db, 'connect', unavailable)
    response = client.get('/cashier')
    assert response.status_code == 503
    assert 'secret' not in response.get_data(as_text=True)
