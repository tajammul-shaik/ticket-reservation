#!/usr/bin/env bash
# One-command burst test (BRD section 14): storms a running ts-ticketing-system and checks the
# three guarantees against what the API and /metrics report.
#
#   ADMIN_SECRET=<admin secret> ./burst.sh <BASE_URL> [options]
#   ADMIN_SECRET=compose-local-admin-secret ./burst.sh http://localhost:8777
#   ./burst.sh --help
#
# Steps:
#   1. Wake the service, mint an admin token, create a fresh show (200 seats, limit 4), mint
#      user tokens.
#   2. Hot-seat storm: many users reserve A12 at once -> exactly one 201.
#   3. Stampede: ~20,000 requests for 1-2 seats each, half aimed at five hot seats.
#   4. Idempotency: 20 parallel calls with one key -> one reservation; same key, other seats -> 409.
#   5. Per-user limit: one user, 10 parallel requests -> at most 4 seats confirmed.
#   6. Spoofing: body user_id is ignored; cancelling another user's reservation -> 404.
#   7. Reconcile: show invariant, no seat sold twice, nobody over the limit, metrics vs API.
#
# Needs only bash, curl (7.84+), jq and a POSIX awk. Each batch of requests runs through one
# `curl --parallel` process fed from a generated config file, so the admin secret and tokens never
# appear on a command line. Transport errors and client timeouts are counted apart from HTTP
# statuses (BRD R-10), and so are gateway errors from the hosting proxy (a 502/503/504 without the
# app's JSON body; the app never returns 502 or 504). Only 5xx responses from the app, server-side
# 5xx in /metrics, a restart during the run, and broken invariants fail the run (exit code 1).
set -uo pipefail

HOT_SEATS="A1 A2 A3 A12 B5"
CURL_PARALLEL_CAP=300   # curl's own ceiling for --parallel-max; more in flight = more processes

usage() {
  cat <<'EOF'
Usage: ADMIN_SECRET=<secret> ./burst.sh <BASE_URL> [options]

Options:
  --requests N       stampede size (default 20000)
  --users N          user tokens to mint (default 2000)
  --storm N          users storming the hot seat A12 (default 500)
  --concurrency N    requests in flight from this client (default 1000; use 200 on Render)
  --timeout S        per-request timeout in seconds (default 60)
  --wake-timeout S   seconds to wait for a sleeping service (default 180)
  --keep             keep the working directory with every response body
  -h, --help         show this help

Needs bash, curl 7.84+ and jq. Exit code 0 when every check passes, 1 when any fails,
2 on a usage or setup error.
EOF
}

die() { echo "burst.sh: $*" >&2; exit 2; }

# ---- arguments ------------------------------------------------------------------------------

BASE="" REQUESTS=20000 USERS=2000 STORM=500 CONCURRENCY=1000 TIMEOUT=60 WAKE_TIMEOUT=180 KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --requests) REQUESTS="${2:?}"; shift 2 ;;
    --users) USERS="${2:?}"; shift 2 ;;
    --storm) STORM="${2:?}"; shift 2 ;;
    --concurrency) CONCURRENCY="${2:?}"; shift 2 ;;
    --timeout) TIMEOUT="${2:?}"; shift 2 ;;
    --wake-timeout) WAKE_TIMEOUT="${2:?}"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -*) usage >&2; die "unknown option $1" ;;
    *) [[ -z "$BASE" ]] || die "only one BASE_URL"; BASE="${1%/}"; shift ;;
  esac
done
[[ -n "$BASE" ]] || { usage >&2; exit 2; }
for n in "$REQUESTS" "$USERS" "$STORM" "$CONCURRENCY" "$WAKE_TIMEOUT"; do
  [[ "$n" =~ ^[0-9]+$ && "$n" -gt 0 ]] || die "numeric options must be positive integers"
