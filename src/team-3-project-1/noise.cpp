/** Generates simulated sales, meal selections, stock usage, and shipments as SQL. */

#include <bits/stdc++.h>
#include <string>
#include "data_utils.h"

using namespace std;
using RandomEngine = std::mt19937_64;

/** Advances the Ornstein-Uhlenbeck process used for sales intensity. */
double ouStep(RandomEngine &rng, double x, double theta, double mu, double sigma, double dt) {
    normal_distribution<double> nd(0.0, 1.0);
    double dW = nd(rng) * sqrt(dt);
    return x + theta * (mu - x) * dt + sigma * dW;
}

/** Chooses a random menu index from a category, or returns -1 if empty. */
int sampleCategory(RandomEngine &rng, const vector<int> &items) {
    if (items.empty()) {
        return -1;
    }

    return items[uniform_int_distribution<int>(0, items.size() - 1)(rng)];
}

/** Combines the shipment date and daily sequence into an identifier. */
long long generateShipmentId(const tm &date, int sequence) {
    long long year = date.tm_year + 1900;
    long long month = date.tm_mon + 1;
    long long day = date.tm_mday;

    long long dateNumber = year * 10000 + month * 100 + day;

    return dateNumber * 1000 + sequence;
}

/** Sums the remaining quantity in an ingredient's shipment lots. */
double calculateInventoryQuantity(int inventoryId, const map<int, deque<Shipment>> &shipments) {
    auto it = shipments.find(inventoryId);

    if (it == shipments.end()) {
        return 0.0;
    }

    double total = 0.0;

    for (const Shipment &shipment : it->second) {
        total += shipment.quantity;
    }

    return total;
}

/** Sums starting inventory and remaining shipment quantities. */
double getTotalInventory(int inventoryId, const map<int, double> &baseInventory,
    const map<int, deque<Shipment>> &shipments) {
    double total = 0.0;

    auto baseIt = baseInventory.find(inventoryId);

    if (baseIt != baseInventory.end()) {
        total += baseIt->second;
    }

    total += calculateInventoryQuantity(inventoryId, shipments);

    return total;
}

/** Returns the stock needed to reach four times the starting quantity. */
double calculateReorderQuantity(const InventoryItem &item, double currentQuantity) {
    double targetQuantity = item.quantity * 4;

    if (currentQuantity >= targetQuantity) {
        return 0.0;
    }

    return (targetQuantity - currentQuantity);
}

/** Samples a positive wholesale unit price for an ingredient. */
double generateWholesalePrice(RandomEngine &rng, const InventoryItem &item) {
    double basePrice;

    if (item.unit == "lbs") {
        basePrice = 2.50;
    } else if (item.unit == "oz") {
        basePrice = 0.15;
    } else {
        basePrice = 0.30;
    }

    normal_distribution<double> priceNoise(1.0, 0.05);

    double price = basePrice * priceNoise(rng);

    if (price < 0.01) {
        price = 0.01;
    }

    return price;
}

/** Creates a shipment with its price, receipt date, and expiration date. */
Shipment createShipment(const InventoryItem &item, double quantity, const tm &receivedDate,
    int sequence, RandomEngine &rng) {
    Shipment shipment;

    shipment.shipmentId = generateShipmentId(receivedDate, sequence);

    shipment.inventoryId = item.id;

    shipment.quantity = quantity;

    shipment.wholesalePrice = generateWholesalePrice(rng, item);

    shipment.receivedDate = formatDate(receivedDate);

    tm expiration = addDays(receivedDate, item.shelfLife);

    shipment.expirationDate = formatDate(expiration);

    return shipment;
}

/** Writes an INSERT statement for a shipment. */
void printShipmentInsert(const Shipment &shipment) {
    cout << "INSERT INTO InventoryItem "
            "(ShipmentID, InventoryID, WholesalePrice, Quantity, "
            "ReceivedDate, ExpirationDate) VALUES (";

    cout << shipment.shipmentId << "," << shipment.inventoryId << "," << fixed << setprecision(2)
         << shipment.wholesalePrice << "," << fixed << setprecision(3) << shipment.quantity << ",'"
         << shipment.receivedDate << "','" << shipment.expirationDate << "');\n";
}

