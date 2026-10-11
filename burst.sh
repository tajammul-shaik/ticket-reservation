#!/usr/bin/env bash
# One-command burst test (BRD section 14).
#
#   ADMIN_SECRET=<admin secret> ./burst.sh <BASE_URL> [options]
#   ADMIN_SECRET=compose-local-admin-secret ./burst.sh http://localhost:8777
#   ./burst.sh --help
#
# Runs burst.py with httpx, using the first of these that works:
#   1. a private virtualenv in .burst-venv (created on first run; needs python3 with venv),
#   2. a python:3.12-slim Docker container (needs Docker; uses host networking so
#      http://localhost URLs reach a local stack).
# Exits non-zero if any check fails.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
venv="$here/.burst-venv"
httpx_pin='httpx==0.28.1'

# Many concurrent sockets: raise the open-file limit if the shell allows it.
ulimit -n 65536 2>/dev/null || ulimit -n "$(ulimit -Hn)" 2>/dev/null || true

if [[ -x "$venv/bin/python" ]] && "$venv/bin/python" -c 'import httpx' 2>/dev/null; then
  exec "$venv/bin/python" -I "$here/burst.py" "$@"
fi

if python3 -m venv "$venv" >/dev/null 2>&1 \
   && "$venv/bin/python" -m pip install --quiet --disable-pip-version-check "$httpx_pin"; then
  exec "$venv/bin/python" -I "$here/burst.py" "$@"
fi
rm -rf "$venv"

if command -v docker >/dev/null 2>&1; then
  echo "python3 venv unavailable; running burst.py in a python:3.12-slim container" >&2
  tty_flag=(); [[ -t 1 ]] && tty_flag=(-t)
  exec docker run --rm -i "${tty_flag[@]}" --network host --ulimit nofile=65536:65536 \
    -e ADMIN_SECRET -e PYTHONUNBUFFERED=1 \
    -v "$here/burst.py:/burst.py:ro" python:3.12-slim \
    sh -c "pip install --quiet --disable-pip-version-check --root-user-action=ignore '$httpx_pin' && exec python -I /burst.py \"\$@\"" \
    burst "$@"
fi

echo "Need either python3 with the venv module (e.g. apt install python3-venv) or Docker." >&2
exit 2