done
[[ "$TIMEOUT" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "--timeout must be a number of seconds"
(( STORM <= USERS )) || die "--storm cannot exceed --users"
[[ -n "${ADMIN_SECRET:-}" ]] || die "ADMIN_SECRET must be set in the environment (never pass it on the command line)"
[[ "$ADMIN_SECRET" != *'"'* && "$ADMIN_SECRET" != *'\'* ]] || die "ADMIN_SECRET must not contain quotes or backslashes"

command -v curl >/dev/null || die "curl is required"
command -v jq >/dev/null || die "jq is required (e.g. apt install jq, brew install jq)"
curl --help all 2>/dev/null | grep -q -- '--parallel-immediate' \
  || die "curl 7.84 or newer is required (found: $(curl --version | head -1))"

# Many sockets at once: raise the open-file limit if the shell allows it.
ulimit -n 65536 2>/dev/null || ulimit -n "$(ulimit -Hn)" 2>/dev/null || true

umask 077   # the working directory holds tokens and the admin secret
WORK="$(mktemp -d "${TMPDIR:-/tmp}/burst.XXXXXX")" || die "cannot create a working directory"
cleanup() { if (( KEEP )); then echo "  (working directory kept: $WORK)"; else rm -rf "$WORK"; fi; }
trap cleanup EXIT

RUN="$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
STARTED=$SECONDS
CHECKS_TOTAL=0
FAILED=()

check() {  # check <name> <1 = pass> <detail>
  CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
  if [[ "$2" == 1 ]]; then echo "  [PASS] $1${3:+: $3}"
  else echo "  [FAIL] $1${3:+: $3}"; FAILED+=("$1: $3"); fi
}

now() { echo "${EPOCHREALTIME:-$(date +%s)}"; }   # bash 5 has sub-second time
elapsed() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%.1f", b - a }'; }

# ---- request batches ------------------------------------------------------------------------
#
# A batch is described as TSV lines:  id  method  path  token|-  idempotency-key|-  json-body|-
# gen_cfg turns them into a curl config: one block per request, separated by "next". Each block
# saves its body to <dir>/<id>.json and prints one result line:
#   id  http_code  seconds  content_type  curl_exit  Idempotent-Replay header
# JSON bodies here never contain spaces, so they go into the config unquoted.

gen_cfg() {  # gen_cfg <body dir> < requests.tsv > batch.cfg
  awk -F'\t' -v base="$BASE" -v dir="$1" -v t="$TIMEOUT" '
    NR > 1 { print "next" }
    {
      print "url = \"" base $3 "\""
      print "request = \"" $2 "\""
      print "max-time = " t
      print "connect-timeout = " t
      print "output = \"" dir "/" $1 ".json\""
      if ($4 != "-") print "header = \"Authorization: Bearer " $4 "\""
      if ($5 != "-") print "header = \"Idempotency-Key: " $5 "\""
      if ($6 != "-") { print "header = \"Content-Type: application/json\""; print "data = " $6 }
      print "write-out = \"" $1 "\\t%{http_code}\\t%{time_total}\\t%{content_type}\\t%{exitcode}\\t%header{idempotent-replay}\\n\""
    }'
}

# run_batch <name> < requests.tsv: runs the batch with up to $CONCURRENCY requests in flight
# (split across several curl processes above curl's per-process ceiling), then writes
#   $WORK/<name>.tsv   id  status  seconds  unknown(0/1)  replay  error-code
#   $WORK/<name>.wins  user_id  seats(comma-separated)  reservation_id   (one line per 201)
# Bodies are kept in $WORK/<name>/.
run_batch() {
  local name="$1" dir="$WORK/$1" procs per i blocks
  mkdir -p "$dir"
  cat > "$WORK/$name.req"
  blocks=$(wc -l < "$WORK/$name.req")
  procs=$(( (CONCURRENCY + CURL_PARALLEL_CAP - 1) / CURL_PARALLEL_CAP ))
  (( procs > blocks )) && procs=$blocks
  (( procs < 1 )) && procs=1
  per=$(( (CONCURRENCY + procs - 1) / procs ))
  for ((i = 0; i < procs; i++)); do
    awk -v n="$procs" -v k="$i" '(NR - 1) % n == k' "$WORK/$name.req" | gen_cfg "$dir" > "$WORK/$name.$i.cfg"
  done
  for ((i = 0; i < procs; i++)); do
    curl -s --parallel --parallel-immediate --parallel-max "$per" -K "$WORK/$name.$i.cfg" \
      > "$WORK/$name.$i.out" 2>/dev/null &
  done
  wait
  cat "$WORK/$name".*.out > "$WORK/$name.out"

  # Error codes and winners, read with one jq call per few thousand bodies, not one per body.
  awk -F'\t' -v d="$dir" '$2 >= 400 && $4 ~ /application\/json/ { print d "/" $1 ".json" }' "$WORK/$name.out" \
    | xargs -r jq -r '[(input_filename | sub(".*/"; "") | sub("\\.json$"; "")), (.error // "?")] | @tsv' \
    > "$WORK/$name.errors" 2>/dev/null
  awk -F'\t' -v d="$dir" '$2 == 201 { print d "/" $1 ".json" }' "$WORK/$name.out" \
    | xargs -r jq -r '[.user_id, (.seats | join(",")), .reservation_id] | @tsv' \
    > "$WORK/$name.wins" 2>/dev/null
  # FILENAME, not NR == FNR: the errors file is often empty, and then NR == FNR would be true
  # for the results file instead.
  awk -F'\t' -v OFS='\t' -v errors="$WORK/$name.errors" '
    FILENAME == errors { err[$1] = $2; next }
    {
      unknown = ($2 == "000") || ($2 ~ /^50[234]$/ && $4 !~ /application\/json/)
      print $1, $2, $3, unknown + 0, $6, (($1 in err) ? err[$1] : "")
    }' "$WORK/$name.errors" "$WORK/$name.out" > "$WORK/$name.tsv"
}

# Counts lines of result TSVs matching an awk condition on $2 status, $4 unknown, $5 replay,
# $6 error code.
count() {
  local cond="$1"; shift
  awk -F'\t' "$cond { n++ } END { print n + 0 }" "$@"
}
app_5xx() { count '$4 == 0 && $2 >= 500' "$@"; }
status_of() { awk -F'\t' -v id="$2" '$1 == id { s = $2 } END { print (s == "" ? "000" : s) }' "$WORK/$1.tsv"; }
error_of() { awk -F'\t' -v id="$2" '$1 == id { print $6 }' "$WORK/$1.tsv"; }

distribution() {  # status / reason / transport / proxy counts of result TSVs
  awk -F'\t' '
    $4 == 1 { if ($2 == "000") t++; else p[$2]++; next }
    { s[$2]++; if ($6 != "") r[$6]++ }
    END {
      printf "  statuses {"; c = ""; for (k in s) { printf "%s%s: %d", c, k, s[k]; c = ", " }
      printf "}  reasons {"; c = ""; for (k in r) { printf "%s%s: %d", c, k, r[k]; c = ", " }
      printf "}  transport %d  proxy {", t; c = ""; for (k in p) { printf "%s%s: %d", c, k, p[k]; c = ", " }
      print "}"
    }' "$@"
}

# ---- single calls (setup and reconcile), retried on 429, proxy and transport errors --------
#
# call <id> <method> <path> <token|-> <json body|-> [admin secret]: sets STATUS and BODY (a file).
# Runs in the current shell (never inside $(...)), so a setup failure stops the whole script.
call() {
  local id="$1" method="$2" path="$3" token="$4" body="$5" admin="${6:-}" attempt line ctype cfg
  mkdir -p "$WORK/single"
  cfg="$WORK/single/$id.cfg"
  BODY="$WORK/single/$id.json"
  for ((attempt = 0; attempt < 8; attempt++)); do
    printf '%s\t%s\t%s\t%s\t-\t%s\n' "$id" "$method" "$path" "$token" "$body" | gen_cfg "$WORK/single" > "$cfg"
    [[ -n "$admin" ]] && printf 'header = "X-Admin-Secret: %s"\n' "$admin" >> "$cfg"
    line="$(curl -s -K "$cfg" 2>/dev/null)"
    STATUS="$(cut -f2 <<< "$line")"; ctype="$(cut -f4 <<< "$line")"
    if [[ "$STATUS" != 000 && "$STATUS" != 429 ]] \
       && ! [[ "$STATUS" =~ ^50[234]$ && "$ctype" != *application/json* ]]; then
      return 0
    fi
    sleep "$(awk -v a="$attempt" 'BEGIN { srand(); d = 0.25 * 2 ^ a; if (d > 5) d = 5; print d + rand() * 0.25 }')"
  done
  die "$method $path kept failing (last status $STATUS)"
}

mint() {  # mint <id> <user_id> [admin secret]: sets TOKEN and ROLE
  call "$1" POST /auth/token - "{\"user_id\":\"$2\"}" "${3:-}"
  [[ "$STATUS" == 200 ]] || die "token for $2 failed: $STATUS $(cat "$BODY")"
  TOKEN="$(jq -r .token "$BODY")"; ROLE="$(jq -r .role "$BODY")"
}

create_show() {  # create_show <id> <admin token> <name> <seat rows> <seats per row>: sets SHOW_JSON
  call "$1" POST /shows "$2" "{\"name\":\"$3\",\"seats\":$(seat_labels "$4" "$5"),\"price_paise\":25000,\"per_user_limit\":4}"
  [[ "$STATUS" == 201 ]] || die "create show failed: $STATUS $(cat "$BODY")"
  SHOW_JSON="$BODY"
}

seat_labels() {  # seat_labels <rows> <per row>: JSON array A1, A2, ... (no spaces)
  awk -v r="$1" -v p="$2" 'BEGIN {
    printf "["; c = ""
    for (i = 0; i < r; i++) for (n = 1; n <= p; n++) { printf "%s\"%s%d\"", c, substr("ABCDEFGHIJKLMNOPQRSTUVWXYZ", i + 1, 1), n; c = "," }
    print "]" }'
}

scrape() {  # scrape <file>: saves /metrics
  call "metrics-${1##*/}" GET /metrics - -
  [[ "$STATUS" == 200 ]] || die "/metrics returned $STATUS"
  cp "$BODY" "$1"
}

# metric <file> <name> [label="value" ...]: sum of every series of <name> carrying those labels.
# Labels run greedily to the last "}", because values contain braces (uri="/shows/{showId}/reserve").
metric() {
  local file="$1" name="$2"; shift 2
  awk -v name="$name" -v want="$*" '
    /^#/ || NF < 2 { next }
    {
      b = index($0, "{"); sp = index($0, " ")
      if (b > 0 && b < sp) { n = substr($0, 1, b - 1); e = match($0, /\}[^}]*$/); lbl = substr($0, b, e - b + 1) }
      else { n = substr($0, 1, sp - 1); lbl = "" }
      if (n != name) next
      k = split(want, w, " "); ok = 1
      for (x = 1; x <= k; x++) if (index(lbl, w[x]) == 0) ok = 0
      if (ok) s += $NF
    }
    END { printf "%.0f", s + 0 }' "$file"
}

server_5xx() {  # server_5xx <file> <1: /health endpoints, 0: everything else>
  awk -v health="$2" '
    /^http_server_requests_seconds_count\{/ && /status="5/ {
      h = (index($0, "uri=\"/health") > 0)
      if (h == health) s += $NF
    }
    END { printf "%.0f", s + 0 }' "$1"
}

# ---- 1. setup -------------------------------------------------------------------------------

echo "== Burst run $RUN against $BASE"
echo
echo "1. Setup"
deadline=$((SECONDS + WAKE_TIMEOUT))
while :; do
  ready="$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 30 "$BASE/health/ready" 2>/dev/null)"
  if [[ "${ready%% *}" == 200 ]]; then
    awk -v t="${ready#* }" 'BEGIN { printf "  ready (%.0f ms)\n", t * 1000 }'
    break
  fi
  (( SECONDS > deadline )) && die "service not ready after ${WAKE_TIMEOUT}s (last: ${ready:-no answer})"
  echo "  not ready yet (free instances sleep when idle), retrying..."
  sleep 5
done

mint admin "burst-admin-$RUN" "$ADMIN_SECRET"
[[ "$ROLE" == admin ]] || die "ADMIN_SECRET was not accepted: /auth/token returned a $ROLE token"
ADMIN_TOKEN="$TOKEN"

create_show show "$ADMIN_TOKEN" "burst-$RUN" 10 20   # A1..J20, 200 seats, includes the hot seat A12
SHOW_ID="$(jq -r .id "$SHOW_JSON")"
echo "  show $SHOW_ID: $(jq -r .total_seats "$SHOW_JSON") seats, limit $(jq -r .per_user_limit "$SHOW_JSON")"

# User tokens in one parallel batch; the ones shed or lost are retried in later rounds.
t0="$(now)"
: > "$WORK/users.tsv"
seq 0 $((USERS - 1)) > "$WORK/pending"
for ((round = 0; round < 8; round++)); do
  [[ -s "$WORK/pending" ]] || break
  (( round > 0 )) && sleep 2
  awk -v run="$RUN" '{ printf "u%s\tPOST\t/auth/token\t-\t-\t{\"user_id\":\"burst-%s-u%s\"}\n", $1, run, $1 }' \
    "$WORK/pending" | run_batch "tokens$round"
  awk -F'\t' -v d="$WORK/tokens$round" '$2 == 200 { print d "/" $1 ".json" }' "$WORK/tokens$round.out" \
    | xargs -r jq -r '[.user_id, .token] | @tsv' >> "$WORK/users.tsv"
  awk -F'\t' '$2 != 200 { sub(/^u/, "", $1); print $1 }' "$WORK/tokens$round.out" > "$WORK/pending"
done
[[ -s "$WORK/pending" ]] && die "could not mint $(wc -l < "$WORK/pending") of $USERS user tokens"
echo "  minted $(wc -l < "$WORK/users.tsv") user tokens in $(elapsed "$t0")s"
scrape "$WORK/before.prom"

# ---- 2. hot-seat storm ----------------------------------------------------------------------

echo
echo "2. Hot-seat storm: $STORM users reserve A12 at once"
head -n "$STORM" "$WORK/users.tsv" \
  | awk -F'\t' -v OFS='\t' -v path="/shows/$SHOW_ID/reserve" -v run="$RUN" \
      '{ print "s" NR, "POST", path, $2, "storm-" run "-" $1, "{\"seats\":[\"A12\"]}" }' \
  | run_batch storm
distribution "$WORK/storm.tsv"
storm_wins=$(wc -l < "$WORK/storm.wins")
check "hot seat A12 has exactly one winner" "$(( storm_wins == 1 ))" "$storm_wins x 201"
n=$(app_5xx "$WORK/storm.tsv")
check "no 5xx in the storm" "$(( n == 0 ))" "$n x 5xx"

# ---- 3. stampede ----------------------------------------------------------------------------

echo
echo "3. Stampede: $REQUESTS requests for 1-2 seats, weighted toward [${HOT_SEATS// /, }]"
# The plan: id, user, seats, key, token. Half the requests pick from the hot seats, half from
# all 200; one or two distinct seats each, from users picked at random.
awk -F'\t' -v n="$REQUESTS" -v run="$RUN" -v hot="$HOT_SEATS" -v seed="$((16#$RUN % 2147483647))" '
  { users[NR - 1] = $1; tokens[NR - 1] = $2 }
  END {
    srand(seed)
    nh = split(hot, H, " ")
    for (r = 0; r < 10; r++) for (c = 1; c <= 20; c++) ALL[++na] = substr("ABCDEFGHIJ", r + 1, 1) c
    for (i = 0; i < n; i++) {
      u = int(rand() * NR); two = (rand() < 0.5); fromhot = (rand() < 0.5)
      size = fromhot ? nh : na
      a = fromhot ? H[int(rand() * size) + 1] : ALL[int(rand() * size) + 1]
      seats = a
      if (two) { do b = fromhot ? H[int(rand() * size) + 1] : ALL[int(rand() * size) + 1]; while (b == a); seats = a "," b }
      printf "t%d\t%s\t%s\tstampede-%s-%d\t%s\n", i, users[u], seats, run, i, tokens[u]
    }
  }' "$WORK/users.tsv" > "$WORK/plan.tsv"
t0="$(now)"
awk -F'\t' -v OFS='\t' -v path="/shows/$SHOW_ID/reserve" '{
    s = $3; gsub(/,/, "\",\"", s)
    print $1, "POST", path, $5, $4, "{\"seats\":[\"" s "\"]}"
  }' "$WORK/plan.tsv" | run_batch stampede
