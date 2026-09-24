# Lane fix-affected-callers-interpret (2026-09-24)

A branch acquisition interprets its changed rows plus their reverse closure
(`seon.sci.eval/acquire-program!`). `install-first-party-namespaces!` copies only
the Vars that have function rows (`select-keys (ns-interns host) indexed-names`),
and SCI carries no dependency namespace. So an interpreted caller could not see
its private `def`s, a referred `defonce`, `clojure.core` names SCI omits, or any
alias target outside the first-party set. Each unresolved name refused one `:core`
row, and any one of those refused the whole acquisition. This lane makes every
name the caller's compiled namespace reaches resolve to its compiled host Var.
Only the branch's rows become SCI definitions.

## Still broken

- **JVM macros that read `&env` (for example `async/go-loop`) cannot expand in SCI.**
  Rows in a loaded test namespace that use one still refuse the acquisition. The
  producer is `seon.fn/host-bound-callers` (`src/seon/fn.clj:805`), which is outside
  this lane: it classifies these rows as interpretable. This is recorded in
  `docs/seon/issues/affected-callers-fail-to-interpret-against-uninstalled-bindings.md`,
  which stays open. The regression asserts only the class this lane fixed
  (no refusal says "Unable to resolve" or "does not name an installed SCI Var"),
  so it stays green while this residual is open.
- `branch-definitions-and-unchanged-callers-share-one-context` still errors with
  "Effective configuration requires a matching cluster row with every required
  dial". That is the follow-up item, which gets its own commit and section.
- Save gates for these edits stayed red for foreign reasons: fixture writes refused
  on `:seon.agent/branch`, plus `my.plan-test` and `my.note-test`. The final gate
  held 0 "Cannot interpret" lines, where the earlier gate had 16
  (`seon.cluster/publication-base!: Unable to resolve symbol: spit`).
- Default OOM-died at 18:08Z (heap dump `data/heap/java_pid70372.hprof`, 14 GB)
  during another lane's run. Root restarted it at 18:13Z (pid 99265). Nothing in
  this lane was running then.

## Change

`src/seon/sci/eval.clj` (+57/-8):
- `install-caller-host-bindings!` runs after `install-first-party-namespaces!` and
  before any caller is analyzed. It covers only the namespaces of interpreted rows.
  - The caller's own namespace gets `ns-interns` plus the `clojure.core` refers that
    SCI's core lacks.
  - Each alias and refer target in its namespace row gets `ns-publics`.
  - Existing SCI bindings win, so first-party copies, interpreted definitions and
    SCI's interrupt-aware `clojure.core` and `clojure.string` are never replaced.
  - Copies use `copy-host-var` (SCI `copy-var*`, `reference-code/sci/src/sci/core.cljc:112`)
    through the previously unused `install-host-namespace!`.
  - Resolution is SCI's ordinary namespace-map and alias lookup, so no new resolver
    was added. The refer check is `install-namespace-bindings!`, `core.cljc:771-835`.
- `install-row!` `unbound?` now also covers an unchanged interpretable `:core` row
  in an unloaded namespace. There is no compiled namespace to analyze it against,
  so it binds `install-refusing-root!`, the same as the host-bound rows from
  f2bc7088a. A changed row in an unloaded namespace still interprets.

`test/seon/sci/branch_execution_test.clj` (+43/-0):
- `interpreted-callers-resolve-unchanged-names-to-compiled-vars` stamps a new digest
  on `seon.render.hiccup/class-value`. It asserts that `seon.render.web/derive-page`
  (private def `stream-strip-id`), `seon.render.web/handler` (`ring/router`),
  `seon.cluster/serve!` and `seon.cluster.boot/selected-instance` (referred
  `defonce running-instances`) load as `:interpreted`. It also asserts that the
  interpreted `selected-instance` returns the identical compiled instance map, and
  that no refusal is an unbound name.
