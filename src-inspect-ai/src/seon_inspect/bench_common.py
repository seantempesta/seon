"""Shared bench-arm machinery — run bounds + wire-REPL, transport-agnostic.

Both anchors (SWE-bench Verified via inspect's docker sandbox, terminal-bench
via THEIR harness + our `--agent-import-path` adapter) boot the SAME Seon
runtime inside the task container and must apply the SAME interim run bounds
onto the ROOT agent before the task posts. The mechanism is identical; only
the CHANNEL differs (inspect `sandbox().exec` vs docker-py
`container.exec_run`). This module holds the channel-agnostic parts so the two
arms share ONE implementation instead of copying it:

- `NODE_WIRE_REPL_JS` / `NODE_BIN` — the bundled node speaks the wire-REPL
  socket protocol from INSIDE the task container (no `nc` in the task images;
  the host cannot reach the loopback-only REPL). Mirrors
  `cluster.wire_repl_json`: send one form, half-close, read all, print.
- `run_bounds_form` — the wire-REPL form: install-if-missing the two run-bound
  attrs' datahike schema, transact them onto the ROOT agent, read back, print
  the `WIRE-JSON<{...}>WIRE-JSON` sentinel.
- `apply_run_bounds(exec_fn, ...)` — retrying transact-then-verify over an
  async `argv -> stdout+stderr` executor the caller supplies (the arm binds
  its own channel). RAISES when the read-back doesn't carry what was asked —
  a run whose bounds didn't take must never be scored as bounded.

The `parse_wire_json` sentinel parser stays in `cluster.py` (its original
home, shared by the host-loopback REPL channel too); this module imports it.
"""

from __future__ import annotations

from seon_inspect.cluster import parse_wire_json

# The bundled node speaks the wire-REPL socket protocol from INSIDE the task
# container. Send one form + newline, half-close, accumulate, print on close.
NODE_WIRE_REPL_JS = (
    'const s=require("net").connect(7891,"127.0.0.1");let b="";'
    's.on("data",d=>b+=d);'
    's.on("close",()=>{process.stdout.write(b);process.exit(0);});'
    's.on("connect",()=>{s.end(process.argv[1]+"\\n");});'
    's.on("error",e=>{console.error(String(e));process.exit(1);});'
    'setTimeout(()=>{process.stdout.write(b);process.exit(2);},30000);'
)

NODE_BIN = "/opt/seon/node/bin/node"

# The pod deadline is set STRICTLY below the door (HTTP) timeout so the pod's
# OWN bound always cuts first. If the two are equal it is a coin-flip which
# clock fires on time exhaustion: door-first → `solve_timeout` (an EXCLUDED
# flake that silently inflates the capability mean); deadline-first →
# `behavior_miss` (scored FAIL, the correct attribution). One margin fraction,
# shared by both arms (quality-review finding 1, 2026-07-06).
DEADLINE_FRACTION = 0.9


def deadline_below_door(door_timeout_ms: int,
                        explicit_ms: int | None = None) -> int:
    """The transacted pod deadline, guaranteed strictly below the door timeout.

    `explicit_ms` (when the caller wants a specific bound) is still asserted
    below the door — a deadline ≥ the door timeout is a measurement bug, not a
    tunable, and must fail loudly rather than corrupt the flake accounting."""
    dl = (explicit_ms if explicit_ms is not None
          else int(door_timeout_ms * DEADLINE_FRACTION))
    assert dl < door_timeout_ms, (
        f"pod deadline {dl}ms must be STRICTLY below the door timeout "
        f"{door_timeout_ms}ms (else time exhaustion is a coin-flip between "
        f"solve_timeout-excluded and behavior_miss-scored)")
    return dl

# Datahike schema for the two run-bound attrs, derived via the one bridge
# (`seon.db/malli->datahike-schema` on `:int` → :db.type/long, same mapping
# as :seon.ai/timeout-ms in cluster.py's precedent). Needed because a fresh
# cluster installs schema lazily on the pod-side write path and the wire REPL
# bypasses that installer. Install-if-missing only.
_RUN_BOUND_SCHEMA_EDN = (
    '[{:db/ident :seon.agent.run/default-turn-limit'
    ' :db/valueType :db.type/long :db/cardinality :db.cardinality/one}'
    ' {:db/ident :seon.agent.run/default-deadline-ms'
    ' :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]'
)


