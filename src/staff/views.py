"""Cashier and manager pages (Flask port of the Java CashierGUI / ManagerGUI)."""

import hmac
import math
from datetime import datetime, timedelta
from functools import wraps
from zoneinfo import ZoneInfo

import psycopg
from flask import (Blueprint, current_app, flash, jsonify, redirect, render_template, request,
                   session, url_for)
from markupsafe import Markup

from ..customer import db as customer_db
from . import db
from .db import DBError, DBUnavailable

bp = Blueprint('staff', __name__, template_folder='templates',
               static_folder='static', static_url_path='/staff-assets')
bp.teardown_app_request(db.close_conn)

CATEGORIES = ['Appetizers', 'Meals', 'Extras', 'Drinks']
# Size suffixes used to group drinks ("NAME - SIZE"); MEDDIUM is a typo in the data
SIZES = ['KIDS', 'SMALL', 'MEDIUM', 'MEDDIUM', 'LARGE']
SIZE_ORDER = {'KIDS': 0, 'SMALL': 1, 'MEDIUM': 2, 'MEDDIUM': 2, 'LARGE': 3}
MAX_CART_LINES = 30  # keeps the session cookie well under the 4 KB browser limit
DB_ERRORS = (DBError, psycopg.Error)


@bp.app_template_filter('money')
def money(v):
    v = v or 0
    return f'-${-v:,.2f}' if v < 0 else f'${v:,.2f}'


@bp.app_template_filter('num')
def num(v):
    return f'{(v or 0):,.2f}'


@bp.app_template_filter('pct')
def pct(v):
    return '--' if v is None else f'{v:.1f}%'


@bp.errorhandler(DBUnavailable)
def db_unavailable(_exc):
    return render_template('staff/error.html', title="Can't reach the database",
                           message='Check the database settings in .env and try again.'), 503


def today():
    return datetime.now(ZoneInfo(customer_db.timezone_name())).date()


# ---------------------------------------------------------------------------
# Mode select + manager PIN
# ---------------------------------------------------------------------------

@bp.route('/staff')
def mode_select():
    return render_template('staff/mode_select.html')


@bp.route('/manager/login', methods=['GET', 'POST'])
def manager_login():
    if request.method == 'POST':
        if hmac.compare_digest(request.form.get('pin', ''), current_app.config['MANAGER_PIN']):
            session['manager'] = True
            session.pop('staff_cart', None)  # switching mode discards the current order
            return redirect(url_for('.inventory_page'))
        flash('Incorrect PIN.', 'error')
    return render_template('staff/pin.html')


@bp.route('/manager/logout')
def manager_logout():
    session.pop('manager', None)
    return redirect(url_for('.mode_select'))


def manager_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if not session.get('manager'):
            return redirect(url_for('.manager_login'))
        return view(*args, **kwargs)
    return wrapped


# ---------------------------------------------------------------------------
# Cashier
# ---------------------------------------------------------------------------
# Session cart lines are {key, menu_item_id, selections, qty, price}; descriptions are
# rebuilt from MenuItem names when the page renders, to keep the cookie small.

def base_name(name):
    """'COCA-COLA - SMALL' -> 'COCA-COLA'; unchanged if there is no size suffix."""
    head, sep, suffix = name.rpartition(' - ')
    return head if sep and suffix in SIZES else name


def size_label(name):
    s = name.rpartition(' - ')[2]
    return ('MEDIUM' if s == 'MEDDIUM' else s).capitalize()


def cart():
    return session.setdefault('staff_cart', [])


def find_line(key):
    return next((line for line in cart() if line['key'] == key), None)


def new_key():
    session['staff_next_line'] = session.get('staff_next_line', 0) + 1
    return session['staff_next_line']


def cart_totals(lines):
    sub = sum(line['price'] * line['qty'] for line in lines)
    tax = sub * float(customer_db.TAX_RATE)
    return {'subtotal': sub, 'tax': tax, 'total': sub + tax}


def describe(lines):
    """Ticket text for each line: 'PLATE / FRIED RICE / ORANGE CHICKEN'."""
    ids = sorted({i for line in lines for i in [line['menu_item_id'], *line['selections']]})
    names = {}
    if ids:
        names = {r['id']: r['name'] for r in db.query(
            'SELECT MenuItemID AS id, MenuItemName AS name FROM MenuItem WHERE MenuItemID = ANY(%s)',
            (ids,))}
    return [dict(line, description=' / '.join(names.get(i, f'#{i}') for i in
                                              [line['menu_item_id'], *line['selections']]))
            for line in lines]


