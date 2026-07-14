---
type: issue
status: resolved
severity: friction
tags: [issue, agent]
---

# `bin/acme cluster reset` wiped the store but never restarted acme's processes

## Symptom (observed live, 2026-06-28)

`bin/acme cluster reset acme` wiped `data/clusters/acme/store` but printed

```
▶ cluster acme store wiped (no processes registered for it)
```

and did NOT restart the acme pod + wire-server. This left acme's wire-server
(still running, registered in `tmp/proc-acme`) holding a now-DELETED store — a
broken state. The reporter recovered manually with `bin/acme down && bin/acme up`.

## Root cause — the bounce gate keyed off the literal name "default"

`bin/acme` is a thin wrapper: it exports the isolated env block
(`SEON_CLUSTER_DIR=data/clusters/acme`, `SEON_PROC_DIR=tmp/proc-acme`,
`SEON_REQ_SOCK=tmp/acme-cluster-req.sock`, ports 7980/7981) and then
`exec`s `bin/seon "$@"`. So `bin/acme cluster reset acme` runs
`bin/seon cluster reset acme` with acme's env.

Inside `bin/seon`'s `cluster_reset`, the decision to stop/restart processes
(`bounce`) was gated on the **literal string** `name == "default"`:

```bash
local bounce=0
[ "$name" = "default" ] && bounce=1
```

The requested name under acme is `"acme"`, not `"default"`, so `bounce=0`: the
store got wiped, the restart was skipped, and it printed "no processes
registered for it" — even though acme's pod + wire-server ARE registered, just
in a different process namespace (`tmp/proc-acme`). The gate confused "which
cluster the *string* names" with "which cluster THIS supervisor invocation
manages."

## Fix — gate on the cluster THIS supervisor manages, not a name string

In `bin/seon` `cluster_reset`:

```bash
local bounce=0
[ "$store_dir" = "$SEON_CLUSTER_DIR/store" ] && bounce=1
```

`store_dir` is `$SEON_CLUSTER_DIR/store` for the supervisor's own cluster.
Behavior table (all `SEON_*` env from the invoking wrapper):

| invocation | requested name | `store_dir` | `$SEON_CLUSTER_DIR/store` | bounce |
|---|---|---|---|---|
| `bin/seon`  | default | `data/clusters/default/store` | `data/clusters/default/store` | yes (unchanged) |
| `bin/seon`  | acme    | `data/clusters/acme/store`    | `data/clusters/default/store` | no  (unchanged — wipe only) |
| `bin/acme`  | acme    | `data/clusters/acme/store`    | `data/clusters/acme/store`    | **yes (FIXED)** |
| `bin/acme`  | default | `data/clusters/acme/store`    | `data/clusters/acme/store`    | **yes (FIXED)** |

The default cluster's behavior is **byte-identical** to before (for the default
supervisor, `store_dir` was just assigned `$SEON_CLUSTER_DIR/store`, so the new
gate is trivially true exactly when the old one was). The restart drives
`bin/seon`'s existing race-safe sequence — `cmd_stop` both → wipe → `cmd_start
wire-server` → `wait_ready wire-server` → `cmd_start pod` → `wait_ready pod` —
all reading acme's env, so it bounces acme's pod+wire-server in `tmp/proc-acme`
and can NEVER touch the live default cluster.

`bin/acme` gets only a comment documenting that `cluster reset` needs no
intercept (it execs straight through). An early attempt to fix this purely in
`bin/acme` via a `down && up` intercept was REJECTED and reverted: after a store
WIPE, the wire-server's cold mint (fresh DB + orphan embedding-index delete)
took ~9s, outlasting the pod's ~10s ping window when `up` starts pod
back-to-back — so the fresh pod died (`logs/acme/pod.log`: 5× ECONNREFUSED on
`tmp/acme-cluster-req.sock`). Only `cluster_reset`'s `wait_ready wire-server`
BETWEEN stop and pod-start is race-safe. One mechanism, not two.

## Live proof

`bin/acme cluster reset acme` after the fix:

```
○ wire-server stopped (was pid 67070)
✗ wiped data/clusters/acme/store
● wire-server started (pid 68474)
  waiting for wire-server ready (bound 180s) ........
  ● wire-server ready (8s)
● pod started (pid 68542)
▶ cluster acme reset complete — pod boot re-seeds the core
```

- acme HTTP 7980 → `200`; acme wire REPL 7981 (`nc`) → evaluates forms.
- No "no processes registered" message.
- Live DEFAULT cluster UNTOUCHED across the reset: pod pid `63340` and
  wire-server pid `63196` identical before and after; HTTP 7890 → `200`.

## Related smell (FIXED in a follow-up — see seon-port-non-namespaced.md)

`tmp/seon-port` is a single, non-namespaced file that BOTH clusters' pods write
on boot. After an acme boot it reads `7980`, so `bin/seon status` AND
`bin/acme status` both print "pod port: 7980" regardless of which cluster you
asked about. Consequently `ready_check pod` (which curls
`http://127.0.0.1:$(cat tmp/seon-port)/`) can false-pass against the OTHER
cluster's pod — harmless inside `cluster_reset` because the pod is already gated
on its wire-server socket (the readiness check is only confirmation), but it is
a latent isolation leak. Fix would be a `SEON_PORT_FILE` (or
`$SEON_RUNTIME_ROOT`-relative path) so each cluster writes its own port file.
Flagged for a follow-up task.

## Resolution (2026-06-28 audit)

Closed RESOLVED per `docs/seon/issues-audit-2026-06-28.md`: the
`bin/seon` `cluster_reset` bounce gate now keys off `store_dir` (`bin/seon:837`,
commit `c6d7c440`) instead of the literal name "default"; live-proven.
