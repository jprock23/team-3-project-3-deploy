"""Panda Express POS - Flask front end (port of the Swing GUI).

Run:  python app.py      then open http://127.0.0.1:5000
"""

import math
from datetime import date, datetime, timedelta
from functools import wraps

import psycopg2
from flask import (Flask, abort, flash, jsonify, redirect, render_template, request,
                   session, url_for)
from markupsafe import Markup

import config
import db
from db import DBError

app = Flask(__name__)
app.secret_key = config.SECRET_KEY
app.teardown_appcontext(db.release_conn)

CATEGORIES = ["Appetizers", "Meals", "Extras", "Drinks"]
# Size suffixes used to group drinks ("NAME - SIZE"); MEDDIUM is a typo in the data
SIZES = ["KIDS", "SMALL", "MEDIUM", "MEDDIUM", "LARGE"]
SIZE_ORDER = {"KIDS": 0, "SMALL": 1, "MEDIUM": 2, "MEDDIUM": 2, "LARGE": 3}


@app.template_filter("money")
def money(v):
    return f"${(v or 0):,.2f}"


@app.template_filter("num")
def num(v):
    return f"{(v or 0):,.2f}"


@app.errorhandler(psycopg2.OperationalError)
def db_down(exc):
    return render_template("error.html", message=str(exc).strip()), 503


def db_error_message(exc):
    if isinstance(exc, DBError):
        return str(exc)
    return getattr(exc, "pgerror", None) or str(exc)


# ---------------------------------------------------------------------------
# Mode select + PIN
# ---------------------------------------------------------------------------

@app.route("/")
def mode_select():
    return render_template("mode_select.html")


@app.route("/pin", methods=["GET", "POST"])
def manager_pin():
    if request.method == "POST":
        if request.form.get("pin", "") == config.MANAGER_PIN:
            session["manager"] = True
            session.pop("cart", None)  # switching mode discards the current order
            return redirect(url_for("inventory_page"))
        flash("Incorrect PIN.", "error")
    return render_template("pin.html")


@app.route("/switch-mode")
def switch_mode():
    session.pop("manager", None)
    return redirect(url_for("mode_select"))


def manager_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if not session.get("manager"):
            return redirect(url_for("manager_pin"))
        return view(*args, **kwargs)
    return wrapped


# ---------------------------------------------------------------------------
# Cashier
# ---------------------------------------------------------------------------

def base_name(name):
    """'COCA-COLA - SMALL' -> 'COCA-COLA'; unchanged if there is no size suffix."""
    head, sep, suffix = name.rpartition(" - ")
    return head if sep and suffix in SIZES else name


def size_label(name):
    s = name.rpartition(" - ")[2]
    return ("MEDIUM" if s == "MEDDIUM" else s).capitalize()


def cart():
    return session.setdefault("cart", [])


def cart_totals(lines):
    sub = sum(l["price"] * l["qty"] for l in lines)
    tax = sub * config.TAX_RATE
    return {"subtotal": sub, "tax": tax, "total": sub + tax}


def render_cashier(category, **extra):
    lines = cart()
    return render_template("cashier.html", categories=CATEGORIES, category=category,
                           lines=lines, totals=cart_totals(lines), **extra)


@app.route("/cashier")
def cashier():
    category = request.args.get("category", "Meals")
    if category not in CATEGORIES:
        category = "Meals"
    try:
        items = db.menu_for_category(category)
    except psycopg2.Error as exc:
        flash("Error loading menu: " + db_error_message(exc), "error")
        items = []

    tiles = []  # {kind: 'item'|'group', ...}
    drink = request.args.get("drink")
    if category == "Drinks":
        groups = {}
        for it in items:
            groups.setdefault(base_name(it["name"]), []).append(it)
        if drink and drink in groups:
            sizes = sorted(groups[drink], key=lambda i: SIZE_ORDER.get(
                i["name"].rpartition(" - ")[2], 4))
            tiles = [dict(kind="item", label=size_label(i["name"]), **i) for i in sizes]
        else:
            drink = None
            for base, group in groups.items():
                if len(group) == 1:
                    tiles.append(dict(kind="item", label=group[0]["name"], **group[0]))
                else:
                    tiles.append({"kind": "group", "label": base})
    else:
        tiles = [dict(kind="item", label=i["name"], **i) for i in items]
    return render_cashier(category, tiles=tiles, drink=drink)


