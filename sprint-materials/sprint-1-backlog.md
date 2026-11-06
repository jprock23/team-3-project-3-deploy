# Sprint 1 Backlog — User Stories

*Sprint 1 · User types covered: customer, manager, cashier (team of 4) · Total: 13 story points*

---

## Story 1 — Order History & Reordering
**As a** customer, **I want** the option to see my previous orders **so that** I can order the same items again without having to look for them.

**Acceptance Criteria:**
- User must be able to log in to an account (Google OAuth2)
- User must be able to create a new account
- Logged-in user can view all their orders from the last month
- User can reorder items from a previous order

**Story Points:** 1 · **Sprint:** 1

---

## Story 2 — Menu Item Photos
**As a** customer, **I want** to be able to see item photos **so that** I can decide what to buy.

**Acceptance Criteria:**
- User can see a photo for every menu item
- Photos load on the menu browsing page without errors
- Photos include alt text (WCAG 2.2 AA compliance)

**Story Points:** 3 · **Sprint:** 1

---

## Story 3 — Restock Report
**As a** manager, **I want** to be able to understand what needs to be restocked **so that** the establishment's inventory won't likely be out of stock.

**Acceptance Criteria:**
- Manager can view a restock report
- Report lists items at or below their restock threshold
- Report is accessible only to authenticated managers

**Story Points:** 1 · **Sprint:** 1

---

## Story 4 — Profit Margin Reporting
**As a** manager, **I want** an expense capability **so that** I can see what the profit margin is for each day.

**Acceptance Criteria:**
- Manager can view the Sales report
- Sales report includes a profit margin column alongside revenue
- Profit margin is calculated per day from order history data

**Story Points:** 1 · **Sprint:** 1

---

## Story 5 — Cashier Schedule View
**As a** cashier, **I want** the ability to view my schedule **so that** I can plan around it and determine if I'm free.

**Acceptance Criteria:**
- Cashier can log in and view their assigned shifts
- Schedule shows dates, start times, and end times for upcoming shifts
- Schedule data is stored in and read from the database (schema change documented in CHANGELOG.md)
- Only the logged-in cashier's own schedule is visible

**Story Points:** 5 · **Sprint:** 1

---

## Story 6 — Editable Bowls/Plates
**As a** cashier, **I want** to be able to edit bowls/plates **so that** I can take orders faster.

**Acceptance Criteria:**
- Cashier can add items to a bowl/plate
- Cashier can remove items from a bowl/plate
- Changes update the order total in real time
- Edited bowl/plate persists correctly when the order is submitted

**Story Points:** 2 · **Sprint:** 1
