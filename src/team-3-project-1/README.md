
Shakir Islam - Project Manager

Madelyn Jaynes - Backend

Max Leal - Frontend

Vishanth Ramamurthy Yoganand - Frontend

JP Roque - Backend

# Documentation

Javadoc for all Java sources in `gui/` is auto-generated into the `docs/`
folder at the top of this repository:

```bash
cd gui
javadoc -d ../docs -classpath ".:postgresql-42.2.8.jar" *.java
```

The generated documentation is hosted via GitHub Pages at:
https://csce331-fall2026.github.io/team-3-project/

(To host: push `docs/` to `main`, then in the repo go to
Settings → Pages → Build and deployment → Deploy from a branch →
`main` / `/docs`.)

Note: the Javadoc comments in the Java sources were written with the
assistance of AI (GitHub Copilot) and reviewed by the team.

# Database Design Justification
## InventoryItem
inventoryItem allows for tracking specific shipments of each ingredient in inventory, including price, quantity, and expiration date. This allows for making sure that different wholesale orders, with different expiration dates, are handled accordingly. This information is mainly useful for managers rather than the cashier. It allows managers to track incoming inventory, monitor expiration, and compare costs over time. Keeping shipment information separate from the general Inventory table allows multiple batches of the same ingredient to be tracked separately, which is useful when items will expire at different times depending on when they came in.

## Inventory
inventory holds data on each ingredient type as a whole, including how much of that item is available and what the minimum threshold for that item is that determines when more needs to be ordered. This contains all the shipments of chicken, for example, as well as how low the supply of chicken needs to get before we order more. Although cashiers do not interact directly with this table in a normal transaction, inventory levels can be affected by the menu items they sell. For managers, this table is important because it supports reports about current inventory levels and restocking needs.

## MenuItem
menuItem is for abstracting the joining of ingredients into one singular menuItem, which is used in an order item. It contains the intended price of the symphony of ingredients. For the cashier, this allows items to be selected quickly when creating an order without needing to interact directly with the individual inventory items. For managers, the table provides a consistent list of products that can be used in reports such as most popular items or looking to see which products are sold the most/least.

## MenuItemInventory
menuItemInventory is a junction table that allows multiple ingredients in the inventory to be combined into a single menuItem. For example, chicken and teriyaki sauce would both be in inventory, and menuItemInventory would allow them to be combined into teriyaki chicken in the menuItem table. It also supports manager reports by making it possible to track ingredient usage and estimate when ingredients will need to be reordered. It is not used by cashiers but supports the orders table.

## OrderItem
The orderItem is labeled by the OrderItem ID and contains the information for the OrderId (order it is in), the MenuItem (food it is), and the Qty of that particular item (Qty). For the cashier, this table allows multiple menu items and quantities to be added to the same transaction. For managers, OrderItem provides detailed sales information that can be used to determine how many of each menu item were sold as well as to analyze sales patterns.

## Orders
The order is pretty much the symphony of orderItems, where it contains the total price, along with all the orderItems in which is in the order. For the cashier, this table represents the final transaction created after the customer's items have been entered. For managers, it supports reports involving total sales, number of orders, revenue over a time period, and other sales trends.

## Data Generation
Run `g++ generate_base_data.cpp data_utils.cpp -o generate_base_data` \
`g++ noise.cpp data_utils.cpp -o noise` \
`./generate_base_data MenuItems.csv Inventory.csv Recipes.csv > generated_data` \
`./noise MenuItems.csv Inventory.csv Recipes.csv 364 3 12345 >> generated_data` \
`generate_base_data.cpp` loads `Inventory`, `MenuItem`, and `MenuItemInventory` \
`noise.cpp` generates data for `Order`, `OrderItem`, and `InventoryItem` \
You should then run `psql -h csce-315-db.engr.tamu.edu -p 5432 -U team3 -d team3db -v ON_ERROR_STOP=1 -f generated_data` to enter the data into the database
You can run `psql -h csce-315-db.engr.tamu.edu -p 5432 -U team3 -d team3db -v ON_ERROR_STOP=1 -f drop_sales.sql` if you need to drop all tables from the database.

## Cashier GUI

