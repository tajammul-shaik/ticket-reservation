# ts-ticketing-system: Seat Reservation at Scale

A Java 21 / Spring Boot 4.1 JSON API that sells assigned seats for a show and never sells a seat
twice, even when thousands of buyers press "reserve" at the same moment. It uses one MySQL 8
(InnoDB) database.

It guarantees three things:

1. **No double-sell.** Each seat has one winner; the others get `409 seat_taken`, never a 500.
2. **No limit breach.** A user never holds more seats than the show's `per_user_limit`.
3. **No double-charge.** Retrying with the same idempotency key reserves exactly once.

Design details are in [`architecture.md`](architecture.md); the build log is in
[`build-steps.md`](build-steps.md).

- **Live URL:** https://ticket-reservation-05jk.onrender.com

> The free Render instance sleeps when idle, so the first request after a pause can take up to a
> minute while it wakes. Call `/health/ready` once before testing.

---

## Contents

1. [Tech stack](#1-tech-stack)
2. [Prerequisites](#2-prerequisites)
3. [Get the code](#3-get-the-code)
4. [Run locally with Docker Compose (quickest)](#4-run-locally-with-docker-compose-quickest)
5. [Run locally with Maven against your own MySQL](#5-run-locally-with-maven-against-your-own-mysql)
6. [Run the tests](#6-run-the-tests)
7. [Try the API](#7-try-the-api)
8. [Run the Postman collection](#8-run-the-postman-collection)
9. [Run the burst test](#9-run-the-burst-test)
10. [Deploy to Render with Aiven MySQL](#10-deploy-to-render-with-aiven-mysql)
11. [Configuration reference](#11-configuration-reference)
12. [Observability](#12-observability)
13. [Error codes](#13-error-codes)
14. [Troubleshooting](#14-troubleshooting)

---

## 1. Tech stack

| Concern | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 4.1 (virtual threads on) |
| Database | MySQL 8 InnoDB, READ COMMITTED, schema managed by Flyway |
| Data access | Hand-written SQL via `JdbcTemplate` (no JPA) |
| Auth | Stateless HS256 JWT (`jjwt`) |
| Metrics | Micrometer + Prometheus at `/metrics` |
| Logs | JSON lines (`logstash-logback-encoder`) in the `prod` profile |
| Tests | JUnit 5 + Testcontainers (MySQL 8.4) |
| Packaging | Multi-stage `Dockerfile`, `docker-compose.yml` |
| Hosting | Render (web service, Docker), Aiven (managed MySQL) |

## 2. Prerequisites

| Tool | Needed for | Check |
|---|---|---|
| Git | cloning | `git --version` |
| Docker + Docker Compose v2 | compose stack **and** the test suite (Testcontainers) | `docker version`, `docker compose version` |
| JDK 21 | running with Maven / running tests (not needed for compose only) | `java -version` |
| MySQL 8 | only for [section 5](#5-run-locally-with-maven-against-your-own-mysql) | `mysql --version` |
| Node.js (for `npx`) | only for running Postman with Newman | `node --version` |
| Python 3 with `venv` (or Docker) | only for the burst test | `python3 -m venv --help` |
| `curl`, `openssl` | trying the API, generating secrets | |

You don't need to install Maven: the repository ships the Maven wrapper (`./mvnw`).

## 3. Get the code

```sh
git clone https://github.com/tajammul-shaik/ticket-reservation.git
cd ticket-reservation
```

## 4. Run locally with Docker Compose (quickest)

This starts MySQL 8.4 and the app in containers. The app uses the same image and the same `prod`
profile as the deployed service.

```sh
docker compose up --build -d     # build the image and start both containers
docker compose ps                # wait until both show "healthy"
curl http://localhost:8777/health/ready
# {"status":"UP"}
```

The first run is the slowest. The image build downloads the Maven dependencies, and MySQL
initializes its data directory, which can take several minutes on a slow disk. Later runs reuse
the cached image layers and the `mysql-data` volume, and start in seconds.

Here is what you get:

| Service | Address | Credentials |
|---|---|---|
| App | `http://localhost:8777` | admin secret: `compose-local-admin-secret` |
| MySQL | `localhost:3307`, schema `ticket_reservation` | `ticket_app` / `ticket_app` (root: `root`) |

The compose secrets are local-only defaults. To override them, set them in your shell or in a
git-ignored `.env` file next to `docker-compose.yml`:

```sh
ADMIN_SECRET=my-local-secret JWT_SECRET=$(openssl rand -base64 48) docker compose up -d
```

Common commands:

```sh
docker compose logs -f app       # follow app logs (JSON lines)
docker compose restart app       # restart the app; data is kept
docker compose down              # stop everything; data is kept in the mysql-data volume
docker compose down -v           # stop and WIPE the database
```

> MySQL passwords are applied only when the volume is first created. If you change
> `MYSQL_PASSWORD` later, run `docker compose down -v` first.

## 5. Run locally with Maven against your own MySQL

### 5.1 Create the env file (outside the repo)

The app reads local settings from `~/.config/ticket-reservation/ticket-reservation.env`. Real
environment variables override the file. You can point at another file with `ENV_FILE=/path`.

```sh
mkdir -p ~/.config/ticket-reservation
cp .env.example ~/.config/ticket-reservation/ticket-reservation.env
chmod 600 ~/.config/ticket-reservation/ticket-reservation.env
```

Edit the file and fill in the values:

```properties
DATABASE_URL=jdbc:mysql://localhost:3306/ticket_reservation?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
DATABASE_USERNAME=root
DATABASE_PASSWORD=<your MySQL password>
DB_POOL_MIN_IDLE=10
DB_POOL_MAX_SIZE=20
JWT_SECRET=<output of: openssl rand -base64 48>
ADMIN_SECRET=<output of: openssl rand -hex 16>
```

Keep one `KEY=value` per line, with no quotes and no spaces around `=`. Never commit this file.

### 5.2 Start MySQL

Use any MySQL 8 on `localhost:3306`. The database is created on first connect
(`createDatabaseIfNotExist=true`), and Flyway creates the tables when the app starts.

### 5.3 Run the app

```sh
./mvnw spring-boot:run                                    # readable text logs
SPRING_PROFILES_ACTIVE=json-logs ./mvnw spring-boot:run   # JSON logs, as in production
```

The app listens on `http://localhost:8777`. To build and run the jar instead:

```sh
./mvnw -DskipTests package
java -jar target/ticket-reservation-*.jar
```

## 6. Run the tests

Docker must be running: the tests start a throwaway MySQL 8.4 container with Testcontainers.
They never touch your local database or env file.

```sh
./mvnw test                          # full suite (68 tests)
./mvnw test -Dtest=ConcurrencyTest   # only the concurrency tests
```

What the suite covers:

| Test class | What it proves |
|---|---|
| `ConcurrencyTest` | T-01 500 users / 1 seat gives exactly 1 winner; T-02 per-user limit under parallel requests; T-03/T-04 idempotency under a race; T-05 no partial reservations; T-06 no deadlocks with reversed seat order; T-07 a spoofed `user_id` is ignored; T-10 invariants after every test |
| `ReserveApiTest`, `CancelApiTest` | the functional reserve and cancel rules (T-08, T-09) |
| `ShowApiTest`, `AuthApiTest`, `JwtServiceTest` | show creation and validation, tokens, roles |
| `HealthControllerTest` | readiness returns 503 within the time limit when the DB hangs |
| `ReadinessUnderLoadTest` | readiness stays 200 while every main-pool connection is busy |
| `ObservabilityTest`, `AdmissionControlFilterTest` | request ids, metrics reconcile with the DB, 429 load shedding |

## 7. Try the API

In this section `BASE` is `http://localhost:8777` locally or your Render URL when deployed, and
`ADMIN_SECRET` is the admin secret for that environment.

```sh
BASE=http://localhost:8777
ADMIN_SECRET=compose-local-admin-secret
```

### Endpoints

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /auth/token` | none (`X-Admin-Secret` header for admin) | issue a JWT |
| `POST /shows` | admin token | create a show and its seats |
| `GET /shows/{id}` | any token | show state, counts, per-seat status |
| `POST /shows/{id}/reserve` | user token | reserve seats (idempotent) |
| `POST /reservations/{id}/cancel` | the owner's token | cancel and free the seats |
| `GET /health/live` | none | the process is up |
| `GET /health/ready` | none | the DB answers within 2 s (else 503) |
| `GET /metrics` | none | Prometheus metrics |

### 7.1 Get tokens

```sh
ADMIN_TOKEN=$(curl -s -X POST $BASE/auth/token \
  -H 'Content-Type: application/json' -H "X-Admin-Secret: $ADMIN_SECRET" \
  -d '{"user_id":"admin"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

USER_TOKEN=$(curl -s -X POST $BASE/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"alice"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
```

The response is `{"token":"...","user_id":"alice","role":"user"}`. A wrong admin secret silently
returns a `user` token. Tokens are valid for 24 h.

### 7.2 Create a show (admin)

```sh
SHOW_ID=$(curl -s -X POST $BASE/shows \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Evening Show","seats":["A1","A2","A3","A4","A5"],"price_paise":25000,"per_user_limit":4}' \
  | sed 's/.*"id":"\([^"]*\)".*/\1/')
echo $SHOW_ID
```

The request rules:
- Prices are **integer paise**. `250.5` is rejected with 400.
- `per_user_limit` defaults to 4.
- Seat labels are 1–8 characters and must be unique.
- A show can have at most 10,000 seats.

### 7.3 Read the show

```sh
curl -s $BASE/shows/$SHOW_ID -H "Authorization: Bearer $USER_TOKEN"
```

The response contains `counts` (`available`, `held`, `confirmed`), `invariant_ok` and each seat's
status. It never says who holds a seat.

### 7.4 Reserve seats

```sh
curl -si -X POST $BASE/shows/$SHOW_ID/reserve \
  -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: alice-order-1' \
  -d '{"seats":["A1","A2"]}'
```

| Outcome | Status |
|---|---|
| New reservation | `201` with `reservation_id`, `seats`, `amount_paise` (= seats × price), `status: confirmed` |
| Same key, same seats again | `200`, the same reservation, header `Idempotent-Replay: true` |
| Same key, different seats | `409 idempotency_key_conflict` |
| Any seat already taken | `409 seat_taken` (nothing is reserved; all-or-nothing) |
| Over the per-user limit | `409 per_user_limit` |
| Unknown seat label | `400 invalid_seat` |
| Server overloaded | `429 overloaded`: retry later with the same key |

The idempotency key can go in the `Idempotency-Key` header or in the body as
`"idempotency_key"`. A request can include at most 100 seats. Any `user_id` in the body is
ignored, because the user always comes from the token.

### 7.5 Cancel

```sh
curl -s -X POST $BASE/reservations/<reservation_id>/cancel -H "Authorization: Bearer $USER_TOKEN"
```

A cancel returns `200` with `status: cancelled` and frees the seats. Cancelling again returns the
same body. Cancelling someone else's reservation returns `404 reservation_not_found`.

## 8. Run the Postman collection

`postman/ticket-reservation.postman_collection.json` has 23 requests covering health, auth, shows,
reservations, cancel and metrics, with assertions. Each request depends on the ones before it, so
run them in order.

**In the Postman app:**
1. Import the collection.
2. Set the collection variables `baseUrl` and `adminSecret`.
3. Run the whole collection.

**From the terminal (Newman):**

```sh
# local / compose
npx -y newman@6.2.1 run postman/ticket-reservation.postman_collection.json \
  --env-var "adminSecret=compose-local-admin-secret"

# deployed service
npx -y newman@6.2.1 run postman/ticket-reservation.postman_collection.json \
  --env-var "baseUrl=https://<your-service>.onrender.com" \
  --env-var "adminSecret=<production ADMIN_SECRET>"
```

The expected result is 23 requests and 47 assertions, with 0 failures.

## 9. Run the burst test

`burst.sh` storms a running service (local or deployed) and checks the three guarantees end to
end against the API and `/metrics`:

1. **Setup:** wakes the service, creates a 200-seat show (limit 4) and mints 2,000 user tokens.
2. **Hot-seat storm:** 500 users reserve `A12` at once; exactly one may win.
3. **Stampede:** 20,000 requests for one or two seats each, half aimed at five hot seats.
4. **Idempotency:** 20 parallel calls with one key make one reservation; the same key with other
   seats gets `409 idempotency_key_conflict`.
5. **Per-user limit:** one user, 10 parallel requests; at most 4 seats confirmed.
6. **Spoofing:** a `user_id` in the body is ignored; cancelling another user's reservation is 404.
7. **Reconcile:** `invariant_ok`, no seat sold twice, nobody over the limit, metrics match the API,
   and no server-side 5xx on API endpoints.

```sh
# local compose stack
ADMIN_SECRET=compose-local-admin-secret ./burst.sh http://localhost:8777

# deployed service (pass the secret only through the environment)
ADMIN_SECRET=<production ADMIN_SECRET> ./burst.sh https://<your-service>.onrender.com

./burst.sh --help    # --requests, --users, --storm, --concurrency, --timeout, --wake-timeout
```

The script needs either `python3` with the `venv` module (it creates a git-ignored `.burst-venv/`
with `httpx` on the first run) or Docker (it then runs in a `python:3.12-slim` container on the
host network). It prints an outcome table and p50/p95/p99 latency per status, and exits 1 if any
check fails. `429 overloaded` responses and client timeouts are reported but do not fail the run:
shedding load is the intended behaviour, and a timed-out request may still have committed.

## 10. Deploy to Render with Aiven MySQL

```
git push ──► GitHub ──► Render builds the Dockerfile ──► container (prod profile, $PORT)
                                                              │ JDBC over TLS
                                                              ▼
                                                        Aiven MySQL 8
```

- **Render** builds the `Dockerfile` from this repository and runs the container as a web
  service with a public HTTPS URL.
- **Aiven** hosts the MySQL database. It takes the place of the `mysql` container from compose.
- Only environment variables differ between compose and production.

### Step 1: create the MySQL database on Aiven

1. Sign up at [aiven.io](https://aiven.io) and create a project.
2. Click **Create service**, choose **MySQL** and the **Free** plan. Pick a region close to the
   Render region you will use, because every query crosses the internet.
3. Wait until the service status is **Running** (a few minutes).
4. Under **Overview → Connection information**, note:
   - **Host:** e.g. `mysql-xxxx.aivencloud.com`
   - **Port:** e.g. `12345` (not 3306)
   - **User:** `avnadmin`, and its **Password**
   - **Database:** `defaultdb`
   - **SSL mode:** `REQUIRED`
5. Optional: test the connection from your machine:
   ```sh
   mysql -h <host> -P <port> -u avnadmin -p --ssl-mode=REQUIRED defaultdb
   ```

You don't need to create any tables, because Flyway creates them when the app first starts. Every
table has a primary key, which Aiven requires.

### Step 2: convert Aiven's URI to a JDBC URL

Aiven shows a service URI like this one:

```
mysql://avnadmin:PASSWORD@mysql-xxxx.aivencloud.com:12345/defaultdb?ssl-mode=REQUIRED
```

Split it into three variables for the Java driver:

```
DATABASE_URL      = jdbc:mysql://mysql-xxxx.aivencloud.com:12345/defaultdb?sslMode=REQUIRED&serverTimezone=UTC
DATABASE_USERNAME = avnadmin
DATABASE_PASSWORD = PASSWORD
```

- Add the `jdbc:` prefix.
- Move the user and password into their own variables.
- **`ssl-mode` becomes `sslMode`** in the Java driver.

`REQUIRED` encrypts the connection but does not verify the server certificate. Using
`VERIFY_CA` with Aiven's CA certificate is optional hardening.

### Step 3: generate production secrets

Generate new secrets; never reuse your local ones:

```sh
openssl rand -base64 48   # JWT_SECRET (at least 32 bytes; shorter fails startup)
openssl rand -hex 16      # ADMIN_SECRET
```

### Step 4: create the web service on Render

1. Sign up at [render.com](https://render.com) and connect your GitHub account. Give Render access
   to the `ticket-reservation` repository.
2. Click **New → Web Service** and select the repository.
3. Fill in the settings:

   | Field | Value |
   |---|---|
   | Branch | `main` |
   | Language / Runtime | **Docker** (detected from `Dockerfile`) |
   | Region | nearest to your Aiven region |
   | Instance type | **Free** (512 MB) |
   | Dockerfile path | `./Dockerfile` |
   | Health check path (Advanced) | **`/health/ready`** |
   | Auto-deploy | **On** |

4. Add the **environment variables**:

   | Key | Value |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `DATABASE_URL` | from step 2 |
   | `DATABASE_USERNAME` | `avnadmin` |
   | `DATABASE_PASSWORD` | the Aiven password |
   | `JWT_SECRET` | from step 3 |
   | `ADMIN_SECRET` | from step 3 |
   | `DB_POOL_MAX_SIZE` | `5` (raise only if Aiven's connection limit allows; the readiness check uses one more) |
   | `ADMISSION_MAX_IN_FLIGHT` | optional, default `200` |

   **Do not set `PORT`**, because Render provides it and the app reads it.

5. Click **Create Web Service**.

### Step 5: watch the first deploy

Render goes through these stages:

1. **Build:** Render clones the repository and runs the Dockerfile. The Maven output appears in
   the build logs. The first build is the slowest.
2. **Start:** the container starts with your variables. The app connects to Aiven, and Flyway
   creates `shows`, `seats`, `reservations` and `user_show_counts` in `defaultdb`.
3. **Health check:** Render polls `/health/ready`. The deploy goes live only after it returns 200.
4. **Live:** the service is served at `https://<your-service>.onrender.com`.

If a secret is missing or the database is unreachable, the deploy fails and the previous version
keeps serving. The cause appears in the **Logs** tab.

### Step 6: verify the deployment

```sh
BASE=https://<your-service>.onrender.com
curl -s $BASE/health/live      # {"status":"UP"}
curl -s $BASE/health/ready     # {"status":"UP"}  (DB reachable)
curl -s $BASE/metrics | grep reservations_
```

Then work through the following:
- Run the flow from [section 7](#7-try-the-api) with `BASE` and the production `ADMIN_SECRET`.
- Run the Postman collection against the URL ([section 8](#8-run-the-postman-collection)).
- Run the burst test against the URL ([section 9](#9-run-the-burst-test)).
- Open the Render **Logs** tab and check that you see one JSON line per request, with
  `request_id`, `user_id`, `status` and `latency_ms`.
- Put the live URL at the top of this README.

### Step 7: day-to-day operations

| Task | How |
|---|---|
| Deploy a change | `git push` to `main`; Render rebuilds and redeploys automatically |
| Roll back | Render **Events** tab, then redeploy an earlier deploy |
| Change config or rotate a secret | edit the environment variables in the Render dashboard; the service restarts |
| Wake before a review | `curl $BASE/health/ready` a minute or two beforehand |
| Check the database | Aiven console, or the `mysql` command from step 1 |

### Free-tier limits to keep in mind

- **Render Free** sleeps after a period of inactivity, so it cold-starts on the next request. It
  has 512 MB of memory; the JVM heap is set to 75% of that by the Dockerfile.
- **Aiven Free MySQL** is small, allows a limited number of connections, and can be powered off
  if unused for a long time. Check that it is **Running** before a review.
- **Logs are not public on Render.** For reviewers, record the Logs tab during a load burst and
  link the recording.
- Free-tier terms change, so confirm the current limits on both providers' pricing pages.

## 11. Configuration reference

All settings are environment variables. In the `prod` profile the secrets and database settings
**have no defaults**: if one is missing, the app refuses to start.

| Variable | Default (local) | Prod | Purpose |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | none | `prod` | `prod` = env-only config, JSON logs, `$PORT`; `json-logs` = JSON logs only |
| `PORT` | `8777` | set by Render | HTTP port (prod profile) |
| `DATABASE_URL` | local MySQL on 3306 | **required** | JDBC URL |
| `DATABASE_USERNAME` | `root` | **required** | DB user |
| `DATABASE_PASSWORD` | `root` | **required** | DB password |
| `DB_POOL_MIN_IDLE` | `10` | `2` | Hikari minimum idle connections |
| `DB_POOL_MAX_SIZE` | `20` | `10` | Hikari maximum pool size (readiness uses one extra connection of its own) |
| `JWT_SECRET` | dev value | **required** | HS256 signing key, at least 32 bytes |
| `ADMIN_SECRET` | dev value | **required** | value of `X-Admin-Secret` that yields an admin token |
| `ADMISSION_MAX_IN_FLIGHT` | `200` | `200` | concurrent requests before new ones queue (max 5 s), then get 429 |
| `ENV_FILE` | `~/.config/ticket-reservation/ticket-reservation.env` | not used | path of the optional local env file |

## 12. Observability

**Request ids.** Every response carries an `X-Request-Id` header, and the same id appears in
error bodies and log lines. Send your own `X-Request-Id` header to trace a request end to end.

**Logs.** In `prod` and `json-logs`, the app writes one JSON line per request and per reservation
decision, with the fields `request_id`, `user_id`, `show_id`, `seats`, `outcome`, `reason` and
`latency_ms`. Tokens and secrets are never logged.

**Metrics** (`GET /metrics`, Prometheus format):

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total{show_id}` | committed reservations |
| `reservations_declined_total{reason}` | declines by reason (`seat_taken`, `per_user_limit`, …) |
| `reservations_cancelled_total{show_id}` | cancellations |
| `seats_available` / `seats_held` / `seats_confirmed{show_id}` | read from the DB at scrape time |
| `http_server_requests_seconds` | latency histogram (p50/p95/p99) |
| `hikaricp_connections_pending` | requests waiting for a DB connection |

**Health.** `/health/live` checks only that the process is running. `/health/ready` runs
`SELECT 1` with a hard 2 s limit and returns `503 not_ready` when the database is down. It uses
its own one-connection pool, so a burst that saturates the main pool does not make it fail. Render
uses `/health/ready` as the deploy gate.

## 13. Error codes

Every error body has this shape:
`{"error":"<code>","message":"<human readable>","request_id":"<id>"}`.

| Status | `error` | When |
|---|---|---|
| 400 | `invalid_request` | malformed JSON, validation failure, float money |
| 400 | `invalid_seat` | a seat label that does not exist on the show |
| 401 | `unauthorized` | missing, expired or invalid token |
| 403 | `forbidden` | a user token on an admin endpoint |
| 404 | `not_found`, `show_not_found`, `reservation_not_found` | unknown path or id |
| 405 | `method_not_allowed` | wrong HTTP method |
| 409 | `seat_taken` | at least one requested seat is no longer available |
| 409 | `per_user_limit` | the request would exceed the show's per-user limit |
| 409 | `idempotency_key_conflict` | the key was reused with a different request |
| 429 | `overloaded` | load shedding or lock contention; retry with the same key |
| 500 | `internal_error` | unexpected failure (never used for domain outcomes) |
| 503 | `not_ready` | readiness check failed (DB unreachable) |

## 14. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| Tests fail with "Could not find a valid Docker environment" | Docker isn't running. Start Docker; Testcontainers needs it. |
| App exits at startup with a placeholder or "JWT secret" error | In `prod`, a required variable is missing or `JWT_SECRET` is under 32 bytes. |
| `/health/ready` returns 503, `/health/live` 200 | The database is unreachable. Check `DATABASE_URL`, the credentials, that Aiven is Running, and `sslMode=REQUIRED`. |
| `Access denied for user` in compose after changing passwords | The volume kept the old password. Run `docker compose down -v` and start again. |
| Port 8777 or 3307 already in use | Stop the other process, or change the host port in `docker-compose.yml`. |
| Render deploy stuck on "health check" | Check the Logs tab. Usually it's the DB connection, or a wrong `ssl-mode` (it must be `sslMode`) in the URL. |
| First request on Render is very slow | The free instance was asleep. Wait for it to wake, then retry. |
| Many `429 overloaded` during a burst | This is expected load shedding. Tune `DB_POOL_MAX_SIZE` within Aiven's connection limit, and `ADMISSION_MAX_IN_FLIGHT`. |
| `Too many connections` from Aiven | The pool is larger than the free tier allows. Lower `DB_POOL_MAX_SIZE` (the app uses that many plus one for readiness). |
| `burst.sh` says it needs python3 venv or Docker | Install `python3-venv` (e.g. `apt install python3-venv`) or start Docker. |