wall="$(elapsed "$t0")"
total=$(wc -l < "$WORK/stampede.tsv")
echo "  $total requests in ${wall}s ($(awk -v n="$total" -v w="$wall" 'BEGIN { printf "%.0f", n / w }') req/s)"
n=$(app_5xx "$WORK/stampede.tsv")
check "no 5xx in the stampede" "$(( n == 0 ))" "$n x 5xx"

# ---- 4-6. functional checks on a second show, which still has free seats ---------------------

create_show side "$ADMIN_TOKEN" "burst-$RUN-checks" 2 10
SIDE_ID="$(jq -r .id "$SHOW_JSON")"
SIDE="/shows/$SIDE_ID/reserve"

echo
echo "4. Idempotency: 20 parallel calls with one key, then the same key with other seats"
mint idem "burst-$RUN-idem"; IDEM_TOKEN="$TOKEN"
for i in $(seq 1 20); do
  printf 'i%s\tPOST\t%s\t%s\tidem-%s\t{"seats":["A1","A2"]}\n' "$i" "$SIDE" "$IDEM_TOKEN" "$RUN"
done | run_batch idem
created=$(count '$2 == 201' "$WORK/idem.tsv")
replays=$(count '$5 == "true"' "$WORK/idem.tsv")
okn=$(count '$2 == 200 || $2 == 201' "$WORK/idem.tsv")
ids=$(awk -F'\t' -v d="$WORK/idem" '$2 == 200 || $2 == 201 { print d "/" $1 ".json" }' "$WORK/idem.tsv" \
  | xargs -r jq -r .reservation_id | sort -u | wc -l)
