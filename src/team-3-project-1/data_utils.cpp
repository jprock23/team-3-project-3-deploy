/** Parses input CSV files and supplies shared SQL and date helpers. */

#include "data_utils.h"
#include <bits/stdc++.h>

using namespace std;

namespace {

/** Reports invalid CSV input and stops generation. */
[[noreturn]] void invalidCsv(const string &path, const string &message) {
    cerr << "Invalid CSV " << path << ": " << message << "\n";
    exit(1);
}

/** Parses a finite, nonnegative number or reports invalid CSV input. */
double nonnegativeNumber(const string &value, const string &path) {
    try {
        size_t end;
        double number = stod(value, &end);
        while (end < value.size() && isspace((unsigned char)value[end]))
            ++end;
        if (end != value.size() || !isfinite(number) || number < 0)
            invalidCsv(path, "invalid non-negative number: " + value);
        return number;
    } catch (const exception &) {
        invalidCsv(path, "invalid number: " + value);
    }
}

/** Parses a whole nonnegative number within the inventory integer range. */
int nonnegativeInteger(const string &value, const string &path) {
    double number = nonnegativeNumber(value, path);
    // InventoryItem currently stores whole units; never silently truncate fractions.
    if (number > numeric_limits<int>::max() || floor(number) != number)
        invalidCsv(path, "expected a whole number: " + value);
    return static_cast<int>(number);
}
} // namespace

/** Removes a matching pair of surrounding double quotes. */
string trimQuotes(const string &s) {
    if (s.size() >= 2 && s.front() == '"' && s.back() == '"') {
        return s.substr(1, s.size() - 2);
    }

    return s;
}

/** Splits a CSV row while preserving commas inside quoted fields. */
vector<string> splitCsvLine(const string &line) {
    vector<string> out;
    string cur;
    bool inQuotes = false;

    for (char c : line) {
        if (c == '"') {
            inQuotes = !inQuotes;
            cur += c;
        } else if (c == ',' && !inQuotes) {
            out.push_back(trimQuotes(cur));
            cur.clear();
        } else {
            cur += c;
        }
    }

    out.push_back(trimQuotes(cur));
    return out;
}

/** Escapes single quotes for SQL string literals. */
string sqlEscape(const string &s) {
    string out;

    for (char c : s) {
        if (c == '\'')
            out += "''";
        else
            out += c;
    }

    return out;
}

/** Returns the number of entree selections included in a meal. */
int mealEntreeCount(const string &name) {
    if (name == "BOWL")
        return 1;
    if (name == "PLATE")
        return 2;
    if (name == "BIGGER PLATE")
        return 3;
    return 0;
}

/** Maps a menu name to its category or marks an excluded component. */
string classifyItem(const string &originalName) {
    string name = originalName;

    for (char &c : name) {
        c = toupper((unsigned char)c);
    }

    if (mealEntreeCount(name) > 0)
        return "Meals";

    if (name.find("- CUB MEAL") != string::npos)
        return "EXCLUDE";
    if (name.find("CUB MEAL (") != string::npos)
        return "Meals";

    if (name == "CHOW FUN" || name == "CHOW MEIN" || name == "FRIED RICE" ||
        name == "SUPER GREENS" || name == "WHITE STEAMED RICE")
        return "Sides";

    if (name.find("EGG ROLL") != string::npos || name.find("POTSTICKER (") != string::npos ||
        name.find("CREAM CHEESE RANGOON") != string::npos ||
        name.find("SPRING ROLL") != string::npos)
        return "Appetizers";

    if (name.find("SAUCE") != string::npos || name == "HOT MUSTARD" || name == "FORTUNE COOKIE" ||
        name == "TREE TOP APPLE CRISPS" || name == "APPLE PIE ROLL")
        return "Extras";

    if (name.find("SOUP") != string::npos)
        return "Sides";

    if (name.find("HI-C") != string::npos || name.find("COCA-COLA") != string::npos ||
        name.find("COKE") != string::npos || name.find("SPRITE") != string::npos ||
        name.find("FANTA") != string::npos || name.find("DR PEPPER") != string::npos ||
        name.find("ROOT BEER") != string::npos || name.find("TEA") != string::npos ||
        name.find("LEMONADE") != string::npos || name.find("POWERADE") != string::npos ||
        name.find("GINGER ALE") != string::npos || name.find("VITAMINWATER") != string::npos ||
        name.find("REFRESHER") != string::npos)
        return "Drinks";

    return "Entrees";
}