- `on-branch` returns nil, which is its declared contract. It had returned the
  body's `is` value, and the armed contract refused
  `an-adoption-record-moves-the-loaded-program-every-acquisition-compares-against`.

## Evidence (default pid 99265, `bin/test-check`; outputs in `tmp/fix-affected-callers-interpret/`)

| Run | Result |
|---|---|
| the lane's first cut (alias/intern install only), `after-2.txt` | 22 refusals remained, all in unloaded test namespaces; `find-ns` was nil for all 22 (JVM probe) |
| with the fix, `after-9.txt` (run e6d5d3529ce2) | 5 pass / 0 fail |
| fix disabled by the `(when false …)` / `(and false …)` toggles, `without-fix.txt` (run 963a9521ef48) | 2 fail / 1 error: load-results empty for the callers, `Unable to resolve symbol: seon.cluster.boot/selected-instance`, and unbound-name refusals present |
| regression + f2bc7088a regression + `my.turn-test/a-contract-forbidden-argument-is-a-value-the-agent-reads` (run abbdadb86499) | 74 pass |
| all 8 `branch-execution-test` members by name (run efc277be292d) | 7 green; 1 is the foreign config error above |

Acquisition time for the 78-declaration closure: 1,794 ms before the fix (it
refused), and 1,239 ms and 1,426 ms after (two runs). That is over one second.
The work is proportional to the closure: 78 rows interpreted from source, each
analyzed by SCI. The new binding copy covers only those rows' namespaces and
their alias targets. `measured` covers the whole acquisition, and I did not
separate the copy's own cost.

Net lines: +100/-8 source and test, plus this note and the issue update. RESET NEEDED: no.

## Limits

- Hook publication raced other lanes ("source head changed before publication").
  Some runs executed an older published test body, so each run I cite was checked
  against the assertion lines it printed.
- An agent evaluating inside a first-party namespace that holds an interpreted row
  now also resolves that namespace's private interns and the `clojure.core` names
  SCI omits, such as `spit`. The compiled namespace already reaches all of these.

## Follow-up: the "every required dial" refusal in `branch-definitions-and-unchanged-callers-share-one-context`

The proposed cause was wrong. `database-effective-config` does pick an arbitrary
row, but both rows the fixture holds are complete. A probe inside the member read:

- `{:rows #{["default" 42401] ["realities-proof" 62445]}, :effective {"default" nil, "realities-proof" nil}}`
  (nil means no refusal)
- the refusal's `::available ["default" "realities-proof"]`
- `::missing` = every dial
- `:seon.config/missing-effective "current-src"`, thrown from `seon.sci.eval/shown-result`
  (`eval.clj:3116`) when an evaluation on the fork rendered its result

The producer is the 3-arity `seon.sci.eval/fork-cluster-ctx`. It built a fresh
`projection-state`, which hardcodes `:seon.boot/cluster-name "current-src"`
(`eval.clj:181`), so the fork lost its base context's cluster. The production
callers (`seon.cluster.boot:91`, `seon.cluster.agent:902`) pass the environment
explicitly. Only this test used the 3-arity.

Change:
- The 3-arity now passes `{:seon.env/environment (env/of base-ctx)}` when the base
  has one (+6/-1). The fork keeps its cluster, and connection, basis and projection
  are still replaced from the receiving branch.
- That exposed a second, test-side assumption: `interpreted-count` equals the
  closure. The fixture inherits default's agent-authored `my.agents.root/largest`,
  which interprets in every acquisition. The assertion now says the branch
  interprets exactly its parent's set plus the closure (+6/-2).

Evidence:
- Before: run efc277be292d errored.
- After: all 8 `branch-execution-test` members, run by name, passed as run
  9a92b7eecfc9 (69 pass / 0 fail / 0 error, 21,226 ms for the eight).
- `database-effective-config` is unchanged. Its arbitrary pick is still a latent
  ambiguity, but it did not cause this failure.
