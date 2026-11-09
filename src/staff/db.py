"""Staff (cashier + manager) database layer.

Python port of the Java InventoryDAO, OrderHistoryDAO, and the SQL inside CashierGUI and
ReportWithFourQuads. Connects through src/customer/db.connect(), so it reads the same
.env settings (DB_URL / DB_USER / DB_PASSWORD or DATABASE_URL).
"""

from contextlib import contextmanager
from decimal import Decimal, InvalidOperation

import psycopg
from flask import current_app, g

from ..customer import db as customer_db

THRESHOLD_COL = 'Minimum_Threshold'


class DBError(Exception):
    """A business-rule failure with a message that is safe to show staff."""


class DBUnavailable(Exception):
    """The database could not be reached or is not configured."""


# ---------------------------------------------------------------------------
# Connection handling: one connection per request, autocommit unless in transaction()
# ---------------------------------------------------------------------------

def get_conn():
    if 'staff_db' not in g:
        try:
            conn = customer_db.connect()  # looked up at call time so tests can patch it
            conn.autocommit = True
        except (psycopg.OperationalError, RuntimeError) as exc:
            current_app.logger.error('Staff database connection failed: %s', type(exc).__name__)
            raise DBUnavailable() from exc
        g.staff_db = conn
    return g.staff_db


def close_conn(_exc=None):
    conn = g.pop('staff_db', None)
    if conn is not None:
        conn.close()


def _clean(row):
    return {k: float(v) if isinstance(v, Decimal) else v for k, v in row.items()}


def query(sql, params=()):
    return [_clean(r) for r in get_conn().execute(sql, params).fetchall()]


def query_one(sql, params=()):
    rows = query(sql, params)
    return rows[0] if rows else None


@contextmanager
def transaction():
    """Yields the connection inside one transaction; commits, or rolls back on error."""
    conn = get_conn()
    with conn.transaction():
        yield conn


def error_message(exc):
    """User-facing text for a database error, without connection details."""
    if isinstance(exc, DBError):
        return str(exc)
    if isinstance(exc, psycopg.OperationalError):
        return 'The database is unavailable. Please try again shortly.'
    diag = getattr(exc, 'diag', None)
    return (diag.message_primary if diag and diag.message_primary else None) or str(exc)


# ---------------------------------------------------------------------------
# Inventory (InventoryDAO)
# ---------------------------------------------------------------------------

_INV_COLS = (f'InventoryID AS id, ItemName AS name, Unit AS unit, {THRESHOLD_COL} AS threshold, '
             'Quantity AS quantity, ShelfLifeDays AS shelf_life_days')


def fetch_inventory(active=True):
    """Active (or discontinued) inventory items, ordered by name."""
    rows = query(f'SELECT {_INV_COLS} FROM Inventory WHERE Active = %s ORDER BY ItemName', (active,))
    for r in rows:
        r['low'] = r['quantity'] <= r['threshold']
    return rows


def update_quantities(changes):
    """changes: {InventoryID: new quantity}, applied in one transaction."""
    if not changes:
        return
    with transaction() as conn:
        conn.cursor().executemany('UPDATE Inventory SET Quantity = %s WHERE InventoryID = %s',
                                  [(q, i) for i, q in changes.items()])


def insert_item(name, unit, threshold, quantity, shelf_life_days):
    """Adds a brand-new inventory row (ID = MAX + 1). Names are unique, even vs inactive rows."""
    with transaction() as conn:
        row = conn.execute('SELECT Active AS active FROM Inventory WHERE LOWER(ItemName) = LOWER(%s)',
                           (name,)).fetchone()
        if row:
            if row['active']:
                raise DBError(f'An active inventory item named "{name}" already exists.')
            raise DBError(f'"{name}" is discontinued. Use Reactivate Item instead.')
        new_id = conn.execute('SELECT COALESCE(MAX(InventoryID), 0) + 1 AS id FROM Inventory').fetchone()['id']
        conn.execute(
            f'INSERT INTO Inventory (InventoryID, ItemName, Unit, {THRESHOLD_COL}, Quantity, '
            'ShelfLifeDays, Active) VALUES (%s, %s, %s, %s, %s, %s, TRUE)',
            (new_id, name, unit, threshold, quantity, shelf_life_days))
    return new_id


