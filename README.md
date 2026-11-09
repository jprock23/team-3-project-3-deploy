# Team 3 — Panda Express POS Web App

A publicly hosted, WCAG 2.2 AA–compliant point-of-sale and inventory web app for Panda Express that serves cashiers, managers, kitchen staff, and customers, who can place their own orders and earn rewards.

## Live Links
- **Deployed app:** <URL or "TBD">
- **Documentation:** <Cloudflare URL or "TBD">
- **Project board (backlog + burn-up):** <GitHub Projects URL>

## Roles
| Name | Role |
|------|------|
| Max Leal| Project Manager & Full-stack |
| Madelyn Jaynes | Front-end Lead |
| JP Roque | Front-end Lead |
| Shakir Islam | Back-end Lead |
| Vishanth Yoganand | Back-end Lead |

## Novel Feature
**What it is:**  
A customer rewards program. Signed-in customers earn points on every order and can redeem them for free menu items at checkout. Points balances and redemption history are stored in our database and displayed on the customer's account page.

**Motivation:**  
Adding customer logins gives us the opportunity to reward repeat business. Rewards give customers a reason to sign in and order directly. This also gives managers data on repeat visits and redemption trends they can use in reports. The feature mainly serves customers, with secondary value for managers and cashiers: cashiers can apply a customer's rewards to an order at the register.

## Tech Stack
- **Front-end:** Python, Flask
- **Back-end:** Java/JDBC
- **Database:** Neon
- **Hosting service:** Vercel
- **Authentication:** Google OAuth2

## Web app (customer, cashier, manager)

One Flask app serves all three interfaces:

| URL | Interface | Code |
|-----|-----------|------|
| `/` | Customer ordering: photo menu, meal builder, cart, guest or Google login | [`src/customer`](src/customer/README.md) |
| `/staff` | Staff mode select | [`src/staff`](src/staff) |
| `/cashier` | Cashier POS: meals, drink sizes, duplicate/edit ticket lines, checkout | `src/staff` |
| `/manager/...` | Manager (PIN): inventory, menu prices, order history, reports | `src/staff` |

Run from the repository root:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env   # then fill in the Neon dev settings, CUSTOMER_SECRET_KEY and MANAGER_PIN
.venv/bin/flask --app src.app run
```

Open http://127.0.0.1:5000 (customer) or http://127.0.0.1:5000/staff (cashier/manager). Both database migrations must be applied to the branch you point at: `src/customer/migrations/001_customer_ordering.sql` and `src/team-3-project-1/migrations/001_inventory_unit_costs.sql` (the profit columns need `Inventory.UnitCost`).

The cashier and manager pages are a Flask port of the Java Swing GUI in `src/team-3-project-1/gui`, using the same tables and the same checkout locking, so the Java app, the web cashier, and customer web orders can run against one database at the same time.

Tests (disposable database only): `CUSTOMER_TEST_DATABASE_URL=postgresql://... .venv/bin/python -m pytest src -q`

