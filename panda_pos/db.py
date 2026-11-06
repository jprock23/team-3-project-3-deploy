"""Database layer: Python port of InventoryDAO, OrderHistoryDAO, and the SQL
that lived inside CashierGUI and ReportWithFourQuads.

Same schema, same queries, same transaction rules as the Java version.
Functions raise DBError with a message that is safe to show the user.
"""

from contextlib import contextmanager
from datetime import date
from decimal import Decimal, InvalidOperation

import psycopg2
import psycopg2.extras
import psycopg2.pool
from flask import g

import config

THRESHOLD_COL = "Minimum_Threshold"


class DBError(Exception):
    """A database or business-rule failure with a user-facing message."""


# ---------------------------------------------------------------------------
# Connection handling (one pooled connection per request)
# ---------------------------------------------------------------------------

_pool = None


def _get_pool():
    global _pool
    if _pool is None:
        _pool = psycopg2.pool.ThreadedConnectionPool(
            1, 10,
            host=config.DB_HOST, port=config.DB_PORT, dbname=config.DB_NAME,
            user=config.DB_USER, password=config.DB_PASSWORD,
        )
    return _pool


def get_conn():
    if "db" not in g:
        conn = _get_pool().getconn()
        conn.autocommit = True
        g.db = conn
    return g.db


def release_conn(_exc=None):
    conn = g.pop("db", None)
    if conn is None:
        return
    if not conn.closed and not conn.autocommit:
        conn.rollback()
        conn.autocommit = True
    _get_pool().putconn(conn, close=bool(conn.closed))


def _num(v):
    return float(v) if isinstance(v, Decimal) else v


def _clean(row):
    return {k: _num(v) for k, v in row.items()}


def query(sql, params=()):
    with get_conn().cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(sql, params)
        return [_clean(r) for r in cur.fetchall()]


def query_one(sql, params=()):
    rows = query(sql, params)
    return rows[0] if rows else None


@contextmanager
def transaction():
    """Yields a dict cursor inside one transaction; commits or rolls back."""
    conn = get_conn()
    conn.autocommit = False
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            yield cur
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.autocommit = True


# ---------------------------------------------------------------------------
# Inventory (InventoryDAO)
# ---------------------------------------------------------------------------

_INV_COLS = (f"InventoryID AS id, ItemName AS name, Unit AS unit, "
             f"{THRESHOLD_COL} AS threshold, Quantity AS quantity, "
             f"ShelfLifeDays AS shelf_life_days")


def fetch_inventory(active=True):
    """Active (or discontinued) inventory items, ordered by name."""
    rows = query(f"SELECT {_INV_COLS} FROM Inventory WHERE Active = %s ORDER BY ItemName",
                 (active,))
    for r in rows:
        r["low"] = r["quantity"] <= r["threshold"]
    return rows


def update_quantities(changes):
    """changes: {InventoryID: new quantity}, applied in one transaction."""
    if not changes:
        return
    with transaction() as cur:
        cur.executemany("UPDATE Inventory SET Quantity = %s WHERE InventoryID = %s",
                        [(q, i) for i, q in changes.items()])


def insert_item(name, unit, threshold, quantity, shelf_life_days):
    """Adds a brand-new inventory row (ID = MAX + 1). Names are unique, even vs inactive rows."""
    with transaction() as cur:
        cur.execute("SELECT Active FROM Inventory WHERE LOWER(ItemName) = LOWER(%s)", (name,))
        row = cur.fetchone()
        if row:
            if row["active"]:
                raise DBError(f'An active inventory item named "{name}" already exists.')
            raise DBError(f'"{name}" is discontinued. Use Reactivate Item instead.')
        cur.execute("SELECT COALESCE(MAX(InventoryID), 0) + 1 AS id FROM Inventory")
        new_id = cur.fetchone()["id"]
        cur.execute(
            f"INSERT INTO Inventory (InventoryID, ItemName, Unit, {THRESHOLD_COL}, Quantity, "
            f"ShelfLifeDays, Active) VALUES (%s, %s, %s, %s, %s, %s, TRUE)",
            (new_id, name, unit, threshold, quantity, shelf_life_days))
    return new_id


def reactivate_inventory_item(inventory_id, starting_quantity):
    with transaction() as cur:
        cur.execute("UPDATE Inventory SET Quantity = %s, Active = TRUE "
                    "WHERE InventoryID = %s AND Active = FALSE",
                    (starting_quantity, inventory_id))
        if cur.rowcount == 0:
            raise DBError("This inventory item could not be reactivated. It may already be active.")