def reactivate_inventory_item(inventory_id, starting_quantity):
    with transaction() as conn:
        cur = conn.execute('UPDATE Inventory SET Quantity = %s, Active = TRUE '
                           'WHERE InventoryID = %s AND Active = FALSE',
                           (starting_quantity, inventory_id))
        if cur.rowcount == 0:
            raise DBError('This inventory item could not be reactivated. It may already be active.')


def discontinue_inventory_item(inventory_id):
    with transaction() as conn:
        cur = conn.execute('UPDATE Inventory SET Active = FALSE WHERE InventoryID = %s', (inventory_id,))
        if cur.rowcount == 0:
            raise DBError(f'No inventory item found with ID {inventory_id}')


# ---------------------------------------------------------------------------
# Menu items (InventoryDAO)
# ---------------------------------------------------------------------------

def fetch_menu_items():
    return query('SELECT MenuItemID AS id, MenuItemName AS name, Price AS price '
                 'FROM MenuItem WHERE Active = TRUE ORDER BY MenuItemID')


def update_prices(changes):
    """changes: {MenuItemID: new price}, applied in one transaction."""
    if not changes:
        return
    with transaction() as conn:
        conn.cursor().executemany('UPDATE MenuItem SET Price = %s WHERE MenuItemID = %s',
                                  [(p, i) for i, p in changes.items()])


def valid_recipe_qty(qty):
    """Positive, at most 9,999,999.999, at most three decimal places."""
    try:
        d = Decimal(str(qty))
    except InvalidOperation:
        return False
    if not d.is_finite() or d <= 0 or d > Decimal('9999999.999'):
        return False
    return -d.normalize().as_tuple().exponent <= 3


def insert_menu_item(name, category, price, ingredients):
    """Creates a menu item and its recipe atomically.

    A discontinued item with the same name is reactivated instead, keeping its existing
    recipe. ingredients: {InventoryID: QuantityRequired}. Returns (id, reactivated).
    """
    with transaction() as conn:
        existing = None
        for row in conn.execute('SELECT MenuItemID AS id, Active AS active FROM MenuItem '
                                'WHERE LOWER(MenuItemName) = LOWER(%s)', (name,)).fetchall():
            if row['active']:
                raise DBError(f'An active menu item named "{name}" already exists.')
            if existing is not None:
                raise DBError('Multiple discontinued items have this name.')
            existing = row['id']
        if existing is not None:
            conn.execute('UPDATE MenuItem SET Price = %s, Category = %s, Active = TRUE '
                         'WHERE MenuItemID = %s', (price, category, existing))
            return existing, True

        for inv_id, qty in ingredients.items():
            if not valid_recipe_qty(qty):
                raise DBError('Ingredient quantities must be positive with at most three '
                              'decimal places.')
            row = conn.execute('SELECT Active AS active FROM Inventory WHERE InventoryID = %s FOR UPDATE',
                               (inv_id,)).fetchone()
            if not row or not row['active']:
                raise DBError('A selected ingredient is no longer active. Reopen the form.')

        new_id = conn.execute('SELECT COALESCE(MAX(MenuItemID), 0) + 1 AS id FROM MenuItem').fetchone()['id']
        conn.execute('INSERT INTO MenuItem (MenuItemID, MenuItemName, Category, Price, Active) '
                     'VALUES (%s, %s, %s, %s, TRUE)', (new_id, name, category, price))
        if ingredients:
            conn.cursor().executemany(
                'INSERT INTO MenuItemInventory (MenuItemID, InventoryID, QuantityRequired) '
                'VALUES (%s, %s, %s)', [(new_id, i, q) for i, q in ingredients.items()])
    return new_id, False


def discontinue_menu_item(menu_item_id):
    with transaction() as conn:
        cur = conn.execute('UPDATE MenuItem SET Active = FALSE WHERE MenuItemID = %s', (menu_item_id,))
        if cur.rowcount == 0:
            raise DBError(f'No menu item found with ID {menu_item_id}')


