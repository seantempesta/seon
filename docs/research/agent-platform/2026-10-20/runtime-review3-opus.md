---
title: "Runtime review 3 (Opus): HEAD 06614f6a8, the closed runtime after P7"
type: research
status: review, 2026-10-20
created: 2026-10-20
---

# Runtime review 3 (Opus): HEAD `06614f6a8`

Lane review3-opus (Opus, independent; wrote none of the code under review; did not read the other reviewer's note),
2026-10-20. Question: is it safe for root to restart default onto HEAD? Owner requirement: a branch's change is live
in its whole runtime, exact for every mix of branches and order of evaluation, independent of main's compiled JVM, and
cheap. Inputs read: [branch-runtime](../2026-10-19/branch-runtime.md), [runtime-review](../2026-10-19/runtime-review.md)
(F1-F7), [runtime-rereview](../2026-10-19/runtime-rereview.md) (R1-R8), [closed-runtime-hybrid](../2026-10-19/closed-runtime-hybrid.md)
(P0-P7 and their status), and the commits since `2416e7476` touching `src/seon/sci/`, `src/seon/cache.clj`,
`src/seon/schema*` and `src/seon/db.clj`.

Method. Every probe ran in a throwaway check JVM (`bin/seon check --at SHA`) on a dangling commit (`git commit-tree`
over a private index) of HEAD, or of `716cc7527` for the cost base, plus one probe file or one mutated
`src/seon/sci/eval.clj`. No SCI request was made on default and default was neither restarted nor reloaded; the only
reads of default were `runtime_status` and `bin/seon status`. Probe sources and raw results are in
`tmp/runtime-review3/` (`probe.clj.txt`, `probe2.clj.txt`, `cost.clj.txt`, `eval.*.clj` mutants, `out/*.edn`,
`check-*.log`, `shas.txt`); `tmp/` is gitignored, so every number used below is quoted here.

## Verdict: SAFE to restart default onto HEAD; NOT yet the exact runtime it is described as

Safe, in the sense the question asks:

- Every earlier blocker's reproduction answers as required on HEAD, except F2 (the stated, ruled exception) and the
  predicate gap (filed with its red proof, pre-existing, ruled acceptable by root as mechanism 2).
- Nothing hung, crashed or ran away: 24 test vars of the runtime's own proofs and my probes ran in 18.2 s in one
  check JVM; every probe answered in seconds; no request exceeded the bounds; no new error class appeared.
- Default runs `a92ba5b94`, the revert of the exact wiring, where no branch change reaches a compiled caller at all.
  Every wrong answer found below is a case where HEAD answers main's definition (or serves one runtime's derivation
  to another); on `a92ba5b94` the same cases, and many more, answer main's. HEAD is strictly closer to the
  requirement and introduces no regression of what default does today.
- The cost gate passes on its recorded baseline (below), with the caveat that a same-run parent puts the hub sequence
  at 1.32x, just past the 1.3x bar.

Not exact: one reproduced violation of the requirement remains in the R1 class (N1 below), the predicate gap stands,
one latent class (N3) exists by SCI's design, and two mechanisms are not held by their proofs (step 3). Root should
restart knowing the runtime is "exact except N1, N3, F2 and predicates", and should not relax any merge-time check
(for example the deferred platform tests) on the strength of exactness until N1 is fixed.

## Blockers to the "exact" claim, ranked (none blocks the restart itself)

1. **N1. A compiled function caching a derivation it is handed serves it across runtimes** (R1's class, through a
   callback). Reproduced. Filed: `docs/seon/issues/a-compiled-function-caching-a-handed-derivation-serves-it-across-runtimes.md`.
2. **Predicates** (`seon.schema/a-branch-changed-predicate-is-seen-only-after-merge`): still red, as filed. Judged below.
3. **Proofs that do not hold their mechanism**: the macro analysis key's reached changes and the `defonce` exclusion
   survive mutation (step 3). The first is a real defect a mutation introduces silently. Filed:
   `docs/seon/issues/the-runtime-macro-and-defonce-proofs-survive-mutations-of-their-mechanism.md`.

## Step 1: earlier blockers re-run on HEAD

One check JVM, `a8ca24ee3` (HEAD plus `test/seon/runtime_review3_probe_test.clj`), with every committed row that now
carries an earlier finding: run `e37685a6fe98`, 24 vars executed, 68 pass, 1 fail, 18,248 ms. The one red is the
filed predicate proof.