/** Writes an UPDATE statement for a shipment's remaining quantity. */
void printShipmentUpdate(const Shipment &shipment) {
    cout << "UPDATE InventoryItem "
            "SET Quantity = "
         << fixed << setprecision(3) << shipment.quantity
         << " WHERE ShipmentID = " << shipment.shipmentId << ";\n";
}

/** Writes a DELETE statement for a shipment identifier. */
void printShipmentDelete(long long shipmentId) {
    cout << "DELETE FROM InventoryItem "
            "WHERE ShipmentID = "
         << shipmentId << ";\n";
}

/** Writes an UPDATE statement for an active ingredient's quantity. */
void printInventoryUpdate(int inventoryId, double quantity) {
    cout << "UPDATE Inventory "
            "SET Quantity = "
         << fixed << setprecision(3) << quantity << " WHERE InventoryID = " << inventoryId
         << " AND Active = TRUE;\n";
}

/** Consumes starting stock, then shipment lots in receipt order. */
bool consumeInventoryFifo(int inventoryId, double amount, map<int, double> &baseInventory,
    map<int, deque<Shipment>> &shipments) {
    double available = getTotalInventory(inventoryId, baseInventory, shipments);

    if (available < amount) {
        cerr << "Not enough inventory for InventoryID " << inventoryId << ". Needed: " << amount
             << ", Available: " << available << "\n";

        return false;
    }

    // Consume original starting inventory first
    double &baseQty = baseInventory[inventoryId];

    if (baseQty > 0) {
        double used = min(baseQty, amount);

        baseQty -= used;
        amount -= used;
    }

    // Then consume Sunday shipment lots FIFO
    auto &queue = shipments[inventoryId];

    while (amount > 0.000001 && !queue.empty()) {
        Shipment &oldest = queue.front();

        if (oldest.quantity <= amount) {
            amount -= oldest.quantity;
            queue.pop_front();
        } else {
            oldest.quantity -= amount;
            amount = 0;
        }
    }

    return true;
}

/** Checks a menu recipe and consumes the required ingredient quantities. */
bool consumeMenuItem(int menuItemId, int qty,
    const map<int, vector<MenuItemInventoryRow>> &recipeMap, map<int, double> &baseInventory,
    map<int, deque<Shipment>> &shipments) {
    auto it = recipeMap.find(menuItemId);

    if (it == recipeMap.end()) {
        return true; // Items without a recipe consume no inventory.
    }

    // First check that every ingredient is available
    for (const auto &ingredient : it->second) {
        double amount = ingredient.quantityRequired * qty;

        double available = getTotalInventory(ingredient.inventoryId, baseInventory, shipments);

        if (available < amount) {
            cerr << "Not enough InventoryID " << ingredient.inventoryId << " for MenuItemID "
                 << menuItemId << "\n";

            return false;
        }
    }

    // Everything is available, now actually consume it
    for (const auto &ingredient : it->second) {
        double amount = ingredient.quantityRequired * qty;

        consumeInventoryFifo(ingredient.inventoryId, amount, baseInventory, shipments);
    }

    return true;
}

/** Appends a shipment to its ingredient's queue. */
void addShipment(const Shipment &shipment, map<int, deque<Shipment>> &shipments) {
    shipments[shipment.inventoryId].push_back(shipment);
}

/** Adds Sunday shipments for ingredients below their target quantities. */
void restockInventory(const tm &day, const vector<InventoryItem> &inventory,
    const map<int, double> &baseInventory, map<int, deque<Shipment>> &shipments,
    RandomEngine &rng) {
    if (day.tm_wday != 0) {
        return;
    }

    int sequence = 1;

    for (const InventoryItem &item : inventory) {
        double current = getTotalInventory(item.id, baseInventory, shipments);

        double quantityToOrder = calculateReorderQuantity(item, current);

        if (quantityToOrder <= 0) {
            continue;
        }

        Shipment shipment = createShipment(item, quantityToOrder, day, sequence++, rng);

        addShipment(shipment, shipments);
    }
}

