-- Remove the project sales, menu, recipe, and inventory tables.

DROP TABLE IF EXISTS OrderItemSelection;
DROP TABLE IF EXISTS "inventoryitem" CASCADE;
DROP TABLE IF EXISTS "inventory" CASCADE;
DROP TABLE IF EXISTS "menuitem" CASCADE;
DROP TABLE IF EXISTS "menuiteminventory" CASCADE;
DROP TABLE IF EXISTS "orders" CASCADE;
DROP TABLE IF EXISTS "orderitem" CASCADE;
