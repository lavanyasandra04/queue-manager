# Queue Manager (Java + MySQL)

Full-stack queue management app. Managers create queues, add people as tokens, call the next person, and track queue analytics.

**Stack:** Java 17+ · Spring Boot 3 (REST API) · MySQL 8 · JDBC · JWT login with BCrypt-hashed passwords · vanilla JS frontend (served by Spring Boot, no build step) · Chart.js dashboard.

## Features

| # | Feature | Where |
|---|---------|-------|
| 1 | Manager can log in and create queues | Register / Log in screen, "New queue" in the sidebar |
| 2 | Each queue has a name | `queues.name` |
| 3 | Add a person, a token is created | "Add to queue" gives a per-queue token number (#001, #002 ...) |
| 4 | Queue shows waiting tokens | Waiting list, auto-refreshes every 4 s |
| 5 | Move tokens up / down | Up / down arrow buttons |
| 6 | Assign the top token for service | **Call next person** button (previous token in service is auto-completed) |
| 7 | Cancel a token | Cancel (x) button |
| 8 | Analytics dashboard | **Dashboard** tab: average / longest wait, average service time, served / cancelled counts, queue-length trend, wait time per token, arrivals per hour |

## Setup

1. Install **JDK 17+** and **MySQL 8** (make sure the MySQL server is running).
2. Set an environment variable named `DB_PASSWORD` with your MySQL root password. The app connects as user `root` (change `spring.datasource.username` in `src/main/resources/application.properties` if your MySQL user is different).
3. The database `queuedb` and all tables are created automatically on first start. You can also create the database yourself first: `CREATE DATABASE queuedb;`
4. Run:
   ```bash
   mvn spring-boot:run
   ```
   or, in Eclipse / IntelliJ, run `QueueManagerApplication`.
5. Open http://localhost:8080

## Test

```bash
mvn test
```

`QueueApiTest` runs the whole flow (auth, add, reorder, cancel, serve, analytics, access control) against an in-memory H2 database in MySQL mode, so MySQL does not need to be running for the tests.

## API

All routes except `/api/auth/*` need `Authorization: Bearer <token>`.

| Method | Route | Purpose |
|--------|-------|---------|
| POST | `/api/auth/register`, `/api/auth/login` | Get a JWT |
| GET / POST | `/api/queues` | List / create queues |
| GET / DELETE | `/api/queues/{id}` | Queue state (waiting, serving, history) / delete |
| POST | `/api/queues/{id}/tokens` | Add a person (creates a token) |
| POST | `/api/queues/{id}/serve` | Call the token at the top |
| POST | `/api/tokens/{id}/move` | `{ "direction": "up" or "down" }` |
| POST | `/api/tokens/{id}/cancel` | Cancel a waiting token |
| POST | `/api/tokens/{id}/complete` | Finish the token being served |
| GET | `/api/queues/{id}/analytics` | Dashboard data |

## Design notes

- Token lifecycle: `waiting -> serving -> served`, or `waiting -> cancelled`.
- Moving a token swaps its `pos` with the neighbouring waiting token inside a transaction.
- Every query is scoped to the logged-in manager's id (taken from the JWT), so managers can't see each other's queues.
- Wait time = time from token creation to being called. Timestamps are stored as epoch milliseconds.
- `AuthInterceptor` validates the JWT for `/api/**` (except `/api/auth/**`); `GlobalExceptionHandler` turns `ApiException` into `{ "error": "..." }`.

## Project structure

```
pom.xml
src/main/java/com/queuemanager/
  QueueManagerApplication.java   entry point
  AuthController / UserService   register, login
  QueueController / QueueService queues, tokens, analytics
  JwtService, AuthInterceptor, WebConfig   JWT auth
  Models, ApiException, GlobalExceptionHandler
src/main/resources/
  application.properties, schema.sql
  static/  index.html, style.css, app.js   (frontend)
src/test/java/com/queuemanager/QueueApiTest.java
```