@app.route("/cashier/meal/<int:item_id>")
def cashier_meal(item_id):
    """Side/entree picker for a meal (replaces the Swing combo-box dialog)."""
    category = request.args.get("category", "Meals")
    try:
        item = db.active_menu_item(item_id)
        if item["entree_count"] <= 0:
            return redirect(url_for("cashier", category=category))
        slots = [("Side", db.meal_options("Sides"))]
        entrees = db.meal_options("Entrees")
        slots += [(f"Entree {n}", entrees) for n in range(1, int(item["entree_count"]) + 1)]
    except (DBError, psycopg2.Error) as exc:
        flash(db_error_message(exc), "error")
        return redirect(url_for("cashier", category=category))
    return render_cashier(category, meal=item, slots=slots)


@app.route("/cashier/add", methods=["POST"])
def cashier_add():
    category = request.form.get("category", "Meals")
    back = redirect(url_for("cashier", category=category, drink=request.form.get("drink") or None))
    try:
        item = db.active_menu_item(int(request.form["menu_item_id"]))
        description, selections = item["name"], []
        count = int(item["entree_count"])
        if count > 0:
            chosen = request.form.getlist("selection")
            if len(chosen) != count + 1:
                raise DBError("Choose a side and every entree for this meal.")
            pools = [db.meal_options("Sides")] + [db.meal_options("Entrees")] * count
            for pick, options in zip(chosen, pools):
                match = next((o for o in options if str(o["id"]) == pick), None)
                if match is None:
                    raise DBError("A selected item is no longer available.")
                selections.append(match["id"])
                description += " / " + match["name"]

        lines = cart()
        session["next_line"] = session.get("next_line", 0) + 1
        line = {"key": session["next_line"], "description": description,
                "price": item["price"], "qty": 1, "menu_item_id": item["id"],
                "selections": selections}
        db.validate_cart(lines + [line])  # combined demand of the whole ticket
        lines.append(line)
        session.modified = True
    except (DBError, psycopg2.Error, ValueError, KeyError) as exc:
        flash("Cannot add item: " + db_error_message(exc), "error")
    return back


@app.route("/cashier/remove/<int:key>", methods=["POST"])
def cashier_remove(key):
    lines = cart()
    for line in lines:
        if line["key"] == key:
            if line["qty"] > 1:
                line["qty"] -= 1
            else:
                lines.remove(line)
            break
    session.modified = True
    return redirect(url_for("cashier", category=request.form.get("category", "Meals")))


@app.route("/cashier/checkout", methods=["POST"])
def cashier_checkout():
    category = request.form.get("category", "Meals")
    lines = cart()
    if not lines:
        flash("The order is empty.", "error")
        return redirect(url_for("cashier", category=category))
    total = round(cart_totals(lines)["total"], 2)
    try:
        order_id = db.checkout(lines, total)
    except (DBError, psycopg2.Error) as exc:
        flash("Order failed: " + db_error_message(exc), "error")
        return redirect(url_for("cashier", category=category))
    session["cart"] = []
    flash(f"Order #{order_id} submitted. Total: {money(total)}. Thank you, have a nice day!",
          "success")
    return redirect(url_for("cashier", category=category))


# ---------------------------------------------------------------------------
# Manager: editable tables (Inventory / Menu)
# ---------------------------------------------------------------------------

def parse_non_negative(text, integer=False):
    try:
        v = int(text.strip()) if integer else float(text.strip())
    except (ValueError, AttributeError):
        return None
    if v < 0 or (not integer and not math.isfinite(v)):
        return None
    return v