def discontinue_inventory_item(inventory_id):
    with transaction() as cur:
        cur.execute("UPDATE Inventory SET Active = FALSE WHERE InventoryID = %s", (inventory_id,))
        if cur.rowcount == 0:
            raise DBError(f"No inventory item found with ID {inventory_id}")


# ---------------------------------------------------------------------------
# Menu items (InventoryDAO)
# ---------------------------------------------------------------------------

def fetch_menu_items():
    return query("SELECT MenuItemID AS id, MenuItemName AS name, Price AS price "
                 "FROM MenuItem WHERE Active = TRUE ORDER BY MenuItemID")


def update_prices(changes):
    """changes: {MenuItemID: new price}, applied in one transaction."""
    if not changes:
        return
    with transaction() as cur:
        cur.executemany("UPDATE MenuItem SET Price = %s WHERE MenuItemID = %s",
                        [(p, i) for i, p in changes.items()])


def valid_recipe_qty(qty):
    """Positive, at most 9,999,999.999, at most three decimal places."""
    try:
        d = Decimal(str(qty))
    except InvalidOperation:
        return False
    if not d.is_finite() or d <= 0 or d > Decimal("9999999.999"):
        return False
    return -d.normalize().as_tuple().exponent <= 3


def insert_menu_item(name, category, price, ingredients):
    """Creates a menu item and its recipe atomically.

    A discontinued item with the same name is reactivated instead, keeping its
    existing recipe. ingredients: {InventoryID: QuantityRequired}.
    """
    with transaction() as cur:
        cur.execute("SELECT MenuItemID AS id, Active FROM MenuItem "
                    "WHERE LOWER(MenuItemName) = LOWER(%s)", (name,))
        existing = None
        for row in cur.fetchall():
            if row["active"]:
                raise DBError(f'An active menu item named "{name}" already exists.')
            if existing is not None:
                raise DBError("Multiple discontinued items have this name.")
            existing = row["id"]
        if existing is not None:
            cur.execute("UPDATE MenuItem SET Price = %s, Category = %s, Active = TRUE "
                        "WHERE MenuItemID = %s", (price, category, existing))
            return existing, True

        for inv_id, qty in ingredients.items():
            if not valid_recipe_qty(qty):
                raise DBError("Ingredient quantities must be positive with at most three "
                              "decimal places.")
            cur.execute("SELECT Active FROM Inventory WHERE InventoryID = %s FOR UPDATE",
                        (inv_id,))
            row = cur.fetchone()
            if not row or not row["active"]:
                raise DBError("A selected ingredient is no longer active. Reopen the form.")

        cur.execute("SELECT COALESCE(MAX(MenuItemID), 0) + 1 AS id FROM MenuItem")
        new_id = cur.fetchone()["id"]
        cur.execute("INSERT INTO MenuItem (MenuItemID, MenuItemName, Category, Price, Active) "
                    "VALUES (%s, %s, %s, %s, TRUE)", (new_id, name, category, price))
        cur.executemany("INSERT INTO MenuItemInventory (MenuItemID, InventoryID, QuantityRequired) "
                        "VALUES (%s, %s, %s)",
                        [(new_id, i, q) for i, q in ingredients.items()])
    return new_id, False


def discontinue_menu_item(menu_item_id):
    with transaction() as cur:
        cur.execute("UPDATE MenuItem SET Active = FALSE WHERE MenuItemID = %s", (menu_item_id,))
        if cur.rowcount == 0:
            raise DBError(f"No menu item found with ID {menu_item_id}")


# ---------------------------------------------------------------------------
# Order history (OrderHistoryDAO)
# ---------------------------------------------------------------------------

def fetch_today_orders():
    return query("SELECT OrderID AS id, TO_CHAR(OrderTime, 'HH12:MI:SS AM') AS time_made, "
                 "Total AS total FROM Orders WHERE OrderDate = CURRENT_DATE "
                 "ORDER BY OrderTime DESC, OrderID DESC")


def fetch_order_items(order_id):
    return query(
        "SELECT m.MenuItemName AS name, oi.Qty AS qty, COALESCE((SELECT STRING_AGG("
        "s.Qty::text || 'x ' || sm.MenuItemName, CHR(10) ORDER BY sm.Category DESC, "
        "sm.MenuItemName) "
        "FROM OrderItemSelection s JOIN MenuItem sm ON sm.MenuItemID = s.MenuItemID "
        "WHERE s.OrderItemID = oi.OrderItemID), "
        "CASE WHEN m.EntreeCount > 0 THEN 'Selections not recorded' ELSE '' END) AS selections "
        "FROM OrderItem oi JOIN MenuItem m ON m.MenuItemID = oi.MenuItemID "
        "WHERE oi.OrderID = %s ORDER BY oi.OrderItemID", (order_id,))


