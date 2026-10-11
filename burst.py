#!/usr/bin/env python3
"""Burst test for ts-ticketing-system (BRD section 14, "Burst script (live URL)").

Storms a running service and checks the three guarantees against what the API and /metrics
report. Run it through ./burst.sh, which sets up httpx:

    ADMIN_SECRET=... ./burst.sh https://ticket-reservation-05jk.onrender.com

Steps:
  1. Wake the service, mint an admin token, create a fresh show (200 seats, limit 4),
     mint ~2,000 user tokens.
  2. Hot-seat storm: 500 users reserve A12 at once -> exactly one 201.
  3. Stampede: ~20,000 requests for 1-2 seats each, weighted toward a few hot seats.
  4. Idempotency: 20 parallel calls with one key -> one reservation; same key, other seats -> 409.
  5. Per-user limit: one user, 10 parallel requests -> at most 4 seats confirmed.
  6. Spoofing: body user_id is ignored; cancelling another user's reservation -> 404.
  7. Reconcile: show invariant, no seat sold twice, nobody over the limit, metrics vs API.

Steps 4-6 use a second small show, so they still have free seats after the stampede.
Transport errors and client timeouts are counted apart from HTTP statuses (BRD R-10): they are
reported, but only real 5xx responses and broken invariants fail the run (exit code 1).
"""

import argparse
import asyncio
import collections
import os
import random
import re
import sys
import time
import uuid

import functools
import socket

import httpx

# Opening ~1,000 connections at once makes ~1,000 DNS lookups, which local resolvers answer
# with "Temporary failure in name resolution". Resolve each host once and reuse the answer.
socket.getaddrinfo = functools.lru_cache(maxsize=None)(socket.getaddrinfo)

HOT_SEATS = ["A1", "A2", "A3", "A12", "B5"]


class Outcomes:
    """Counts what came back: HTTP statuses, error codes, and transport failures."""

    def __init__(self):
        self.status = collections.Counter()
        self.reasons = collections.Counter()
        self.transport = collections.Counter()
        self.latency = collections.defaultdict(list)

    def record(self, resp, elapsed):
        if isinstance(resp, Exception):
            self.transport[type(resp).__name__] += 1
            return
        self.status[resp.status_code] += 1
        self.latency[resp.status_code].append(elapsed)
        if resp.status_code >= 400:
            try:
                self.reasons[resp.json().get("error", "?")] += 1
            except ValueError:
                self.reasons["<non-json>"] += 1

    @property
    def server_errors(self):
        return sum(n for code, n in self.status.items() if code >= 500)

    def total(self):
        return sum(self.status.values()) + sum(self.transport.values())


class Client:
    def __init__(self, base, concurrency, timeout):
        self.base = base.rstrip("/")
        self.gate = asyncio.Semaphore(concurrency)
        self.http = httpx.AsyncClient(
            base_url=self.base,
            timeout=httpx.Timeout(timeout, connect=timeout),
            limits=httpx.Limits(max_connections=concurrency, max_keepalive_connections=concurrency),
            http2=False,
        )

    async def close(self):
        await self.http.aclose()

    async def call(self, method, path, outcomes=None, **kwargs):
        """Returns (response or exception, seconds). Never raises for transport errors."""
        async with self.gate:
            start = time.perf_counter()
            try:
                resp = await self.http.request(method, path, **kwargs)
            except httpx.HTTPError as exc:
                resp = exc
            elapsed = time.perf_counter() - start
        if outcomes is not None:
            outcomes.record(resp, elapsed)
        return resp, elapsed

    async def call_with_retry(self, method, path, attempts=8, **kwargs):
        """For setup calls (tokens, show creation): retry 429 and transport errors."""
        for attempt in range(attempts):
            resp, _ = await self.call(method, path, **kwargs)
            if not isinstance(resp, Exception) and resp.status_code != 429:
                return resp
            await asyncio.sleep(min(2 ** attempt * 0.25, 5) + random.random() * 0.25)
        raise RuntimeError(f"{method} {path} kept failing: {resp!r}")


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def pct(values, p):
    if not values:
        return float("nan")
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))]