def collect_changes(form, prefix):
    """Reads value_<id>/orig_<id> pairs; returns ({id: new}, error)."""
    changes = {}
    for key, raw in form.items():
        if not key.startswith(prefix):
            continue
        item_id = int(key[len(prefix):])
        new = parse_non_negative(raw)
        if new is None:
            return None, "Values must be non-negative numbers."
        new = round(new, 2)
        if new != float(form.get(f"orig_{item_id}", "nan")):
            changes[item_id] = new
    return changes, None


@app.route("/manager/inventory", methods=["GET", "POST"])
@manager_required
def inventory_page():
    if request.method == "POST":
        changes, err = collect_changes(request.form, "value_")
        if err:
            flash(err, "error")
        elif not changes:
            flash("No changes to save.", "info")
        else:
            try:
                db.update_quantities(changes)
                flash(f"Saved {len(changes)} change(s).", "success")
            except psycopg2.Error as exc:
                flash("Save failed: " + db_error_message(exc), "error")
        return redirect(url_for("inventory_page"))
    try:
        items = db.fetch_inventory()
    except psycopg2.Error as exc:
        flash("Error loading inventory: " + db_error_message(exc), "error")
        items = []
    return render_template("manager/inventory.html", page="Inventory", items=items)


@app.route("/manager/inventory/<int:item_id>/discontinue", methods=["POST"])
@manager_required
def inventory_discontinue(item_id):
    name = request.form.get("name", "Item")
    try:
        db.discontinue_inventory_item(item_id)
        flash(f'"{name}" was discontinued from inventory.', "success")
    except (DBError, psycopg2.Error) as exc:
        flash("Could not discontinue item: " + db_error_message(exc), "error")
    return redirect(url_for("inventory_page"))


@app.route("/manager/inventory/add", methods=["GET", "POST"])
@manager_required
def inventory_add():
    form = request.form
    error = None
    if request.method == "POST":
        name, unit = form.get("name", "").strip(), form.get("unit", "").strip()
        threshold = parse_non_negative(form.get("threshold", ""))
        quantity = parse_non_negative(form.get("quantity", ""))
        shelf = parse_non_negative(form.get("shelf_life", ""), integer=True)
        if not name:
            error = "Item name is required."
        elif len(name) > 100:
            error = "Item name is too long (max 100 characters)."
        elif not unit:
            error = "Unit is required."
        elif len(unit) > 20:
            error = "Unit is too long (max 20 characters)."
        elif threshold is None:
            error = "Minimum threshold must be a non-negative number."
        elif quantity is None:
            error = "Starting quantity must be a non-negative number."
        elif shelf is None:
            error = "Shelf life must be a non-negative whole number."
        else:
            try:
                db.insert_item(name, unit, threshold, quantity, shelf)
                flash(f'"{name}" was added to inventory.', "success")
                return redirect(url_for("inventory_page"))
            except (DBError, psycopg2.Error) as exc:
                error = "Add failed: " + db_error_message(exc)
    return render_template("manager/add_item.html", page="Inventory", form=form, error=error)


@app.route("/manager/inventory/reactivate", methods=["GET", "POST"])
@manager_required
def inventory_reactivate():
    error = None
    try:
        inactive = db.fetch_inventory(active=False)
    except psycopg2.Error as exc:
        flash("Could not load discontinued inventory items: " + db_error_message(exc), "error")
        return redirect(url_for("inventory_page"))
    if not inactive:
        flash("There are no discontinued inventory items to reactivate.", "info")
        return redirect(url_for("inventory_page"))
    if request.method == "POST":
        item = next((i for i in inactive if str(i["id"]) == request.form.get("item_id")), None)
        qty = parse_non_negative(request.form.get("quantity", ""))
        if item is None:
            error = "Select an inventory item."
        elif qty is None:
            error = "Starting quantity must be a non-negative number."
        else:
            try:
                db.reactivate_inventory_item(item["id"], qty)
                flash(f'"{item["name"]}" was reactivated.', "success")
                return redirect(url_for("inventory_page"))
            except (DBError, psycopg2.Error) as exc:
                error = "Reactivation failed: " + db_error_message(exc)
    return render_template("manager/reactivate.html", page="Inventory", items=inactive,
                           error=error, form=request.form)