/** Loads and validates inventory records from a CSV file. */
vector<InventoryItem> loadInventory(const string &path) {
    ifstream f(path);

    if (!f) {
        cerr << "Failed to open inventory CSV: " << path << "\n";
        exit(1);
    }

    string header;
    getline(f, header);

    vector<InventoryItem> inventory;

    string line;

    while (getline(f, line)) {
        if (line.find_first_not_of(" \t\r") == string::npos)
            continue;

        vector<string> fields = splitCsvLine(line);

        if (fields.size() < 6)
            invalidCsv(path, "inventory row needs six fields");

        InventoryItem item;

        item.id = nonnegativeInteger(fields[0], path);
        item.name = fields[1];
        item.unit = fields[2];
        item.minimumThreshold = nonnegativeInteger(fields[3], path);
        item.quantity = nonnegativeInteger(fields[4], path);
        item.shelfLife = nonnegativeInteger(fields[5], path);

        inventory.push_back(item);
    }

    if (inventory.empty())
        invalidCsv(path, "no inventory items");
    return inventory;
}

/** Loads and validates menu records, assigning IDs and categories. */
vector<MenuItem> loadMenu(const string &path) {
    ifstream f(path);

    if (!f) {
        cerr << "Failed to open menu CSV: " << path << "\n";
        exit(1);
    }

    string header;
    getline(f, header);

    vector<MenuItem> menu;

    string line;
    int id = 1;

    while (getline(f, line)) {
        if (line.find_first_not_of(" \t\r") == string::npos)
            continue;

        vector<string> fields = splitCsvLine(line);

        if (fields.size() < 2)
            invalidCsv(path, "menu row needs a name and price");

        string name = fields[0];

        string category = classifyItem(name);

        if (category == "EXCLUDE")
            continue;

        MenuItem item;

        item.id = id++;
        item.name = name;
        item.category = category;
        item.price = nonnegativeNumber(fields[1], path);

        menu.push_back(item);
    }

    if (menu.empty())
        invalidCsv(path, "no menu items");
    return menu;
}

/** Loads recipes and resolves ingredient and menu names to IDs. */
vector<MenuItemInventoryRow> loadRecipes(const string &path, const map<string, int> &menuNameToId,
    const map<string, int> &inventoryNameToId) {
    ifstream f(path);

    if (!f) {
        cerr << "Failed to open recipes CSV: " << path << "\n";
        exit(1);
    }

    string header;
    getline(f, header);

    vector<MenuItemInventoryRow> rows;
    string line;

    while (getline(f, line)) {
        if (line.find_first_not_of(" \t\r") == string::npos)
            continue;

        vector<string> fields = splitCsvLine(line);

        if (fields.size() < 4)
            invalidCsv(path, "recipe row needs four fields");

        string menuName = fields[0];
        string inventoryName = fields[1];
        double quantityRequired = nonnegativeNumber(fields[2], path);
        if (quantityRequired <= 0)
            invalidCsv(path, "recipe quantities must be positive");

        auto menuIt = menuNameToId.find(menuName);
        auto inventoryIt = inventoryNameToId.find(inventoryName);

        if (menuIt == menuNameToId.end()) {
            if (classifyItem(menuName) == "EXCLUDE")
                continue;

            invalidCsv(path, "menu item not found: " + menuName);
        }

        if (inventoryIt == inventoryNameToId.end()) {
            invalidCsv(path, "inventory item not found: " + inventoryName);
        }

        MenuItemInventoryRow row;

        row.menuItemId = menuIt->second;
        row.inventoryId = inventoryIt->second;
        row.quantityRequired = quantityRequired;

        rows.push_back(row);
    }

    return rows;
}

/** Formats a calendar date as an ISO date string. */
string formatDate(const tm &date) {
    char buffer[20];

    strftime(buffer, sizeof(buffer), "%Y-%m-%d", &date);

    return string(buffer);
}

/** Advances and normalizes a calendar date by the requested number of days. */
tm addDays(tm date, int days) {
    date.tm_mday += days;
    mktime(&date);

    return date;
}