def seat_labels(rows, per_row):
    return [f"{chr(ord('A') + r)}{n}" for r in range(rows) for n in range(1, per_row + 1)]


async def wake(client, wait_s):
    deadline = time.monotonic() + wait_s
    while True:
        resp, elapsed = await client.call("GET", "/health/ready")
        if not isinstance(resp, Exception) and resp.status_code == 200:
            print(f"  ready ({elapsed * 1000:.0f} ms)")
            return
        if time.monotonic() > deadline:
            raise RuntimeError(f"service not ready after {wait_s}s: {resp!r}")
        print("  not ready yet (free instances sleep when idle), retrying...")
        await asyncio.sleep(5)


async def mint(client, user_id, admin_secret=None):
    headers = {"X-Admin-Secret": admin_secret} if admin_secret else {}
    resp = await client.call_with_retry("POST", "/auth/token", json={"user_id": user_id}, headers=headers)
    resp.raise_for_status()
    return resp.json()


async def create_show(client, admin_token, name, seats, limit, price):
    resp = await client.call_with_retry(
        "POST", "/shows", headers=auth(admin_token),
        json={"name": name, "seats": seats, "price_paise": price, "per_user_limit": limit})
    if resp.status_code != 201:
        raise RuntimeError(f"create show failed: {resp.status_code} {resp.text}")
    return resp.json()


async def get_show(client, token, show_id):
    resp = await client.call_with_retry("GET", f"/shows/{show_id}", headers=auth(token))
    resp.raise_for_status()
    return resp.json()


async def scrape(client):
    resp = await client.call_with_retry("GET", "/metrics")
    resp.raise_for_status()
    metrics = {}
    for line in resp.text.splitlines():
        if line.startswith("#"):
            continue
        # Greedy label match: label values can contain braces (uri="/shows/{showId}/reserve").
        m = re.match(r"^([a-zA-Z_:][\w:]*)(\{.*\})?\s+(\S+)$", line)
        if m:
            metrics[(m.group(1), m.group(2) or "")] = float(m.group(3))
    return metrics


def metric(metrics, name, **labels):
    """Sum of every series of `name` whose labels include all of `labels`."""
    total = 0.0
    for (n, lbl), value in metrics.items():
        if n == name and all(f'{k}="{v}"' in lbl for k, v in labels.items()):
            total += value
    return total


async def reserve(client, outcomes, token, show_id, seats, key, extra_body=None):
    body = {"seats": seats, **(extra_body or {})}
    resp, _ = await client.call("POST", f"/shows/{show_id}/reserve", outcomes,
                                headers={**auth(token), "Idempotency-Key": key}, json=body)
    return resp


class Checks:
    def __init__(self):
        self.rows = []

    def check(self, name, ok, detail=""):
        self.rows.append((name, bool(ok), detail))
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f": {detail}" if detail else ""))

    @property
    def failed(self):
        return [r for r in self.rows if not r[1]]


def confirmed_from(responses):
    """(user_id, seats, reservation_id) for every 201 in a list of (user_id, response)."""
    out = []
    for user_id, resp in responses:
        if not isinstance(resp, Exception) and resp.status_code == 201:
            body = resp.json()
            out.append((body["user_id"], body["seats"], body["reservation_id"]))
    return out


