"""Customer ordering pages and API (a blueprint of the combined app in src/app.py).

Run from the repository root: flask --app src.app run
(flask --app src.customer.app run still works and starts the same combined app.)
"""

from uuid import UUID

from authlib.integrations.base_client.errors import OAuthError
from flask import (Blueprint, current_app, jsonify, redirect, render_template, request,
                   send_from_directory, session, url_for)
import psycopg
from requests.exceptions import RequestException

from . import db

bp = Blueprint('customer', __name__, template_folder='templates',
               static_folder='static', static_url_path='/static')


def create_app(test_config=None):
    """Kept so existing commands and tests keep working; builds the combined app."""
    from ..app import create_app as create_combined_app
    return create_combined_app(test_config)


def google():
    return current_app.extensions['authlib.integrations.flask_client'].google


@bp.errorhandler(db.OrderError)
def invalid_order(error):
    return jsonify(error=str(error)), 409


@bp.errorhandler(psycopg.Error)
@bp.errorhandler(RuntimeError)
def database_error(error):
    current_app.logger.error('Customer database operation failed: %s', type(error).__name__)
    return jsonify(error='The ordering service is unavailable. Please try again shortly.'), 503


@bp.get('/')
def index():
    return render_template('index.html', csrf=session['csrf'],
                           google_enabled=bool(current_app.config['GOOGLE_CLIENT_ID']
                                               and current_app.config['GOOGLE_CLIENT_SECRET']))


@bp.get('/images/<path:filename>')
def image(filename):
    return send_from_directory(db.IMAGES, filename, max_age=86400)


@bp.get('/api/menu')
def menu():
    return jsonify(items=db.read_menu())


@bp.get('/api/session')
def identity():
    customer = db.account(session['customer_id']) if session.get('customer_id') else None
    if session.get('customer_id') and not customer:
        session.pop('customer_id')
    return jsonify(customer=customer, guest=bool(session.get('guest')), csrf=session['csrf'])


@bp.post('/auth/guest')
def guest():
    session.pop('customer_id', None)
    session['guest'] = True
    return jsonify(ok=True)


@bp.post('/auth/logout')
def logout():
    # Keep browser_key so a timed-out checkout can still be recovered after signing out.
    session.pop('customer_id', None)
    session.pop('guest', None)
    return jsonify(ok=True)


@bp.get('/auth/google')
def login():
    config = current_app.config
    if not config['GOOGLE_CLIENT_ID'] or not config['GOOGLE_CLIENT_SECRET']:
        return redirect(url_for('.index', auth_error='unconfigured'))
    callback_uri = config['GOOGLE_REDIRECT_URI'] or url_for('.callback', _external=True)
    return google().authorize_redirect(callback_uri)


@bp.get('/auth/google/callback')
def callback():
    try:
        token = google().authorize_access_token()
        info = token.get('userinfo')
        if not info or not info.get('sub') or info.get('email_verified') is not True:
            raise ValueError('Google identity is not verified.')
        customer_id = db.google_customer(info['sub'], info.get('name') or 'Panda customer')
    except (OAuthError, ValueError, RequestException):
        return redirect(url_for('.index', auth_error='failed'))
    session.pop('guest', None)
    session['customer_id'] = customer_id
    return redirect(url_for('.index'))


def cart_body():
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        raise db.OrderError('Invalid order request.')
    return body


@bp.post('/api/quote')
def quote():
    body = cart_body()
    with db.connect() as conn:
        receipt, _ = db.validate_cart(conn, body.get('cart'))
    return jsonify(receipt)


@bp.get('/api/orders')
def order_history():
    if not session.get('customer_id'):
        return jsonify(error='Log in to view your previous orders.'), 401
    return jsonify(orders=db.recent_orders(session['customer_id']))


@bp.post('/api/orders/<int:order_id>/reorder')
def reorder(order_id):
    if not session.get('customer_id'):
        return jsonify(error='Log in to reorder a previous order.'), 401
    orders = db.recent_orders(session['customer_id'], order_id)
    if not orders:
        return jsonify(error='This order is not available in your recent orders.'), 404
    cart = [{key: line[key] for key in ('id', 'quantity', 'selections')}
            for line in orders[0]['receipt']['lines']]
    with db.connect() as conn:
        receipt, _ = db.validate_cart(conn, cart)
    return jsonify(cart=cart, quote=receipt)


@bp.post('/api/orders')
def order():
    if not session.get('customer_id') and not session.get('guest'):
        return jsonify(error='Log in or continue as a guest before placing your order.'), 401
    body = cart_body()
    try:
        checkout_key = UUID(body.get('checkout_key', ''))
    except (ValueError, TypeError, AttributeError):
        raise db.OrderError('Invalid checkout request. Please reload and try again.')
    receipt = db.checkout(body.get('cart'), session.get('customer_id'), checkout_key,
                          UUID(session['browser_key']), body.get('expected_total'))
    return jsonify(receipt), 201
