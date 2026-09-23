---
type: landing
status: landed
created: 2026-09-23
tags: [agent-platform, invalidation, receipts, landing]
---

# Lane receipts-no-program-edges (invalidation step 1)

Fix-schedule item #24c, step 1 of
[the invalidation review](../../../research/agent-platform/review-invalidation-design-2026-09-23.md).
Census row C1/W3 in
[the invalidation census](../../../research/agent-platform/invalidation-census-2026-09-23.md).

## Defect

Settling an ordinary agent form ran kondo analysis on its source. It then
asserted the form's static call targets as `:seon.fn/calls` on the receipt
(`seon.turn/relation-assertions`, fed by the non-declaration branch of
`seon.fn/analyzed-form`). `:seon.fn/calls` is a program attribute
(`seon.program/program-attributes`, 189 attributes on default). Datahike
advances one revision per attribute a commit touches
(`reference-code/datahike/src/datahike/query.cljc:2568-2589`, fork pin
`fbd1ad2d10fb1261ef7092737a02801537c16e70`). So every receipt counted as a
program change for every program-keyed derivation.

## Change

- `src/seon/turn.clj`
  - `analyze-settlement` analyzes a declaration only. An ordinary form gets
    no analysis and no `:seon.fn/calls`. It now has a contract, and it
    re-throws a namespace refusal whole instead of destructuring it.
  - The batch settlement path submits only declaration forms to
    `seon.fn/analyze-forms`.
  - Deleted: `relation-assertions` and the `:seon.turn/form-facts` plumbing
    (settlement projection, accretion install, refused-batch dissoc and batch
    reduce). For declarations the first tuple member was always `{}`; the
    declaration row owns its edges.
- `src/seon/fn.clj`: the `analyze-forms` docstring now says the non-declaration
  member is analysis only and nothing persists it.

**Consumers of receipt call edges.** Every reader of `:seon.fn/calls` was
checked. Each one joins through `:seon.fn/sym`, `:seon.test/sym`, a file ref or
`:seon.program/analyzed-source-digest`, which receipts do not carry:
`fn.clj:1543` (the reverse closure maps `:e` through the identity map),
`:1638`, `:1749`, `:1923`, `:2385`, `:2403`, `bootstrap.clj:414/430`,
`effect.clj:144` and `my/program.clj:99`. Test reach (`test/runner.clj:786-857`)
reads function and test rows. The only unfiltered reader is the "Callers" query
text rendered at `render/ns.clj:867-871`. Dropping receipts from it fixes what
it means, as the review says. No consumer needs a receipt-specific fact, so no
receipt attribute is declared. No schema resource changed.

**Behaviour change.** An ordinary form evaluated in a namespace with no stored
`:seon.ns/name` row no longer gets `:seon.fn/namespace-unresolvable` at
settlement, because nothing now needs that row. A declaration still gets it.

### Bundled items (orchestrator)

- **R-PRED `turn.clj:3092` `repaired-span`.** The `(catch Exception _ nil)` is
  gone. parinferish 0.8.0 declares only one throw, smart mode without a cursor
  (`core.cljc:326-328`), and indent mode never reaches it. An unparseable input
  is data: `parse` drops the mode and sets `:error?` metadata
  (`core.cljc:350,356`). The function now reads that metadata, and anything
  thrown propagates. It also gained a contract.
- **Dead projection stamp, `fn.clj:3589`.** The publication transaction no
  longer runs `(vary-meta database assoc :seon.schema/projection projection)`.
  This comes from the regression bisect, `5e3f5cf20`.
- **Test fixture repair in `test/seon/cluster/turn_test.clj`.** Every
  `with-cluster` test failed at seeding on HEAD for two reasons.
  `agent-row` lacked the required `:seon.agent/branch`, and new namespace rows
  lacked `:seon.program/definition-digest` (issue class
  `docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md`).
  Now `agent-row` supplies `(registry/cluster-branch "turn-test")`, which is the
  value production's `creation-tx` uses. A `namespace-row` helper derives the
  digest through `seon.program/definition-digest`, and a script converted the
  six absent-namespace literals.

## Proof

### Verification path and its limit