# ---------------------------------------------------------------------------
# Order history (OrderHistoryDAO)
# ---------------------------------------------------------------------------

def fetch_today_orders():
    return query("SELECT OrderID AS id, TO_CHAR(OrderTime, 'HH12:MI:SS AM') AS time_made, "
                 'Total AS total FROM Orders WHERE OrderDate = CURRENT_DATE '
                 'ORDER BY OrderTime DESC, OrderID DESC')


def fetch_order_items(order_id):
    return query(
        'SELECT m.MenuItemName AS name, oi.Qty AS qty, COALESCE((SELECT STRING_AGG('
        "s.Qty::text || 'x ' || sm.MenuItemName, CHR(10) ORDER BY sm.Category DESC, "
        'sm.MenuItemName) '
        'FROM OrderItemSelection s JOIN MenuItem sm ON sm.MenuItemID = s.MenuItemID '
        'WHERE s.OrderItemID = oi.OrderItemID), '
        "CASE WHEN m.EntreeCount > 0 THEN 'Selections not recorded' ELSE '' END) AS selections "
        'FROM OrderItem oi JOIN MenuItem m ON m.MenuItemID = oi.MenuItemID '
        'WHERE oi.OrderID = %s ORDER BY oi.OrderItemID', (order_id,))


# ---------------------------------------------------------------------------
# Cashier (CashierGUI)
# ---------------------------------------------------------------------------

def menu_for_category(category):
    """Active menu items in a category whose recipe uses no discontinued ingredient."""
    return query(
        'SELECT m.MenuItemID AS id, m.MenuItemName AS name, m.Price AS price, '
        'COALESCE(m.EntreeCount, 0) AS entree_count '
        'FROM MenuItem m WHERE m.Category = %s AND m.Active = TRUE '
        'AND NOT EXISTS (SELECT 1 FROM MenuItemInventory mii '
        '  JOIN Inventory i ON i.InventoryID = mii.InventoryID '
        '  WHERE mii.MenuItemID = m.MenuItemID AND i.Active = FALSE) '
        'ORDER BY m.MenuItemID', (category,))


def active_menu_item(menu_item_id):
    row = query_one('SELECT MenuItemID AS id, MenuItemName AS name, Price AS price, '
                    'COALESCE(EntreeCount, 0) AS entree_count '
                    'FROM MenuItem WHERE MenuItemID = %s AND Active = TRUE', (menu_item_id,))
    if row is None:
        raise DBError('This menu item is no longer available.')
    return row


def meal_options(category):
    """Choices for a meal slot ('Sides' or 'Entrees'), soups excluded, with standalone prices."""
    rows = query('SELECT MenuItemID AS id, MenuItemName AS name, Price AS price FROM MenuItem '
                 "WHERE Category = %s AND Active = TRUE AND MenuItemName NOT LIKE '%%SOUP%%' "
                 'ORDER BY MenuItemName', (category,))
    if not rows:
        raise DBError(f'No active {category} to choose from.')
    return rows


def meal_price(base, entree_count, components, kinds, prices):
    """Unit price of a meal: the base price covers one side and the included entrees;
    extra components are charged at their standalone prices (CashierGUI.mealPrice)."""
    free_sides, free_entrees, extra = 1, entree_count, 0.0
    for comp in components:
        if kinds.get(comp) == 'Sides':
            if free_sides > 0:
                free_sides -= 1
                continue
        elif free_entrees > 0:
            free_entrees -= 1
            continue
        extra += prices.get(comp, 0.0)
    return base + extra


def _components(line):
    return [line['menu_item_id']] + list(line['selections'])


