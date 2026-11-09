"""Print SQL to create the customer table; does not connect to a database."""


def main() -> None:
    print(
        """CREATE TABLE customer (
    customer_id INTEGER PRIMARY KEY,
    phone VARCHAR(20) NOT NULL,
    points INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
    full_name VARCHAR(150) NOT NULL
);"""
    )


if __name__ == "__main__":
    main()