def back_to_cashier(category=None, **args):
    category = category or request.values.get('category', 'Meals')
    return redirect(url_for('.cashier', category=category, **args))


def render_cashier(category, **extra):
    lines = cart()
    try:
        described = describe(lines)
    except psycopg.Error as exc:
        flash('Error loading ticket: ' + db.error_message(exc), 'error')
        described = [dict(line, description=f"Item #{line['menu_item_id']}") for line in lines]
    return render_template('staff/cashier.html', categories=CATEGORIES, category=category,
                           lines=described, totals=cart_totals(lines), **extra)


def add_line(line):
    """Validates combined stock for the cart plus a new line, then adds it."""
    lines = cart()
    if len(lines) >= MAX_CART_LINES:
        raise DBError(f'A ticket can have at most {MAX_CART_LINES} lines.')
    db.validate_cart(lines + [line])
    lines.append(line)
    session.modified = True


@bp.route('/cashier')
def cashier():
    category = request.args.get('category', 'Meals')
    if category not in CATEGORIES:
        category = 'Meals'
    try:
        items = db.menu_for_category(category)
    except psycopg.Error as exc:
        flash('Error loading menu: ' + db.error_message(exc), 'error')
        items = []

    tiles = []
    drink = request.args.get('drink')
    if category == 'Drinks':
        groups = {}
        for it in items:
            groups.setdefault(base_name(it['name']), []).append(it)
        if drink and drink in groups:
            sizes = sorted(groups[drink], key=lambda i: SIZE_ORDER.get(i['name'].rpartition(' - ')[2], 4))
            tiles = [dict(kind='item', label=size_label(i['name']), **i) for i in sizes]
        else:
            drink = None
            for base, group in groups.items():
                if len(group) == 1:
                    tiles.append(dict(kind='item', label=group[0]['name'], **group[0]))
                else:
                    tiles.append({'kind': 'group', 'label': base})
    else:
        tiles = [dict(kind='item', label=i['name'], **i) for i in items]
    return render_cashier(category, tiles=tiles, drink=drink)


@bp.route('/cashier/meal/<int:item_id>')
def cashier_meal(item_id):
    """Side/entree picker for a meal (replaces the Swing combo-box dialog)."""
    category = request.args.get('category', 'Meals')
    try:
        item = db.active_menu_item(item_id)
        if item['entree_count'] <= 0:
            return back_to_cashier(category)
        entrees = db.meal_options('Entrees')
        slots = [('Side', db.meal_options('Sides'))]
        slots += [(f'Entree {n}', entrees) for n in range(1, int(item['entree_count']) + 1)]
    except DB_ERRORS as exc:
        flash(db.error_message(exc), 'error')
        return back_to_cashier(category)
    return render_cashier(category, meal=item, slots=slots)


@bp.route('/cashier/add', methods=['POST'])
def cashier_add():
    try:
        item = db.active_menu_item(int(request.form['menu_item_id']))
        selections = []
        count = int(item['entree_count'])
        if count > 0:
            chosen = request.form.getlist('selection')
            if len(chosen) != count + 1:
                raise DBError('Choose a side and every entree for this meal.')
            pools = [db.meal_options('Sides')] + [db.meal_options('Entrees')] * count
            for pick, options in zip(chosen, pools):
                match = next((o for o in options if str(o['id']) == pick), None)
                if match is None:
                    raise DBError('A selected item is no longer available.')
                selections.append(match['id'])
        add_line({'key': new_key(), 'menu_item_id': item['id'], 'selections': selections,
                  'qty': 1, 'price': item['price']})
    except (*DB_ERRORS, ValueError, KeyError) as exc:
        flash('Cannot add item: ' + db.error_message(exc), 'error')
    return back_to_cashier(drink=request.form.get('drink') or None)


@bp.route('/cashier/remove/<int:key>', methods=['POST'])
def cashier_remove(key):
    line = find_line(key)
    if line:
        if line['qty'] > 1:
            line['qty'] -= 1
        else:
            cart().remove(line)
        session.modified = True
    return back_to_cashier()