Default (pid 70720) loads published source
`data/source/76b42f90…/src/seon/turn.clj`, byte-identical to HEAD's
`turn.clj` and `turn_test.clj`. Hook publication is paused
(`.claude/seon-hook.edn`), and `bin/test-fast` plus the published bases no
longer exist. So HEAD+lane code ran in default through a disposable
`with-redefs-fn`: `tmp/receipts-no-program-edges/redef-run.clj`. It compiles
every top-level `seon.turn` defn whose checkout form differs from the published
one into a fn and installs it for the probe or test body only, then restores it
(verified: `(class @#'seon.turn/receipt-settle-call)` is
`seon.turn$receipt_settle_call` afterwards). Changed test-namespace helpers are
redefined the same way. The test bodies run under `db/*conn*` bound to an
isolated handle from `seon.cluster.agent/acquire-context!`, so
`with-database` branches and unlinks as `seon.test/run` would. This is not a
recorded `seon.test/run` result. The regression's recorded run belongs to the
next adoption.

The disposable mutations of default's test namespace persist. They add
`program` and `registry` aliases and intern `namespace-row` in
`seon.cluster.turn-test`. One disposable `(require 'seon.turn :reload)`
reloaded the identical published file twice, with `instrument/state`/`restore!`
around it; no `seon.turn` Var was armed before or after. It left default's code
unchanged.

### Receipt settlement, program-attribute revisions (branch created and retired)

Probe `tmp/receipts-no-program-edges/probe.clj`: branch default's head, settle
receipt 0 of a system run whose form is
`(seon.db/q '[:find ?e :where [?e :seon.agent/id]] (seon.db/db))`, compare
`:datahike.cache/attribute-revisions` before and after the settlement commit,
then release and retire the branch.

| | HEAD `seon.turn` | lane `seon.turn` |
|---|---|---|
| program attributes moved (of 189) | `#{:seon.fn/calls}` | `#{}` |
| acquisition attributes moved (of 290: program + projection + dials + cluster + commit-id) | `#{:seon.fn/calls}` | `#{}` |
| all attributes moved | `#{:seon.eval/shown :seon.fn/calls}` | `#{:seon.eval/shown}` |
| conservative revision moved | false | false |
| receipt `:seon.fn/calls` | `#{seon.db/db seon.db/q}` | absent |
| settlement tx-data build (`receipt-settle-tx db req`) | 181–228 ms (kondo analysis) | 0.06 ms |
| settlement transaction | 380–469 ms | 352 ms |

### "The next SCI evaluation after a receipt does no re-acquisition"

The revision key landed as `5f2aa93f4` (sci-program-revisions) while this lane
ran. HEAD `src/seon/sci/eval.clj` re-acquires only when the program basis
changes: the `acquisition-attributes` revisions within one connection
generation, plus the conservative revision (`program-basis`, `revision-basis`,
`acquired-database?` at `:2452`). The probe's 290-attribute set is that exact
definition: program attributes, `schema/projection-attributes`,
`config/dial-attributes`, `:seon.config/cluster` and `:seon.source/commit-id`.
Across one receipt settlement it moves `#{:seon.fn/calls}` with HEAD `seon.turn`
and `#{}` with the lane's, and the conservative revision does not move in
either case. So with this slice the next evaluation after a receipt finds an
equal program basis and reuses the held acquisition. Without the slice, every
ordinary receipt re-acquires. That re-acquisition costs 295–309 ms, measured
here as two `base-ctx` calls in the default JVM, which still runs the
pre-`5f2aa93f4` commit-keyed code. Limit: default has not adopted `5f2aa93f4`,
so no evaluation through the revision-keyed `acquire!` itself was timed. That
row belongs to the adoption that loads both commits.

### Regression

`seon.cluster.turn-test/an-ordinary-turn-advances-no-program-attribute-revision`
drives one ordinary two-form turn for `agent-a` on a fixture branch. It asserts
that the turn committed, both forms settled clean, no receipt carries
`:seon.fn/calls`, and program-attribute revisions plus the conservative revision
are equal before and after the whole turn.

- Lane `seon.turn`: 6 pass, 0 fail (43.3 s wall).
- HEAD `seon.turn` (falsification, same body): 4 pass, 2 fail. "a settled
  receipt carries no static call edge", and the revisions differ exactly by
  `:seon.fn/calls` (43.7 s wall).