/** Groups ingredient recipe rows by menu item identifier. */
map<int, vector<MenuItemInventoryRow>> buildRecipeMap(const vector<MenuItemInventoryRow> &recipes) {
    map<int, vector<MenuItemInventoryRow>> recipeMap;

    for (const auto &row : recipes) {
        recipeMap[row.menuItemId].push_back(row);
    }

    return recipeMap;
}

/** Writes final ingredient quantities as a batched UPDATE statement. */
void printFinalInventoryUpdates(const vector<InventoryItem> &inventory,
    const map<int, double> &baseInventory, const map<int, deque<Shipment>> &shipments) {
    cout << "UPDATE Inventory AS i "
            "SET Quantity = v.Quantity "
            "FROM (VALUES\n";

    for (size_t i = 0; i < inventory.size(); ++i) {
        double quantity = getTotalInventory(inventory[i].id, baseInventory, shipments);

        cout << "(" << inventory[i].id << "," << fixed << setprecision(3) << quantity << ")";

        if (i + 1 < inventory.size())
            cout << ",\n";
        else
            cout << "\n";
    }

    cout << ") AS v(InventoryID, Quantity) "
            "WHERE i.InventoryID = v.InventoryID AND i.Active = TRUE;\n";
}

/** Writes remaining quantities for all shipment lots. */
void printFinalShipmentUpdates(const map<int, deque<Shipment>> &shipments) {
    for (const auto &entry : shipments) {
        for (const Shipment &shipment : entry.second) {
            printShipmentUpdate(shipment);
        }
    }
}

/** Writes the remaining shipment lots as a batched INSERT statement. */
void printFinalShipments(const map<int, deque<Shipment>> &shipments) {
    vector<Shipment> remaining;

    for (const auto &entry : shipments) {
        for (const Shipment &shipment : entry.second) {
            remaining.push_back(shipment);
        }
    }

    if (remaining.empty()) {
        return;
    }

    cout << "INSERT INTO InventoryItem "
            "(ShipmentID, InventoryID, WholesalePrice, Quantity, "
            "ReceivedDate, ExpirationDate) VALUES\n";

    for (size_t i = 0; i < remaining.size(); ++i) {
        const Shipment &shipment = remaining[i];

        cout << "(" << shipment.shipmentId << "," << shipment.inventoryId << "," << fixed
             << setprecision(2) << shipment.wholesalePrice << "," << fixed << setprecision(3)
             << shipment.quantity << ",'" << shipment.receivedDate << "','"
             << shipment.expirationDate << "')";

        if (i + 1 < remaining.size()) {
            cout << ",\n";
        } else {
            cout << ";\n";
        }
    }
}