Cashier-only POS interface based on the cashier GUI sketch. Reads menu items
from `MenuItem`, checks stock via `MenuItemInventory`/`Inventory`, and on
Checkout writes the `Orders` + `OrderItem` rows and decrements `Inventory`
in a single transaction. INV./REP. buttons are manager-only placeholders.

Build and run (fill in credentials in `DbSetup.java` first; on Mac/Linux
replace the `;` with a `:`):

Compile with:
```bash
cd gui
javac -cp ".:postgresql-42.2.8.jar" DbSetup.java CashierGUI.java
cd ..
```

Run with:
```bash
cd gui
java -cp ".:postgresql-42.2.8.jar" CashierGUI
cd ..
```

## Manager GUI

The Order History tab lists today's orders with their order ID, time, and total.
Click an order to open a popup showing item names and quantities. Refresh reloads
the list, and reopening the tab also loads the latest orders. Today follows the
database date used by cashier checkout.

`gui/OrderHistoryPage.java` builds the read-only history table and
item popup using the shared GUI theme. `gui/OrderHistoryDAO.java`
queries today's orders and each order's item quantities, including discontinued
menu items. Individual item prices are not displayed.

Compile with:
```bash
cd gui
javac -cp ".:postgresql-42.2.8.jar" DbSetup.java InventoryDAO.java ManagerGUI.java
cd ..
```

Run with:
```bash
cd gui
java -cp ".:postgresql-42.2.8.jar" ManagerGUI
cd ..
```

## Bowl, Plate, and Bigger Plate meals

The cashier opens on Meals. Bowl includes one side and one entree; Plate includes
one side and two entrees; Bigger Plate includes one side and three entrees.
Repeated entree choices are allowed. Soup is available a la carte, not as a meal
side. Prices start at $9.50, $11.00, and $12.50 in `MenuItems.csv`; these are project
prices, editable through the manager price editor. All entree choices are included
without premium surcharges. Existing a la carte items and Cub Meals remain available.

`Inventory.csv` contains Bowl Containers, Plate Containers, and Bigger Plate
Containers. `Recipes.csv` maps each meal to one container. Selected sides and
entrees consume their existing ingredient recipes in addition to the container.
The meal price is charged once, with the existing 8.25% sales tax.

Regenerate the schema as well as the data: `MenuItem` now has `EntreeCount`, and
`OrderItemSelection` records each meal's food choices separately from its priced
`OrderItem`. Order history shows those choices, and the product usage report
includes their ingredients. The noise generator creates matching meals and totals.
Old schema/data must be removed using your normal database reset process before
loading the generated SQL (`drop_sales.sql` includes the new table).

```bash
g++ -std=c++17 generate_base_data.cpp data_utils.cpp -o generate_base_data
g++ -std=c++17 noise.cpp data_utils.cpp -o noise
./generate_base_data MenuItems.csv Inventory.csv Recipes.csv > generated_data
./noise MenuItems.csv Inventory.csv Recipes.csv 364 3 12345 >> generated_data
# Load generated_data after resetting the database as usual.
cd gui
javac -cp ".:postgresql-42.2.8.jar" *.java
java -cp ".:postgresql-42.2.8.jar" CashierGUI
```


# Code style

[Style Guide.pdf](<Style Guide.pdf>) is the project style guide. Source code uses
four spaces for C++, two for Java, camelCase variables and methods, capitalized
class names, uppercase constants, inline opening braces, and purpose comments
for files and functions. Python uses four-space indentation. Database column
names, CSV labels, and names supplied by external APIs retain their existing spelling.

`.clang-format` records the Java and C++ formatting rules. With clang-format 23.1.3
installed, format or check the maintained sources from the repository root:

```bash
clang-format -i *.cpp *.h gui/*.java jdbc_swing_demo/*.java
clang-format --dry-run --Werror *.cpp *.h gui/*.java jdbc_swing_demo/*.java
```

Formatting checks cover whitespace and layout; review naming, declaration
placement, and comment accuracy against the PDF during code review. Generated
Javadoc in `docs/`, compiled binaries/classes, the JDBC JAR, and third-party
JavaScript/CSS bundled with Javadoc are generated or external artifacts.
Regenerate Javadoc using the command above after source changes.

