"""Read the existing POS schema and submit orders atomically."""

import os
from collections import Counter, defaultdict
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import re

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

ROOT = Path(__file__).resolve().parents[2]
IMAGES = ROOT / 'pandaimages'
TAX_RATE = Decimal('0.0825')


class OrderError(ValueError):
    """A cart can no longer be fulfilled."""


def connect():
    standard_url = os.environ.get('DATABASE_URL')
    url = standard_url or os.environ.get('DB_URL', '')
    if not url or '<' in url:
        raise RuntimeError('Database connection is not configured.')
    if url.startswith('jdbc:'):
        url = url[5:]
    options = {'connect_timeout': 10, 'row_factory': dict_row}
    if not standard_url and os.environ.get('DB_USER'):
        options['user'] = os.environ['DB_USER']
    if not standard_url and os.environ.get('DB_PASSWORD'):
        options['password'] = os.environ['DB_PASSWORD']
    conn = psycopg.connect(url, **options)
    # Store order dates/times in restaurant time, like the Java JDBC driver does
    # (it sends the laptop's timezone); the database default on Neon is UTC.
    conn.execute("SELECT set_config('TimeZone', %s, false)", (timezone_name(),))
    conn.commit()
    return conn


def timezone_name():
    return os.environ.get('POS_TIMEZONE', 'America/Chicago')


def image_name(name):
    """Only reuse photos for the same food or drink, never a different flavor."""
    images = {p.stem.upper(): p.name for p in IMAGES.iterdir()
              if p.suffix.lower() in ('.png', '.webp', '.jpg', '.jpeg', '.avif')}
    name = name.upper().strip()
    if name in images:
        return images[name]
    base = re.sub(r' - (KIDS|SMALL|MEDIUM|MEDDIUM|LARGE)$', '', name)
    base = re.sub(r' - CUB MEAL(?: ENTREE)?$', '', base)
    if base == 'SUPER GREENS ENTREE':
        base = 'SUPER GREENS'
    if base == 'HOT & SOUR SOUP - CUP':
        base = 'HOT & SOUR SOUP - BOWL'
    # Share each fountain drink's size-free photo across its size options.
    base = re.sub(r' \((?:PR|TX) ONLY\)$', '', base)
    if base in images:
        return images[base]
    # Diet/light versions can share the regular drink's photo when needed.
    regular = re.sub(r'\b(?:DIET|LIGHT|LITE|ZERO SUGAR|ZERO)\b\s*', '', base).strip()
    return images.get(regular)


def menu_rows(conn):
    return conn.execute('''
        SELECT m.MenuItemID AS id, m.MenuItemName AS name, m.Price AS price,
               CASE WHEN m.Category = 'Drinks'
                         AND m.MenuItemName ~* '\\m(STEAK|STEAMED)\\M'
                    THEN 'Entrees' ELSE m.Category END AS category,
               COALESCE(m.EntreeCount, 0) AS entree_count,
               (EXISTS (SELECT 1 FROM MenuItemInventory r WHERE r.MenuItemID=m.MenuItemID)
                AND NOT EXISTS (
                    SELECT 1 FROM MenuItemInventory r
                    LEFT JOIN Inventory i ON i.InventoryID=r.InventoryID
                    WHERE r.MenuItemID=m.MenuItemID AND
                      (i.InventoryID IS NULL OR NOT i.Active OR i.Quantity < r.QuantityRequired)
                )) AS available
        FROM MenuItem m WHERE m.Active=TRUE ORDER BY m.MenuItemID
    ''').fetchall()


def read_menu():
    with connect() as conn:
        rows = menu_rows(conn)
    for row in rows:
        row['price'] = str(money(row['price']))
        row['image'] = image_name(row['name'])
    return rows


def money(value):
    return Decimal(str(value)).quantize(Decimal('.01'), rounding=ROUND_HALF_UP)


def validate_cart(conn, cart):
    if not isinstance(cart, list) or not 1 <= len(cart) <= 50:
        raise OrderError('Choose between 1 and 50 order lines.')
    menu = {row['id']: row for row in menu_rows(conn)}
    required = defaultdict(Decimal)
    lines = []
    subtotal = Decimal(0)
    for line in cart:
        if not isinstance(line, dict):
            raise OrderError('Invalid order line.')
        item_id, qty = line.get('id'), line.get('quantity')
        choices = line.get('selections', [])
        if type(item_id) is not int or type(qty) is not int or not 1 <= qty <= 20:
            raise OrderError('Item quantities must be between 1 and 20.')
        if not isinstance(choices, list) or any(type(i) is not int for i in choices):
            raise OrderError('Invalid meal choices.')
        item = menu.get(item_id)
        if not item or not item['available']:
            raise OrderError('An item is no longer available. Please refresh the menu.')
        count = item['entree_count']
        if len(choices) != (count + 1 if count else 0):
            raise OrderError('Choose one side and the included number of entrees.')
        for slot, selected in enumerate(choices):
            option = menu.get(selected)
            category = 'Sides' if slot == 0 else 'Entrees'
            if (not option or not option['available'] or option['category'] != category
                    or 'SOUP' in option['name'].upper() or option['entree_count']):
                raise OrderError('One of your meal choices is unavailable.')
        for component in [item_id, *choices]:
            recipes = conn.execute('''SELECT InventoryID AS id, QuantityRequired AS quantity
                FROM MenuItemInventory WHERE MenuItemID=%s''', (component,)).fetchall()
            if not recipes:
                raise OrderError('An item is missing its inventory recipe.')
            for recipe in recipes:
                required[recipe['id']] += Decimal(str(recipe['quantity'])) * qty
        price = money(item['price'])
        subtotal += price * qty
        lines.append({'id': item_id, 'name': item['name'], 'quantity': qty,
                      'selections': choices, 'choice_names': [menu[i]['name'] for i in choices],
                      'unit_price': str(price), 'line_total': str(money(price * qty))})
    for inventory_id, needed in required.items():
        stock = conn.execute('''SELECT ItemName AS name, Quantity AS quantity, Active AS active
            FROM Inventory WHERE InventoryID=%s''', (inventory_id,)).fetchone()
        if not stock or not stock['active'] or Decimal(str(stock['quantity'])) < needed:
            raise OrderError('There is not enough stock for this order. Please reduce quantities or choose another item.')
    tax = money(subtotal * TAX_RATE)
    return {'lines': lines, 'subtotal': str(money(subtotal)), 'tax': str(tax),
            'total': str(money(subtotal + tax))}, required


