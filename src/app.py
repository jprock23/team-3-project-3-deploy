"""Combined Panda Express web app: customer ordering, cashier, and manager.

Run from the repository root:  flask --app src.app run

    /           customer ordering (src/customer)
    /staff      staff mode select (src/staff)
    /cashier    cashier POS
    /manager/*  manager pages (PIN protected)
"""

import hmac
import os
import secrets
from datetime import timedelta
from uuid import uuid4

from authlib.integrations.flask_client import OAuth
from dotenv import load_dotenv
from flask import Flask, jsonify, render_template, request, session

from .customer import db as customer_db
from .customer.app import bp as customer_bp
from .staff.views import bp as staff_bp


def create_app(test_config=None):
    load_dotenv(customer_db.ROOT / '.env')
    app = Flask(__name__, static_folder=None)
    app.config.update(
        SECRET_KEY=os.environ.get('CUSTOMER_SECRET_KEY'),
        SESSION_COOKIE_NAME='panda_customer',
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE='Lax',
        SESSION_COOKIE_SECURE=os.environ.get('CUSTOMER_SECURE_COOKIE', 'false').lower() == 'true',
        PERMANENT_SESSION_LIFETIME=timedelta(hours=8),
        MAX_CONTENT_LENGTH=32768,
        GOOGLE_CLIENT_ID=os.environ.get('GOOGLE_CLIENT_ID'),
        GOOGLE_CLIENT_SECRET=os.environ.get('GOOGLE_CLIENT_SECRET'),
        GOOGLE_REDIRECT_URI=os.environ.get('GOOGLE_REDIRECT_URI'),
        MANAGER_PIN=os.environ.get('MANAGER_PIN', '1234'),
    )
    if test_config:
        app.config.update(test_config)
    if not app.secret_key or '<' in app.secret_key:
        raise RuntimeError('Set CUSTOMER_SECRET_KEY to a persistent random secret in .env.')

    # Stored in app.extensions['authlib.integrations.flask_client'].
    oauth = OAuth(app)
    oauth.register('google', server_metadata_url='https://accounts.google.com/.well-known/openid-configuration',
                   client_kwargs={'scope': 'openid email profile', 'code_challenge_method': 'S256'})

    @app.before_request
    def protect_session():
        session.permanent = True
        session.setdefault('csrf', secrets.token_urlsafe(32))
        session.setdefault('browser_key', str(uuid4()))
        if request.method == 'POST':
            supplied = request.headers.get('X-CSRF-Token') or request.form.get('csrf', '')
            if not hmac.compare_digest(session['csrf'], supplied):
                message = 'Your session expired. Reload the page and try again.'
                if request.blueprint == 'staff':
                    return render_template('staff/error.html', title='Session expired',
                                           message=message), 403
                return jsonify(error=message), 403

    @app.context_processor
    def csrf_token():
        return {'csrf_token': lambda: session.get('csrf', '')}

    @app.after_request
    def security_headers(response):
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['Referrer-Policy'] = 'same-origin'
        response.headers['Content-Security-Policy'] = (
            "default-src 'self'; img-src 'self'; style-src 'self'; script-src 'self'; "
            "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
        is_static = (request.endpoint or '').endswith('.static')
        if not is_static and (request.path == '/' or request.path == '/staff' or
                              request.path.startswith(('/api', '/auth', '/cashier', '/manager'))):
            response.headers['Cache-Control'] = 'no-store'
        return response

    app.register_blueprint(customer_bp)
    app.register_blueprint(staff_bp)
    return app
