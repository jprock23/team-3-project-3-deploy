# Combined Flask web app: customer, cashier, and manager

- Added `src/app.py`, one Flask app serving customer ordering at `/`, a staff mode select at `/staff`, the cashier at `/cashier`, and PIN-protected manager pages at `/manager/...`. Run with `flask --app src.app run`; `flask --app src.customer.app run` still works.
- Added `src/staff`, a Flask port of the Java cashier and manager GUI: drink size picker, meal builder, ticket duplicate/edit meal, transactional checkout (same lock order as the Java cashier and customer checkout), inventory and menu editing, add/reactivate/discontinue, order history, and the sales, daily profit margin, usage, restock, and X-report reports.
- `src/customer/app.py` is now a blueprint; its CSRF check, security headers, and session settings apply to the whole app. The customer page footer links to `/staff`.
- Database connections now set the session timezone from `POS_TIMEZONE` (default `America/Chicago`) so web orders get the same local dates/times as Java orders; Neon defaults to UTC.
- New settings in `.env.example`: `MANAGER_PIN`, `POS_TIMEZONE`. No schema changes; the existing customer and unit-cost migrations are required.
- Added `src/staff/tests` (12 integration tests) and a root `requirements.txt`.

# Cashier item duplication, editable meals, and sales margins

- Cashiers can duplicate any selected ticket line, preserving meal selections; stock is re-validated for the copy.
- Cashiers can edit a bowl/plate on the ticket: remove components (down to one side and one entree) or add extra sides/entrees at their standalone prices. Totals update immediately and every component persists to `OrderItemSelection` at checkout; no schema change was needed.
- The manager Sales report now shows estimated profit and margin columns alongside revenue, using the `Inventory.UnitCost` estimates.
- Removed the unused Schedule placeholder tab from the manager window.

# Profit margin reporting and restock threshold fix

- Added `src/team-3-project-1/migrations/001_inventory_unit_costs.sql`: estimated `Inventory.UnitCost` column for cost-of-goods estimates. Apply to the Neon dev branch before running; production application happens at deploy time.
- Reports page: added a Daily Sales & Profit Margin report (revenue, estimated ingredient cost, estimated profit, and margin percent per day from order history), and the restock report now lists items at or below their minimum threshold.

# Customer ordering interface

- Added `src/customer`, a Flask customer ordering app with menu photos, meal choices, drink sizes, cart review, Google login, and guest ordering.
- Added transactional checkout against the existing POS tables, inventory validation/deduction, and duplicate request protection.
- Added `src/customer/migrations/001_customer_ordering.sql` for Google identity mappings and customer/guest order associations. Apply to dev before running; no hosted database changes were applied as part of this implementation.
- Added environment configuration, setup documentation, and PostgreSQL integration tests.