The Java credential and JDBC demo classes are now `DbSetup`, `JdbcGUI`, and
`JdbcSQL`, with matching filenames. Update local launch commands that use the
previous lowercase class names. Compile `jdbc_swing_demo/DbSetup.java` separately
from `gui/` because both directories contain a default-package `DbSetup` class.

# Source file inventory

| File | Purpose |
| --- | --- |
| `data_utils.h` | Shared menu, ingredient, recipe, and shipment records and utility declarations. |
| `data_utils.cpp` | CSV validation/parsing, menu categorization, SQL escaping, and date helpers. |
| `generate_base_data.cpp` | Schema and base inventory, menu, and recipe SQL generator. |
| `noise.cpp` | Simulated sales, meal selections, ingredient usage, and shipment SQL generator. |
| `structures.h` | Legacy database record objects from the original C++ prototype. |
| `web_scraper.py` | Selenium nutrition scraper that writes `results.csv`. |
| `drop_sales.sql` | Removes the project database tables for a reset. |
| `validation.sql` | Sales, stock, and meal-selection validation queries. |
| `gui/DbSetup.java` | Database login configuration for the GUI and JDBC demos. |
| `gui/ModeSelect.java` | Shared database connection and cashier/manager selection screen. |
| `gui/CashierGUI.java` | Menu browsing, meal selection, ticket, and transactional checkout. |
| `gui/ManagerGUI.java` | Manager navigation, inventory/menu actions, history, and reports. |
| `gui/InventoryDAO.java` | Inventory/menu queries and updates, including ingredient recipes. |
| `gui/AddItem.java` | New inventory ingredient dialog. |
| `gui/ReactivateItem.java` | Discontinued inventory ingredient reactivation dialog. |
| `gui/AddMenuItem.java` | Menu item and ingredient recipe form. |
| `gui/TableEdit.java` | Table model that tracks pending numeric edits. |
| `gui/TablePage.java` | Shared table page with refresh, save, and unsaved-change controls. |
| `gui/PandaTheme.java` | Shared colors, fonts, controls, and cell renderers. |
| `gui/OrderHistoryDAO.java` | Today's orders, item quantities, and meal-selection queries. |
| `gui/OrderHistoryPage.java` | Order history table and item-details popup. |
| `gui/ReportWithFourQuads.java` | Sales, ingredient usage, restock, and daily X reports. |
| `gui/BarGraph.java` | Reusable bar chart panel. |
| `gui/LineGraph.java` | Reusable line chart panel. |
| `gui/XReportGraph.java` | Hourly sales chart for the daily X report. |
| `gui/JdbcGUI.java` | Swing JDBC query demonstration. |
| `gui/JdbcSQL.java` | Console JDBC query demonstration. |
| `jdbc_swing_demo/DbSetup.java` | Separate credential example for the original JDBC demo. |

`MenuItems.csv`, `Inventory.csv`, and `Recipes.csv` are generator inputs;
`results.csv` is scraper output. `generated_data`, `generate_base_data`, `noise`,
and `.class` files are generated SQL or compiled artifacts. `docs/` contains
published Javadoc and its assets. The PDFs and GUI sketch image document the
style, database design, and interface sketches. `CHANGELOG.md` records project
changes; `.gitignore` lists ignored artifacts; `.clang-format` configures source
formatting. The empty `java` and `gui/java` files contain no application code.

# AI usage

On October 19, 2026, OpenAI Codex assisted with reviewing the maintained source
code against `Style Guide.pdf`, applying formatting and naming changes, adding
file/function comments, updating Java class filenames and references, and
writing this README's style guidance and source inventory. Temporary PDF-reading
and formatting tools supported the review. Validation included Java and C++
compilation, formatter checks, Python syntax checking, and comparison of the C++
generators' output before and after the changes. This review did not exercise
the live database, browser scraper, or interactive GUI flows.

The user prompt for this assistance was:

```bash
go through all code and make sure that it follows the style guide, add a section to the README detailing this AI usage and prompt
```

The AI style review was reviewed by the team. 