# ---------------------------------------------------------------------------
# Cashier (CashierGUI)
# ---------------------------------------------------------------------------

def menu_for_category(category):
    """Active menu items in a category whose recipe uses no discontinued ingredient."""
    return query(
        "SELECT m.MenuItemID AS id, m.MenuItemName AS name, m.Price AS price, "
        "COALESCE(m.EntreeCount, 0) AS entree_count "
        "FROM MenuItem m WHERE m.Category = %s AND m.Active = TRUE "
        "AND NOT EXISTS (SELECT 1 FROM MenuItemInventory mii "
        "  JOIN Inventory i ON i.InventoryID = mii.InventoryID "
        "  WHERE mii.MenuItemID = m.MenuItemID AND i.Active = FALSE) "
        "ORDER BY m.MenuItemID", (category,))


def active_menu_item(menu_item_id):
    row = query_one("SELECT MenuItemID AS id, MenuItemName AS name, Price AS price, "
                    "COALESCE(EntreeCount, 0) AS entree_count "
                    "FROM MenuItem WHERE MenuItemID = %s AND Active = TRUE", (menu_item_id,))
    if row is None:
        raise DBError("This menu item is no longer available.")
    return row


def meal_options(category):
    """Choices for a meal slot ('Sides' or 'Entrees'), soups excluded."""
    rows = query("SELECT MenuItemID AS id, MenuItemName AS name FROM MenuItem "
                 "WHERE Category = %s AND Active = TRUE AND MenuItemName NOT LIKE '%%SOUP%%' "
                 "ORDER BY MenuItemName", (category,))
    if not rows:
        raise DBError(f"No active {category} to choose from.")
    return rows


def _components(line):
    return [line["menu_item_id"]] + list(line["selections"])


def validate_cart(lines, cur=None):
    """Checks combined ingredient demand for the whole cart.

    Returns {InventoryID: total quantity required}. Raises DBError if an item
    is inactive, has no recipe, or stock is insufficient.
    """
    def one(sql, params):
        if cur is None:
            return query_one(sql, params)
        cur.execute(sql, params)
        r = cur.fetchone()
        return _clean(r) if r else None

    def many(sql, params):
        if cur is None:
            return query(sql, params)
        cur.execute(sql, params)
        return [_clean(r) for r in cur.fetchall()]

    required = {}
    for line in lines:
        for mid in _components(line):
            row = one("SELECT Active FROM MenuItem WHERE MenuItemID = %s", (mid,))
            if not row or not row["active"]:
                raise DBError("A selected item is no longer active.")
            recipe = many("SELECT InventoryID AS inv, QuantityRequired AS qty "
                          "FROM MenuItemInventory WHERE MenuItemID = %s", (mid,))
            if not recipe:
                raise DBError(f"A selected item has no inventory recipe (menu item {mid}).")
            for r in recipe:
                required[r["inv"]] = required.get(r["inv"], 0.0) + r["qty"] * line["qty"]

    for inv_id, need in required.items():
        row = one("SELECT ItemName AS name, Quantity AS qty, Active FROM Inventory "
                  "WHERE InventoryID = %s", (inv_id,))
        if not row:
            raise DBError("A required inventory item is missing.")
        if not row["active"] or row["qty"] + 0.000001 < need:
            raise DBError(f"Not enough {row['name']} for this order.")
    return required