| Finding | Form | Expected | HEAD | Status |
|---|---|---|---|---|
| F1 value definition | `a-branch-change-is-live-through-a-value-holding-functions` (branch's `missing-models` through `families`) | `[{... "BRANCH"}]` | green | fixed |
| F2 host-bound callee | branch `seon.ref/words` -> `["BRANCH"]`; `#'seon.ref/parsed` cites `seon.db/q` | `false` (fresh) | `true` (main's); `words` itself `["BRANCH"]` | unchanged, the stated exception (R4 deleted the report, `c23e1f807`) |
| F3 macro, database path | `a-branch-changed-macro-is-expanded-by-its-callers` | 22 cold and warm, main 3 | green | fixed |
| F3 macro, MCP path | `an-agent-change-is-live-through-its-macro-callers-and-keeps-namespace-rows` | 33 | green | fixed |
| F3 macro, `seon.test-support/evaluate` | `(in-ns 'seon.profile) (defmacro bucket-of [elapsed] 33)` then `(seon.profile/bucket 5)` | 33 | **3**, `bucket` compiled | unchanged; test-only entrance (it never runs `seon.sci.arm/arm!`), noted below |
| F4 hang | the two `rr-probe` defns, same row | answers | green | fixed |
| F4b own-class import | same row, stored imports | unchanged | green | fixed |
| F5 half-bound | code reading of `seon.sci.eval/bind-analysed!` | | `produced-roots` builds every root on a dropped fork before the one `doseq` of `sci/bind-root!` | fixed |
| F6/R6 differential test | `seon.sci.runtime-test` (all 8 vars) | green | green | drives `bind-analysed!`; see step 3 |
| F7/R1 cache key, either order | `a-branch-derivation-is-keyed-by-its-runtime-in-either-order` | `[777 main]`, `[main 777]` | green | fixed for closed and open derivers; **N1 is its residual** |
| R1 every entrance | `a-runtime-derivation-is-keyed-by-its-own-program-through-every-entrance` (overlay, continuation, fork) | | green | fixed |
| R2 escapes | `an-agent-derivation-is-keyed-by-its-runtime-wherever-it-runs` (direct, closure, lazy, thread, future) | no main-key entry | green | fixed |
| R3 macro reads value | both `...-value-a-macro-reads-...` rows (database path and MCP) | 2 | green | fixed |
| R4 host-bound report | deleted with every reader | | | resolved by deletion (F2 stays) |
| R5 launch defonces | `a-changed-runtime-keeps-the-process-launch-observations` | identical to the JVM's | green | holds, but its proof does not hold the rule (step 3) |
| R6 M2 two-step chain | `a-child-derivation-holds-its-own-change-of-its-parents-helper`, `a-derivation-is-keyed-by-its-runtimes-whole-delta` | | green | fixed |
| R7 open value captured once | `an-open-value-holds-the-functions-of-its-runtime` | | green | fixed |
| R8 reload loads the cached class | `ef8161ff8` (launch loads from source) | | issue `fixed-pending-default-reload` | takes effect on this restart |
| Predicate gap | `a-branch-changed-predicate-decides-its-branch-projection` | `false` on the branch | `true` | open, filed, held for mechanism 1 |

The issue files of R3, R4, R5 and R6 (`a-compiled-macro-expands-with-mains-values-on-a-branch.md`,
`the-host-bound-report-has-no-consumer-and-names-the-platform-on-every-change.md`,
`a-changed-runtime-re-evaluates-launch-defonces-from-the-disk.md`,
`the-differential-runtime-test-misses-a-rekey-defect-and-never-drives-arm.md`) and R1/R2's
`the-branch-runtime-cache-key-is-a-dynamic-binding-so-derivations-still-cross.md` still say `status: open`
although their commits landed; their owner should close them (this lane does not edit them).

## Step 2: new failures of the requirement

### N1 (reproduced). `seon.health/derived` keys a branch's handed derivation by main's identity

Probe `n1-callback-crosses-a-compiled-lookup` (`out/n1.edn`). A branch replaces `seon.bootstrap/sections` with
`[database text] []`; on HEAD's real program `seon.bootstrap/groups` runs analysed on the branch
(`groups-compiled-on-branch? false`) and calls `seon.health/derived` with `#(sections % text)`;
`seon.health/derived` is closed (`derived-closed? true`) and stays compiled on the branch
(`derived-compiled-on-branch? true`). Same committed branch database value (`committed? true`), kinds
`:seon.health/derived` and `:seon.health/reads` evicted before each order; main answers 8 groups.

| Order | branch `(count (:seon.help/groups (seon.bootstrap/help-value db)))` | compiled, same value | expected |
|---|---|---|---|
| branch first | 0 | **0** | 0 / 8 |
| compiled first | **8** | 8 | 0 / 8 |

Root cause: whether a lookup is keyed by the runtime is decided by whether its caller runs analysed, and a closed
function's compiled status by its closed reach over `:seon.fn/calls`, which cannot see code handed in as an argument.
The hybrid's rule "a callback must never cross a cache lookup" was applied to `seon.db/content-projection` and is
enforced nowhere. Census (edamame over `src`, functions holding a lookup whose contract takes a function):
`seon.health/derived` (every problems family through `seon.health/problems-unit`, help groups, `::calls`, `::tasks`,
`::function-files`, `::untested`, `::regressions`), `seon.embed/index` and `similar-now` (`request`),
`seon.schema/with-bound-predicates`. Scope of harm: the same cases R1 named (compiled code reading a branch's database
value: root's health of a branch, context assembly, the reply pipeline); Datahike's `connection-id` holds the branch,
so a branch's and main's own database values never share these keys. No reach into the done rule or merge was found.

### N2 (measured, cost). Values evaluated again on a changed runtime

Probe `n2-value-census` (`out/n2.edn`): the program has 410 value rows, 7 macro rows, 4,491 function rows. One changed
leaf (`seon.print/literal`): `base-ctx` 751 ms, 884 analysed, **12** values evaluated (R5's 72 are gone; all 12 are open,
11 of them `(def x (delay (requiring-resolve ...)))` seams or generators, plus `seon.problems/families`), 6,049
compiled. Continuing it with a second change (`seon.ref/words`, `acquire!`): 103 ms, 27 more analysed, the 12 open
values evaluated again (24 in all). Proportional to what changed plus the open values; none holds process state.

### N3 (reproduced, latent). A branch's closure called under another bound context answers that context's definitions

Probe `p-lazy-value-escapes-to-another-context` (`out/p-lazy.edn`). On a branch where
`(seon.print/form-text '(a 1))` is `"(BRANCH BRANCH)"`, a lazy sequence and a closure made on the branch answer:
under the branch's context `("(BRANCH BRANCH)")`; on a raw thread `("(BRANCH BRANCH)")`; under main's context bound
on the thread **`("(a 1)")`** and **`"(a 1)"`**. This is the SCI fork's stated rule (`sci.impl.fns/in-defining-ctx`:
"a context bound on the calling thread still wins"). No installed entrance was found that realizes one runtime's
value under another's context; candidates are the HTML render of a live result in the viewer's context and host
callbacks an agent registers. Filed: `docs/seon/issues/a-branch-closure-called-under-another-context-answers-that-contexts-definitions.md`.

### Supported (no failure found)

- **Two branches, a continuation, a fork of a fork**: the every-entrance row and the two-branch macro probe
  (`p-two-branches-change-a-macro-value`: buckets 3 then 5 on one connection answer 2 and 3) are exact.
- **Host dispatch**: a branch's `(defmethod print-method java.util.UUID ...)` is refused by SCI
  (`SecurityException`, also through `support/evaluate`), and `extend-protocol clojure.core.protocols/Datafiable`
  fails; the JVM's `pr-str` and `datafy` are unchanged afterwards (`out/p-host-dispatch.edn`). No branch leaks into
  main's compiled dispatch. `src` holds 5 top-level `defprotocol`/`defmulti`/`defmethod`/`defrecord`/`deftype` forms,
  as before.
- **Threads and lazy values of agent code**: R2's row green (the fork's `in-defining-ctx` binds the defining context on
  a context-less thread).
- **Cache eviction** (`analyses-are-evicted-from-a-full-cache-and-analysed-again.md`): keys are exact, so eviction is
  re-analysis cost, never a wrong answer; nothing found that compares analysed roots by identity across a re-analysis
  in a way that changes an answer. Default's cache sits at 507.4 of 512 MB (`runtime_status`), so after the restart
  analyses will be evicted and redone routinely; that is the issue's cost, not a correctness risk.
- **Hang, crash, OOM**: none observed. Allocation per changed runtime is bounded by the reverse closure analysed
  (about 18 KB retained per analysis, shared by content).
- **Boot**: check JVMs boot HEAD in 40-52 s ready (load-dependent; the rereview's were 41-47 s), so the class-cache
  removal of `ef8161ff8` did not visibly slow a cold boot of a check JVM. Default's own restart time is not measured.

### The predicate gap: acceptable to restart with

A branch that changes one of the 69 SCI-runnable `src` predicates its schema forms name validates with main's
definition through the projection its database carries, until merge. It is not a regression: the proof was red when
filed (`e8aeac94d`) on the HEAD before mechanism 2 (default's `a92ba5b94` was not probed for it). Consequence:
a branch's own tests of a changed predicate's contract behavior are judged with main's predicate, so a merge can
land a predicate change whose effect on contracts was never exercised; root's post-merge run on main is then the first
to see it. Acceptable for a restart, provided root keeps the post-merge run and the gap's task stays red.

### Noted, not blocking

- `seon.test-support/evaluate` (F3's third row) keeps `seon.profile/bucket` compiled after an agent-style
  `defmacro`: the helper evaluates without `seon.sci.arm/arm!`, so tests that use it to judge an agent change see main's
  answer. Test fidelity only; MCP is exact.

## Step 3: do the proofs hold the mechanism?

Each a dangling commit of HEAD with one edit of `src/seon/sci/eval.clj`, run with the proofs that claim it.

| Mutation | Proofs run | Result |
|---|---|---|
| mb: macros never due (`:macro false`) | both macro-value rows, changed-macro row | **red** (2 vars) |
| md: an open value due only when unbound (R7 reintroduced) | `seon.sci.runtime-test`, value-holding row | **red** (`a-composed-runtime-...`, `an-open-value-holds-...`: 1044 vs 1034) |
| me: due roots produced in name order, not dependency order | `seon.sci.runtime-test`, macro-value row | **red** (macro-value row: 3 for 2) |
| mh: key is the call's own changes, not the whole delta | `seon.sci.runtime-test`, every-entrance row | **red** (`a-derivation-is-keyed-by-its-runtimes-whole-delta`) |
| mg: macro analysis key without the macro's reached changes | both macro-value rows | **green** |
| mc: a `defonce` gets a value row | launch-observations row | **green** |

mg is a real defect the proofs miss: probe `p-two-branches-change-a-macro-value` on HEAD answers `[2 3]`; on mg
(`cd379497e`) `[2 2]` (the second branch served the first's analysis of `bucket`). The committed row's assertion
"main's analysis is not the branch's" cannot see it because main runs `bucket` compiled and never reads the analysed
cache. mc survives because the row's change reaches neither launch observation and an SCI `defonce` of a bound Var is a
no-op, so the exclusion is either redundant or untested. The hybrid's own mutations (entry binding, M2's class, M5)
were not re-run here; mh and md cover two of them and went red.

## Step 4: cost, HEAD against `716cc7527`

`seon.cost-estimate-test/measured` once per JVM through `tmp/runtime-review3/cost.clj.txt` (HEAD plus probe
`2b2450275`; `716cc7527` plus probe and HEAD's `cost_estimate_test.clj`, `10928dd49`), two pairs run back to back,
one check slot, no other check running. MB allocated / calls (reads where they moved):

| Row | 716cc7527 (pair 1, 2) | HEAD (pair 1, 2) | ratio MB (calls) |
|---|---|---|---|
| `:hub-change-sequence` (the gate) | 3,726 / 640,300; 3,710 / 638,414; reads 5,830 | 4,909 / 747,285; 4,916 / 754,533; reads 6,127 | **1.32x** (1.17-1.18x); reads 1.05x |
| `:hub-behavior-change` | 908 / 198,468; 898 / 198,265 | 1,736 / 276,746; 1,745 / 278,980 | 1.91-1.94x (1.39-1.41x), the recorded overrun |
| `:seon-db-nine-forms` | 982 / 106,361; 980 / 106,425; reads 1,563 | 1,001 / 112,487; 1,009 / 113,253; reads 1,845 | 1.02-1.03x (1.06x); reads 1.18x |
| `:unchanged-request` | 18 / 6,570; 18 / 6,507 | 20 / 7,007; 21 / 7,177 | 1.11-1.17x (1.07-1.10x) |
| `:similar-by-name-warm` | 27 / 7,990; 26 / 7,930 | 30 / 8,859; 31 / 9,026 | 1.11-1.19x (1.11-1.14x) |
| `:current-state-with-task` | 209 / 38,162; 206 / 38,012 | 223 / 40,046; 224 / 40,195 | 1.07-1.09x (1.05-1.06x) |
| `:fresh-agent-first-request` | 55 / 7,609; 53 / 7,546 | 65 / 8,064; 67 / 8,234 | 1.18-1.26x (1.06-1.09x) |
| `:new-def` | 92 / 19,445; 92 / 19,321 | 115 / 20,602; 116 / 20,834 | 1.25-1.26x (1.06-1.08x) |
| `:redefine-one-form` | 330 / 50,094; 327 / 49,911 | 409 / 55,913; 419 / 56,438 | 1.24-1.28x (1.12-1.13x) |
| `:reuse-one-unchanged-test` | 29 / 7,984; 29 / 7,914 | 32 / 8,542; 34 / 8,691 | 1.10-1.17x (1.07-1.10x) |
| `:merge-one-function` | 121 / 19,800; 119 / 19,652 | 131 / 20,898; 133 / 21,208 | 1.08-1.12x (1.06-1.08x) |
| `:hub-docstring-change` | 254 / 58,970; 249 / 58,674 | 273 / 59,825; 276 / 60,180 | 1.07-1.11x (1.01-1.03x) |
| `:cold-stale-merge-members` | 4,322 / 823,882; 4,282 | 4,388 / 845,032; 4,436 | 1.02-1.04x (1.03x) |
| `:unresolved-symbol-request` | 34 / 14,550; 33 / 14,490 | 36 / 15,146; 37 / 15,322 | 1.06-1.12x (1.04-1.06x) |
| `:unmap-one-def` | 91; 102 | 142; 103 | noisy (1.01-1.56x), calls 1.06-1.07x |

- **The gate passes on its recorded denominator, not on the parent measured beside it.** `closed-runtime-rows-...`
  bounds the sequence at 5,053 MB, 1.3x of the 3,886.9 MB recorded for `716cc7527`. Measured now, `716cc7527` runs the
  same sequence in 3,710-3,726 MB, so HEAD is 1.32x of its parent in the same run, past the owner's 1.3x; calls are
  1.17-1.18x, within it. HEAD's 4,909-4,916 MB leaves 2.7% headroom against the recorded bound. AGENTS asks a cost
  proof to compare parent and change in the same run; the gate does not.
- **Ordinary non-branch work got slower.** These rows run on an agent whose branch already differs from main (the
  program-change passes come first), so they measure ordinary requests on a changed runtime: 5-14% more calls and
  7-26% more allocation per ordinary request (`:unchanged-request` +7-10% calls; `:new-def` and `:redefine-one-form`
  +24-28% MB). The rereview measured +5% calls on the similar row; this is wider. Each is within its table estimate or
  accepted overrun (the table test was not the question here); none is near 10x of its work. Main's own requests
  (no changed runtime) were not measured separately.

## Changed paths

- `docs/research/agent-platform/2026-10-20/runtime-review3-opus.md` (this note)
- `docs/seon/issues/a-compiled-function-caching-a-handed-derivation-serves-it-across-runtimes.md` (N1)
- `docs/seon/issues/a-branch-closure-called-under-another-context-answers-that-contexts-definitions.md` (N3)
- `docs/seon/issues/the-runtime-macro-and-defonce-proofs-survive-mutations-of-their-mechanism.md` (step 3)

## Dangling commits (HEAD `06614f6a8` or `716cc7527` plus the named file)

| Use | Commit |
|---|---|
| probe 1 (F2, F3 evaluate path, N1, N2) with the committed rows | `a8ca24ee3` |
| probe 2 (N3, host dispatch, two branches) | `f5326c87b`; on mg `cd379497e` |
| mutants mb, mc, md, me, mg, mh | `af31d5058`, `55439192e`, `8071fd5d1`, `b1bb593f8`, `37a6cc014`, `886098afb` |
| cost probe HEAD / `716cc7527` | `2b2450275` / `10928dd49` |