@bp.route('/cashier/duplicate/<int:key>', methods=['POST'])
def cashier_duplicate(key):
    """Copies a ticket line (meal selections included) as a new line; stock is re-validated."""
    line = find_line(key)
    if line:
        try:
            add_line(dict(line, key=new_key(), qty=1, selections=list(line['selections'])))
        except DB_ERRORS as exc:
            flash('Cannot duplicate item: ' + db.error_message(exc), 'error')
    return back_to_cashier()


def meal_editor(line, components):
    """Data for editing a bowl/plate: names, kinds, standalone prices, and live price."""
    meal = db.active_menu_item(line['menu_item_id'])
    options = {cat: db.meal_options(cat) for cat in ('Sides', 'Entrees')}
    kinds = {o['id']: cat for cat, opts in options.items() for o in opts}
    prices = {o['id']: o['price'] for opts in options.values() for o in opts}
    names = {o['id']: o['name'] for opts in options.values() for o in opts}
    if any(c not in kinds for c in components):
        raise DBError('A component of this meal is no longer available. Remove the line and add it again.')
    free = {'Sides': 1, 'Entrees': int(meal['entree_count'])}
    rows = []
    for c in components:
        kind = kinds[c]
        extra = free[kind] == 0
        if not extra:
            free[kind] -= 1
        rows.append({'id': c, 'name': names[c], 'extra': prices[c] if extra else None})
    price = db.meal_price(meal['price'], int(meal['entree_count']), components, kinds, prices)
    return {'line': line, 'meal': meal, 'options': options, 'rows': rows, 'kinds': kinds,
            'price': price, 'components': components}


@bp.route('/cashier/edit/<int:key>', methods=['GET', 'POST'])
def cashier_edit(key):
    """Edit a bowl/plate in place: remove components (keeping at least one side and one
    entree) or add extra sides/entrees at their standalone prices."""
    category = request.values.get('category', 'Meals')
    line = find_line(key)
    if line is None:
        return back_to_cashier(category)
    if not line['selections']:
        flash('That line is not a bowl or plate. Select a meal to edit.', 'error')
        return back_to_cashier(category)
    error = None
    try:
        if request.method == 'GET':
            components = list(line['selections'])
        else:
            components = [int(c) for c in request.form.getlist('comp')]
        editor = meal_editor(line, components)
        if request.method == 'POST':
            op = request.form.get('op', '')
            if op.startswith('remove-'):
                i = int(op[len('remove-'):])
                kind = editor['kinds'][components[i]]
                if sum(1 for c in components if editor['kinds'][c] == kind) == 1:
                    error = 'A meal must keep at least one side and one entree.'
                else:
                    components.pop(i)
            elif op in ('add-Sides', 'add-Entrees'):
                cat = op[len('add-'):]
                pick = int(request.form.get(f'pick-{cat}', ''))
                if pick not in {o['id'] for o in editor['options'][cat]}:
                    raise DBError('That item is no longer available.')
                components.append(pick)
            elif op == 'save':
                edited = dict(line, selections=components, price=editor['price'])
                db.validate_cart([edited if l['key'] == key else l for l in cart()])
                line.update(selections=components, price=editor['price'])
                session.modified = True
                return back_to_cashier(category)
            editor = meal_editor(line, components)
    except (ValueError, IndexError):
        return back_to_cashier(category)
    except DB_ERRORS as exc:
        if request.method == 'GET':
            flash('Cannot edit meal: ' + db.error_message(exc), 'error')
            return back_to_cashier(category)
        error = db.error_message(exc)
        try:
            editor = meal_editor(line, components)
        except DB_ERRORS as exc2:
            flash('Cannot edit meal: ' + db.error_message(exc2), 'error')
            return back_to_cashier(category)
    return render_cashier(category, editor=editor, edit_error=error)


@bp.route('/cashier/checkout', methods=['POST'])
def cashier_checkout():
    lines = cart()
    if not lines:
        flash('The order is empty.', 'error')
        return back_to_cashier()
    total = round(cart_totals(lines)['total'], 2)
    try:
        order_id = db.checkout(lines, total)
    except DB_ERRORS as exc:
        flash('Order failed: ' + db.error_message(exc), 'error')
        return back_to_cashier()
    session['staff_cart'] = []
    flash(f'Order #{order_id} submitted. Total: {money(total)}. Thank you, have a nice day!', 'success')
    return back_to_cashier()


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


