---
title: "Runtime re-review: 85299376b, f06474e75 and the arm-gap patch"
type: research
status: review, 2026-10-19
created: 2026-10-19
---

# Runtime re-review: 85299376b, f06474e75 and the arm-gap patch

Lane runtime-rereview (Opus, independent; wrote none of the code under review), 2026-10-19. Scope: `85299376b`
(runtime-fix), `f06474e75` (F7 option 2), `3ab155566` and its revert `3a34b1d3f`, and the held
`runtime-f7b-arm-gap.patch`, against the owner's requirement: "Trivial continuations of an existing env or doing a
branch are supposed to always be cheap and correct and not sometimes broken based on what is on main … It shouldn't
matter how they are mixed it should just work … make sure this is done right." Design and history:
[branch-runtime](branch-runtime.md); the first review: [runtime-review](runtime-review.md) (`e890511a5`).

Method. Every probe ran in a throwaway check JVM (`bin/seon check --at SHA`), never on default, and no SCI request was
made on default. Each candidate is a dangling commit (`git commit-tree` over a private index) of `d1f338b09` (the HEAD
when the lane started; `e7af24f14`, committed meanwhile, touches only `seon.ref`) plus one probe file or one mutated
file; HEAD, the shared index and the working tree were untouched. Probe sources and raw results are under
`tmp/runtime-rereview/` (`probe.clj.txt`, `probe2.clj.txt`, `probe3.clj.txt`, `cost.clj.txt`, `out/*.edn`, the
`check-*.log` files, and `shas.txt` naming every dangling commit). Because `tmp/` is gitignored, every number below is
quoted in full.

## Verdict: NOT SAFE TO RESTART ONTO

