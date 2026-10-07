# defn-cost: what a one-function change costs, and why

Lane `defn-cost`, 2026-10-07, default at da0ae05dd+ (restart 09:2xZ). Probes ran as JVM-mode forms of agent
`defn-cost` driving `seon.mcp/call` for disposable agents `defn-cost-a` and `defn-cost-b` (scratch namespace
`scratch.defncost`), with `seon.profile/begin`/`explain` and allocation from `ThreadMXBean`. JVM-wide allocation
is noisy (other lanes' work and checks shared the JVM; load average 26-42 during checks), so each number below is
a repeat minimum or a per-thread measurement where stated.

## Broken things first

- **Post-defn acquisition rebuilds the whole program, 0.8-1.2 s and ~2 GB.** Fix is in `src/seon/sci/eval.clj`,
  held by lane test-cost: patch `eval-acquisition.patch` beside these notes, for root. Task
  `seon.sci.eval/acquisition-rebuilds-the-whole-program` (`4a738d4f7138`).
- **A trivial pass allocates ~110 MB** (budget 10 MB). Not found: new task
  `seon.turn/a-trivial-pass-allocates-a-hundred-megabytes` (`bf9186de3b28`).
- **The JVM run that root reported (fault 09:19Z, twice)**: a DEFECT, fixed here. See "The 09:19Z fault".
- **A Datahike join on a freshly materialized commit costs ~1 GB**: new task
  `seon.db/a-join-on-a-materialized-commit-costs-a-gigabyte` (`fb221de3aaf7`).
- `seon.schema-test/an-incremental-projection-equals-its-full-build` sits at its 5 s bound under load: it went over
  at HEAD too (5,009 ms, without this change) and passed with it on the next run. Already recorded in the
  `seon.test` task naming it "over its bound".
- `bin/seon check --help` answers an "Unknown option." error map, not help. Recorded here only.

## Before (one agent, per pass)

| stage (agent defn-cost-a/b, SCI unless noted) | ms | MB (JVM-wide) | main armed cost |
|---|---|---|---|
| first pass on a new agent `(+ 1 2)` | 567-2,467 | 1,279-4,665 | `acquire!` → `derive-base-ctx` (whole program) |
| steady `(+ 1 2)` (5 repeats) | 99-113 | 108-113 | 81 `with-declarations`, 41 `q`, settle `transact!` 33-57 |
| `(defn f "x" {:malli/schema [:=> [:cat :int] :int]} [x] x)` | 420-1,059 | 702-1,455 | settle `transact!` 230-358: 3 `load-projection` 113-129, 25 `speculative-projection` 104, `acquired-database?` 87 |
| `(f 1)` | 142-143 | 155-157 | settle 89-93 |
| a second contracted defn | 222-240 | 278-375 | settle 152-171 |
| JVM `(+ 1 2)` right after a defn | 1,210 | 2,388 | `acquire-context!` → `acquire!` → `derive-base-ctx` 1,002, `acquire-program!` 966, 8 `db/q` 813 |
| next JVM `(+ 1 2)` | 121 | 303 | |

The four functions in the assignment:

| function | proportional to | evidence |
|---|---|---|
| `seon.fn/analyzed-files` | the selected files plus their dependents; called only by source publication (`seon.cluster` publication at `analyze-entities`, write-back), never by a REPL pass | absent from every pass profile above; the 11 calls are publications (lane checks, write-back) |
| `seon.sci.eval/call-detecting-program-change` | nothing of its own: an inclusive wrapper around `evaluate` (0.8 us empty) | its time equals the evaluated form's; task `seon.profile/scope-wrappers-report-callback-time-as-work` |
| `seon.schema/call-with-registration-delta` | nothing of its own: a `binding` around `evaluate` in `seon.turn/evaluate-row` | 1-4 ms in a defn pass; the 2.8 s max is a slow form it wrapped |
| `seon.sci.eval/acquire-program!` | the whole program, every time the program identity changes | reads every function row, every namespace (one pull each), and runs `function-rows` on a fresh materialization of the loaded commit |

## What each cost is proportional to

1. **`seon.schema/deleted-predicate-functions` walks the whole declaration population on every projection
   derivation.** A contracted `defn` changes `:seon.fn/spec`, a projection input, so its settlement derives the
   projection three times (the writer's in-transaction value, the speculative report, the commit). Each call
   `tree-seq`s all 3,394 schema forms and 2,872 contracts: 28-30 ms and 82.7 MB per call (per-thread, JVM mode,
   default). `projection-from-entities` already derives everything else from its base by replacement.
2. **`acquire-program!` queries a cold materialization of the loaded commit.** `host-function-rows` calls
   `(function-rows loaded)`, and `loaded-program` materializes the boot commit with `d/commit-as-db` on every
   derivation. Measured on default: `function-rows` on a fresh materialization 777-1,219 ms and 1,864-1,865 MB;
   the same query again on the same value 18 ms / 43 MB. A whole derivation (`base-ctx` on a `d/with` value):
   933 ms, 2,351 MB on the probe thread, of which 8 `db/q` 813 ms. `loaded-digests` already caches the other
   loaded-commit read by `[store commit]`; the rows were the one uncached read. The 475 per-namespace pulls
   cost 122-366 ms / 136-138 MB; `pull-many` gives equal rows in 173 ms / 89 MB.
   The cause underneath: a join over 5,640 entities on a fresh commit value costs 435-493 ms and ~1 GB in
   Datahike, against 7 ms on the connection's value (task `fb221de3aaf7`).
3. **Why SCI passes do not pay 2 but JVM passes and system turns do.** `seon.cluster.agent/acquire-context!`
   acquires through the handle's held base context (`acquire!` on `base`), not the agent's own context. After a
   `defn` the agent's context holds the new program, but the base still holds the old one, so the next
   `acquire-context!` (every MCP JVM run, `mcp/jvm-run`; every refresh system turn, `seon.turn/system-turn`) misses
   the memoized base and derives the whole program again. The incremental form, advancing the held base by the
   changed rows and their reverse closure, is the real fix and is TARGET in `eval.clj` (held); the patch removes
   the dominant 80% of it.

## Fixes

### Landed: `seon.schema/deleted-predicate-functions` examines only what can be deleted (BEHAVIOR: EQUIVALENT)

With a base projection, the candidates are the predicates the base binds plus those named by definitions not
`identical?` to the base's own objects; only when one of those is deleted does it walk the whole population as
before. Precondition for equivalence: every predicate the detector finds in an unchanged definition is bound in
the base. It holds for every constructor (`predicate-bindings` and the incremental path bind each predicate
symbol of the population; a missing binding refuses compilation), and on default's population the detector's
101 symbols are all among the base's 101 keys, with no `[:fn sym]` map entry outside a schema vector.

Inline measurement on default's real projection (one changed form and one changed contract): old walk 27.5-29.7 ms
and 82.7 MB per call; the new check 1.9 ms and 1.7 MB, same answer (`{}`). Per contracted defn (3 derivations):
about 80 ms and 245 MB less. Not measured as a whole pass after landing: default runs the committed archive, and
the change reaches it at root's next restart.

Regression: `seon.schema-test/a-stored-declaration-naming-a-deleted-predicate-stays-readable` is now a table over
no base, a changed definition naming the deleted predicate, a base binding it, and nothing deleted. No work-count
test: the walk is not observable without instrumenting it.

### Landed: a settlement commits under its own connection's custody (BEHAVIOR CHANGE, regression added)

See below.

### Patch for root: `src/seon/sci/eval.clj` (held by test-cost)

`docs/research/agent-platform/2026-10-07/defn-cost/eval-acquisition.patch` (applies to the working tree's
`eval.clj`, checked with `git apply --check`):

- `loaded-function-rows`: `function-rows` of a loaded commit through `seon.cache` by `[store-identity commit-id]`,
  exactly as `loaded-digests`; weighed by its source text. Contract checked on default's rows:
  `[:vector [:tuple :qualified-symbol :string :symbol :keyword :boolean]]` validates all 5,646.
- `acquire-program!` reads all namespace rows with one `pull-many` instead of one pull each; equal rows verified on
  default.

Expected effect, from the parts measured: a derivation after a program change drops from ~933 ms / 2.35 GB to
about 150 ms / 450 MB (the remaining whole-program reads), so a JVM run after a `defn` from ~1.2 s to ~0.3 s.
Not proven installed: needs the lane holding `eval.clj` (or root) to apply it and run
`seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle` and the acquisition
tests. The remaining 450 MB is the TARGET incremental acquisition (task `4a738d4f7138`).

## The 09:19Z fault: a defect, fixed

My probe ran `seon.mcp/call` for agent `defn-cost-a` in JVM mode from inside agent `defn-cost`'s JVM-mode
evaluation. Reproduced with one form (agent `defn-cost`, JVM mode):

    (seon.mcp/call (clojure.data.json/write-str {"name" "eval_clj" "arguments" {"code" "(+ 1 2)" "agent" "defn-cost-a" "mode" "jvm"}}))

It answered `3`, then "The run was evaluated but not recorded": the writer refused "This write names branch
:agent-1048bc748e6e, which is not the writing cluster's branch :agent-a664b3b890ee" and recorded a fault.

Classification: not a cross-branch write of mine. The inner run's settlement writes its own agent's branch
through its own world's writer; `seon.turn.batch/settle` runs outside `world/with-world`, so the writer's custody
check (`seon.db/foreign-connection-error`) compared that write with the calling thread's ambient `seon.db/*conn*`,
the outer evaluation's branch. An entrant inherited its caller's custody. SCI-mode nested runs did not fail because
they settle on the agent's Flow proc thread. Fix: `settle` commits under `db/call-with-custody` of its settlement
connection (a test agent's settlement connection is the requester's branch, so the world's own connection would be
wrong there). Task `seon.turn.batch/an-entrant-inside-another-custody-cannot-record` (`3d651884784e`).
Regression `seon.run-test/a-settlement-inside-another-custody-records-on-its-own-branch` (a JVM batch settled
from inside a `with-database` custody). Its first form, through `seon.mcp/call` in `seon.mcp-entrance-test`, was
red at HEAD (check run 03f64c260174) and green with the change (827777c2e577) but exceeded the 5 s bound under
load (6.2 s, load average 48), so it moved to the fold's own entrance without the flow launcher; green in check run
3fbc3819955d (3 tests, 5,952 ms).

## Out of my files, recorded

- The steady 110 MB per trivial pass: JFR samples (10 passes) put most bytes in clojure.core collection building
  (`transient`/`into` of hash maps and sets, `clojure.core/supers` about 14 MB per pass), but JFR's stack walk
  returned 5-frame stacks, so the callers are unresolved. Task `bf9186de3b28` names the next step (deeper stack
  depth in a test JVM). The only `supers` caller in `src` is `seon.instrument/primitive-fn?`; it did not appear in
  a pass profile.
- `acquired-database?` cost 87 ms in one defn settlement (`install-evaluated-rows!`), `eval.clj`; not traced.

## Design check

- Held: `projection-from-entities` already derived the projection from its base by replacement; only the
  deleted-predicate walk ignored the base. The fix reuses the base's own predicate bindings and the parse step's
  object reuse (`identical?`), no new state or cache.
- Held: the acquisition patch composes the existing `seon.cache` kind pattern of `loaded-digests` and the existing
  `db/pull-many`. It does not make acquisition proportional to the change; that is the incremental base, a
  semantic change to `acquire!` that belongs to the `eval.clj` owner (Opus-level, REPL-proven).
- Awkward: three projection derivations per contracted defn remain, because function contracts are projection
  inputs (the "contracts-out" slice noted in `seon.schema/schema-scoped-admissions`). Removing contracts from the
  projection inputs would remove all three.
- The one happy path: every change here serves "record" (settlement) and the acquisition before "eval"; nothing
  is added beside them.

## Targets

One-line defn pass under 300 ms / 30 MB, next pass under 150 ms / 10 MB: NOT met. Measured before: 420-684 ms /
0.7-1.5 GB and 80-140 ms / ~110 MB. This lane removes about 80 ms / 245 MB per contracted defn (landed) and about
0.8 s / 1.9 GB per post-change acquisition (patch). The rest is per-pass settlement work (task `bf9186de3b28`)
and incremental acquisition (task `4a738d4f7138`).

Default needs a restart to load the landed changes (root's batched restart); no reset needed.
Branches created: agents `defn-cost-a`, `defn-cost-b` (retired below).