def validate_cart(lines, conn=None):
    """Checks combined ingredient demand for the whole cart.

    Returns {InventoryID: total quantity required}. Raises DBError if an item is
    inactive, has no recipe, or stock is insufficient.
    """
    conn = conn or get_conn()
    required = {}
    for line in lines:
        for mid in _components(line):
            row = conn.execute('SELECT Active AS active FROM MenuItem WHERE MenuItemID = %s',
                               (mid,)).fetchone()
            if not row or not row['active']:
                raise DBError('A selected item is no longer active.')
            recipe = conn.execute('SELECT InventoryID AS inv, QuantityRequired AS qty '
                                  'FROM MenuItemInventory WHERE MenuItemID = %s', (mid,)).fetchall()
            if not recipe:
                raise DBError(f'A selected item has no inventory recipe (menu item {mid}).')
            for r in recipe:
                required[r['inv']] = required.get(r['inv'], Decimal(0)) + Decimal(str(r['qty'])) * line['qty']

    for inv_id, need in required.items():
        row = conn.execute('SELECT ItemName AS name, Quantity AS qty, Active AS active '
                           'FROM Inventory WHERE InventoryID = %s', (inv_id,)).fetchone()
        if not row:
            raise DBError('A required inventory item is missing.')
        if not row['active'] or Decimal(str(row['qty'])) < need:
            raise DBError(f"Not enough {row['name']} for this order.")
    return required


def checkout(lines, total):
    """Writes the order in one transaction: Orders, OrderItem, OrderItemSelection, and
    inventory decrements. Lock order matches the Java cashier and the customer app so
    their MAX+1 IDs cannot collide. Returns the new OrderID."""
    with transaction() as conn:
        conn.execute('LOCK TABLE MenuItem, MenuItemInventory IN SHARE MODE')
        conn.execute('LOCK TABLE Inventory IN SHARE ROW EXCLUSIVE MODE')
        conn.execute('LOCK TABLE Orders, OrderItem IN EXCLUSIVE MODE')
        required = validate_cart(lines, conn)

        ids = conn.execute('SELECT (SELECT COALESCE(MAX(OrderID), 0) + 1 FROM Orders) AS oid, '
                           '(SELECT COALESCE(MAX(OrderItemID), 0) FROM OrderItem) AS oiid').fetchone()
        order_id, order_item_id = ids['oid'], ids['oiid']

        conn.execute('INSERT INTO Orders (OrderID, OrderDate, OrderTime, Total) '
                     'VALUES (%s, CURRENT_DATE, CURRENT_TIME, %s)', (order_id, total))

        for line in lines:
            order_item_id += 1
            conn.execute('INSERT INTO OrderItem (OrderItemID, OrderID, MenuItemID, Qty) '
                         'VALUES (%s, %s, %s, %s)',
                         (order_item_id, order_id, line['menu_item_id'], line['qty']))
            counts = {}
            for comp in line['selections']:
                counts[comp] = counts.get(comp, 0) + 1
            for comp, n in counts.items():
                conn.execute('INSERT INTO OrderItemSelection (OrderItemID, MenuItemID, Qty) '
                             'VALUES (%s, %s, %s)', (order_item_id, comp, n))

        for inv_id, need in sorted(required.items()):
            cur = conn.execute('UPDATE Inventory SET Quantity = Quantity - %s '
                               'WHERE InventoryID = %s AND Active = TRUE AND Quantity >= %s',
                               (need, inv_id, need))
            if cur.rowcount != 1:
                raise DBError('Inventory changed; this order cannot be completed.')
    return order_id


# ---------------------------------------------------------------------------
# Reports (ReportWithFourQuads)
# ---------------------------------------------------------------------------

# Every order line plus its meal selections; selections are portions with no extra revenue.
_ORDER_PORTIONS = (
    '(SELECT OrderID, MenuItemID, Qty, Qty AS PaidQty FROM OrderItem '
    'UNION ALL SELECT parent.OrderID, s.MenuItemID, parent.Qty * s.Qty, 0 '
    'FROM OrderItemSelection s JOIN OrderItem parent ON parent.OrderItemID = s.OrderItemID) oi')