def collect_changes(form):
    """Reads value_<id> / orig_<id> pairs; returns ({id: new value}, error)."""
    changes = {}
    for key, raw in form.items():
        if not key.startswith('value_'):
            continue
        item_id = int(key[len('value_'):])
        new = parse_non_negative(raw)
        if new is None:
            return None, 'Values must be non-negative numbers.'
        new = round(new, 2)
        if new != float(form.get(f'orig_{item_id}', 'nan')):
            changes[item_id] = new
    return changes, None


def save_table(store):
    changes, err = collect_changes(request.form)
    if err:
        flash(err, 'error')
    elif not changes:
        flash('No changes to save.', 'info')
    else:
        try:
            store(changes)
            flash(f'Saved {len(changes)} change(s).', 'success')
        except DB_ERRORS as exc:
            flash('Save failed: ' + db.error_message(exc), 'error')


@bp.route('/manager/inventory', methods=['GET', 'POST'])
@manager_required
def inventory_page():
    if request.method == 'POST':
        save_table(db.update_quantities)
        return redirect(url_for('.inventory_page'))
    try:
        items = db.fetch_inventory()
    except DB_ERRORS as exc:
        flash('Error loading inventory: ' + db.error_message(exc), 'error')
        items = []
    return render_template('staff/manager/inventory.html', page='Inventory', items=items)


@bp.route('/manager/inventory/<int:item_id>/discontinue', methods=['POST'])
@manager_required
def inventory_discontinue(item_id):
    name = request.form.get('name', 'Item')
    try:
        db.discontinue_inventory_item(item_id)
        flash(f'"{name}" was discontinued from inventory.', 'success')
    except DB_ERRORS as exc:
        flash('Could not discontinue item: ' + db.error_message(exc), 'error')
    return redirect(url_for('.inventory_page'))


@bp.route('/manager/inventory/add', methods=['GET', 'POST'])
@manager_required
def inventory_add():
    form = request.form
    error = None
    if request.method == 'POST':
        name, unit = form.get('name', '').strip(), form.get('unit', '').strip()
        threshold = parse_non_negative(form.get('threshold', ''))
        quantity = parse_non_negative(form.get('quantity', ''))
        shelf = parse_non_negative(form.get('shelf_life', ''), integer=True)
        if not name:
            error = 'Item name is required.'
        elif len(name) > 100:
            error = 'Item name is too long (max 100 characters).'
        elif not unit:
            error = 'Unit is required.'
        elif len(unit) > 20:
            error = 'Unit is too long (max 20 characters).'
        elif threshold is None:
            error = 'Minimum threshold must be a non-negative number.'
        elif quantity is None:
            error = 'Starting quantity must be a non-negative number.'
        elif shelf is None:
            error = 'Shelf life must be a non-negative whole number.'
        else:
            try:
                db.insert_item(name, unit, threshold, quantity, shelf)
                flash(f'"{name}" was added to inventory.', 'success')
                return redirect(url_for('.inventory_page'))
            except DB_ERRORS as exc:
                error = 'Add failed: ' + db.error_message(exc)
    return render_template('staff/manager/add_item.html', page='Inventory', form=form, error=error)


@bp.route('/manager/inventory/reactivate', methods=['GET', 'POST'])
@manager_required
def inventory_reactivate():
    error = None
    try:
        inactive = db.fetch_inventory(active=False)
    except DB_ERRORS as exc:
        flash('Could not load discontinued inventory items: ' + db.error_message(exc), 'error')
        return redirect(url_for('.inventory_page'))
    if not inactive:
        flash('There are no discontinued inventory items to reactivate.', 'info')
        return redirect(url_for('.inventory_page'))
    if request.method == 'POST':
        item = next((i for i in inactive if str(i['id']) == request.form.get('item_id')), None)
        qty = parse_non_negative(request.form.get('quantity', ''))
        if item is None:
            error = 'Select an inventory item.'
        elif qty is None:
            error = 'Starting quantity must be a non-negative number.'
        else:
            try:
                db.reactivate_inventory_item(item['id'], qty)
                flash(f'"{item["name"]}" was reactivated.', 'success')
                return redirect(url_for('.inventory_page'))
            except DB_ERRORS as exc:
                error = 'Reactivation failed: ' + db.error_message(exc)
    return render_template('staff/manager/reactivate.html', page='Inventory', items=inactive,
                           error=error, form=request.form)


