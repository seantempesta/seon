---
title: Landed review (Opus), 2026-10-08
type: review
status: active
created: 2026-10-08
---

# Landed review (Opus), 2026-10-08

Scope: the code commits on `refactor/agent-platform` since 2026-10-08 00:00 (HEAD `432138ee6`), docs-only and
task-EDN-only commits skipped. Independent of the Astra review (not read). Measured on default (running
`406bd11d3`, so the commits after it, S6-06a, stopfix-projection, RF-4, B1 and the lane tool among them, are reviewed
from source only) through MCP `eval_clj` as agents `landed-review-opus` and `landed-review-opus-probe`. The rest of
the ~70 code commits were read at diff level only; findings are ranked by severity.

## Broken first

- **RESET NEEDED (restart).** `bin/seon check --test seon.context-test` refuses: "JVM RESTART NEEDED: loaded
  dependencies changed (deps.edn, reference-code/hiccup, reference-code/malli, reference-code/sci)" (diagnostic
  `data/clusters/default/logs/operator-check-20261008T165514.424.edn`). No lane can prove a slice through the gate
  until root restarts default. So finding 2 below could not be run.

## Findings

### 1. HIGH: F4a's test step can replace the evaluated values with a refusal (`09f86c000`)

`src/seon/turn.clj` (HEAD), `seon.turn/step`: `tests (when (step-due? …) (run-proofs cluster agent-id))` runs
*before* `(async/offer! reply …)` in the same `let`. Any throw from `acquire-context!` or `seon.test/run` escapes to
the step's `(catch Throwable escaped …)`, which answers the reply with only a `:seon.turn.loop/refusal`. The eval
request's values are already settled and durable, but the caller never sees them: this breaks "The path is total"
(a later stage's failure goes beside the value and never replaces it). `summary` already has a
`:seon.turn.loop/test-error` arm, but nothing feeds it a thrown failure. The step also has no time bound yet (the
ruling's 10 s dial), so a slow proof holds the reply.

Fix: the Flow step is an entrant boundary, so it may catch here. Wrap the test step so a throw becomes an error map
(`ex-data`, or the one constructor for anything else) and pass it into `summary`'s `:seon.turn.loop/test-error`.
Offer the reply with the values either way. Regression: a proof whose test runner throws still returns the form's
value with the error beside it. (The working tree holds uncommitted F4b edits to this `let` and still has the same
ordering.)

### 2. HIGH (predicted red, not run): S6-06a turns CS-01b's two tests red (`d9789e22e` vs `88bfd19cb`)

`test/seon/context_test.clj` `proof-task` calls `task/assign!` before any deftest carries `:seon/tasks
#{'tq.cs/shape}`. Since S6-06a, `seon.task/assign!` (`src/seon/task.clj:498-517`) refuses `:no-proofs` in that
case. The test ignores what `assign!` returns, so no task is held, the block has no `:seon.context/proofs`, and both
`a-red-proof-shows-expected-actual-and-message-as-data` and
`a-proof-result-before-the-latest-reached-definition-is-pending` fail on `(:seon.context/counts proofs)`. S6-06a
fixed the CS-01a test (`steps/declare-proof!` before assign) but did not name `seon.context-test` among its tests,
although its bodies reach `assign!`. Every assign in today's tests (`turn_test.clj`, `context_test.clj`) also drops
the refusal, against "the harness … surfaces every refusal."

Fix: in `proof-task`, `steps/declare-proof!` (or define the proof deftest) before `assign!`, and assert
`(string? (:seon.task/assignment …))`. Run `seon.context-test` after the restart.

### 3. MEDIUM-HIGH: the cluster base is weighed at about 1/50 of its size and gets evicted (`6191eace4`, `48191071f`)

`src/seon/sci/eval.clj` `base-ctx`: the weigher is `#(* 2048 (count (:functions …)))`, a number nobody measured.
On default, `seon.cache/status` weighs `:seon.sci.eval/base-context` at 11.65 MB. PB-01's own commit message
measured a second base at 624 MB. The cache is full (510-511 MB of 512 MB), and the base has already been evicted
once (misses 2, evictions 1, load 1,396 ms). That breaks the ruling "One shared base per cluster … never rebuilt per
eval request", and the "one configured byte budget" stops describing real memory (live heap 1.36 GB).

Fix: the base is a live host object with one owner per cluster (AGENTS "Live host objects … live in exactly one
roster per scope"). Hold it in the cluster's roster, keyed by the loaded commit, and advance it there. If it stays
in `seon.cache`, weigh it by a measurement taken at derive time.

### 4. MEDIUM: F4a acquires a second agent context on every eval request before knowing whether there are proofs (`09f86c000`)

HEAD `run-proofs` calls `(@cluster-agent-acquire-context! cluster agent-id)` first and reads `task-proofs` second.
Measured on default for a task-less agent, warm: `acquire-context!` 7.5-7.9 ms and **22.8-27.6 MB** allocated per
call; `task-proofs` alone 1.5 ms and 4.9 MB. A plain `(+ 1 2)` eval request costs 67-78 ms and 57-65 MB in total, so
this step is about 40% of its allocation and does nothing. Fix: read `task-proofs` from the cluster connection's
value first, and acquire only when proofs exist (or reuse the execution the pass already holds). Separately, 23 MB
for a cache-hit acquisition is about 10× what it should cost; that needs its own task, since it is not from today.

### 5. MEDIUM: two rules for "a proof is green" (`88bfd19cb` vs `d9789e22e`)

CS-01b adds `seon.context/proof-status`: green when the latest result passed and its reach digest equals the
current one. S6-06a decides "all proofs green" with `seon.test/verified?` (`src/seon/test.clj:1573`), which also
checks test-input digests, source and fixture observation. The block can show `:green` for a proof that `assign!`
treats as not green, and the reverse. They also read proofs from different places: `seon.context/proofs` reads
`task-edges` on the branch only, while F4a's `task-proofs` takes the union of the home and the branch. Fix: one
function in `seon.test` answers a proof's status (`:green :red :pending`) and both callers use it. The proofs
section takes the same home ∪ branch edges as `task-proofs`.

### 6. MEDIUM: check-slots is a dial in name only (`e6d5ef732`)

`script/seon/operator.clj:1701`: `(operator-check-slots {})` is always called with an empty manifest, so it always
answers the shipped default and the configured `:seon.config.operator/check-slots` is never read.
`operator-boot-bound-ms {}` on the same line and at 785/1683 has the same defect, which predates today. The ruling
says "a hardcoded policy number is a defect", and this one is still hardcoded behind a dial. Fix: pass the root's
bootstrap manifest, as `operator-heap-max-bytes` reads `(:seon.config/manifest request)` (line 729). The new helpers
also carry `:any` contracts (`[:=> [:cat :any] [:vector :any]]`), and `operator-check-slots` throws a bare `ex-info`
instead of an error map.

### 7. MEDIUM: an absent dial reads as "no bound" (`9fc503097`, `89583e65a`)

`seon.blob/inline-or-stage` uses `(if (and threshold (> size threshold)) stage inline)`. With no
`:seon.config.eval.result/blob-threshold` fact, any text, however large, is stored inline as a datom. The
compression dials in `seon.blob/compressed-size?` likewise "compress nothing" when absent. AGENTS: "Never read
silence … as health"; turn.clj itself says "absence is never 'no bound'". Fix: a missing threshold refuses by name
(error map naming the dial), as `operator-check-slots` does. Also, `utf8-size` still builds a byte array to measure
(`blob.clj:217-221`), against the Guava ruling (DEPS-1 is pending in root's restart batch).

### 8. MEDIUM: an arming failure is a bare string and a second failure is dropped (`0667ee448`, `c2000f9bb`)

`src/seon/sci/arm.clj:61-69`: `(catch Throwable t (assoc write :seon.batch/arm-error (ex-message t)))`. LR-STALE2
then adds `(update armed :seon.batch/arm-error #(or % (ex-message t)))`. The diagnostic beside the value is a
message string with no type, operation or cause chain, and when both arming and the reinterpretation of inherited
callers fail, the second failure is silently lost. These catches sit in a non-boundary layer (AGENTS allows three
catches). Fix: the arm and reinterpret failures become error maps built by the one constructor, kept as a vector
(`:seon.batch/arm-errors`) or as two named keys, and the batch fold (the boundary) delivers them.

### 9. MEDIUM: S6-06a's `own-task` writes a task that can never be assigned (`d9789e22e`)

`src/my/task.clj:108-126`: when the agent holds no task, `plan!` and `step!` still `task/create!` a
`work-of-<agent>` `:work` task in the shared task home, then call `assign!`. That call now always refuses
`:no-proofs`, so every agent that calls `plan!` with no task leaves one unassignable task behind; `create!` is
idempotent by name, so it is one per agent. Fix: delete the create-and-assign branch. `own-task` returns the
`unassigned` refusal (its fix text already says a held task is needed).

### 10. MEDIUM: LR-CACHEb shares the reach cache only through the agent's own connection; lineage lives in an evictable cache (`ead65e695`)

Measured: a fresh agent branch that ran only `(+ 1 2)` gets a `reach-revision-key` **equal** to main's through its
acquired connection (35 attribute revisions, opening commit recorded). Through `seon.db/branch-database` the same
branch differs from main on all 10 reach attributes: conservative revision = branch head, no attribute revisions,
`recorded-revisions` nil. So readers that open a branch value fresh (merge, write-back, propose, RF-4's
branch-state form) miss the shared cache. The lineage itself is a `seon.cache` entry (`::commit-revisions`, weighed
`(constantly 0)` on lookup): on default it shows 21 evictions and a 43% hit rate, and an evicted entry silently
falls back to per-branch keys. `:seon.test.runner/reach-declarations` shows 0 hits in 9 misses (86 MB held, 2.3 s
load) since boot. Fix: derive the source commit from Datahike's branch metadata in the fork (the commit parents
`versioning.cljc` records) instead of a cache entry, and resolve `branch-database` values through it. Then
re-measure the declaration hit rate.

### 11. LOW-MEDIUM: RF-4 hook (`3c15a3434`)

`bin/seon-hook` `entrance-one`:

- It sends only changed top-level definitions after `(in-ns …)`, never the file's `ns` form. A new `:require` or
  alias in the edit is missing on the branch, and the definitions fail to resolve.
- Up to three root calls of 60 s each, so a blocking edit hook can take 3 minutes.
- It calls `my.test/check` by hand. The ruling is that nobody does: F4 runs reaching tests automatically, so after
  F4b both paths run the tests.
- The value printer's text then goes through `clip-feedback` (300 tokens). That is clipping outside the one printer.
  The mechanism predates today, but RF-4 now routes printer output through it.

Fix: send the `ns` form when it differs. Drop the explicit check once F4b lands. Bound the whole entrance with one
deadline. Let the printer's budget (`seon.print/*budget*`) size the text instead of clipping it.

### 12. LOW: UL-2's commit message overclaims (`406bd11d3`)

"the reify is deleted": the structural registry (`schema.clj:~1298`) still wraps a `reify` inside
`mr/composite-registry`. Also, `composite-registry`'s `-schemas` (`reference-code/malli` `418df441`,
`src/malli/registry.cljc:54-59`) re-merges on every call, where the old code held a `delay`, and it merges in
reverse precedence (defaults win), where the old `-schemas` let references win. No src caller uses `-schemas` today,
so this is harmless now.

### 13. LOW: printed output stored whole as a datom (`d78473124`, P5; known as SR-C)

`(dotimes [i 20000] (println "line" i))`: the reply shows about 4.6 KB with a dig form, but the evaluation stores
`:seon.cluster.eval/output` as a **208,890-character datom**, against the storage ruling (inline ≤ 4,096 bytes,
else a blob). This is ledger row SR-C, not new. Note for SR-C: P5's dig form
`(subs (:seon.cluster.eval/output (seon.db/pull …)) 4660)` re-pulls the database and will break when the key moves
to `output-blob`; the ruling wants plain `clojure.core` forms on the live value.

### 14. LOW: JVM-mode REPL has no `doc`

`(doc seon.profile/summary)` in MCP `mode jvm` fails "Unable to resolve symbol: doc". `clojure.main`'s REPL refers
`clojure.repl` in `user` (`repl-requires`); the JVM entrance does not. Fix: refer `clojure.main/repl-requires` in the
JVM entrance's starting namespace.

## Measured and sound

| Probe (default, warm) | Time | Allocated (all threads) | Judgment |
|---|---|---|---|
| `(+ 1 2)` eval request, `seon.mcp/eval-clj` | 67-78 ms | 57-65 MB | over the 20-40 MB owner number; about 25 MB of it is finding 4 |
| `defn` with contract, eval request | 118-154 ms | 128-138 MB | 3-4× the owner's 20-40 MB per eval request; not attributed here |
| `seon.cluster.agent/acquire-context!` (cache hit) | 7.5-7.9 ms | 22.8-27.6 MB | about 10× what a hit should cost |
| `seon.sci.forgive/segments`, 27,400 chars, 400 forms | 1.8 ms | 1.7 MB | meets the "lightweight forgiveness" ruling |
| same, unbalanced (repair scan) | 2.9 ms | 2.7 MB | meets it |
| 20,000 `println` lines | 92 ms | — | the reply is bounded; storage is finding 13 |

Health at review time (pre-existing, not from today's commits): `seon.schema/call-with-projection-state` mean 50.8
s, max 817 s over 258 calls; `seon.turn/resume-turn` max 13.5 s; 23 errored receipts.
`ad032b3b3` (stopfix-projection) is the claimed fix for the first. Default does not run it yet, so its gain is not
measured here.

Read and found consistent with their row: SR-D (`0c97d0bdd`; `:seon.error/data-edn` and the `:seon.instrument/*`
copies remain, owned by SP-1a), the konserve immutable and LZ4 blob publication (`89583e65a`, measured in its
message), NK-1 (`42740dfe3`, a mechanical rename), B1 write-back against the merged program (`aff834c5a`, red merge
tests filed), the lane tool (`494600ec7`), and the forgiveness stepper (`84a3c5e4d`, `c98ade404`, `4c3be3361`,
measured above).

## Limits

- 14 findings from about 20 commits read closely. The roughly 50 remaining code commits got a diff skim plus a grep
  of today's added lines for `subs`, `take`, `pr-str`, `atom`, `volatile!`, `println` and `truncate`. Hits outside
  the printer were limited to code-building `pr-str` in the hook, the reader's own span `subs`, and the
  `(pr-str contract)` passed as `:seon.fn/spec` in `arm.clj:58`, which prints a live contract to hand it on and
  should pass the form.
- No test was run: the gate refuses until default restarts. Finding 2 is a prediction from source.
- Allocation is summed over all JVM threads during the call; idle noise over 150 ms was 0.06 MB.
- The probe agents `landed-review-opus` (`scratch.lro/lro-inc`) and `landed-review-opus-probe` wrote only on their
  own branches.