async def main():
    ap = argparse.ArgumentParser(description="Burst-test a ts-ticketing-system deployment.")
    ap.add_argument("base_url")
    ap.add_argument("--requests", type=int, default=20_000, help="stampede size (default 20000)")
    ap.add_argument("--users", type=int, default=2_000, help="user tokens to mint (default 2000)")
    ap.add_argument("--storm", type=int, default=500, help="users storming the hot seat (default 500)")
    ap.add_argument("--concurrency", type=int, default=1_000, help="client-side in-flight cap (default 1000)")
    ap.add_argument("--timeout", type=float, default=60.0, help="per-request timeout in seconds (default 60)")
    ap.add_argument("--wake-timeout", type=int, default=180, help="seconds to wait for a sleeping service")
    args = ap.parse_args()

    admin_secret = os.environ.get("ADMIN_SECRET")
    if not admin_secret:
        sys.exit("ADMIN_SECRET must be set in the environment (it is never passed on the command line).")

    run = uuid.uuid4().hex[:8]
    client = Client(args.base_url, args.concurrency, args.timeout)
    checks = Checks()
    started = time.monotonic()
    try:
        print(f"== Burst run {run} against {client.base}")

        # --- 1. setup -----------------------------------------------------------------------
        print("\n1. Setup")
        await wake(client, args.wake_timeout)
        admin = await mint(client, f"burst-admin-{run}", admin_secret)
        if admin["role"] != "admin":
            sys.exit("ADMIN_SECRET was not accepted: /auth/token returned a user token.")
        labels = seat_labels(10, 20)  # A1..J20, 200 seats, includes the hot seat A12
        show = await create_show(client, admin["token"], f"burst-{run}", labels, 4, 25_000)
        show_id = show["id"]
        print(f"  show {show_id}: {show['total_seats']} seats, limit {show['per_user_limit']}")

        t0 = time.perf_counter()
        user_ids = [f"burst-{run}-u{i}" for i in range(args.users)]
        tokens = dict(zip(user_ids, [t["token"] for t in await asyncio.gather(
            *(mint(client, uid) for uid in user_ids))]))
        print(f"  minted {len(tokens)} user tokens in {time.perf_counter() - t0:.1f}s")
        before = await scrape(client)

        # --- 2. hot-seat storm --------------------------------------------------------------
        print(f"\n2. Hot-seat storm: {args.storm} users reserve A12 at once")
        storm = Outcomes()
        storm_users = user_ids[:args.storm]
        storm_resps = await asyncio.gather(*(
            reserve(client, storm, tokens[u], show_id, ["A12"], f"storm-{run}-{u}") for u in storm_users))
        storm_wins = confirmed_from(zip(storm_users, storm_resps))
        print(f"  statuses {dict(storm.status)}  reasons {dict(storm.reasons)}  transport {dict(storm.transport)}")
        checks.check("hot seat A12 has exactly one winner", len(storm_wins) == 1, f"{len(storm_wins)} x 201")
        checks.check("no 5xx in the storm", storm.server_errors == 0, f"{storm.server_errors} x 5xx")

        # --- 3. stampede --------------------------------------------------------------------
        print(f"\n3. Stampede: {args.requests} requests for 1-2 seats, weighted toward {HOT_SEATS}")
        stampede = Outcomes()
        rng = random.Random(run)
        plan = []
        for i in range(args.requests):
            user = rng.choice(user_ids)
            n = rng.choice((1, 2))
            pool = HOT_SEATS if rng.random() < 0.5 else labels
            plan.append((user, sorted(rng.sample(pool, n)), f"stampede-{run}-{i}"))
        t0 = time.perf_counter()
        stampede_resps = await asyncio.gather(*(
            reserve(client, stampede, tokens[u], show_id, seats, key) for u, seats, key in plan))
        wall = time.perf_counter() - t0
        stampede_wins = confirmed_from((u, r) for (u, _, _), r in zip(plan, stampede_resps))
        print(f"  {stampede.total()} requests in {wall:.1f}s ({stampede.total() / wall:.0f} req/s)")
        checks.check("no 5xx in the stampede", stampede.server_errors == 0, f"{stampede.server_errors} x 5xx")

        # --- 4-6. functional checks on a second show ----------------------------------------
        side = await create_show(client, admin["token"], f"burst-{run}-checks", seat_labels(2, 10), 4, 25_000)
        side_id = side["id"]
        misc = Outcomes()

        print("\n4. Idempotency: 20 parallel calls with one key, then the same key with other seats")
        idem_user = await mint(client, f"burst-{run}-idem")
        key = f"idem-{run}"
        idem = await asyncio.gather(*(
            reserve(client, misc, idem_user["token"], side_id, ["A1", "A2"], key) for _ in range(20)))
        ok = [r for r in idem if not isinstance(r, Exception) and r.status_code in (200, 201)]
        ids = {r.json()["reservation_id"] for r in ok}
        created = sum(1 for r in ok if r.status_code == 201)
        replays = sum(1 for r in ok if r.headers.get("Idempotent-Replay") == "true")
        checks.check("one reservation for 20 same-key calls", len(ids) == 1 and created == 1,
                     f"{created} x 201, {replays} replays, {len(ids)} distinct ids, "
                     f"{20 - len(ok)} shed or failed")
        conflict = await reserve(client, misc, idem_user["token"], side_id, ["A3"], key)
        checks.check("same key, different seats -> 409 idempotency_key_conflict",
                     not isinstance(conflict, Exception) and conflict.status_code == 409
                     and conflict.json().get("error") == "idempotency_key_conflict",
                     getattr(conflict, "status_code", repr(conflict)))

        print("\n5. Per-user limit: one user, 10 parallel single-seat requests (limit 4)")
        limit_user = await mint(client, f"burst-{run}-limit")
        limit_seats = [f"B{n}" for n in range(1, 11)]
        lim = await asyncio.gather(*(
            reserve(client, misc, limit_user["token"], side_id, [s], f"limit-{run}-{s}") for s in limit_seats))
        lim_wins = sum(1 for r in lim if not isinstance(r, Exception) and r.status_code == 201)
        lim_reasons = collections.Counter(
            r.json().get("error") for r in lim if not isinstance(r, Exception) and r.status_code >= 400)
        checks.check("at most 4 confirmed for one user", lim_wins <= 4, f"{lim_wins} x 201, declines {dict(lim_reasons)}")

        print("\n6. Spoofing: body user_id is ignored; cross-user cancel is 404")
        attacker = await mint(client, f"burst-{run}-attacker")
        victim = await mint(client, f"burst-{run}-victim")
        spoof = await reserve(client, misc, attacker["token"], side_id, ["A9"], f"spoof-{run}",
                              {"user_id": victim["user_id"]})
        spoof_owner = spoof.json().get("user_id") if not isinstance(spoof, Exception) and spoof.status_code == 201 else None
        checks.check("reservation belongs to the token user, not the body user_id",
                     spoof_owner == attacker["user_id"], f"owner={spoof_owner}")
        victim_res = await reserve(client, misc, victim["token"], side_id, ["A10"], f"victim-{run}")
        if not isinstance(victim_res, Exception) and victim_res.status_code == 201:
            cancel, _ = await client.call(
                "POST", f"/reservations/{victim_res.json()['reservation_id']}/cancel", misc,
                headers=auth(attacker["token"]))
            checks.check("cancelling another user's reservation -> 404",
                         not isinstance(cancel, Exception) and cancel.status_code == 404,
                         getattr(cancel, "status_code", repr(cancel)))
        else:
            checks.check("cancelling another user's reservation -> 404", False,
                         f"setup reserve failed: {getattr(victim_res, 'status_code', victim_res)!r}")
        checks.check("no 5xx in the functional checks", misc.server_errors == 0, f"{misc.server_errors} x 5xx")

        # --- 7. reconcile -------------------------------------------------------------------
        print("\n7. Reconcile")
        await asyncio.sleep(1.5)  # seat gauges are cached for 1 s
        final = await get_show(client, admin["token"], show_id)
        after = await scrape(client)
        c = final["counts"]
        wins = storm_wins + stampede_wins
        sold = [s for _, seats, _ in wins for s in seats]
        # A request that timed out on the client may still have committed on the server, so its
        # seats and reservation can show up in the API and metrics without a 201 here.
        unknown = [seats for seats, r in
                   [(["A12"], r) for r in storm_resps] + [(seats, r) for (_, seats, _), r in zip(plan, stampede_resps)]
                   if isinstance(r, Exception)]
        unknown_seats = sum(len(x) for x in unknown)
        per_user = collections.Counter()
        for user, seats, _ in wins:
            per_user[user] += len(seats)

        checks.check("invariant_ok and available + held + confirmed == total_seats",
                     final["invariant_ok"] and c["available"] + c["held"] + c["confirmed"] == final["total_seats"],
                     f"{c} total={final['total_seats']}")
        checks.check("no seat confirmed to two users", len(sold) == len(set(sold)),
                     f"{len(sold)} seats in 201s, {len(set(sold))} distinct")
        checks.check("confirmed seats in API match the 201 responses",
                     len(sold) <= c["confirmed"] <= len(sold) + unknown_seats,
                     f"api={c['confirmed']} responses={len(sold)} (+ up to {unknown_seats} from timed-out requests)")
        over = {u: n for u, n in per_user.items() if n > 4}
        checks.check("no user above the per-user limit", not over, f"max held={max(per_user.values(), default=0)}")
        m_confirmed = metric(after, "reservations_confirmed_total", show_id=show_id)
        checks.check("reservations_confirmed_total matches the 201 responses",
                     len(wins) <= m_confirmed <= len(wins) + len(unknown),
                     f"metric={m_confirmed:.0f} responses={len(wins)} (+ up to {len(unknown)} timed out)")
        checks.check("seats_confirmed gauge == API confirmed count",
                     metric(after, "seats_confirmed", show_id=show_id) == c["confirmed"],
                     f"gauge={metric(after, 'seats_confirmed', show_id=show_id):.0f}")
        main_reasons = storm.reasons + stampede.reasons
        for reason in ("seat_taken", "per_user_limit"):
            delta = (metric(after, "reservations_declined_total", reason=reason)
                     - metric(before, "reservations_declined_total", reason=reason))
            seen = main_reasons[reason] + misc.reasons[reason]
            # Counters are global; another client hitting the service at the same time can only add.
            checks.check(f"reservations_declined_total{{reason={reason}}} delta >= declines seen",
                         delta >= seen, f"delta={delta:.0f} seen={seen}")
        def server_5xx(metrics, health):
            return sum(v for (n, lbl), v in metrics.items()
                       if n == "http_server_requests_seconds_count" and 'status="5' in lbl
                       and ('uri="/health' in lbl) == health)
        api_5xx = server_5xx(after, False) - server_5xx(before, False)
        checks.check("server-side 5xx on API endpoints did not grow", api_5xx == 0, f"delta={api_5xx:.0f}")
        health_503 = server_5xx(after, True) - server_5xx(before, True)
        print(f"  (info) /health/ready 503s during the run: {health_503:.0f}")

        # --- outcome table ------------------------------------------------------------------
        everything = Outcomes()
        for o in (storm, stampede, misc):
            everything.status.update(o.status)
            everything.reasons.update(o.reasons)
            everything.transport.update(o.transport)
            for k, v in o.latency.items():
                everything.latency[k].extend(v)
        print("\n== Outcome table (storm + stampede + functional checks)")
        rows = [
            ("reserve requests sent", everything.total()),
            ("confirmed (201)", everything.status[201]),
            ("idempotent replays (200)", everything.status[200]),
            ("declined: seat_taken (409)", everything.reasons["seat_taken"]),
            ("declined: per_user_limit (409)", everything.reasons["per_user_limit"]),
            ("declined: idempotency_key_conflict (409)", everything.reasons["idempotency_key_conflict"]),
            ("shed: overloaded (429)", everything.status[429]),
            ("other 4xx", sum(n for s, n in everything.status.items() if 400 <= s < 500 and s not in (409, 429))),
            ("server errors (5xx)", everything.server_errors),
            ("client timeouts / transport errors", sum(everything.transport.values())),
            ("hot-seat A12 winners", len(storm_wins)),
            ("seats sold (main show)", f"{c['confirmed']} / {final['total_seats']}"),
            ("reconciliation", "OK" if final["invariant_ok"] else "BROKEN"),
        ]
        width = max(len(r[0]) for r in rows)
        for name, value in rows:
            print(f"  {name:<{width}}  {value}")
        if everything.transport:
            print(f"  transport detail: {dict(everything.transport)}")
        print("\n  latency (s)      p50     p95     p99     max")
        for code in sorted(everything.latency):
            v = everything.latency[code]
            print(f"  {code:<12} {pct(v, 50):7.3f} {pct(v, 95):7.3f} {pct(v, 99):7.3f} {max(v):7.3f}   (n={len(v)})")
        print(f"\n  total run time {time.monotonic() - started:.0f}s; main show {show_id}")
    finally:
        await client.close()

    if checks.failed:
        print(f"\nFAILED: {len(checks.failed)} check(s)")
        for name, _, detail in checks.failed:
            print(f"  - {name}: {detail}")
        return 1
    print(f"\nALL {len(checks.rows)} CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