def run_bounds_form(turn_limit: int, deadline_ms: int,
                    db_name: str = "default") -> str:
    """The wire-REPL form: run-bound attrs onto the ROOT agent + read-back.

    `open-run!` seeds every run's turn-limit/deadline from these agent-entity
    attrs (`src/seon/agent/run.cljs:263-270`) — so transacting them BEFORE
    the task posts bounds the task's run. Prints the WIRE-JSON sentinel with
    the read-back row (fail-loud verification host-side)."""
    return (
        "(do (require (quote [cheshire.core :as json])"
        " (quote [datahike.api :as d]))"
        " (let [conn (:seon.server.registry/conn (seon.server.registry/get-conn"
        f" {{:seon.server.registry/db-name :{db_name}}}))"
        " installed (set (keys (d/schema @conn)))"
        " schema-tx (into [] (remove (fn [s] (installed (:db/ident s)))) "
        + _RUN_BOUND_SCHEMA_EDN + ")"
        " _ (when (seq schema-tx) (d/transact conn {:tx-data schema-tx}))"
        " _ (d/transact conn {:tx-data"
        " [{:db/id [:seon.agent/id \"root\"]"
        f" :seon.agent.run/default-turn-limit {int(turn_limit)}"
        f" :seon.agent.run/default-deadline-ms {int(deadline_ms)}}}]}})"
        " row (d/q (quote [:find (pull ?e [:seon.agent.run/default-turn-limit"
        " :seon.agent.run/default-deadline-ms]) . :where"
        " [?e :seon.agent/id \"root\"]]) @conn)]"
        " (println (str \"WIRE-JSON<\" (json/generate-string"
        " {\"turn_limit\" (:seon.agent.run/default-turn-limit row)"
        "  \"deadline_ms\" (:seon.agent.run/default-deadline-ms row)})"
        " \">WIRE-JSON\"))))"
    )


def run_bounds_argv(turn_limit: int, deadline_ms: int,
                    db_name: str = "default") -> list[str]:
    """The argv that evals `run_bounds_form` via the bundled node wire-REPL."""
    return [NODE_BIN, "-e", NODE_WIRE_REPL_JS,
            run_bounds_form(turn_limit, deadline_ms, db_name)]


async def apply_run_bounds(exec_fn, *, turn_limit: int, deadline_ms: int,
                           db_name: str = "default",
                           retries: int = 12, retry_sleep_s: float = 5.0
                           ) -> dict:
    """Transact the run bounds onto the in-container root agent, verified.

    `exec_fn` is an async `argv -> stdout+stderr text` executor inside the
    task container (SWE-bench passes `sandbox().exec`; the tb adapter passes a
    `container.exec_run` wrapper). Retries cover the window where the pod
    answers HTTP but the root agent is not yet minted (the transact's
    lookup-ref fails → no sentinel → retry). RAISES when the read-back doesn't
    carry what was asked — a run whose bounds didn't take must never be scored
    as bounded."""
    import anyio

    argv = run_bounds_argv(turn_limit, deadline_ms, db_name)
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            out = await exec_fn(argv)
            row = parse_wire_json(out)
            if (row.get("turn_limit") == turn_limit
                    and row.get("deadline_ms") == deadline_ms):
                return {"turn_limit": turn_limit, "deadline_ms": deadline_ms}
            raise RuntimeError(
                f"run-bounds read-back mismatch: asked "
                f"turn_limit={turn_limit} deadline_ms={deadline_ms}, "
                f"row says {row}")
        except Exception as e:  # no sentinel yet / root not minted / exec err
            last_err = e
            if attempt < retries - 1:
                await anyio.sleep(retry_sleep_s)
    raise RuntimeError(
        f"run bounds not applied after {retries} attempts "
        f"(seon_env_fault — wire REPL unreachable or root agent absent): "
        f"{last_err}")