`delimiter-repair-is-span-local-and-precedes-intent` holds the converted
assertion: the receipt carries no call edge, where it used to assert an edge to
the definition. Its run in default hit `OutOfMemoryError` inside
`seon.reconcile/plan-transaction-data` (reconcile.cljc:349) after 93.8 s, while
another lane's `seon.test/run` (id `7365f44c382f`) ran in the same JVM. It is
not a verdict. `repaired-span` was checked directly instead
(`tmp/receipts-no-program-edges/repair-probe.clj`). Over 8 inputs (repairable,
clean, a stray closer, an unterminated string, empty), the lane and HEAD results
were identical, each ≤1.3 ms, with no exception.

The declaration path (the batch submission now keeps only declaration forms,
with their original indices) was checked with
`tmp/receipts-no-program-edges/declaration-body.clj`. In one turn, `agent-a`
evaluates a contracted `(defn … plus1 [x] (inc x))`, then `(plus1 41)`, then
completes. Lane code: 4 pass, 0 fail (31.9 s wall). Row `my.agents.agent-a/plus1`
stores its spec and its own `clojure.core/inc` call edge, the ordinary form
shows `"42"`, and no receipt carries `:seon.fn/calls`.
`mixed-plan-publishes-only-the-contracted-function` fails the same way with lane
and HEAD `seon.turn`: 1 pass and 3 identical failures (51.0 s and 53.3 s). Two
failures come from the unscoped `agent-evaluations`, and one looks up
`"my.agents.agent-a/durable"` as a string where the attribute stores a symbol.
Both are old failures, not caused by this slice.

### Hot path: stamp deletion (publication transaction)

`tmp/receipts-no-program-edges/stamp-probe.clj` builds a speculative value (the
`:db.fn/call` shape, no committed identity) and times
`seon.db/carried-projection` on it three times, stamped and unstamped.
Stamped: 165, 210, 224 ms. Unstamped: 169, 223, 236 ms. The stamped value's
projection is not the stamped object (`identical?` false), so HEAD never reads
the stamp and deleting it is timing-neutral: within noise, no slowdown. Each
call derives about 200 ms on a speculative value. That is the writer-cost lane's
defect (its content-keyed memo is uncommitted `db.clj`).

## Out-of-scope findings (exact changes for their holders)

