---
type: issue
status: open
severity: blocker
tags: [issue, operator, tooling, wave/publication-velocity]
---

# A stale operator JVM refuses every `bin/seon init --changed` publication

## Problem

Every incremental publication against the shared operator root
(`data/clusters`) fails, for every file, including files nobody edited. The
edit hook publishes through this path, so the hook's ADVISORY fires on every
Clojure edit any agent in this tree makes, and no edit reaches `current-src`.

The refusal, verbatim:

```
seon.fn/manifest-function-symbols violated its contract (invalid-input):
missing required key
  offending: #:seon.fn.manifest{:artifacts {0 #:seon.fn.file{:rows {1 #:seon.ns{:name nil}}}}}
  problem-count: 50197
```

## Evidence (measured 2026-09-07)

- Reproduced on three unrelated files, two of them with no working-tree
  change at all: `src/seon/repl.clj` (new), `src/seon/render/value.clj`
  (untouched), `src/seon/print.cljc` (untouched). Identical refusal each
  time. The failure is therefore not a property of the edited file.
- The refusal's stack names `seon.cluster/incremental-source-refresh!` at
  `cluster.clj:1669`, invoked from `cluster.clj:1656`. On disk at HEAD
  (`c1268a015`) that function begins at `cluster.clj:1694` and the offending
  call sits at `cluster.clj:1707`. **The serving JVM is not running the code
  in the tree.**
- The operator JVM is pid 14798, started `Sat Sep 5 18:19:25 2026`
  (`ps -o lstart=`). The commit that made source manifest rows satisfy this
  contract, `b0fdadd2e "Reload development namespaces in requires order and
  declare sparse upsert rows"`, landed `Sun Sep 6 21:35:50 2026` — more than
  a day after that JVM loaded its code.
- Against HEAD's code the gate is correct and would not enter this branch:
  probed live through MCP `eval_clj`,
  `(#'seon.cluster/valid-source-manifest? manifest)` returns **false** for
  the cached artifact in `data/clusters`, which routes to
  `full-source-refresh!` at `cluster.clj:1704`. The cached artifact's rows
  carry `:seon.fn/ns` and no `:seon.ns/name` (rows 1–6 of artifact 0,
  `src/my/background.clj`), which is exactly the shape the older loaded code
  did not expect.

## Why it matters

This is the failure class AGENTS.md §6 names in its session-start hygiene:
"a stale long-lived JVM serves old code — reset it onto current source
rather than debugging phantoms". Two agents have now spent time attributing
this refusal to their own edits. The hook reports it as an ADVISORY naming
the *agent's* file, which points every reader at the wrong cause.

## Fix

1. Reset the shared operator root onto current source (`bin/seon down`, then
   the ordinary republication) so the serving JVM's code matches the tree.
   This needs the owner or the orchestrator: a lane may not stop or reset the
   shared root.
2. Then the derived question: the hook's ADVISORY attributes a publication
   failure to the file being published. When the cause is the operator's own
   staleness, the advisory should say so rather than naming the innocent
   file — a refusal that names the wrong subject is worse than a louder one
   that names the right one.

## Not the cause

The cached `data/clusters` source artifact being stale is a *symptom*, not
the disease: HEAD's `valid-source-manifest?` already rejects it and falls
back to a complete rebuild. Only the pre-`b0fdadd2e` code in the running JVM
turns it into a throw.
