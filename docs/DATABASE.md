# Database Setup (Neon)

Our database is PostgreSQL hosted on **Neon** (project **Team 3 Project 3**, region AWS US East).
It holds the same tables and data as Project 2. Ask Vishanth for access.

## Branches: which database to use

| Branch | Use it for | Who points at it |
|---|---|---|
| `dev` | Local development, testing, trying schema changes | Everyone's local `.env` |
| `production` | The deployed app only | The hosting service's environment variables |

**Never point your local code at `production`.** If you break `dev`, it can be reset from
`production` in the Neon console (Branches → `dev` → Reset from parent).

## 1. Get the connection details

1. Get invited to the Neon project, then sign in at console.neon.tech.
2. Open **Team 3 Project 3** → **Connect**.
3. Choose branch **`dev`**, database `neondb`, and turn **connection pooling off**.
4. Note the host (`ep-....us-east-1.aws.neon.tech`), the user, and the password.

## 2. Create your `.env`

At the repository root, copy `.env.example` to `.env` and fill it in:

```
DB_URL=jdbc:postgresql://<host>/neondb?sslmode=require
DB_USER=<user>
DB_PASSWORD=<password>
```

`.env` is git-ignored. **Never commit it, paste it into Slack/Discord, or put real
credentials in any `.java` / `.py` file.** Real environment variables override `.env`, which
is how the deployed app is configured.

## 3. Run the Java code

You need the PostgreSQL JDBC driver jar (it is not in the repo). Use the
`postgresql-42.2.8.jar` from Project 2, or download the latest from
https://jdbc.postgresql.org/download/ (any recent version works). Put it in
`src/team-3-project-1/gui/`, or use its full path in `-cp` below.

`gui/DbSetup.java` finds `.env` by searching the current folder and its parents, so run from
inside the repo. Use `;` as the classpath separator on Windows and `:` on Mac/Linux:

```bash
cd src/team-3-project-1/gui
javac -cp ".;postgresql-42.2.8.jar" *.java
java -cp ".;postgresql-42.2.8.jar" JdbcSQL     # prints "Opened database successfully"
java -cp ".;postgresql-42.2.8.jar" ModeSelect  # the full app
```

If you see `Missing database setting DB_URL`, your `.env` is missing or not in the repository
root. `javac: Invalid filename: *.java` means you are not in the `gui` folder.

## 4. Look at the data without code

- **Neon console → SQL Editor:** run queries in the browser (pick the `dev` branch first).
- **psql:** `psql "postgresql://<user>:<password>@<host>/neondb?sslmode=require"`

## Good to know

- **Cold starts:** on the free tier, Neon pauses after ~5 minutes idle. The first query after
  a pause takes about a second longer. That's normal.
- **Connections are limited** on the free tier: open one connection and reuse it, and always
  close connections, statements and result sets.
- **Schema changes** (e.g. new tables for the rewards feature): try them on `dev` first, then
  write the SQL into a file in the repo, apply it to `production`, and add an entry to
  `CHANGELOG.md` (required by the assignment).
- **Front end (Flask):** if Python ever needs the database directly, use the same `dev`
  settings via environment variables (`psycopg` accepts the
  `postgresql://user:password@host/neondb?sslmode=require` form), never hardcoded.