def checkout(lines, total):
    """Writes the order in one transaction: Orders, OrderItem, OrderItemSelection,
    and inventory decrements. Rolls back on any failure. Returns the OrderID."""
    with transaction() as cur:
        # Serialize checkout and manager stock changes while validating combined demand.
        cur.execute("LOCK TABLE MenuItem, MenuItemInventory IN SHARE MODE")
        cur.execute("LOCK TABLE Inventory IN SHARE ROW EXCLUSIVE MODE")
        cur.execute("LOCK TABLE Orders, OrderItem IN EXCLUSIVE MODE")
        required = validate_cart(lines, cur)

        cur.execute("SELECT (SELECT COALESCE(MAX(OrderID), 0) + 1 FROM Orders) AS oid, "
                    "(SELECT COALESCE(MAX(OrderItemID), 0) FROM OrderItem) AS oiid")
        ids = cur.fetchone()
        order_id, order_item_id = ids["oid"], ids["oiid"]

        cur.execute("INSERT INTO Orders (OrderID, OrderDate, OrderTime, Total) "
                    "VALUES (%s, CURRENT_DATE, CURRENT_TIME, %s)", (order_id, total))

        for line in lines:
            order_item_id += 1
            cur.execute("INSERT INTO OrderItem (OrderItemID, OrderID, MenuItemID, Qty) "
                        "VALUES (%s, %s, %s, %s)",
                        (order_item_id, order_id, line["menu_item_id"], line["qty"]))
            counts = {}
            for comp in line["selections"]:
                counts[comp] = counts.get(comp, 0) + 1
            for comp, n in counts.items():
                cur.execute("INSERT INTO OrderItemSelection (OrderItemID, MenuItemID, Qty) "
                            "VALUES (%s, %s, %s)", (order_item_id, comp, n))

        for inv_id, need in required.items():
            cur.execute("UPDATE Inventory SET Quantity = Quantity - %s "
                        "WHERE InventoryID = %s AND Active = TRUE AND Quantity >= %s",
                        (need, inv_id, need))
            if cur.rowcount != 1:
                raise DBError("Inventory changed; this order cannot be completed.")

        cur.execute("SELECT ItemName AS name FROM Inventory WHERE Quantity < 0")
        neg = cur.fetchone()
        if neg:
            raise DBError(f"Not enough {neg['name']} to complete this order.")
    return order_id


# ---------------------------------------------------------------------------
# Reports (ReportWithFourQuads)
# ---------------------------------------------------------------------------

def sales_report(start, end_exclusive):
    """Qty sold and revenue per menu item. Meal selections count as portions;
    their revenue is included in the meal price."""
    return query(
        "SELECT m.MenuItemName AS name, SUM(oi.Qty) AS qty, SUM(oi.PaidQty * m.Price) AS revenue "
        "FROM Orders o "
        "JOIN (SELECT OrderID, MenuItemID, Qty, Qty AS PaidQty FROM OrderItem "
        "UNION ALL SELECT parent.OrderID, s.MenuItemID, parent.Qty * s.Qty, 0 "
        "FROM OrderItemSelection s "
        "JOIN OrderItem parent ON parent.OrderItemID = s.OrderItemID) oi "
        "ON oi.OrderID = o.OrderID "
        "JOIN MenuItem m ON m.MenuItemID = oi.MenuItemID "
        "WHERE (o.OrderDate + o.OrderTime) >= %s AND (o.OrderDate + o.OrderTime) < %s "
        "GROUP BY m.MenuItemID, m.MenuItemName ORDER BY revenue DESC",
        (start, end_exclusive))


def usage_report(start, end_exclusive):
    """Ingredient usage including meal recipes and selected food."""
    return query(
        "SELECT i.ItemName AS name, i.Unit AS unit, SUM(oi.Qty * mii.QuantityRequired) AS used "
        "FROM Orders o "
        "JOIN (SELECT OrderID, MenuItemID, Qty FROM OrderItem "
        "UNION ALL SELECT parent.OrderID, s.MenuItemID, parent.Qty * s.Qty "
        "FROM OrderItemSelection s "
        "JOIN OrderItem parent ON parent.OrderItemID = s.OrderItemID) oi "
        "ON oi.OrderID = o.OrderID "
        "JOIN MenuItemInventory mii ON mii.MenuItemID = oi.MenuItemID "
        "JOIN Inventory i ON i.InventoryID = mii.InventoryID "
        "WHERE (o.OrderDate + o.OrderTime) >= %s AND (o.OrderDate + o.OrderTime) < %s "
        "GROUP BY i.InventoryID, i.ItemName, i.Unit ORDER BY used DESC",
        (start, end_exclusive))


def restock_report():
    return query(
        f"SELECT ItemName AS name, Quantity AS quantity, {THRESHOLD_COL} AS threshold, "
        f"Unit AS unit FROM Inventory WHERE Active = TRUE AND Quantity < {THRESHOLD_COL} "
        f"ORDER BY ({THRESHOLD_COL} - Quantity) DESC")


def hourly_sales(day=None):
    """24 totals indexed by hour of day, for the X-report."""
    sales = [0.0] * 24
    rows = query("SELECT EXTRACT(HOUR FROM OrderTime)::int AS hour, SUM(Total) AS total "
                 "FROM Orders WHERE OrderDate = %s GROUP BY 1 ORDER BY 1",
                 (day or date.today(),))
    for r in rows:
        if 0 <= r["hour"] < 24:
            sales[r["hour"]] = r["total"] or 0.0
    return sales