@bp.route('/manager/menu', methods=['GET', 'POST'])
@manager_required
def menu_page():
    if request.method == 'POST':
        save_table(db.update_prices)
        return redirect(url_for('.menu_page'))
    try:
        items = db.fetch_menu_items()
    except DB_ERRORS as exc:
        flash('Error loading menu prices: ' + db.error_message(exc), 'error')
        items = []
    return render_template('staff/manager/menu.html', page='Menu', items=items)


@bp.route('/manager/menu/<int:item_id>/discontinue', methods=['POST'])
@manager_required
def menu_discontinue(item_id):
    name = request.form.get('name', 'Item')
    try:
        db.discontinue_menu_item(item_id)
        flash(f'"{name}" was discontinued.', 'success')
    except DB_ERRORS as exc:
        flash('Could not discontinue menu item: ' + db.error_message(exc), 'error')
    return redirect(url_for('.menu_page'))


@bp.route('/manager/menu/add', methods=['GET', 'POST'])
@manager_required
def menu_add():
    form = request.form
    error = None
    try:
        inventory = db.fetch_inventory()
    except DB_ERRORS as exc:
        inventory = []
        error = 'Could not load recipe ingredients: ' + db.error_message(exc)

    if request.method == 'POST' and not error:
        name = form.get('name', '').strip()
        category = form.get('category')
        try:
            price = float(form.get('price', ''))
        except ValueError:
            price = None
        if not name or len(name) > 150:
            error = 'Enter a menu item name of at most 150 characters.'
        elif price is None:
            error = 'Price must be a valid number.'
        elif not math.isfinite(price) or price < 0 or price > 99999999.99:
            error = 'Price must be finite and between 0 and 99,999,999.99.'
        elif category not in CATEGORIES:
            error = 'Choose a category.'
        else:
            ingredients = {}
            for inv in inventory:
                text = form.get(f"qty_{inv['id']}", '').strip()
                if not text:
                    continue
                if not db.valid_recipe_qty(text):
                    error = (f"Quantity for {inv['name']} must be positive, at most "
                             '9,999,999.999, with at most three decimal places.')
                    break
                ingredients[inv['id']] = float(text)
            if not error:
                try:
                    _, reactivated = db.insert_menu_item(name, category, price, ingredients)
                    verb = 'reactivated on' if reactivated else 'added to'
                    flash(f'"{name}" was {verb} the menu.', 'success')
                    return redirect(url_for('.menu_page'))
                except DB_ERRORS as exc:
                    error = 'Could not add menu item: ' + db.error_message(exc)
    return render_template('staff/manager/add_menu_item.html', page='Menu', form=form, error=error,
                           inventory=inventory, categories=CATEGORIES)


# ---------------------------------------------------------------------------
# Manager: order history and reports
# ---------------------------------------------------------------------------

@bp.route('/manager/orders')
@manager_required
def orders_page():
    try:
        orders = db.fetch_today_orders()
    except DB_ERRORS as exc:
        flash('Error loading order history: ' + db.error_message(exc), 'error')
        orders = None
    return render_template('staff/manager/orders.html', page='Order History', orders=orders)


@bp.route('/manager/orders/<int:order_id>.json')
@manager_required
def order_items(order_id):
    try:
        return jsonify(items=db.fetch_order_items(order_id))
    except DB_ERRORS as exc:
        return jsonify(error=db.error_message(exc)), 500


def parse_dt(text, default, fmt='%Y-%m-%dT%H:%M'):
    try:
        return datetime.strptime(text, fmt)
    except (TypeError, ValueError):
        return default


