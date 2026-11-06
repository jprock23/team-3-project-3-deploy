# Panda Express POS — Flask front end

Python/Flask rewrite of the Java Swing GUI. Same PostgreSQL database and schema;
the DAO queries and checkout transaction are ported one-to-one into `db.py`.

## Run

    pip install -r requirements.txt
    python app.py            # http://127.0.0.1:5000

Connection settings live in `config.py` (the old DbSetup.java). Override with env vars
instead of committing a password: `PANDA_DB_HOST`, `PANDA_DB_PORT`, `PANDA_DB_NAME`,
`PANDA_DB_USER`, `PANDA_DB_PASSWORD`, plus `PANDA_SECRET_KEY` and `PANDA_MANAGER_PIN`.
You need to be on the TAMU network/VPN to reach csce-315-db.

## Java → Python map

| Java                                   | Flask                                              |
|----------------------------------------|----------------------------------------------------|
| ModeSelect                             | `/` (mode_select.html), `/pin`                     |
| CashierGUI                             | `/cashier`, `/cashier/meal/<id>`, add/remove/checkout routes |
| ManagerGUI + TablePage/TableEdit       | `/manager/inventory`, `/manager/menu` (+ static/app.js for unsaved tracking) |
| AddItem / ReactivateItem / AddMenuItem | `/manager/inventory/add`, `/manager/inventory/reactivate`, `/manager/menu/add` |
| OrderHistoryPage / OrderHistoryDAO     | `/manager/orders`, `/manager/orders/<id>.json`     |
| ReportWithFourQuads / XReportGraph     | `/manager/reports` (X-report drawn as inline SVG)  |
| InventoryDAO, SQL in CashierGUI        | `db.py`                                            |
| PandaTheme                             | `static/style.css`                                 |

BarGraph, LineGraph, JdbcGUI and JdbcSQL weren't used by the app, so they weren't ported.

## Behaviour differences from the Swing app

- The cart lives in the browser session (signed cookie) instead of in memory.
- Manager pages always need the PIN, including from the mode-select screen
  (in Swing, the Manager button there skipped it). Switch Mode logs the manager out.
- Discontinue is a per-row button instead of select-a-row-then-click.
- Order totals are rounded to cents before being stored.