def sales_report(start, end_exclusive):
    """Qty sold, revenue, and estimated profit per menu item. Profit uses recipe cost
    (QuantityRequired * Inventory.UnitCost); needs migrations/001_inventory_unit_costs.sql."""
    rows = query(
        'SELECT m.MenuItemName AS name, SUM(oi.Qty) AS qty, SUM(oi.PaidQty * m.Price) AS revenue, '
        '  SUM(oi.Qty) * COALESCE((SELECT SUM(mii.QuantityRequired * i.UnitCost) '
        '    FROM MenuItemInventory mii JOIN Inventory i ON i.InventoryID = mii.InventoryID '
        '    WHERE mii.MenuItemID = m.MenuItemID), 0) AS est_cost '
        f'FROM Orders o JOIN {_ORDER_PORTIONS} ON oi.OrderID = o.OrderID '
        'JOIN MenuItem m ON m.MenuItemID = oi.MenuItemID '
        'WHERE (o.OrderDate + o.OrderTime) >= %s AND (o.OrderDate + o.OrderTime) < %s '
        'GROUP BY m.MenuItemID, m.MenuItemName ORDER BY revenue DESC',
        (start, end_exclusive))
    for r in rows:
        r['profit'] = r['revenue'] - r['est_cost']
        r['margin'] = 100.0 * r['profit'] / r['revenue'] if r['revenue'] > 0 else None
    return rows


def profit_margin_report(start_day, end_day):
    """Per day: recorded revenue (Orders.Total), estimated ingredient cost, profit, margin."""
    days = {}
    for r in query('SELECT OrderDate AS day, SUM(Total) AS revenue FROM Orders '
                   'WHERE OrderDate >= %s AND OrderDate <= %s GROUP BY OrderDate',
                   (start_day, end_day)):
        days.setdefault(r['day'], {'day': r['day'], 'revenue': 0.0, 'cost': 0.0})['revenue'] = r['revenue']
    for r in query(
            'SELECT o.OrderDate AS day, SUM(oi.Qty * mii.QuantityRequired * i.UnitCost) AS cost '
            f'FROM Orders o JOIN {_ORDER_PORTIONS} ON oi.OrderID = o.OrderID '
            'JOIN MenuItemInventory mii ON mii.MenuItemID = oi.MenuItemID '
            'JOIN Inventory i ON i.InventoryID = mii.InventoryID '
            'WHERE o.OrderDate >= %s AND o.OrderDate <= %s GROUP BY o.OrderDate',
            (start_day, end_day)):
        days.setdefault(r['day'], {'day': r['day'], 'revenue': 0.0, 'cost': 0.0})['cost'] = r['cost'] or 0.0
    rows = [days[d] for d in sorted(days)]
    for r in rows:
        r['profit'] = r['revenue'] - r['cost']
        r['margin'] = 100.0 * r['profit'] / r['revenue'] if r['revenue'] > 0 else None
    return rows


def usage_report(start, end_exclusive):
    """Ingredient usage including meal recipes and selected food."""
    return query(
        'SELECT i.ItemName AS name, i.Unit AS unit, SUM(oi.Qty * mii.QuantityRequired) AS used '
        f'FROM Orders o JOIN {_ORDER_PORTIONS} ON oi.OrderID = o.OrderID '
        'JOIN MenuItemInventory mii ON mii.MenuItemID = oi.MenuItemID '
        'JOIN Inventory i ON i.InventoryID = mii.InventoryID '
        'WHERE (o.OrderDate + o.OrderTime) >= %s AND (o.OrderDate + o.OrderTime) < %s '
        'GROUP BY i.InventoryID, i.ItemName, i.Unit ORDER BY used DESC',
        (start, end_exclusive))


def restock_report():
    """Active items at or below their minimum threshold."""
    return query(
        f'SELECT ItemName AS name, Quantity AS quantity, {THRESHOLD_COL} AS threshold, '
        f'Unit AS unit FROM Inventory WHERE Active = TRUE AND Quantity <= {THRESHOLD_COL} '
        f'ORDER BY ({THRESHOLD_COL} - Quantity) DESC')


def hourly_sales():
    """Today's 24 totals indexed by hour of day, for the X-report."""
    sales = [0.0] * 24
    for r in query('SELECT EXTRACT(HOUR FROM OrderTime)::int AS hour, SUM(Total) AS total '
                   'FROM Orders WHERE OrderDate = CURRENT_DATE GROUP BY 1 ORDER BY 1'):
        if 0 <= r['hour'] < 24:
            sales[r['hour']] = r['total'] or 0.0
    return sales
