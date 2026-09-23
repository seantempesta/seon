---
type: landing
status: committed; loaded code in default not yet the new entry (adoption refused, see Limits)
created: 2026-09-23
---
# No prepl session retains results through *1/*2/*3/*e

Owner ruling (2026-09-23): "lets remove the *1 *2 *3 etc code. We are already
making named symbols that are references for every output so this is
redundant." Follow-ups: an MCP evaluation keeps no result, and no
last-commands record is added.

## Seam

Clojure's `prepl` binds `*1 *2 *3 *e` per session through
`clojure.main/with-bindings`. It `set!`s them just before calling `out-fn`
(`reference-code/clojure/src/clj/clojure/core/server.clj:236-238`, and `*e` at
`:251` and `:257`). Our `out-fn` runs on that session thread inside that
binding frame, so it can `set!` all four back to nil before it prints a
`:ret`. That clearing is the only change:
`resources/seon/operator/prepl.clj:12-18`. The prepl loop is not copied or
forked. An `out-fn` for `:tap` runs on the tap thread and is left alone.
`start-server` resolves `:accept` per connection (`server.clj:77`), so an
adopted entry applies to new sessions.

Both prepl listeners use this entry: `src/seon/cluster/boot.clj:424` and
`script/seon/operator.clj:330`.

The named reference the owner means is `seon.sci.admit/result-handle`
(`src/seon/sci/admit.clj:640`). `seon.sci.eval/bind-result!`
(`src/seon/sci/eval.clj:533`) binds it for every SCI evaluation. SCI keeps its
own `*1` only in its REPL loop (`reference-code/sci/src/sci/impl/namespaces.cljc:1008`,
`:init nil`). `seon.sci.eval` never sets it, so SCI retains nothing.
`script/seon/dev/mcp.clj` reads no star var and holds no result values. Its
`clj-sessions` atom holds only sockets and endpoints.

## Changed paths

- `resources/seon/operator/prepl.clj`: +8 −1 (the clearing plus its docstring).
- `script/seon/dev/mcp.clj`: the `eval_clj` description now says the session
  keeps no result.
- `docs/seon/issues/repl-parity-divergences.md`: star vars are retired, not a
  divergence to fix.
- `test/seon/dev/prepl_retention_test.clj`: new, 47 lines.
- src net: 0 lines.

## Evidence (pid 60088, MCP `eval_clj`, sessions `lane-star-a`/`lane-star-b`)

- Live red: session A returned `(byte-array 32 MB)` and kept its WeakReference
  in the throwaway namespace `lane-star-probe`. Session B then ran three
  `System/gc` (239 ms), and the value was still alive (`:alive true`).
- The regression, red on the loaded entry: `clojure.test/test-vars` returned
  `{:test 1 :pass 2 :fail 2}` in 287 ms. Both `*1` and `*e` retained the value.
- Green on the new code: the new file was loaded as the throwaway namespace
  `lane-star-probe.prepl`. No Var in default was touched. The same helper gave
  `{:old-entry {:value false :throw false} :new-entry {:value true :throw true}}`
  in 560 ms.

## Limits and what waits

- `bin/seon init --dev default --changed resources/seon/operator/prepl.clj`
  refused with "Source changed during development adoption". The paths it
  named were other lanes' uncommitted files (`src/seon/cluster/agent.clj`,
  `db.clj`, `cluster/source.clj`, `flow.clj`, `test/seon/db_test.clj`). The
  refusal took 2,659 ms, and that time went to the full source refresh the
  adoption re-derives (`seon.cluster/refresh-source!` x3, 7,583 ms inclusive).
  That cost belongs to the adoption path, not to this slice.
- Default's loaded `seon.operator.prepl/io-prepl` is therefore still the old
  one. Sessions opened after the next adoption or restart get the fix.
  `bin/test-check --ns seon.dev.prepl-retention-test` is green only once it is
  loaded.
- The new `eval_clj` description is shown after the MCP server restarts.
- Out of scope, not edited (held files):
  - `seon.cluster/mcp-io-prepl` (`src/seon/cluster.clj:551`) duplicates this
    entry. Only `test/seon/mcp_test.clj:23` and
    `test/seon/dev/mcp_bridge_test.clj:764` use it. Delete it and point both
    tests at `seon.operator.prepl/io-prepl`.
  - `test/seon/repl_parity_test.clj:479-512` rows C1-C5 are pending parity
    rows for `*1/*2/*3/*e`. They test a retired target and should be deleted.
