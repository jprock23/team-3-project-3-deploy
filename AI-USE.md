# AI Use Disclosure

## Tool

OpenAI Codex and Anthropic Claude were used as coding assistants during this work. Each section names the tool used.

## Image filenames

- Inspected the images in `pandaimages/` and renamed them using menu item names from `src/team-3-project-1/MenuItems.csv` where matches were available.
- Used descriptive filenames for additional products and shared base names for drink size variants.
- Incorporated user-provided drink identifications for Mango Guava Tea, Peach Lychee Refresher, Pomegranate Pineapple Lemonade, and Watermelon Mango Refresher.
- Changed filenames only; the image contents were not generated or altered by AI. Names were checked against the repository's menu data, not a live database connection.

## Customer ordering interface

At the user's request, Codex generated the separate `src/customer/` Flask application, including:

- HTML, CSS, and JavaScript for the photo menu, meal builder, drink sizes, cart, order review, and confirmation.
- PostgreSQL access for menu data, prices, availability, order storage, and inventory deductions.
- Google OpenID Connect login, guest sessions, account association, and checkout retry protection.
- A database migration for Google identity mappings and customer/guest order associations.
- Setup documentation, dependency declarations, environment configuration examples, and related README, changelog, and ignore-file updates.
- Automated integration tests covering ordering, inventory, concurrent checkout, retries, account association, and mocked authentication callbacks.

Codex consulted the existing Java cashier implementation and repository database documentation to align the new interface with the existing POS schema. It also consulted [Authlib's Flask documentation](https://docs.authlib.org/en/latest/oauth2/client/web/flask.html) and [Psycopg's transaction documentation](https://www.psycopg.org/psycopg3/docs/basic/transactions.html), and used the Sites and Neon skills as workflow guidance.

## Customer order history and reordering

At the user's request, OpenAI Codex extended the customer application to let signed-in customers view their account's web orders from the past calendar month and reorder them. Codex generated the account-scoped history and reorder endpoints, database queries, previous-orders dialog, cart restoration with replacement confirmation, and setup documentation updates. Reorders preserve quantities and meal selections, validate current prices and availability, and require normal checkout review. The existing Google login already creates an account on the first verified sign-in; Codex clarified this in the sign-in interface.

Codex used the Neon Postgres and parent Neon skills as database workflow guidance and added integration tests for login requirements, account isolation, expired orders, empty history, ordering by date, preserved selections, updated prices, and unavailable items. All 25 customer integration tests passed against an isolated local PostgreSQL database. JavaScript syntax and Git whitespace checks also passed. Live Google authorization and visual browser testing were not performed for this extension, and no hosted migration or deployment was performed.

## Cashier duplication, editable meals, and sales margins

At the user's request, OpenAI Codex extended the Java POS application (`src/team-3-project-1`):

- Ticket-line duplication in the cashier GUI: any selected line (including customized meals) can be copied as a new line, with stock re-validated.
- Bowl/plate editing in the cashier GUI: components can be removed (keeping at least one side and one entree) and extra sides/entrees added at their standalone prices, with the meal price shown live in the dialog and the order total updating on save. All components persist through the existing `OrderItemSelection` rows.
- Estimated profit and margin columns added to the manager Sales report, complementing the daily profit margin quadrant, and the unused Schedule placeholder tab was removed from the manager window.

Codex compiled the GUI with `javac` and validated the margin query against a scratch PostgreSQL instance, including orders with duplicated meal components. Visual testing against the live dev database remains with the team.

## Profit margin reports and restock threshold

At the user's request, OpenAI Codex implemented the Sprint 1 restock report and profit margin stories in the Java POS application (`src/team-3-project-1`):

- Created `src/team-3-project-1/migrations/001_inventory_unit_costs.sql` with an estimated `Inventory.UnitCost` column for cost-of-goods estimates.
- Added a Daily Sales & Profit Margin report quadrant (per-day revenue, estimated ingredient cost, estimated profit, margin percent) and fixed the restock report to include items exactly at their threshold.

An accompanying cashier schedule feature (employee/shift tables, schedule views, and a staff PIN login) was prototyped and then fully removed at the user's request, including dropping its tables from the dev database.

Codex compiled the full Java GUI with `javac` and validated the migration, its re-run safety, and every new SQL query and DAO method against a scratch local PostgreSQL instance loaded with stand-in POS tables and exercised over JDBC. The migration has not been applied to the hosted Neon database, and the Swing interfaces were not exercised visually or against live data; human verification on the dev branch remains necessary.

## Combined web app and web cashier/manager (Claude)

At JP Roque's request, Anthropic Claude ported the Java Swing cashier and manager GUI (`src/team-3-project-1/gui`) to Flask and merged it with the customer app into one application:

- Generated `src/app.py` (combined app factory), `src/staff/` (database layer, routes, templates, CSS, JavaScript), and `src/staff/tests/test_staff.py`.
- Converted `src/customer/app.py` into a blueprint without changing its routes or behavior, updated three `url_for` calls in its template, and added a footer link to the staff pages.
- Added a session timezone setting to `src/customer/db.py` `connect()` so web orders store local restaurant dates like the Java JDBC client.
- Ported the newer Java features (ticket duplication, meal editing with extra-component pricing, profit/margin reports, restock at threshold) by reading the Java sources in this repository.
- Updated README files, `.env.example`, `CHANGELOG.md`, and this file.

Claude ran the existing 25 customer integration tests and 12 new staff integration tests against an isolated local PostgreSQL database (all passed), and exercised the cashier, meal editor, checkout, manager pages, reports, and order-history dialog in a headless browser against local sample data, checking for Content-Security-Policy and console errors. It was not run against the Neon dev branch, Google login was not exercised, and nothing was deployed. Human review and testing on the dev branch remain necessary.

## Verification and limitations

For the initial customer ordering implementation, Codex ran 21 integration tests successfully against an isolated temporary PostgreSQL database, checked JavaScript syntax, compiled the Python files, and checked for whitespace errors. The tests included simulated write failures, concurrent orders, and duplicate checkout requests.

No hosted Neon database migration or deployment was performed. Real Google authorization and visual browser testing were not completed. The application requires database configuration, the included migration, a session secret, and Google OAuth credentials for account login. Human review and testing against the configured development environment remain necessary; the automated checks do not establish production readiness.
