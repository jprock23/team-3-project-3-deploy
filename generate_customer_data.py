"""Generate SQL to insert 10 fictional customers into the customer table."""

import random


CUSTOMER_COUNT = 10
FIRST_NAMES = (
    "Avery", "Cameron", "Drew", "Elliot", "Harper",
    "Jamie", "Jordan", "Kai", "Morgan", "Riley",
)
LAST_NAMES = (
    "Bennett", "Brooks", "Carter", "Diaz", "Ellis",
    "Foster", "Hayes", "Kim", "Morgan", "Reed",
)


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def main() -> None:
    names = [
        f"{first} {last}"
        for first in FIRST_NAMES
        for last in LAST_NAMES
    ]
    random.shuffle(names)

    rows = []
    for offset, full_name in enumerate(names[:CUSTOMER_COUNT], start=1):
        phone = f"202-555-{1000 + offset:04d}"
        points = random.randint(0, 5000)
        rows.append(
            f"    ({offset}, {sql_string(phone)}, {points}, "
            f"{sql_string(full_name)})"
        )

    print(
        "BEGIN;\n"
        "WITH current_id AS (\n"
        "    SELECT COALESCE(MAX(customer_id), 0) AS max_id FROM customer\n"
        "), new_customers (customer_offset, phone, points, full_name) AS (\n"
        "    VALUES\n"
        + ",\n".join(rows)
        + "\n)\n"
        "INSERT INTO customer (customer_id, phone, points, full_name)\n"
        "SELECT current_id.max_id + new_customers.customer_offset,\n"
        "       new_customers.phone, new_customers.points, "
        "new_customers.full_name\n"
        "FROM current_id CROSS JOIN new_customers;\n"
        "COMMIT;"
    )


if __name__ == "__main__":
    main()