- **`test/seon/turn_test.clj` (unowned in the ledger).**
  `settlement-keeps-unresolved-call-and-require-names-as-values` asserts receipt
  call edges at lines ~1358-1386: `(= #{'seon.bootstrap/help} (set (:seon.fn/calls form)))`
  and the `missing.target/nope` receipt edge query. Both test the retired
  behaviour. Replace them with assertions that the receipt carries no
  `:seon.fn/calls`, keeping "the observed call does not mint its target". The
  same file's `{:seon.ns/name 'my.macro-caller}` seeds need the definition-digest
  helper.
- **`test/seon/fn_test.clj` (realities-commit-5).**
  `ordinary-form-analysis-keeps-call-edges-without-a-declaration` still passes,
  because analysis still returns them. Once that holder agrees, the
  non-declaration branch of `seon.fn/analyzed-form` (`fn.clj:1001-1007`) and
  that test can be deleted together, since nothing in `src/` consumes the
  member.
- **`seon.cluster.turn-test/agent-evaluations` reads every agent's evaluations**,
  and the fixture branches a live cluster that holds root's history. Result: 14
  and 17 evaluations where tests expect 2–3 (`mixed-plan-…`). The new regression
  scopes its read to `agent-a`. The helper needs the same scoping, which is this
  file's next conversion.
- **runtime_status crash (fixed meanwhile by its owner).** While run
  `7365f44c382f` held the problem derivation's refusal,
  `seon.cluster/mcp-runtime-observation` (cluster.clj:629, published source)
  counted the error map's entries and threw "count not supported on this type:
  Date". A later MCP server reports it as `problems-unavailable`.

## Timings (every operation over 1 s)

| operation | wall | phases / cache |
|---|---|---|
| receipt probe on a branch (HEAD) | 4.0–4.8 s | branch 23 ms; settle build 181–228 ms; settle tx 380–469 ms; the rest is the system-run tx, projection carry, release/retire and load-file compile |
| receipt probe on a branch (lane) | 3.5 s | branch 23 ms; settle build 0.06 ms; settle tx 352 ms |
| stamp probe | 1.4 s | 6 × carried-projection at 165–236 ms, each a derivation miss (speculative value) |
| regression body, lane | 43.3 s — **DEFECT >10 s** | fixture ready 15.8–23.4 s; two ordinary turn passes 29–39 s; only 2 SCI acquisitions (605 ms total) inside the drive; stack samples dominated by `seon.schema/projection-from-rows`, `canonical-coll-string` and `projection-rows` (projection derivation) |
| regression body, HEAD | 43.7 s — **DEFECT >10 s** | same fixture and drive |
| `mixed-plan-…` lane | 51.0 s — **DEFECT >10 s** | same fixture and drive class |
| `declaration-body` lane | 31.9 s — **DEFECT >10 s** | same fixture and drive class |
| HEAD-load snapshot JVM | 16.9 s — **DEFECT >10 s** | require 15.3 s, cold source load, classpath cache miss |
| `delimiter-repair-…` lane | 93.8 s — **DEFECT >10 s**, ended in OOM | concurrent foreign test run in the same JVM |

Issue to file (the orchestrator folds lane rows into shared notes): "the turn
integration fixture takes 16–23 s to stand up and two ordinary passes take
29–39 s", with the stack evidence above. This slice adds none of that time;
the lane receipt settlement is 0.06 ms of analysis instead of about 200 ms.

## Commit

One commit, "Receipts assert no program call edges": `src/seon/turn.clj`,
`src/seon/fn.clj`, `test/seon/cluster/turn_test.clj` and this note. Its id is
`f2e6285cc`.

HEAD `f2e6285cc` loads in a fresh JVM. The check ran on a `git archive`
snapshot with `reference-code` and `target/dev-dependency-classes` symlinked
(both unlinked before the snapshot was deleted), using
`clojure -M:test -e "(require 'seon.turn 'seon.fn 'seon.cluster.turn-test)"`.
Require took 15,344 ms and the wall time was 16.9 s. That is a **DEFECT >10 s**
in the cold source-load class
(`docs/seon/issues/a-focused-test-jvm-spends-twenty-seconds-before-its-first-test.md`),
with a classpath-cache miss for the fresh snapshot directory.

RESET NEEDED: no. Default was restored to its published code; it adopts this
commit at the orchestrator's next checkpoint.

## Follow-up (granted `test/seon/turn_test.clj`)

- `test/seon/turn_test.clj`: the two receipt-edge assertions in
  `settlement-keeps-unresolved-call-and-require-names-as-values` now assert that
  the receipt carries no `:seon.fn/calls`. The declaration-row assertion reads
  the pulled edge set as a set; the pull returns a vector. A `namespace-row`
  helper digests the fixture namespace seeds (8 sites; three pull-expectation
  maps were left as plain name maps). Ten bare agent seeds gain the required
  `:seon.agent/branch`, set by one script. Test run with lane `seon.turn` (same
  redef runner, `:test-ns seon.turn-test`): 15 pass, 0 fail, 20.2 s. Before the
  set fix it was 14 pass, 1 fail (24.5 s).
- `seon.cluster.turn-test/agent-evaluations` now reads only turns of agents on
  the fixture cluster's branch (`(registry/cluster-branch "turn-test")`, the
  value `agent-row` writes). The regression uses it: 6 pass (95.3 s under load
  average 16, **DEFECT >10 s**).
- `mixed-plan-publishes-only-the-contracted-function` still fails, now with 0
  scoped evaluations. `tmp/receipts-no-program-edges/mixed-body.clj` shows why,
  with HEAD and lane `seon.turn` behaving identically (45 s and 129 s): the call
  turn evaluates only the `durable` defn (ordinal 0) and closes, so the two
  following forms never run within the test's two passes. The failure predates
  this slice. It belongs to the owner of the per-declaration batch boundary; its
  string symbol lookup is a separate retired assumption.
- Issue filed:
  `docs/seon/issues/turn-integration-fixture-and-two-ordinary-passes-take-forty-seconds.md`.