/** Reads generator arguments and writes the generated SQL to standard output. */
int main(int argc, char **argv) {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);

    if (argc < 4) {
        cerr << "Usage: " << argv[0] << " MenuItems.csv Inventory.csv Recipes.csv "
             << "[days=7] [peak_day=3] [seed]\n";
        return 1;
    }

    string menuPath = argv[1];
    string inventoryPath = argv[2];
    string recipePath = argv[3];

    int days = 7;
    if (argc >= 5)
        days = stoi(argv[4]);

    int peakDay = 3;
    if (argc >= 6)
        peakDay = stoi(argv[5]);

    uint64_t seed = (uint64_t)chrono::high_resolution_clock::now().time_since_epoch().count();

    if (argc >= 7)
        seed = stoull(argv[6]);

    RandomEngine rng(seed);
    auto menu = loadMenu(menuPath);

    auto inventory = loadInventory(inventoryPath);

    map<string, int> menuNameToId;
    map<string, int> inventoryNameToId;

    for (const auto &item : menu)
        menuNameToId[item.name] = item.id;

    for (const auto &item : inventory)
        inventoryNameToId[item.name] = item.id;

    auto recipes = loadRecipes(recipePath, menuNameToId, inventoryNameToId);

    auto recipeMap = buildRecipeMap(recipes);

    map<int, deque<Shipment>> shipments;
    map<int, double> baseInventory;

    for (const auto &item : inventory) {
        baseInventory[item.id] = item.quantity;
    }

    vector<int> entrees;
    vector<int> sides;
    vector<int> drinks;
    vector<int> appetizers;
    vector<int> extras;
    vector<int> cubMeals;
    vector<int> meals;

    for (size_t i = 0; i < menu.size(); ++i) {
        if (menu[i].category == "Entrees")
            entrees.push_back(i);
        else if (menu[i].category == "Sides" && menu[i].name.find("SOUP") == string::npos)
            sides.push_back(i);
        else if (menu[i].category == "Drinks")
            drinks.push_back(i);
        else if (menu[i].category == "Appetizers")
            appetizers.push_back(i);
        else if (menu[i].category == "Extras")
            extras.push_back(i);
        else if (mealEntreeCount(menu[i].name) > 0)
            meals.push_back(i);
        else if (menu[i].category == "Meals")
            cubMeals.push_back(i);
    }

    // Simulation parameters
    double baseLambdaPerMin = 0.2; // baseline orders per minute (adjust to scale)
    // OU intensity parameters (minute units: dt = 1)
    double theta = 0.05; // speed of reversion
    double mu = 0.0;     // we'll use multiplicative noise around 0 (log-space)
    double sigma = 0.3;  // volatility of log-intensity
    // diurnal hourly multipliers (24 entries) - lunch and dinner peaks
    vector<double> hourMult(24, 0.5);
    for (int h = 6; h <= 9; ++h)
        hourMult[h] = 0.6; // breakfast light
    for (int h = 11; h <= 13; ++h)
        hourMult[h] = 1.6; // lunch peak
    for (int h = 16; h <= 19; ++h)
        hourMult[h] = 1.8; // dinner peak
    for (int h = 20; h <= 22; ++h)
        hourMult[h] = 1.0;
    // weekly multiplier: peak day gets boost
    double peakMultiplier = 1.5;
    double otherDayMultiplier = 1.0;

    // operating hours
    int openH = 10, closeH = 22; // 10:00 to 22:00

    // SQL generation bookkeeping
    long long orderId = 1000000; // start high to avoid collisions
    long long orderItemId = 2000000;

    // get today's date as end date
    time_t tnow = time(nullptr);
    tm tmNow = *localtime(&tnow);
    // simulate previous 'days' days ending yesterday (so neat)
    tmNow.tm_mday -= 1;
    mktime(&tmNow); // normalize

    tm startDay = tmNow; // go from first day to now
    startDay.tm_mday -= days - 1;
    mktime(&startDay);
    // For each simulated day
    for (int d = 0; d < days; ++d) {
        // current day
        tm day = startDay;
        day.tm_mday += d;
        mktime(&day);
        int wday = day.tm_wday; // 0=Sun..6=Sat
        double weeklyMult = (wday == peakDay) ? peakMultiplier : otherDayMultiplier;
        // check for resock

        restockInventory(day, inventory, baseInventory, shipments, rng);
        // initialize OU log-noise for intensity per day
        double logNoise = 0.0;

        // per-minute loop
        vector<string> ordersBatch;
        vector<string> orderItemsBatch;
        vector<string> selectionsBatch;

        ordersBatch.reserve(500);
        orderItemsBatch.reserve(1000);

        for (int h = openH; h < closeH; ++h) {
            for (int minute = 0; minute < 60; ++minute) {
                // advance OU with dt=1 minute (t in minutes)
                logNoise = ouStep(rng, logNoise, theta, mu, sigma, 1.0);

                // compute lambda for this minute
                double hourFactor = hourMult[h];
                double lambda = baseLambdaPerMin * hourFactor * weeklyMult * exp(logNoise);
                if (lambda < 1e-6)
                    lambda = 1e-6;

                // sample number of arrivals in this minute
                poisson_distribution<int> pois(lambda);
                int arrivals = pois(rng);
                for (int a = 0; a < arrivals; ++a) {
                    // decide order time within the minute uniformly
                    int sec = uniform_int_distribution<int>(0, 59)(rng);
                    // sample with replacement if possible
                    vector<int> chosenIdx;

                    // 8% chance of a Cub Meal
                    bool cubOrder = uniform_int_distribution<int>(1, 100)(rng) <= 8;

                    if (cubOrder && !cubMeals.empty()) {
                        chosenIdx.push_back(sampleCategory(rng, cubMeals));
                    } else {
                        int meal = sampleCategory(rng, meals);
                        if (meal == -1 || sides.empty() || entrees.empty())
                            continue;
                        chosenIdx.push_back(meal);
                        chosenIdx.push_back(sampleCategory(rng, sides));
                        for (int i = 0; i < mealEntreeCount(menu[meal].name); ++i)
                            chosenIdx.push_back(sampleCategory(rng, entrees));
                        // DRINK

                        if (uniform_int_distribution<int>(1, 100)(rng) <= 40) {
                            int idx = sampleCategory(rng, drinks);

                            if (idx != -1)
                                chosenIdx.push_back(idx);
                        }
                        // APPETIZER

                        if (uniform_int_distribution<int>(1, 100)(rng) <= 12) {
                            int idx = sampleCategory(rng, appetizers);

                            if (idx != -1)
                                chosenIdx.push_back(idx);
                        }
                        // EXTRA / DESSERT

                        if (uniform_int_distribution<int>(1, 100)(rng) <= 7) {
                            int idx = sampleCategory(rng, extras);

                            if (idx != -1)
                                chosenIdx.push_back(idx);
                        }
                    }
                    map<int, int> selectionCounts;
                    int mealIdx = -1;
                    if (!cubOrder && !chosenIdx.empty() &&

                        mealEntreeCount(menu[chosenIdx[0]].name) > 0) {
                        mealIdx = chosenIdx[0];
                        int count = 1 + mealEntreeCount(menu[mealIdx].name);
                        for (int i = 1; i <= count; ++i)
                            selectionCounts[chosenIdx[i]]++;
                        chosenIdx.erase(chosenIdx.begin() + 1, chosenIdx.begin() + 1 + count);
                    }
                    map<int, int> itemCounts;

                    for (int idx : chosenIdx) { // turns multiple orders of same entree into one
                                                // menu item with quantity x
                        itemCounts[idx]++;
                    }
                    if (itemCounts.empty())
                        continue;

                    // Check the whole order before consuming any stock. Different
                    // menu items can share ingredients, so per-item checks are insufficient.
                    map<int, double> required;
                    map<int, int> consumedCounts = itemCounts;
                    for (const auto &entry : selectionCounts)
                        consumedCounts[entry.first] += entry.second;
                    for (const auto &entry : consumedCounts) {
                        auto recipe = recipeMap.find(menu[entry.first].id);
                        if (recipe == recipeMap.end())
                            continue; // unmapped items use no stock
                        for (const auto &ingredient : recipe->second)
                            required[ingredient.inventoryId] +=
                                ingredient.quantityRequired * entry.second;
                    }
                    bool available = true;
                    for (const auto &ingredient : required) {
                        if (getTotalInventory(ingredient.first, baseInventory, shipments) <
                            ingredient.second) {
                            available = false;
                            break;
                        }
                    }
                    if (!available)
                        continue;
                    for (const auto &ingredient : required) {
                        if (!consumeInventoryFifo(
                                ingredient.first, ingredient.second, baseInventory, shipments)) {
                            cerr << "Failed to consume validated order inventory\n";
                            return 1;
                        }
                    }

                    // Meal components are included in the meal price; match cashier sales tax.
                    double total = 0.0;
                    for (const auto &entry : itemCounts)
                        total += menu[entry.first].price * entry.second;
                    total *= 1.0825;
                    // format date/time
                    char bufDate[32], bufTime[32];
                    snprintf(bufDate, sizeof(bufDate), "%04d-%02d-%02d", day.tm_year + 1900,
                        day.tm_mon + 1, day.tm_mday);
                    snprintf(bufTime, sizeof(bufTime), "%02d:%02d:%02d", h, minute, sec);

                    // create Order and OrderItem inserts as strings
                    // Order: (OrderID, OrderDate, OrderTime, Total)
                    ostringstream oorder;
                    oorder << "(" << orderId << ",'" << bufDate << "','" << bufTime << "'," << fixed
                           << setprecision(2) << total << ")";
                    ordersBatch.push_back(oorder.str());

                    // Batch item rows after their parent order rows.
                    for (auto &entry : itemCounts) {
                        int idx = entry.first;
                        int q = entry.second;

                        int mid = menu[idx].id;

                        if (idx == mealIdx) {
                            for (const auto &selection : selectionCounts) {
                                ostringstream row;
                                row << "(" << orderItemId << "," << menu[selection.first].id << ","
                                    << selection.second << ")";
                                selectionsBatch.push_back(row.str());
                            }
                        }
                        ostringstream oitem;
                        oitem << "(" << orderItemId++ << "," << orderId << "," << mid << "," << q
                              << ")";

                        orderItemsBatch.push_back(oitem.str());
                    }

                    orderId++;
                } // arrivals
                // flush batches every so often
                if (!ordersBatch.empty() && ordersBatch.size() >= 500) {
                    cout << "INSERT INTO Orders (OrderID, OrderDate, OrderTime, Total) VALUES\n";

                    for (size_t i = 0; i < ordersBatch.size(); ++i) {
                        cout << ordersBatch[i] << (i + 1 < ordersBatch.size() ? ",\n" : ";\n");
                    }

                    cout << "INSERT INTO OrderItem "
                            "(OrderItemID, OrderID, MenuItemID, Qty) VALUES\n";

                    for (size_t i = 0; i < orderItemsBatch.size(); ++i) {
                        cout << orderItemsBatch[i]
                             << (i + 1 < orderItemsBatch.size() ? ",\n" : ";\n");
                    }

                    if (!selectionsBatch.empty()) {
                        cout << "INSERT INTO OrderItemSelection (OrderItemID, MenuItemID, Qty) "
                                "VALUES\n";
                        for (size_t i = 0; i < selectionsBatch.size(); ++i)
                            cout << selectionsBatch[i]
                                 << (i + 1 < selectionsBatch.size() ? ",\n" : ";\n");
                        selectionsBatch.clear();
                    }
                    ordersBatch.clear();
                    orderItemsBatch.clear();
                }
            } // minutes
        } // hours
        // flush remaining orders for day
        if (!ordersBatch.empty()) {
            cout << "INSERT INTO Orders (OrderID, OrderDate, OrderTime, Total) VALUES\n";

            for (size_t i = 0; i < ordersBatch.size(); ++i) {
                cout << ordersBatch[i] << (i + 1 < ordersBatch.size() ? ",\n" : ";\n");
            }

            cout << "INSERT INTO OrderItem "
                    "(OrderItemID, OrderID, MenuItemID, Qty) VALUES\n";

            for (size_t i = 0; i < orderItemsBatch.size(); ++i) {
                cout << orderItemsBatch[i] << (i + 1 < orderItemsBatch.size() ? ",\n" : ";\n");
            }

            if (!selectionsBatch.empty()) {
                cout << "INSERT INTO OrderItemSelection (OrderItemID, MenuItemID, Qty) VALUES\n";
                for (size_t i = 0; i < selectionsBatch.size(); ++i)
                    cout << selectionsBatch[i] << (i + 1 < selectionsBatch.size() ? ",\n" : ";\n");
                selectionsBatch.clear();
            }
            ordersBatch.clear();
            orderItemsBatch.clear();
        }
    } // days

    printFinalShipments(shipments);

    printFinalInventoryUpdates(inventory, baseInventory, shipments);

    cout << "COMMIT;\n";
    return 0;
}