check "one reservation for 20 same-key calls" "$(( ids == 1 && created == 1 ))" \
  "$created x 201, $replays replays, $ids distinct ids, $((20 - okn)) shed or failed"
printf 'conflict\tPOST\t%s\t%s\tidem-%s\t{"seats":["A3"]}\n' "$SIDE" "$IDEM_TOKEN" "$RUN" | run_batch conflict
st="$(status_of conflict conflict)"
check "same key, different seats -> 409 idempotency_key_conflict" \
  "$([[ "$st" == 409 && "$(error_of conflict conflict)" == idempotency_key_conflict ]] && echo 1 || echo 0)" "$st"

echo
echo "5. Per-user limit: one user, 10 parallel single-seat requests (limit 4)"
mint limit "burst-$RUN-limit"; LIMIT_TOKEN="$TOKEN"
for n in $(seq 1 10); do
  printf 'l%s\tPOST\t%s\t%s\tlimit-%s-B%s\t{"seats":["B%s"]}\n' "$n" "$SIDE" "$LIMIT_TOKEN" "$RUN" "$n" "$n"
done | run_batch limit
lim=$(count '$2 == 201' "$WORK/limit.tsv")
declines="$(awk -F'\t' '$6 != "" { r[$6]++ } END { c = ""; for (k in r) { printf "%s%s: %d", c, k, r[k]; c = ", " } }' "$WORK/limit.tsv")"
check "at most 4 confirmed for one user" "$(( lim <= 4 ))" "$lim x 201, declines {$declines}"