@app.route("/manager/menu", methods=["GET", "POST"])
@manager_required
def menu_page():
    if request.method == "POST":
        changes, err = collect_changes(request.form, "value_")
        if err:
            flash(err, "error")
        elif not changes:
            flash("No changes to save.", "info")
        else:
            try:
                db.update_prices(changes)
                flash(f"Saved {len(changes)} change(s).", "success")
            except psycopg2.Error as exc:
                flash("Save failed: " + db_error_message(exc), "error")
        return redirect(url_for("menu_page"))
    try:
        items = db.fetch_menu_items()
    except psycopg2.Error as exc:
        flash("Error loading menu prices: " + db_error_message(exc), "error")
        items = []
    return render_template("manager/menu.html", page="Menu", items=items)


@app.route("/manager/menu/<int:item_id>/discontinue", methods=["POST"])
@manager_required
def menu_discontinue(item_id):
    name = request.form.get("name", "Item")
    try:
        db.discontinue_menu_item(item_id)
        flash(f'"{name}" was discontinued.', "success")
    except (DBError, psycopg2.Error) as exc:
        flash("Could not discontinue menu item: " + db_error_message(exc), "error")
    return redirect(url_for("menu_page"))


@app.route("/manager/menu/add", methods=["GET", "POST"])
@manager_required
def menu_add():
    form = request.form
    error = None
    try:
        inventory = db.fetch_inventory()
    except psycopg2.Error as exc:
        inventory = []
        error = "Could not load recipe ingredients: " + db_error_message(exc)

    if request.method == "POST" and not error:
        name = form.get("name", "").strip()
        category = form.get("category")
        try:
            price = float(form.get("price", ""))
        except ValueError:
            price = None
        if not name or len(name) > 150:
            error = "Enter a menu item name of at most 150 characters."
        elif price is None:
            error = "Price must be a valid number."
        elif not math.isfinite(price) or price < 0 or price > 99999999.99:
            error = "Price must be finite and between 0 and 99,999,999.99."
        elif category not in CATEGORIES:
            error = "Choose a category."
        else:
            ingredients = {}
            for inv in inventory:
                text = form.get(f"qty_{inv['id']}", "").strip()
                if not text:
                    continue
                if not db.valid_recipe_qty(text):
                    error = (f"Quantity for {inv['name']} must be positive, at most "
                             "9,999,999.999, with at most three decimal places.")
                    break
                ingredients[inv["id"]] = float(text)
            if not error:
                try:
                    _, reactivated = db.insert_menu_item(name, category, price, ingredients)
                    flash(f'"{name}" was {"reactivated on" if reactivated else "added to"} '
                          "the menu.", "success")
                    return redirect(url_for("menu_page"))
                except (DBError, psycopg2.Error) as exc:
                    error = "Could not add menu item: " + db_error_message(exc)
    return render_template("manager/add_menu_item.html", page="Menu", form=form, error=error,
                           inventory=inventory, categories=CATEGORIES)


# ---------------------------------------------------------------------------
# Manager: order history, reports, schedule
# ---------------------------------------------------------------------------

@app.route("/manager/orders")
@manager_required
def orders_page():
    try:
        orders = db.fetch_today_orders()
    except psycopg2.Error as exc:
        flash("Error loading order history: " + db_error_message(exc), "error")
        orders = None
    return render_template("manager/orders.html", page="Order History", orders=orders)


@app.route("/manager/orders/<int:order_id>.json")
@manager_required
def order_items(order_id):
    try:
        return jsonify(items=db.fetch_order_items(order_id))
    except psycopg2.Error as exc:
        return jsonify(error=db_error_message(exc)), 500


def parse_dt(text, default):
    try:
        return datetime.strptime(text, "%Y-%m-%dT%H:%M")
    except (TypeError, ValueError):
        return default