def checkout(cart, customer_id, checkout_key, browser_key, expected_total):
    with connect() as conn:
        # Same order and lock modes as the Java cashier: safe MAX+1 IDs across both apps.
        conn.execute('LOCK TABLE MenuItem, MenuItemInventory IN SHARE MODE')
        conn.execute('LOCK TABLE Inventory IN SHARE ROW EXCLUSIVE MODE')
        conn.execute('LOCK TABLE Orders, OrderItem IN EXCLUSIVE MODE')
        previous = conn.execute('''SELECT receipt, browser_key FROM customer_web_order
            WHERE checkout_key=%s''', (checkout_key,)).fetchone()
        if previous:
            if str(previous['browser_key']) != str(browser_key):
                raise OrderError('This checkout request is invalid.')
            return previous['receipt']
        receipt, required = validate_cart(conn, cart)
        if receipt['total'] != expected_total:
            raise OrderError('Menu prices changed. Review your updated total before placing the order.')
        order_id = conn.execute('SELECT COALESCE(MAX(OrderID),0)+1 AS id FROM Orders').fetchone()['id']
        line_id = conn.execute('SELECT COALESCE(MAX(OrderItemID),0) AS id FROM OrderItem').fetchone()['id']
        conn.execute('''INSERT INTO Orders (OrderID, OrderDate, OrderTime, Total)
            VALUES (%s, CURRENT_DATE, CURRENT_TIME, %s)''', (order_id, receipt['total']))
        for line in receipt['lines']:
            line_id += 1
            conn.execute('''INSERT INTO OrderItem (OrderItemID, OrderID, MenuItemID, Qty)
                VALUES (%s,%s,%s,%s)''', (line_id, order_id, line['id'], line['quantity']))
            for selected, qty in Counter(line['selections']).items():
                conn.execute('''INSERT INTO OrderItemSelection (OrderItemID,MenuItemID,Qty)
                    VALUES (%s,%s,%s)''', (line_id, selected, qty))
        for inventory_id, needed in sorted(required.items()):
            changed = conn.execute('''UPDATE Inventory SET Quantity=Quantity-%s
                WHERE InventoryID=%s AND Active=TRUE AND Quantity >= %s''',
                (needed, inventory_id, needed))
            if changed.rowcount != 1:
                raise OrderError('Inventory changed. Please review your order and try again.')
        receipt['order_id'] = order_id
        conn.execute('''INSERT INTO customer_web_order
            (order_id, customer_id, checkout_key, browser_key, receipt) VALUES (%s,%s,%s,%s,%s)''',
            (order_id, customer_id, checkout_key, browser_key, Jsonb(receipt)))
        return receipt


def google_customer(subject, name):
    with connect() as conn:
        conn.execute('LOCK TABLE customer IN EXCLUSIVE MODE')
        row = conn.execute('SELECT customer_id FROM customer_google_identity WHERE google_subject=%s',
                           (subject,)).fetchone()
        if row:
            return row['customer_id']
        customer_id = conn.execute('SELECT COALESCE(MAX(customer_id),0)+1 AS id FROM customer').fetchone()['id']
        conn.execute('INSERT INTO customer(customer_id,phone,points,full_name) VALUES (%s,%s,0,%s)',
                     (customer_id, '', name[:150]))
        conn.execute('INSERT INTO customer_google_identity(google_subject,customer_id) VALUES (%s,%s)',
                     (subject, customer_id))
        return customer_id


def account(customer_id):
    with connect() as conn:
        return conn.execute('SELECT full_name, points FROM customer WHERE customer_id=%s',
                            (customer_id,)).fetchone()


def recent_orders(customer_id, order_id=None):
    """Return only this account's web orders within the past calendar month."""
    with connect() as conn:
        rows = conn.execute('''SELECT w.order_id, o.OrderDate AS date,
                o.OrderTime AS time, w.receipt
            FROM customer_web_order w JOIN Orders o ON o.OrderID=w.order_id
            WHERE w.customer_id=%s
              AND o.OrderDate + o.OrderTime >= CURRENT_TIMESTAMP - INTERVAL '1 month'
              AND o.OrderDate + o.OrderTime <= CURRENT_TIMESTAMP
              AND (%s::integer IS NULL OR w.order_id=%s)
            ORDER BY o.OrderDate DESC, o.OrderTime DESC, w.order_id DESC''',
            (customer_id, order_id, order_id)).fetchall()
    return [{'order_id': row['order_id'],
             'placed_at': f"{row['date'].isoformat()}T{row['time'].isoformat()}",
             'receipt': row['receipt']} for row in rows]
