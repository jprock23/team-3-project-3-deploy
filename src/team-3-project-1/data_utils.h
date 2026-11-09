/** Declares shared menu, inventory, shipment, CSV, and date utilities. */

#ifndef DATA_UTILS_H
#define DATA_UTILS_H

#include <ctime>
#include <string>
#include <vector>
#include <map>

using namespace std;

struct MenuItem {
    int id;
    string name;
    double price;
    string category;
};

struct InventoryItem {
    int id;
    string name;
    string unit;
    int quantity;
    int minimumThreshold;
    int shelfLife;
};

struct MenuItemInventoryRow {
    int menuItemId;
    int inventoryId;
    double quantityRequired;
};

struct Shipment {
    long long shipmentId;
    int inventoryId;
    double wholesalePrice;
    double quantity;
    string receivedDate;
    string expirationDate;
};

/** Removes a matching pair of surrounding double quotes. */
string trimQuotes(const string &s);

/** Splits a CSV row while preserving commas inside quoted fields. */
vector<string> splitCsvLine(const string &line);

/** Escapes single quotes for SQL string literals. */
string sqlEscape(const string &s);

/** Returns the number of entree selections included in a meal. */
int mealEntreeCount(const string &name);

/** Maps a menu name to its category or marks an excluded component. */
string classifyItem(const string &originalName);

/** Loads and validates inventory records from a CSV file. */
vector<InventoryItem> loadInventory(const string &path);

/** Loads and validates menu records, assigning IDs and categories. */
vector<MenuItem> loadMenu(const string &path);

/** Loads recipes and resolves ingredient and menu names to IDs. */
vector<MenuItemInventoryRow> loadRecipes(const string &path, const map<string, int> &menuNameToId,
    const map<string, int> &inventoryNameToId);

/** Formats a calendar date as an ISO date string. */
string formatDate(const tm &date);

/** Advances and normalizes a calendar date by the requested number of days. */
tm addDays(tm date, int days);

#endif