echo
echo "6. Spoofing: body user_id is ignored; cross-user cancel is 404"
ATTACKER="burst-$RUN-attacker"; VICTIM="burst-$RUN-victim"
mint attacker "$ATTACKER"; ATTACKER_TOKEN="$TOKEN"
mint victim "$VICTIM"; VICTIM_TOKEN="$TOKEN"
{
  printf 'spoof\tPOST\t%s\t%s\tspoof-%s\t{"seats":["A9"],"user_id":"%s"}\n' "$SIDE" "$ATTACKER_TOKEN" "$RUN" "$VICTIM"
  printf 'victim\tPOST\t%s\t%s\tvictim-%s\t{"seats":["A10"]}\n' "$SIDE" "$VICTIM_TOKEN" "$RUN"
} | run_batch spoof
owner=""
[[ "$(status_of spoof spoof)" == 201 ]] && owner="$(jq -r .user_id "$WORK/spoof/spoof.json")"
check "reservation belongs to the token user, not the body user_id" \
  "$([[ "$owner" == "$ATTACKER" ]] && echo 1 || echo 0)" "owner=${owner:-none}"
: > "$WORK/cancel.tsv"
if [[ "$(status_of spoof victim)" == 201 ]]; then
  printf 'cancel\tPOST\t/reservations/%s/cancel\t%s\t-\t-\n' \
    "$(jq -r .reservation_id "$WORK/spoof/victim.json")" "$ATTACKER_TOKEN" | run_batch cancel
  st="$(status_of cancel cancel)"
  check "cancelling another user's reservation -> 404" "$(( st == 404 ))" "$st"
