-- Sprint 1: estimated inventory unit costs for the daily profit margin report.
-- Apply to the Neon dev branch (SQL Editor or psql) before running the Java app,
-- then apply to production when the sprint ships. Safe to re-run.
--
--   Inventory.UnitCost - estimated ingredient cost per unit, used by the
--                        daily profit margin report

BEGIN;

ALTER TABLE Inventory ADD COLUMN IF NOT EXISTS UnitCost NUMERIC(10, 4) NOT NULL DEFAULT 0;

-- Estimated cost per unit (the unit stored in Inventory.Unit). Only rows still at
-- the default 0 are updated, so re-running never overwrites corrected values.
UPDATE Inventory SET UnitCost = CASE
  WHEN ItemName ILIKE 'Sirloin Steak%' THEN 6.00
  WHEN ItemName ILIKE 'Beef%' THEN 4.50
  WHEN ItemName ILIKE 'Shrimp%' THEN 7.00
  WHEN ItemName ILIKE 'White Fish%' THEN 5.00
  WHEN ItemName ILIKE 'Chicken Breast%' THEN 2.75
  WHEN ItemName ILIKE 'Chicken Egg Rolls%' THEN 0.50
  WHEN ItemName ILIKE 'Chicken Potstickers%' THEN 0.30
  WHEN ItemName ILIKE 'Chicken%' THEN 2.50
  WHEN ItemName ILIKE 'Tofu%' THEN 1.50
  WHEN ItemName ILIKE 'Cream Cheese Rangoons%' THEN 0.25
  WHEN ItemName ILIKE 'Vegetable Spring Rolls%' THEN 0.40
  WHEN ItemName ILIKE 'Apple Pie Rolls%' THEN 0.45
  WHEN ItemName ILIKE 'Apple Crisps%' THEN 0.50
  WHEN ItemName ILIKE 'Fortune Cookies%' THEN 0.05
  WHEN ItemName ILIKE 'Broccoli%' THEN 1.50
  WHEN ItemName ILIKE 'Eggplant%' THEN 1.25
  WHEN ItemName ILIKE 'Mushrooms%' THEN 2.00
  WHEN ItemName ILIKE 'String Beans%' THEN 1.75
  WHEN ItemName ILIKE 'Potatoes%' THEN 0.75
  WHEN ItemName ILIKE 'Bell Peppers%' THEN 1.25
  WHEN ItemName ILIKE 'Green Onions%' THEN 0.80
  WHEN ItemName ILIKE 'Onions%' THEN 0.60
  WHEN ItemName ILIKE 'Garlic%' THEN 2.50
  WHEN ItemName ILIKE 'Ginger%' THEN 3.00
  WHEN ItemName ILIKE 'Cabbage%' THEN 0.75
  WHEN ItemName ILIKE 'Carrots%' THEN 0.60
  WHEN ItemName ILIKE 'Mixed Greens%' THEN 2.50
  WHEN ItemName ILIKE 'White Rice%' THEN 0.50
  WHEN ItemName ILIKE 'Fried Rice Mix%' THEN 1.00
  WHEN ItemName ILIKE 'Chow Mein Noodles%' THEN 1.25
  WHEN ItemName ILIKE 'Chow Fun Noodles%' THEN 1.25
  WHEN ItemName ILIKE 'Cooking Oil%' THEN 3.00
  WHEN ItemName ILIKE 'Cornstarch%' THEN 1.00
  WHEN ItemName ILIKE 'Flour%' THEN 0.50
  WHEN ItemName ILIKE 'Sugar%' THEN 0.60
  WHEN ItemName ILIKE 'Salt%' THEN 0.30
  WHEN ItemName ILIKE 'Black Pepper Sauce%' THEN 2.00
  WHEN ItemName ILIKE 'Black Pepper%' THEN 4.00
  WHEN ItemName ILIKE 'Peanuts%' THEN 3.00
  WHEN ItemName ILIKE 'Walnuts%' THEN 8.00
  WHEN ItemName ILIKE 'Sesame Seeds%' THEN 3.50
  WHEN ItemName ILIKE 'Eggs%' THEN 2.00
  WHEN ItemName ILIKE 'Soy Sauce%' THEN 1.50
  WHEN ItemName ILIKE 'Orange Sauce%' THEN 2.00
  WHEN ItemName ILIKE 'Teriyaki Sauce%' THEN 1.75
  WHEN ItemName ILIKE 'Kung Pao Sauce%' THEN 2.00
  WHEN ItemName ILIKE 'Sweet and Sour Sauce%' THEN 1.75
  WHEN ItemName ILIKE 'SweetFire Sauce%' THEN 2.00
  WHEN ItemName ILIKE 'Honey Sesame Sauce%' THEN 2.25
  WHEN ItemName ILIKE 'Honey Walnut Sauce%' THEN 2.50
  WHEN ItemName ILIKE 'Chili Sauce%' THEN 1.50
  WHEN ItemName ILIKE 'Potsticker Sauce%' THEN 1.50
  WHEN ItemName ILIKE 'Hot Mustard%' THEN 1.00
  WHEN ItemName ILIKE 'General Stir Fry Sauce%' THEN 1.75
  WHEN ItemName ILIKE 'Hot and Sour Soup Base%' THEN 2.00
  WHEN ItemName ILIKE '%Syrup' THEN 5.00
  WHEN ItemName ILIKE 'Light Lemonade Mix%' THEN 3.00
  WHEN ItemName ILIKE '%Lemonade Mix' THEN 3.00
  WHEN ItemName ILIKE '%Tea Concentrate' THEN 4.00
  WHEN ItemName ILIKE '%Refresher Mix' THEN 4.50
  WHEN ItemName ILIKE 'Bottled Water%' THEN 0.25
  WHEN ItemName ILIKE '%Cups' THEN 0.08
  WHEN ItemName ILIKE '%Lids' THEN 0.04
  WHEN ItemName ILIKE 'Straws%' THEN 0.01
  WHEN ItemName ILIKE 'Napkins%' THEN 0.01
  WHEN ItemName ILIKE 'Forks%' THEN 0.02
  WHEN ItemName ILIKE 'Takeout Bags%' THEN 0.05
  WHEN ItemName ILIKE 'Cub Meal Containers%' THEN 0.10
  WHEN ItemName ILIKE 'Bigger Plate Containers%' THEN 0.15
  WHEN ItemName ILIKE '%Containers' THEN 0.12
  ELSE UnitCost
END
WHERE UnitCost = 0;

COMMIT;