def run_range_report(prefix, fn):
    """Reads <prefix>_from/<prefix>_to (default: 6 days ago 00:00 to today 23:59)."""
    today = date.today()
    start = parse_dt(request.args.get(f"{prefix}_from"),
                     datetime.combine(today - timedelta(days=6), datetime.min.time()))
    end = parse_dt(request.args.get(f"{prefix}_to"), datetime.combine(today, datetime.min.time())
                   .replace(hour=23, minute=59))
    result = {"from": start.strftime("%Y-%m-%dT%H:%M"), "to": end.strftime("%Y-%m-%dT%H:%M"),
              "rows": [], "error": None}
    if start > end:
        result["error"] = "Start must be on or before end"
        return result
    try:
        result["rows"] = fn(start, end + timedelta(minutes=1))
    except psycopg2.Error as exc:
        result["error"] = "Query failed: " + db_error_message(exc)
    return result


def xreport_svg(sales):
    """Hourly sales line chart (port of XReportGraph), as inline SVG."""
    w, h, left, right, top, bottom = 560, 280, 60, 20, 20, 50
    gw, gh = w - left - right, h - top - bottom
    peak = max(100.0, math.ceil(max(sales) / 100.0) * 100)
    x = lambda hr: left + hr * gw / 23
    y = lambda v: top + gh - v / peak * gh
    parts = [f'<svg class="xchart" viewBox="0 0 {w} {h}" role="img" '
             f'aria-label="Hourly sales today">']
    for i in range(6):
        gy = top + gh - i * gh / 5
        parts.append(f'<line class="grid" x1="{left}" y1="{gy}" x2="{left + gw}" y2="{gy}"/>')
        parts.append(f'<text class="ylab" x="{left - 8}" y="{gy + 4}">${peak * i / 5:,.0f}</text>')
    parts.append(f'<line class="axis" x1="{left}" y1="{top}" x2="{left}" y2="{top + gh}"/>')
    parts.append(f'<line class="axis" x1="{left}" y1="{top + gh}" x2="{left + gw}" '
                 f'y2="{top + gh}"/>')
    pts = " ".join(f"{x(hr):.1f},{y(v):.1f}" for hr, v in enumerate(sales))
    parts.append(f'<polyline class="line" points="{pts}"/>')
    for hr, v in enumerate(sales):
        parts.append(f'<circle class="pt" cx="{x(hr):.1f}" cy="{y(v):.1f}" r="3">'
                     f'<title>{hr:02d}:00 — ${v:,.2f}</title></circle>')
    for hr in range(0, 24, 2):
        lab = "12 AM" if hr == 0 else f"{hr} AM" if hr < 12 else "12 PM" if hr == 12 \
            else f"{hr - 12} PM"
        parts.append(f'<text class="xlab" x="{x(hr):.1f}" y="{top + gh + 18}">{lab}</text>')
    parts.append(f'<text class="xtitle" x="{left + gw / 2}" y="{h - 6}">Hour of Day</text>')
    parts.append("</svg>")
    return Markup("".join(parts))


@app.route("/manager/reports")
@manager_required
def reports_page():
    sales = run_range_report("sales", db.sales_report)
    usage = run_range_report("usage", db.usage_report)
    restock = {"rows": [], "error": None}
    try:
        restock["rows"] = db.restock_report()
    except psycopg2.Error as exc:
        restock["error"] = "Query failed: " + db_error_message(exc)
    xreport = {"error": None, "chart": None, "total": 0.0}
    try:
        hourly = db.hourly_sales()
        xreport["chart"], xreport["total"] = xreport_svg(hourly), sum(hourly)
    except psycopg2.Error as exc:
        xreport["error"] = "Query failed: " + db_error_message(exc)
    return render_template("manager/reports.html", page="Reports", sales=sales, usage=usage,
                           restock=restock, xreport=xreport)


@app.route("/manager/schedule")
@manager_required
def schedule_page():
    return render_template("manager/schedule.html", page="Schedule")


if __name__ == "__main__":
    app.run(debug=True)