The fixes are real: the hang (F4) is gone, value definitions (F1) and changed macros (F3) answer the branch's
definitions on both paths, analysis is complete before anything binds (F5), and the cost stays within 1.14x of the
pre-runtime HEAD. But the owner's bar is "always correct, however mixed", and the runtime still answers by what main
holds or by which runtime ran first in five reproduced ways. Two of them reach root: a branch's code decides what
compiled (main's) code is served for that branch's database, which is what root's done rule and merge read.

Blockers, ranked:

1. **B1. Derivations still cross between a branch and main, in both directions** (R1, R2). The 50 open `seon.cache`
   callers keep main's key, reproduced with `seon.config/effective`: whichever runtime derives first decides the
   other's answer. And the `seon.sci.runtime/reaching` key is a dynamic binding, so anything the wrapped call returns
   unrealized (a closure, a lazy sequence) or hands to a raw thread derives under main's key: reproduced through MCP.
2. **B2. A compiled macro expands with main's values on every branch, and nothing names it** (R3): a branch that
   changes `seon.profile/buckets` gets main's `(seon.profile/bucket 5)`, on the database path and through MCP.
3. **B3. The host-bound report is unread and unreadable** (R4): `seon.sci.eval/host-bound-reaching` has no consumer
   (the done rule never calls it), the batch note never reaches the reply (filed), and every ordinary change names
   26-38 host-bound functions, most of them the platform's entrants and test fixtures, so it could not guide a done
   rule as it stands. F2's wrong answer is unchanged.
4. **B4. The proofs do not hold the binder** (R6): a mutation that drops "bound again to key what it newly reaches"
   survives `seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers`, while a deterministic
   two-step chain shows it serves a child its parent's derivation. The generator never drives `seon.sci.arm/arm!`,
   `fork-cluster-ctx` or `derive-overlay-ctx`, never changes a namespace's resolution, and its reach index is still
   built from the map the program is generated from.

Not blocking a restart, but each a filed defect: the re-evaluated launch values (R5), open values evaluated once
(R7), and the class-cache reload no-op behind the arm-gap red (R8), which is a platform defect of its own.

What would make it safe, simplest first: make the cache key a property of the deriving definition instead of a dynamic
binding around it (R1/R2: key by the analysed function's own reach where the lookup's derive-fn is created, so a
closure carries its key), give compiled macros the reach rule host-bound functions get or run program macros analysed
on a changed runtime (R3), and add R1-R3 and the M2 two-step chain as rows of the differential test driven through the
real `arm!`/overlay entrances (R6).

## The first review's reproductions, re-run on HEAD

All in one throwaway check JVM, `fdb91aaa1` (HEAD `d1f338b09` plus `test/seon/runtime_rereview_probe_test.clj`),
9 tests, 18.4 s, `tmp/runtime-rereview/out/`.

| First review | Form (unchanged from `tmp/runtime-review/probe.clj.txt`) | 011edf42f | HEAD | Status |
|---|---|---|---|---|
| F1 value definition | branch replaces `seon.problems/missing-models`; `(:seon.problems/missing-models (seon.problems/problems db {}))` | `nil` (main's) | `[{:seon.config.ai/model "BRANCH"}]` | **fixed** (`families` is open, so it is evaluated on the first changed runtime) |
| F2 host-bound callee | branch replaces `seon.ref/words`; `#'seon.ref/parsed` cites `seon.db/q` | `true` (main's) | `true` (main's); `::host-bound-reached` names `seon.ref/parsed` -> `#{seon.ref/words}` | **named, still main's answer**; see R4 |
| F3 macro, database path | branch replaces `bucket-of` with 22; `(seon.profile/bucket 5)` | 3 | 22 warm, 22 cold; main 3 before and after | **fixed** |
| F3 macro, agent path through MCP | `seon.sci.eval-test/an-agent-change-is-live-through-its-macro-callers-and-keeps-namespace-rows` | not observed (F4) | green (run `2661378489e4`) | **fixed** |
| F3 macro, `test-support/evaluate` | `(in-ns 'seon.profile) (defmacro bucket-of [elapsed] 33)` then `(seon.profile/bucket 5)` | 3 | 3, `bucket` still compiled | unchanged; its docstring says nothing settles there (cause not isolated); only tests use it |
| F4 hang | the two `rr-probe` `defn`s through MCP | no answer in 120 s | 617 ms and 209 ms | **fixed** |
| F4b own-class import | stored `:seon.ns/imports` of `seon.cluster.agent`, `seon.profile` | not observed | unchanged (9 and 9) | **fixed** |
| F5 half-bound | code reading of `seon.sci.eval/bind-analysed!` | | `functions` and `values` are realized with `into {}` before the one `doseq` of `sci/bind-root!` | **fixed** |
| F6 differential test | | copy of the binder | drives `#'seon.sci.eval/bind-analysed!` | **partly fixed**; see R6 |
| F7 cache keys | | | option 2 landed; residual and escapes reproduced | see R1, R2 |

## Findings, ranked

### R1 (blocker). The 50 open `seon.cache` callers serve a branch main's derivation, and main the branch's

Reproduction (`residual-open-cache-caller`, `out/residual-effective.edn`). A branch replaces
`seon.config/effective-in`'s answer with `(assoc config-effective :seon.config.agent/max-running 777)`; `effective` and
`effective-in` are both open (`seon.sci.runtime/reach-index`) and `effective` runs analysed on the branch. Both runtimes
read the same branch database value `database`, cluster `"default"`:

| Order | branch runtime `(:seon.config.agent/max-running (seon.config/effective database "default"))` | compiled `seon.config/effective`, same arguments | fresh answers |
|---|---|---|---|
| branch first | 777 | **777** | 777 / main's 4 |
| compiled first (cache kind evicted between) | **4** | 4 | 777 / main's 4 |

The branch's `effective-in` called directly answers 777, so the branch definition is live; only the derivation key
decides. Compiled code reading a branch's database value is root's done rule, merge and the reply pipeline; the eval
request's own compiled reads at its start (the second row) are why, within one request, the branch usually sees main's
answer. Root cause: `seon.cache/reached-key` keys by `seon.cache/*reached*`, which only closed analysed functions bind
(`seon.sci.runtime/reached-changes` skips open names). The note's residual section says the cross-branch case "needs
a branch change to the deriving code itself": that is exactly the case reproduced, with one change to a private helper.

Scope: 50 functions (the list is in the note), among them `seon.test.runner/tests-reaching`, `reach-graph`,
`reached-graph`, `current-results` and `seon.cluster.source/merge-base`, `members-of`, `publication`. If a branch's
analysed code derives one of those first for its own database value, root's compiled done rule and merge read that
derivation. Not reproduced for the merge gate itself (time box); it is the same mechanism.

### R2 (blocker). The `reaching` key is dynamic scope, so a closure, a lazy sequence or a raw thread escapes it

Reproduction (`reaching-escape`, `out/reaching-escape.edn`), through the real MCP path (`with-running-agent`). The
agent defines five functions in `seon.batch`, each deriving `(* 10 x)` through
`(seon.cache/lookup :rr/KIND [x] (constantly 1) …)`, and calls them in one request; afterwards the test thread reads
`(seon.cache/present :rr/KIND [n])`, the key compiled main code reads:

| Definition | Shape | Present under main's key |
|---|---|---|
| `rr-direct` | derives in its own frame | nil |
| `rr-future` | `@(future …)` (conveys bindings) | nil |
| `rr-closure` | returns `(fn [x] (lookup …))`, called by the request form | **20** |
| `rr-lazy` | returns `(map (fn [x] (lookup …)) xs)` (output schema `:any`), realized by the request form | **30** |
| `rr-thread` | derives on `(Thread. …)` | **40** |

Root cause: `seon.sci.runtime/reaching` binds `seon.cache/*reached*` only while the wrapped call is on the stack; what
it returns or starts runs later or elsewhere with the caller's binding (nil at a request form). An instrumented output
schema that realizes the value inside the wrapper hides it, which is why the agent's own proof
(`an-own-definition-keys-its-derivations-after-every-re-rooting`) cannot see it. The same holds for analysed program
functions, which carry the same wrapper.

### R3 (blocker). A compiled macro expands with main's values on every branch, and nothing names it

`seon.profile/bucket-of` computes `~(dec buckets)` at expansion, so its expansion reads the value `seon.profile/buckets`.
Program macros have no runtime row: their root is the compiled macro in every runtime, so its body reads the JVM's
`buckets`. The analysis key carries the macro's digest, not the digests of what the macro reads.

| Probe | `buckets` | `(seon.profile/bucket 5)` | fresh answer |
|---|---|---|---|
| database path (`macro-reads-a-changed-value`): branch replaces `(def buckets … 40)` with 3, `base-ctx` | 3 | **3** (`bucket` runs analysed) | 2 |
| agent through MCP (`agent-macro-reads-value`): `(in-ns 'seon.profile) (def buckets "…" 3)`, same request and next request | 3 | **3**, 3 | 2 |
| main | 40 | 3 | 3 |

`bucket-of`'s `:seon.fn/calls` hold `seon.profile/buckets`, so the reach index knows; but the host-bound report does
not name `bucket-of` (it names 26 other functions, below). Today this is the one program macro whose expansion reads a
program value (`let-ok`, `help`, `dir`, `doc`, `my.test/run`, `my.background/background` expand to symbols, which
late-bind). Same root as F2: compiled code whose reach holds the change, with the macro case unnamed.

### R4 (blocker). The host-bound report has no consumer and names the platform on every change

- `seon.sci.eval/host-bound-reaching` ("for the done rule", per its docstring and the note) is called by no `src`
  function; `seon.cluster.source/done-refusals` does not read it. `::host-bound-reached` reaches only
  `:seon.batch/notes`, which no reply reads (`docs/seon/issues/batch-notes-never-reach-the-mcp-reply.md`).
- Noise (`host-bound-noise`, `out/host-bound-noise.edn`): host-bound functions named for one changed name each:
  `seon.db/as-of-key` 34, `seon.id/digest` 38, `seon.schema/handed-projection` 35, `seon.print/literal` 30,
  `seon.error.refusal/error` 29, `seon.db/q` 28, `seon.ref/words` 27; a change of the value `seon.profile/buckets`
  names 26, among them `seon.sci.eval/evaluate`, `seon.sci.arm/arm!`, `fork-cluster-ctx`, `seon.cluster/serve!` and
  test fixtures (`seon.cluster.agent-test/with-connection`, `seon.competency-grade-test/launched`,
  `seon.render.web-test/with-server`). The index is built over every `:seon.fn/sym`, test namespaces included, and
  the platform's entrants reach almost everything, so the list is the same few dozen names for any change. As data for
  a done rule it would bail every chain; as a note it is ignored.
- So F2's answer is unchanged, nobody is told, and phase 3's deletion of deferred platform tests still assumes branch
  tests see changes they do not see through host-bound functions.

### R5 (high). Every open `def`/`defonce` is evaluated again on each changed runtime, including launch records

`out/value-census.edn`: on a branch with one changed leaf (`seon.print/literal`), 72 of 476 value rows are evaluated
again (943 ms for the whole `base-ctx`), every one of them open but `seon.print/node-generator`. No re-evaluated value
captured an analysed function object (identity census against the bound roots). Among them:

- `seon.cluster.source/launched-dependencies`, a `defonce` whose docstring says it is "read once when the namespace
  first loads at launch, and kept across reloads, because the classpath they chose is fixed for the life of the
  process", and `seon.cluster/launched-source-digests` ("the bytes of each first-party source file this JVM loaded at
  launch"). On a changed runtime they are read from the disk at derivation time. Probe `launched-values-read-now`
  (`out/launched-values.edn`, check JVM `8ecf72bd8`): after launch the probe appends a comment to the throwaway
  archive's `src/seon/profile.clj` and a newline to its `deps.edn`, then derives a changed runtime. Compiled:
  `src/seon/profile.clj` `f9f55c7e…`, `deps.edn` `d075ebfe…` (the launch bytes). The branch runtime:
  `f4d22e58…` and `789c1516…`, the bytes on disk now. In the shared checkout every lane's edit or pin bump does this,
  so on a branch the JVM "loaded" whatever the disk holds at the branch's first change, and the derivation re-reads
  every loaded source file.
- `seon.config/defaults`, documented as a compiled constant, and 57 delays, most of them `(defonce x (delay (requiring-resolve …)))`
  seams.

The note's design said "values and state … are never re-evaluated"; 85299376b reversed that for every open value with
no distinction for `defonce`. Root cause: `seon.sci.eval/bind-analysed!` treats an open value as due whenever it is not
yet bound (`(not (contains? roots sym))`), and openness here mostly means "reaches an error constructor".

### R6 (blocker for confidence). The differential test misses a binder defect it was written to catch

`seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers` now drives the production
`#'seon.sci.eval/bind-analysed!` against an independent oracle (a fresh SCI context of the whole composed source, with
`seon.cache/lookup` replaced by a direct derivation): that part of F6 is fixed. Mutations of `src/seon/sci/eval.clj`
on HEAD, each one dangling commit, the test alone in a check JVM:

| Mutation | Result |
|---|---|
| M1: no `reaching` wrapper for analysed functions (`reached-changes` replaced by `{}`) | red |
| M5: `evaluated-values` evaluates without the new analysed roots | red |
| M7: the runtime's own definitions are not wrapped | red |
| M2: drop `(contains? reached sym)` from a function's due rule ("bound again to key what it newly reaches") | **green** |

M2 is a real defect: a deterministic chain (main -> A changes `h0` to 1 -> B, A's child, changes `h0` to 2), the test's
own helpers, answers `cc` = 1049 (expected 1049) on HEAD and **1039** (A's derivation) under M2. The generator
(60 trials, seed 20261019, up to 5 steps of up to 6 changes) does not produce a parent and child that change one helper
to two versions often enough to catch it.

Also outside its reach, by construction:
- It never calls `seon.sci.arm/arm!`, `interpret-affected!`, `fork-cluster-ctx` or `derive-overlay-ctx`
  (`shaped-branch` installs own definitions with `eval-string*` and calls the binder itself), so the arm gap, R2's
  escapes and any re-arm path cannot turn it red.
- `namespaces` is always `#{}`: `resolver-digest`, `plan-changes` and the namespace rule are untested.
- The reach index is built from `shaped-calls`, the map the program is generated from (F6's second point), so an
  incompleteness of `:seon.fn/calls` (R3's macro reading a value) cannot show.
- A value is never changed, and no value is open. With one open value added (`(def vo {:f h3 :r r0})` and a caller
  `cvo`), the test is red; the shrunk case is `[[0 {}] [3 {h3 1}]]` (R7).
`seon.sci.eval-test/no-src-function-a-change-can-reach-runs-its-compiled-var`'s oracle walks callers from 14 resolving
names while the binder's `run-time-resolution` holds 27, and it walks the same `:seon.fn/calls` the binder reads: it
restates the rule rather than checking it.

### R7 (medium, latent). An open value is evaluated once, then keeps the functions it captured

Shown by the generator variant above (`tmp/runtime-rereview/runtime_test.openvalue2.clj`, red, shrunk to
`[[0 {}] [3 {h3 1}]]`): the first runtime with any program difference evaluates every open value with the functions
then bound; a later change to a function the value holds by object (not by Var) is never re-evaluated, because
`seon.sci.runtime/reached-changes` and `::reached-by` hold only closed names. A fresh interpretation of the composed
source captures the later definition. Not reproduced on the real program: no evaluated `src` value holds an analysed
function object today (R5's census), and `families` holds Vars, which late-bind.

### R8 (high, platform; not the runtime). The arm-gap red is `require :reload` loading a cached class

`seon.sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads` is red on the second launch of any tree and
green on the first, with or without the patch:

| Candidate | Launch | `replaced` after `(require 'seon.sci.eval :reload)` | Test |
|---|---|---|---|
| HEAD + diagnostics only (`ea94e5eb8`) | first | 174 Vars, `evaluate` among them | green |
| same tree | second | **0** | **red** |
| HEAD + arm-gap patch + diagnostics (`14a21acc0`) | first | 175 | green |

In every run the test body ran compiled (no `sci.impl` frame), `seon.instrument/state`, `replaced-definitions` and
`restore!` were the compiled roots, `bound?` was true and `evaluate` was in the captured roots, so the suspect
(`replaced-definitions` running analysed) is refuted. Cause, from `script/seon/operator.clj`'s `launch-form` and
`program-classes`: a first launch of a tree compiles into a staging directory that is promoted to
`target/dev-program-classes/<digest of the src, script, test, deps and reference-code trees>` with every file's mtime
set to 2100, "so they must win Clojure's class-newer-than-source test"; the next launch of the same tree puts that
directory first on the classpath. `clojure.lang.RT/load` (clojure `3bc2b3e9`) then prefers `seon/sci/eval__init.class`
over the source, and `loadClassForName` on an already-initialized class re-runs nothing, so `require :reload`
redefines no Var. Probe `reload-reads-the-class-or-the-source` (`c8cfc8ee8`, one tree launched twice,
`tmp/runtime-rereview/reload-{first,second}.edn`): first launch, no `seon/profile__init.class` on the classpath and
`(require 'seon.profile :reload)` replaces `#'seon.profile/bucket`'s root; second launch, the classpath's head is
`target/dev-program-classes/7205cc41…`, the class resolves there with mtime 4102444800000 (2100-01-01) against the
source's 1792417999000, and the reload replaces nothing. The lane runtime-f7b bisection
("red only with the fix and the new deftest together, twice") was a cache effect: that tree had already launched for
its commit check. 85299376b also listed this test among its accepted reds.

This is wider than one test: development adoption reloads with `require :reload`
(`seon.cluster/load-development-definitions!`), and every default launch of a checked commit finds its classes cached,
so an adoption on such a JVM would leave the cached namespaces at their launch code while recording the new commit.
Not probed on default.

## The held arm-gap patch

- Its reported red is R8, not the patch: the patch can land as far as that test is concerned, and the test needs its
  own fix (or the reload owner's), since it is red on HEAD's second launch too.
- It fixes what its proof covers (a behavior-held write and a re-arm on a predicate held apart), but it adds a second
  place that must restore an invariant the first placement breaks: the key lives in a wrapper outside the armed
  definition, and every re-rooting discards it. `seon.sci.eval/fork-cluster-ctx` re-installs the contract of every
  installed interpreted definition (`install-function-contract!` over `::instrument/interpreted-original`, which the
  wrapper's copied metadata still names), so a fork drops the wrapper again without passing through `arm!`
  (code reading; not probed). And it leaves R2 untouched.
- Recommendation: do not land it as is. Put the key where re-arming keeps it: wrap the raw analysed or written
  definition before it is armed, so `::instrument/interpreted-original` is the keyed function and every re-arm
  (`arm!`, `rearm-dependents!`, `fork-cluster-ctx`) wraps the same key; or key at the lookup by the deriving
  definition (R1/R2's fix), which makes the wrapper unnecessary. Its new deftest is worth keeping as a row, with a fork
  step added.

## Cost, two rows, same probe as the first review

`tmp/runtime-rereview/cost.clj.txt`'s `cost-rows` (two `program-change-pass`es, nine `seon.db` forms, the hub
behavior change, then `my.program/similar` after the embed fill), HEAD `d1f338b09` against the pre-runtime HEAD
`716cc7527` (85299376b's parent), each plus the probe. Pair 2 ran as concurrent check JVMs; pair 3 ran back to back.
MB allocated / calls / ms:

| Row | 716cc7527 | d1f338b09 | ratio MB (calls) |
|---|---|---|---|
| `:hub-behavior-change`, pair 2 | 897.1 / 197,463 / 1,332 | 1,024.8 / 222,657 / 1,457 | **1.14x** (1.13x) |
| `:hub-behavior-change`, pair 3 | 898.8 / 197,074 / 1,300 | 1,013.0 / 221,136 / 1,417 | **1.13x** (1.12x) |
| `:similar-by-name-warm`, pair 2 | 25.8, 25.9 / 7,450 / 55 | 26.4, 26.4 / 7,788 / 48 | 1.02x (1.05x) |
| `:similar-by-name-warm`, pair 3 | 25.7, 26.4 / 7,363-7,390 / 51-59 | 26.5, 26.5 / 7,723 / 51-61 | 1.00-1.03x (1.05x) |
| `:seon-db-nine-forms` (context), pair 2 | 1,203.5 / 146,584 | 1,248.7 / 149,690 | 1.04x |

Supported: the commits' figures hold. The hub row is 1.13-1.14x of the pre-runtime HEAD (the lane runtime-f7b table
compares f06474e75 with 85299376b, both already carrying it); calls grow 5% on the similar row and 12-13% on the hub,
the `reaching` frames and the analysed callers. Wall time is approximate under concurrent checks.

## Issues recorded

- R1, R2 and the fork re-arm path: `docs/seon/issues/the-branch-runtime-cache-key-is-a-dynamic-binding-so-derivations-still-cross.md` (of the class `seon-cache-derivations-cross-branches-through-their-deriving-code.md`)
- R3: `docs/seon/issues/a-compiled-macro-expands-with-mains-values-on-a-branch.md`
- R4: `docs/seon/issues/the-host-bound-report-has-no-consumer-and-names-the-platform-on-every-change.md`
- R5: `docs/seon/issues/a-changed-runtime-re-evaluates-launch-defonces-from-the-disk.md`
- R6, R7: `docs/seon/issues/the-differential-runtime-test-misses-a-rekey-defect-and-never-drives-arm.md`
- R8: `docs/seon/issues/require-reload-loads-the-cached-class-and-redefines-nothing.md`

## Dangling commits (each `d1f338b09` or `716cc7527` plus the named file)

| Use | Commit |
|---|---|
| first review's probes and R1-R5 probes, HEAD | `fdb91aaa1` |
| R3 agent path, R5 launch values, committed agent proof | `0131b7dc5`, `8ecf72bd8` |
| cost probe, HEAD / pre-runtime | `51ce1b226` / `3901944d1` |
| R8: HEAD + diagnostics; patch + diagnostics; the reload probe | `ea94e5eb8`; `14a21acc0` (patch alone `fd81ec239`); `c8cfc8ee8` |
| R6 mutants M1, M2, M5, M7 | `96400fa55`, `ffcabbe9c`, `ea42a05d1`, `620936c12` |
| R6 two-step chain, HEAD / M2 | `df9737e97` / `3c79c77b8` |
| R7 open-value generator | `ddbe3993a` |