def run_range_report(prefix, fn):
    """Reads <prefix>_from/<prefix>_to (default: 6 days ago 00:00 through today 23:59)."""
    day = today()
    start = parse_dt(request.args.get(f'{prefix}_from'),
                     datetime.combine(day - timedelta(days=6), datetime.min.time()))
    end = parse_dt(request.args.get(f'{prefix}_to'),
                   datetime.combine(day, datetime.min.time()).replace(hour=23, minute=59))
    result = {'from': start.strftime('%Y-%m-%dT%H:%M'), 'to': end.strftime('%Y-%m-%dT%H:%M'),
              'rows': [], 'error': None}
    if start > end:
        result['error'] = 'Start must be on or before end'
        return result
    try:
        result['rows'] = fn(start, end + timedelta(minutes=1))
    except DB_ERRORS as exc:
        result['error'] = 'Query failed: ' + db.error_message(exc)
    return result


def run_margin_report():
    """Daily Sales & Profit Margin: whole days, default the last 7 days."""
    day = today()
    start = parse_dt(request.args.get('margin_from'), day - timedelta(days=6), '%Y-%m-%d')
    end = parse_dt(request.args.get('margin_to'), day, '%Y-%m-%d')
    start = start.date() if isinstance(start, datetime) else start
    end = end.date() if isinstance(end, datetime) else end
    result = {'from': start.isoformat(), 'to': end.isoformat(), 'rows': [], 'error': None}
    if start > end:
        result['error'] = 'Start must be on or before end'
        return result
    try:
        result['rows'] = db.profit_margin_report(start, end)
    except DB_ERRORS as exc:
        result['error'] = 'Query failed: ' + db.error_message(exc)
    return result


def xreport_svg(sales):
    """Hourly sales line chart (port of XReportGraph), as inline SVG."""
    w, h, left, right, top, bottom = 560, 280, 60, 20, 20, 50
    gw, gh = w - left - right, h - top - bottom
    peak = max(100.0, math.ceil(max(sales) / 100.0) * 100)

    def x(hr):
        return left + hr * gw / 23

    def y(v):
        return top + gh - v / peak * gh

    parts = [f'<svg class="xchart" viewBox="0 0 {w} {h}" role="img" aria-label="Hourly sales today">']
    for i in range(6):
        gy = top + gh - i * gh / 5
        parts.append(f'<line class="grid" x1="{left}" y1="{gy}" x2="{left + gw}" y2="{gy}"/>')
        parts.append(f'<text class="ylab" x="{left - 8}" y="{gy + 4}">${peak * i / 5:,.0f}</text>')
    parts.append(f'<line class="axis" x1="{left}" y1="{top}" x2="{left}" y2="{top + gh}"/>')
    parts.append(f'<line class="axis" x1="{left}" y1="{top + gh}" x2="{left + gw}" y2="{top + gh}"/>')
    pts = ' '.join(f'{x(hr):.1f},{y(v):.1f}' for hr, v in enumerate(sales))
    parts.append(f'<polyline class="line" points="{pts}"/>')
    for hr, v in enumerate(sales):
        parts.append(f'<circle class="pt" cx="{x(hr):.1f}" cy="{y(v):.1f}" r="3">'
                     f'<title>{hr:02d}:00 — ${v:,.2f}</title></circle>')
    for hr in range(0, 24, 2):
        lab = '12 AM' if hr == 0 else f'{hr} AM' if hr < 12 else '12 PM' if hr == 12 else f'{hr - 12} PM'
        parts.append(f'<text class="xlab" x="{x(hr):.1f}" y="{top + gh + 18}">{lab}</text>')
    parts.append(f'<text class="xtitle" x="{left + gw / 2}" y="{h - 6}">Hour of Day</text></svg>')
    return Markup(''.join(parts))


@bp.route('/manager/reports')
@manager_required
def reports_page():
    sales = run_range_report('sales', db.sales_report)
    margin = run_margin_report()
    usage = run_range_report('usage', db.usage_report)
    restock = {'rows': [], 'error': None}
    try:
        restock['rows'] = db.restock_report()
    except DB_ERRORS as exc:
        restock['error'] = 'Query failed: ' + db.error_message(exc)
    xreport = {'error': None, 'chart': None, 'total': 0.0}
    try:
        hourly = db.hourly_sales()
        xreport['chart'], xreport['total'] = xreport_svg(hourly), sum(hourly)
    except DB_ERRORS as exc:
        xreport['error'] = 'Query failed: ' + db.error_message(exc)
    return render_template('staff/manager/reports.html', page='Reports', sales=sales, margin=margin,
                           usage=usage, restock=restock, xreport=xreport)
