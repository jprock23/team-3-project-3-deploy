# Changelog

## Phase 4: Reports

### Summary
We made adjustments to the database to support ordering bowls, plates, and bigger plates. 

### Added
- **`OrderItemSelection`**: Added to store which sides and entrees where chosen for a meal. OrderItem will record that someone bought a plate while OrderItemSelection will record it was orange chicken and fried rice. 

### Changed
- **`Menu`**: Updated menu items to include bowl, plate, and larger plate.

### Removed
- **`Entree`**: Removed entree tab on CashierGUI as they are now ordered in a group with sides, they cannot be ordered individually
- **`Side`**: Removed side tab on CashierGUI as they are now ordered in a group with entrees, they cannot be ordered individually.


## Phase 3: GUI Implementation

### Summary
While implementing the GUI, we made several adjustments to our original designs based on how the menu items and manager functionality needed to be organized. The cashier layout was modified to better separate menu categories and make item selection easier. We also expanded the manager GUI because our original design did not account for all of the functionality managers would need.

### Added
- **`Menu`**: Added a manager tab that allows menu items and prices to be edited.
- **`Switch Mode`**: Added a button that allows the user to switch from manager mode to cashier mode and from cashier mode to manager mode.
- **`Manager Pin`**: Added security check when changing from cashier mode to manager mode to ensure that only authorized users can access manager mode.
- **`Add Item`**: Added functionality for managers to add new inventory items.
- **`Remove Item`**: Added button to discontinue inventory items and menu items on those respective pages
- **`Reactivate Item`**: Added button to reactivate discontinued inventory items that are no longer in use if they have existed in the past

### Changed
- **`Inventory`**: Updated the inventory page to include a remove item button, editable inventory quantities, and a refresh button to resync inventory information with the database.
- **`Cashier Menu`**: Adjusted the original cashier layout to organize items into categories such as Sides, Entrees, Appetizers, Meals, Extras, and Drinks rather than the original Plate/Bowl-style layout.
- **`Inventory`**: Added `Active` category to tell whether inventory should be used in GUI
- **`MenuItem`**: Added `Active` category to tell whether menuItem should be in GUI.

### Not Implemented
- **`Schedule`**: We currently do not have a way to track employee data. Employees will need to be added to the database before this functionality can be implemented.
- **`Reports`**: The Reports tab has been added to the GUI, but its functionality will be implemented during a later stage of the project.

## Phase 2: Database Population

### Summary
While building the data generator, we found that the Phase 1 could not represent some inventory data. Ingredients are measured in fractions, shipments arrive on specific dates and items have different shelf lives.

**No tables were added or removed, and all relationships and cardinalities are unchanged.**

### Added
- **`INVENTORY.Unit`** (string, Not Null): gives each quantity a unit so stock levels are known.
- **`INVENTORY.ShelfLifeDays`** (int, Not Null): records how long each item lasts.
- **`INVENTORYITEM.ReceivedDate`** (date, Not Null): records when each shipment arrived for expiration date.

### Changed
- **`INVENTORYITEM.ShipmentID`**: int → bigint, to allow for a larger history.
- **`INVENTORYITEM.Quantity`**: int → decimal, since shipments arrive in fractions.
- **`INVENTORY.Minimum_Threshold`** → **`Minimum_Threshold`**: changed to decimal to match `Quantity`.
- **`INVENTORY.Quantity`**: int → decimal, since stock is fractional and comes from shipments.
- **`MENUITEMINVENTORY.QuantityRequired`**: int → decimal, since recipes use fractional amounts.
- **`ORDERITEM.Qty`**: default removed so quantity is always written.

### Unchanged
- `MENUITEM` (`MenuItemID`, `MenuItemName`, `Price`)
- `ORDERS` (`OrderID`, `OrderDate`, `OrderTime`, `Total`)
- All primary keys, foreign keys, and relationships:

---

## Phase 1: Database Design

### Added
- Initial schema with six tables: `INVENTORY`, `INVENTORYITEM`, `MENUITEM`, `MENUITEMINVENTORY`, `ORDERS`, `ORDERITEM`.
