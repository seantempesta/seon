#!/bin/zsh
# web/bench/sweep.sh [SIZES...] — root's web at each agent count: boot `target/seon web N light`
# on SEON_WEB_PORT (default 7790), run latency.py (100 GETs, 40 evals, 3 tabs), print uptime
# before and after, stop the root (its stdin closes). Run under the bench lock:
#   /usr/bin/lockf -k runs/bench.lock web/bench/sweep.sh 10 100 500
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PORT=${SEON_WEB_PORT:-7790}
. "$ROOT/bin/env"
for N in ${@:-10 100 500}; do
  D=$(mktemp -d /tmp/w2-sweep.XXXXXX); mkfifo $D/in
  (cd "$ROOT/target" && SEON_WEB_PORT=$PORT ./seon web $N light < $D/in > $D/out 2>&1) &
  RP=$!
  exec 3> $D/in
  until grep -q "^WEB" $D/out 2>/dev/null; do sleep 0.5; done
  sleep 3
  echo "== $N agents · load before: $(uptime | sed 's/.*averages: //')"
  python3 -I "$ROOT/web/bench/latency.py" $PORT 100 40 3
  echo "== load after: $(uptime | sed 's/.*averages: //')"
  exec 3>&-
  wait $RP
  rm -rf $D
done
