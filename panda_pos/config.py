"""Database and app settings (the Python equivalent of DbSetup.java).

Every value can be overridden with an environment variable, so credentials
don't have to live in the repo:

    PANDA_DB_HOST, PANDA_DB_PORT, PANDA_DB_NAME, PANDA_DB_USER, PANDA_DB_PASSWORD
    PANDA_SECRET_KEY   (signs the session cookie that holds the cart)
    PANDA_MANAGER_PIN
"""

import os

DB_HOST = os.environ.get("PANDA_DB_HOST", "csce-315-db.engr.tamu.edu")
DB_PORT = int(os.environ.get("PANDA_DB_PORT", "5432"))
DB_NAME = os.environ.get("PANDA_DB_NAME", "team3db")
DB_USER = os.environ.get("PANDA_DB_USER", "team3")
DB_PASSWORD = os.environ.get("PANDA_DB_PASSWORD", "rEAdEmE")

SECRET_KEY = os.environ.get("PANDA_SECRET_KEY", "dev-only-change-me")

# 4-digit PIN required to open the manager side
MANAGER_PIN = os.environ.get("PANDA_MANAGER_PIN", "1234")

TAX_RATE = 0.0825