else
  check "cancelling another user's reservation -> 404" 0 "setup reserve for the victim failed"
fi
MISC=("$WORK/idem.tsv" "$WORK/conflict.tsv" "$WORK/limit.tsv" "$WORK/spoof.tsv" "$WORK/cancel.tsv")
n=$(app_5xx "${MISC[@]}")
check "no 5xx in the functional checks" "$(( n == 0 ))" "$n x 5xx"

# ---- 7. reconcile ---------------------------------------------------------------------------

echo
echo "7. Reconcile"
sleep 1.5   # seat gauges are cached for 1 s
call final GET "/shows/$SHOW_ID" "$ADMIN_TOKEN" -
[[ "$STATUS" == 200 ]] || die "GET /shows/$SHOW_ID returned $STATUS"
cp "$BODY" "$WORK/final.json"
scrape "$WORK/after.prom"

read -r available held confirmed total_seats invariant < <(jq -r \
  '[.counts.available, .counts.held, .counts.confirmed, .total_seats, .invariant_ok] | @tsv' "$WORK/final.json")
cat "$WORK/storm.wins" "$WORK/stampede.wins" > "$WORK/wins.tsv"
wins=$(wc -l < "$WORK/wins.tsv")
sold=$(cut -f2 "$WORK/wins.tsv" | tr ',' '\n' | grep -c .)
distinct=$(cut -f2 "$WORK/wins.tsv" | tr ',' '\n' | grep . | sort -u | wc -l)
# A request that timed out on the client, or got a proxy error, may still have committed on the
# server, so its seats and reservation can show up in the API and metrics without a 201 here.
unknown_storm=$(count '$4 == 1' "$WORK/storm.tsv")
unknown_stampede=$(count '$4 == 1' "$WORK/stampede.tsv")
unknown=$((unknown_storm + unknown_stampede))
unknown_seats=$(( unknown_storm + $(awk -F'\t' '
  NR == FNR { seats[$1] = $3; next }
  $4 == 1 { n += split(seats[$1], s, ",") } END { print n + 0 }' "$WORK/plan.tsv" "$WORK/stampede.tsv") ))
max_held=$(awk -F'\t' '{ h[$1] += split($2, s, ",") } END { m = 0; for (u in h) if (h[u] > m) m = h[u]; print m }' "$WORK/wins.tsv")

check "invariant_ok and available + held + confirmed == total_seats" \
  "$([[ "$invariant" == true && $((available + held + confirmed)) == "$total_seats" ]] && echo 1 || echo 0)" \
  "available=$available held=$held confirmed=$confirmed total=$total_seats"
check "no seat confirmed to two users" "$(( sold == distinct ))" "$sold seats in 201s, $distinct distinct"
check "confirmed seats in API match the 201 responses" \
  "$(( sold <= confirmed && confirmed <= sold + unknown_seats ))" \
  "api=$confirmed responses=$sold (+ up to $unknown_seats from timed-out requests)"
check "no user above the per-user limit" "$(( max_held <= 4 ))" "max held=$max_held"
m_confirmed=$(metric "$WORK/after.prom" reservations_confirmed_total "show_id=\"$SHOW_ID\"")
check "reservations_confirmed_total matches the 201 responses" \
  "$(( wins <= m_confirmed && m_confirmed <= wins + unknown ))" \
  "metric=$m_confirmed responses=$wins (+ up to $unknown timed out)"
gauge=$(metric "$WORK/after.prom" seats_confirmed "show_id=\"$SHOW_ID\"")
check "seats_confirmed gauge == API confirmed count" "$(( gauge == confirmed ))" "gauge=$gauge"
for reason in seat_taken per_user_limit; do
  delta=$(( $(metric "$WORK/after.prom" reservations_declined_total "reason=\"$reason\"") \
          - $(metric "$WORK/before.prom" reservations_declined_total "reason=\"$reason\"") ))
  seen=$(count "\$6 == \"$reason\"" "$WORK/storm.tsv" "$WORK/stampede.tsv" "${MISC[@]}")
  # Counters are global; another client hitting the service at the same time can only add.
  check "reservations_declined_total{reason=$reason} delta >= declines seen" "$(( delta >= seen ))" \
    "delta=$delta seen=$seen"
done
api_5xx=$(( $(server_5xx "$WORK/after.prom" 0) - $(server_5xx "$WORK/before.prom" 0) ))
check "server-side 5xx on API endpoints did not grow" "$(( api_5xx == 0 ))" "delta=$api_5xx"
up_before=$(metric "$WORK/before.prom" process_uptime_seconds)
up_after=$(metric "$WORK/after.prom" process_uptime_seconds)
check "service did not restart during the run" "$(( up_after > up_before ))" "uptime ${up_before}s -> ${up_after}s"
echo "  (info) /health/ready 503s during the run: $(( $(server_5xx "$WORK/after.prom" 1) - $(server_5xx "$WORK/before.prom" 1) ))"

# ---- outcome table --------------------------------------------------------------------------

ALL=("$WORK/storm.tsv" "$WORK/stampede.tsv" "${MISC[@]}")
row() { printf '  %-45s %s\n' "$1" "$2"; }
echo
echo "== Outcome table (storm + stampede + functional checks)"
row "reserve requests sent" "$(cat "${ALL[@]}" | wc -l)"
row "confirmed (201)" "$(count '$4 == 0 && $2 == 201' "${ALL[@]}")"
row "idempotent replays (200)" "$(count '$4 == 0 && $2 == 200' "${ALL[@]}")"
row "declined: seat_taken (409)" "$(count '$6 == "seat_taken"' "${ALL[@]}")"
row "declined: per_user_limit (409)" "$(count '$6 == "per_user_limit"' "${ALL[@]}")"
row "declined: idempotency_key_conflict (409)" "$(count '$6 == "idempotency_key_conflict"' "${ALL[@]}")"
row "shed: overloaded (429)" "$(count '$4 == 0 && $2 == 429' "${ALL[@]}")"
row "other 4xx" "$(count '$4 == 0 && $2 >= 400 && $2 < 500 && $2 != 409 && $2 != 429' "${ALL[@]}")"
row "server errors (5xx from the app)" "$(app_5xx "${ALL[@]}")"
row "proxy errors (502/503/504, not from the app)" "$(count '$4 == 1 && $2 != "000"' "${ALL[@]}")"
row "client timeouts / transport errors" "$(count '$2 == "000"' "${ALL[@]}")"
row "hot-seat A12 winners" "$storm_wins"
row "seats sold (main show)" "$confirmed / $total_seats"
row "reconciliation" "$([[ "$invariant" == true ]] && echo OK || echo BROKEN)"
echo
echo "  latency (s)      p50     p95     p99     max"
cat "${ALL[@]}" | awk -F'\t' -v OFS='\t' '$4 == 0 { print $2, $3 }' | sort -t "$(printf '\t')" -k1,1n -k2,2g \
  | awk -F'\t' '
      function pick(p,   i) { i = int(p / 100 * (n - 1) + 0.5); return (i > n - 1) ? n - 1 : i }
      function report() { printf "  %-12s %7.3f %7.3f %7.3f %7.3f   (n=%d)\n", cur, v[pick(50)], v[pick(95)], v[pick(99)], v[n - 1], n }
      $1 != cur { if (n) report(); cur = $1; n = 0 }
      { v[n++] = $2 }
      END { if (n) report() }'
echo
echo "  total run time $((SECONDS - STARTED))s; main show $SHOW_ID"

if (( ${#FAILED[@]} )); then
  echo
  echo "FAILED: ${#FAILED[@]} check(s)"
  printf '  - %s\n' "${FAILED[@]}"
  exit 1
fi
echo
echo "ALL $CHECKS_TOTAL CHECKS PASSED"
exit 0
