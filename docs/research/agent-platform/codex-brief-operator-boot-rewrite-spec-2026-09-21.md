---
type: reference
status: brief for Codex (write the spec; Fable reviews it)
created: 2026-09-21
tags: [agent-platform, operator, boot, rewrite, brief]
---

# Brief: write the operator-and-boot rewrite spec

Owner rulings 2026-09-21: the operator and boot are REWRITTEN from the data
flow, not cut (B1 §2c); plumbing lands first and the turn/context code last
(README §4). Codex writes the spec; Fable reviews it against the tables.
Documentation only.

## Deliverable

`docs/prds/agent-platform/plan/lane-b1b-operator-and-boot-rewrite.md`, ≤ 300
lines, tables over prose, in the eight-section shape every spec has (§0 for the
owner, numbers, data flow, reading list, REPL protocol, ordered commits, better
than the floor, tests, done/landing/stop). It replaces B1 §2c and commit 11.

## Read end to end before writing

`script/seon/fresh_operator.clj` (3,727), `src/seon/operator.clj` (1,219),
`src/seon/operator/state.clj` (1,627), `src/seon/cluster.clj:3187-3560`
(`stand-boot-layers!`, `stand-cluster-runtime!`, `start!`, `stop!`), `bin/seon`,
`bin/test-check` (the 53-line model of "one request"), `src/seon/cluster/store.clj:300-360`
(the store `flock`), `src/seon/cluster/registry.clj` (`ensure-cluster!`,
`branch!`), `src/seon/cluster/process.clj` (`(pid, start-instant)`),
`script/seon/dev/mcp.clj:93-114` (how the bridge dials the prepl). Dependency
seams: `ProcessHandle` (JDK), `clojure.core.server` prepl
(`reference-code/clojure/src/clj/clojure/core/server.clj:228`),
`datahike.versioning/branch!` `:212`.

## What the spec must contain

1. **The command table.** For each of `start`, `init`, `init NAME`, `init --dev`,
   `status`, `open`, `stop`, `down`, `reset --force`, `logs`, `config apply`: the
   exact request map sent over the prepl, the exact reply map, which ones need no
   JVM (`start` when none answers, `down` of an unresponsive JVM, `reset`), and
   the refusal each can return. `export` dies with B4's base store.
2. **The boot layer table.** Store → source commit → branch → connection →
   projection (read from the value, A1-3) → context (`base-ctx` once) → graphs →
   prepl → web, one row per layer: input, output value published to the
   instance, the readiness fact it publishes, the refusal when it cannot stand,
   and what `stop!` releases (newest first). No `require-coherent-program!`,
   no `accrete-schema-population!` (B1 deletes them), no search layer.
3. **Process identity and exclusion.** `(pid, start-instant)` only; the
   advertisement file holds the prepl port for a cold dialer and nothing else;
   the store `flock` beside the store (`data/store.lock`) is the ONE exclusion,
   held by `reset` across down → delete → republish → start. No lifecycle lock,
   claim files, process records, phase logs, generation UUID, truth/repair pass.
4. **The seven drills**, each a `deftest` on a scratch root through the one
   `seon.test/run`: cold start; second concurrent start refused naming the
   holder; stop; exact `down` of an unresponsive JVM (kill -STOP the child);
   reset; start during reset refused; reset during start refused. Boot from
   zero stays the platform tier's one subprocess.
5. **Size and shape.** The 900-line target itemised per file: `bin/seon` (bb
   argv → request), `script/seon/operator.clj` (launch, prepl client, reset),
   `src/seon/cluster/boot.clj` (the layer sequence), with `wc -l` per file. Old
   files deleted in the same slice: the three operator files, the boot span of
   `cluster.clj`, `dev/fresh_operator_test.clj`, `dev/fresh_operator_reset_test.clj`,
   `operator_test.clj`, `cluster/boot_test.clj`'s operator drills.
6. **Recovery.** `git revert` of the slice, then `bin/seon reset --force` on the
   reverted operator. The orchestrator restarts `default` through the new
   operator once; lanes never do.
7. **What is NOT rewritten.** Publication (`refresh-source!`), adoption,
   `registry`, `store`, the MCP bridge. Name each seam the new code calls.

## Rules

Every claim cites `file:line`; verify / probe / falsify; no new mechanism,
cache, tuned constant or noun; Clojure, Datahike, core.async, JDK vocabulary
only. Where the old code holds a guarantee the tables do not name, list it in
§0 as "found while reading" with the decision to keep or drop and why. Stop and
list three options at any genuine owner decision.
