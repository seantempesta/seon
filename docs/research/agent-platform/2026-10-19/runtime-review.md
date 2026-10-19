---
title: "Runtime review: 011edf42f (branch runtime, the exact reach rule)"
type: research
status: review, 2026-10-19
created: 2026-10-19
---

# Runtime review: 011edf42f

Lane runtime-review (Opus, independent reviewer; did not write the change), 2026-10-19. Scope: `011edf42f` and the
work before it (`fc071bd76`, `8ef04d3b4`, `f04cbe446`, SCI pin `34d64a09e`), against the owner's requirement:
"Trivial continuations of an existing env or doing a branch are supposed to always be cheap and correct and not
sometimes broken based on what is on main (the compiled JVM). It shouldn't matter how they are mixed it should just
work." Design: [branch-runtime](branch-runtime.md).

Method. Every probe ran in a throwaway check JVM (`bin/seon check --at SHA`), never on default. The probe namespace
`seon.runtime-review-probe-test` was never committed: its source is `tmp/runtime-review/probe.clj.txt`, and each run
used a dangling commit object (`git commit-tree`) of `011edf42f` (or its parent) plus that one file at
`test/seon/runtime_review_probe_test.clj`; HEAD, the index and the working tree were untouched. Raw results are the
`.edn` files under `tmp/runtime-review/out*`. One read-only JVM-mode census ran on default's database as root.

## Verdict: NOT SAFE TO RESTART ONTO as the "exact" rule

The change is safe in the narrow sense: an unchanged agent pays nothing, main runs compiled, and the rows measured
below are within 1.13x of the parent (1.01-1.03x but the hub row). It is **not** what its title and the owner's requirement claim. A
branch's change is still not live for callers that reach it through four kinds of path the reach index cannot see or
the binder does not serve, three reproduced on the real program below. Each is a silent wrong answer on the branch,
not an error. Restarting onto it would be relied on as "exact" while it is not.

Blockers, in order:

1. An ordinary agent `defn` in `seon.cluster.agent` or `seon.profile` never answers on the change; the parent
   answers it in about 1 s (F4). Reproduced twice; cause not isolated. On its own this blocks a restart.
2. Value definitions hold main's functions (F1). Reproduced.
3. Host-bound functions run main's helpers (F2). Reproduced.
4. A changed macro is never live for its callers, on either path (F3). Reproduced; worse than the note admits.
5. The randomized proof cannot see any of these (F6): it tests a copy of the binder over a perfect call graph.

F4b (the `in-ns` fix's import risk), F5 (the removed catch) and F7 (cache keys) are lower; see each.

Once F4 is fixed, if the owner wants the speed win, the honest landing is: keep the commit, retitle the rule as "compiled where
`:seon.fn/calls` reach is unchanged; value definitions, host-bound declarations and macros excepted", and file F1-F3
as red proofs before the deletions of phase 2-3 (deferred platform tests) are allowed to land, because those
deletions assume branch tests see the change.

## Findings (numbered in the order found; severity in each heading, the verdict ranks them)

### F1 (high). A value definition captures main's functions; its callers on a branch call them

The reach index is right that `seon.problems/problems` reaches `seon.problems/missing-models` (through
`seon.problems/families`), so a branch that changes `missing-models` interprets `problems`. But `families` is a value
definition (`def`), which the design keeps as the host's value ("values are never re-evaluated"). Its value is a
vector of JVM Vars (`#'missing-models`, ...). The analysed `problems` iterates it and calls main's compiled
derivations. A fresh interpretation of the branch's source would capture the branch's Var.

Reproduction (`value-definition-holds-functions`, `tmp/runtime-review/out-after/value-definition.edn`): a branch
replaces `seon.problems/missing-models` with `[db] [{:seon.config.ai/model "BRANCH"}]` (as P1's proof replaces
`seon.print/literal`), then `b (eval/base-ctx database)`:

```clojure
((sci/eval-string* b "(fn [db] (#'seon.problems/missing-models db))") database)
;; => [{:seon.config.ai/model "BRANCH"}]            the branch's definition is live
((sci/eval-string* b "(fn [db] (:seon.problems/missing-models (seon.problems/problems db {})))") database)
;; => nil                                            main's, through families; fresh answer: [{... "BRANCH"}]
```

Scope, census on default (JVM mode, read-only): 47 `src` value definitions call or hold 76 runnable `src` functions,
among them `seon.problems/families`, `seon.render/same-call-cache-evidence?` (`(def ... same-invocation-evidence?)`,
an alias holding the compiled function), `seon.print/node-kind-validator*`, `my.edit/_form-operation-predicate`,
`my.fs/_content-predicate`, the `seon.*-generator`s and `seon.config/defaults`. Every caller of these on a branch that
changes what they captured answers main.

Root cause: the runtime keeps every `def` root as the host's value, so a value computed from (or holding) program
functions is computed against main once. The reach index treats it as an ordinary edge, so nothing marks the caller
as needing a value it does not get.

### F2 (high). A host-bound function runs compiled on every runtime, so its helpers' branch changes never reach it

A host-bound declaration (`:seon.fn/host-bound? true`: `reify`, `proxy`, `definterface`, `import`, now
`print-method`) is a leaf whose root is the compiled function in every context. Its body still calls ordinary `src`
helpers, by compiled Var. The reach rule removes its callers from the compiled set when a helper changes, but the
host-bound function itself stays compiled, so everything that goes through it sees main's helper.

Reproduction (`host-bound-callee`, `tmp/runtime-review/out4/host-bound-callee.edn`): a branch replaces
`seon.ref/words` with `[^String text] ["BRANCH"]`; `seon.ref/parsed` (a commonmark `proxy` user, host-bound) keeps
only words containing `/`:

```clojure
(#'seon.ref/words "a b")                                                       ;; => ["BRANCH"] on the branch
(boolean (re-find #"seon.db/q" (pr-str (#'seon.ref/parsed "see seon.db/q here")))) ;; => true on the branch
;; fresh answer: false (the branch's words drop every word); main: true
```

Scope, census on default: 39 host-bound `src` functions call runnable `src` functions, 252 edges, including
`seon.ref/parsed` (every reference and document read), `seon.flow/var-process`, `seon.effect/context-handler`,
`seon.cluster.store/open-store!`, `seon.fault/unfold!` and `seon.cluster/serve!`. `seon.ref/document` also caches
`parsed`'s result by text digest only (F7).

Root cause: host-bound is decided per declaration, and its consequence (compiled root everywhere) is applied without
the reach rule: the rule "compiled only if everything in its reach is unchanged" is not applied to host-bound names.
The design states host-bound as a leaf; in the program it is not a leaf.

### F3 (high). A changed macro is never live for its src callers, on the agent path or the branch path

Reproduction (`macro-change`, `tmp/runtime-review/out4/macro-change.edn`). `seon.profile/bucket` calls the macro
`seon.profile/bucket-of`; `(seon.profile/bucket 5)` is 3 on main.

| Runtime | How the macro changed | `(seon.profile/bucket-of 5)` | `(seon.profile/bucket 5)` | fresh answer | `bucket` runs |
|---|---|---|---|---|---|
| agent A, `fork-cluster-ctx`, one-path eval | `(in-ns 'seon.profile) (defmacro bucket-of [elapsed] 33)` | 33 | **3** | 33 | compiled |
| branch B, `base-ctx` of a db where `bucket-of` is `22` | replacement transaction, as P1's proof | 22 | **3** | 22 | analysed |
| branch B again, analysed and program-context caches evicted first | same | | **3** | 22 | analysed |
| main | none | | 3 | 3 | compiled |

- On the agent path the caller is still the compiled Var (`bucket-runs-compiled {:a true}`): the agent's
  `defmacro` did not take `bucket` out of the compiled set, although `bucket`'s closed reach holds `bucket-of`. Why
  is not isolated (suspected: the macro write never reaches `seon.sci.eval/interpret-affected!` through
  `seon.sci.arm/arm!`, or reaches it with no change the binder counts). Still uncertain.
- On the branch path the caller is analysed but expands main's macro: `derive-overlay-ctx` calls `bind-analysed!`
  before `overlay!` installs the branch's macro, and the analysis key carries main's macro digests
  (`runtime-rows!` computes `::runtime/macro-digests` once, from the loaded program). So the stale result is
  reproduced on a cold cache too: it is not an ordering accident.
- Cache poisoning (an analysis made on a fork of an agent's context that holds a changed macro, cached under main's
  key) was the hypothesis; it was **not reproduced**, because the agent path never analysed the caller (row 1).
  The mechanism is still present: `seon.sci.runtime/analyse` runs on `(sci/fork ctx)` of whichever context binds
  first, and `seon.sci.eval/interpret-affected!` calls `bind-analysed!` after the agent's own definitions are in
  that context. Anything in an agent's context that changes how an unchanged definition analyses (a redefined macro,
  a name defined in a `src` namespace that shadows a referred one) would be analysed into the shared cache under main's
  key, and every other runtime that binds that row afterwards gets it. A probe for this must make the agent path
  analyse (fix row 1 first).

Severity: 7 `defmacro`s in `src` (`seon.profile/bucket-of`, `seon.error.refusal/let-ok`, `seon.bootstrap/help`,
`dir`, `doc`, `my.test/run`, `my.background/background`). Rare, but the answer is silently main's, and the poisoning
mechanism would make one agent's macro change visible on other branches.

### F4 (blocker). An agent's `defn` in `seon.cluster.agent` / `seon.profile` never answers on the change

Found while probing the `in-ns` fix (below). Through the real MCP path (`seon.cost-estimate-test/answer` under
`seon.test-support/with-running-agent`), two requests:

```clojure
(in-ns 'seon.cluster.agent) (defn rr-probe "P." {:malli/schema [:=> [:cat] :int]} [] 1)
(in-ns 'seon.profile) (defn rr-probe "P." {:malli/schema [:=> [:cat] :int]} [] 2)
```

- Parent `011edf42f^`: both answer, 1,063 ms / 473 MB and 446 ms / 242 MB; the test passes in 6.0 s.
- Change `011edf42f`: the test does not exit within the check's limit, twice: once after the other probes in the same
  JVM (111.9 s remainder), once alone in a fresh check JVM (117.8 s remainder): "did not exit within
  :seon.test/check-time-limit-ms ... thread remains live". No result was written, so which of the two requests blocks
  is not known.
- The cost rows' agent, which `defn`s nine forms in `seon.db` and a hub change on the same change, answers (1.06 s and
  1.27 s), so it is not every `src` namespace. The two namespaces here are the two whose own classes
  (`CountedSlidingBuffer`, `Bracket`) the change newly imports on entry (`seon.sci.eval/loaded-bindings`) and which the
  `in-ns` fix was written for; that is a lead, not a cause.
- Root cause not isolated (time box). Next probe: the same two requests one at a time with a thread dump of the
  check JVM at 30 s (`jcmd PID Thread.print`), to tell a deadlock (for example a `seon.cache` derivation that requests
  its own key, which Caffeine's `AsyncCache` cannot complete) from a long computation.

### F4b (medium, not settled). The `in-ns` fix keeps own classes in live resolver state

`seon.sci.eval/loaded-bindings` installs a namespace's own `deftype`/`defrecord`/`definterface` classes as imports on
every entry, and `seon.turn.batch/declared-bindings` keeps `(:imports live)` whole. Stored rows never declare these
classes (on default: `seon.profile` has 9 imports and no `Bracket`; `seon.cluster.agent` 9 and no
`CountedSlidingBuffer`; `seon.env` none and no `Environment`). The risk: any agent definition in one of those
namespaces writes a namespace row that gains an import its `ns` form never had, the merge carries it, and a write-back
that renders imports would import a class before the file defines it.

Probe (`own-class-import`): through the real MCP path (`seon.cost-estimate-test/answer`),
`(in-ns 'seon.cluster.agent) (defn rr-probe "P." {:malli/schema [:=> [:cat] :int]} [] 1)` and the same in
`seon.profile`, then the stored `:seon.ns/imports`.

- Parent `011edf42f^`: imports unchanged in both namespaces (`out4/own-class-import-before.edn`).
- Change: not observed, because the requests never answer (F4). Code reading says the row gains the class: with
  `added-only?` true `declared-bindings` returns `(:imports live)`, which now holds the own class, so `kept` differs
  from `stored`, the row's digest differs, and the new digest guard in `namespace-facts` does not stop it. Still
  uncertain until F4 is fixed and this probe is rerun.

### F5 (medium-low, code reading, not reproduced). The removed catch: an analysis failure aborts the bind half-done

`bind-analysed!` binds inside `(into {} (map ...))`: each row's `sci/bind-root!` runs before the next row is
analysed, and `::analysed-roots`, `::compiled-kept` and `::inherited-var-bindings` are written only after the whole
loop. A row whose analysis throws therefore leaves every earlier due row bound in the context but recorded neither as
inherited nor as analysed, and every later due row compiled though the change reaches it.

- Where it can trip: `derive-overlay-ctx` (a branch's program context; the throw reaches the entrant boundary, so
  every request on that branch fails until the program changes) and `interpret-affected!` (inside `seon.sci.arm/arm!`,
  whose `catch clojure.lang.ExceptionInfo` turns it into `:seon.batch/arm-error`; the request continues on the
  half-bound context).
- What agent code can trip it: analysis runs on a fork of the agent's own context, so anything the agent did to a
  `src` namespace that breaks resolution of an unchanged due row (an `ns-unalias`, an unmapped referred name, a macro
  change whose expansion fails in a caller). Main's rows themselves all analyse (4,463, per the wire's census).
- The half-bound bindings that are not in `::inherited-var-bindings` are, by `own-bindings`, the context's own
  definitions; at the next regeneration they would be carried as private bindings. Not probed.

### F6 (high for confidence). The randomized test does not exercise any hole above

`seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers`:

- It drives `seon.sci.runtime/compiled-names` through a test-side binder, `with-compiled`, not
  `seon.sci.eval/bind-analysed!`. A defect in the production binder (its `held` exclusion, the order against
  `overlay!`, the snapshot writes, the partial bind of F5) cannot turn it red.
- Its reach index is built from the same `callers` map the program is generated from, so the index is complete by
  construction. Every hole above is an incompleteness of `:seon.fn/calls` or of the binder against the real program.
- The program has no macros, no value definitions, no host-bound names, no functions passed as values to compiled
  code, no namespace-declaration changes and no off-thread calls.
- `seon.sci.eval-test/no-src-function-a-change-can-reach-runs-its-compiled-var` walks the same `:seon.fn/calls` the
  rule reads, so it is a restatement of the rule, not an oracle for it. It would pass with F1-F3 present (it did).

Additions proposed (each a generator element of the same differential test, compared with a fresh SCI context of the
composed source):

1. `v0`, a value definition `(def v0 {:f h0})`, and a caller `cv` that calls `((:f v0) x)`; the reference runtime
   keeps `v0` as main's value, as the base does.
2. `m0`, a macro expanding to `(h1 ~x)`, and a caller `cm` that uses it; changes pick versions of `m0` too.
3. `hb`, a "host-bound" name bound to its compiled version in every runtime (as `:seon.fn/host-bound? true` is),
   calling `h2`, and a caller `chb`.
4. `ch`, a caller that hands `h3` to a compiled library function (`(mapv h3 [x])`, `(reduce (fn ...) ...)`).
5. Run the steps through `seon.sci.eval/bind-analysed!` on real contexts (`interpret-affected!` for agent-style
   changes, `derive-overlay-ctx` for database changes), and permute which runtime binds first with
   `seon.cache/evict-kinds! #{:seon.sci.runtime/analysed}` between permutations, so analysis-context poisoning shows.
6. One real-program row per finding here: the F1, F2 and F3 forms above as table rows of
   `seon.sci.eval-test/a-branch-helper-change-is-live-for-its-src-callers-on-that-branch`.

### F7 (medium, code reading, not reproduced). `seon.cache` derivations keyed by input only now cross branches

Before this change a branch's change reached no compiled caller, so a cached derivation computed by `src` code was
computed by main's code on every branch. Now a branch's change is live in the analysed callers, and a derivation whose
key is its input's content but not the deriving definition (`seon.ref/document`, keyed by the text's SHA-256 and
derived by `seon.ref/parsed`; 111 `seon.cache/lookup` sites in `src`) is shared between a branch that changed the
derivation and main: whichever computes first serves both. The CLAUDE.md rule ("the same definition with the same
input is computed once") needs the definition in the key. No reproduction was written; the shape is: a branch changes
a helper inside a cached derivation, computes it, then main reads the same key.

## Items of the assignment

1. **The exact rule.** Contradicted for value definitions (F1), host-bound callees (F2) and macros (F3), each
   reproduced. Supported for:
   - Higher-order use of a program function inside `src`: `:seon.fn/calls` counts value usages (`apply`,
     `partial`, `comp`, syntax-quoted bodies, var quotes; `seon.fn/call-target`), so a caller handing a helper on
     reaches it. Only quoted symbols (`'f`) are references, and resolving them goes through the opening calls.
   - `binding` of dynamic Vars: a copied dynamic Var carries `:sci.impl/host-var`, and the SCI fork binds both
     (`sci.impl.vars`), so compiled callees see an analysed caller's binding. Not reproduced as a hole.
   - Protocols, `deftype`/`defrecord` methods and multimethods: `src` has no `defprotocol`, `defmulti` or
     `extend-*`; its 2 `deftype`s, 2 `defrecord`s and 1 `defmethod` (`print-method`) call no program function. No hole
     today; any future one falls under F2.
   - `alter-var-root` and `intern` on program Vars: only in `seon.instrument` (platform). No agent-path hole found.
   - `apply` with a Var: a var quote is a call target. Supported.
   - Run-time resolution: the opening set is a hand list of 14 `clojure.core` names. `find-ns` plus
     `.findInternedVar` (`seon.schema/runtime-predicate`, `seon.batch/compiled-function?`) and library code that
     resolves a program symbol internally are not in it. Both current sites also call an opening name or never call
     the Var; still uncertain as a class.
   - **Predicates.** `held-apart-var` holds apart any predicate the context has its own binding of, which includes
     every analysed binding `bind-analysed!` made, so "binds a predicate exactly when its compiled Var could answer
     otherwise" holds for what the reach index sees, and fails exactly where F1-F3 fail (a predicate reaching a value
     definition, a host-bound function or a macro). Predicates named in schema resources are covered
     (`population-predicate-symbols` reads the projection's forms). Still uncertain: an analysed or compiled-kept
     function's own contract is armed against the compiled snapshot's projection (`armed-as-compiled`), so a branch
     that changes a predicate a kept function's contract uses validates with main's predicate; `rearm-dependents!`
     serves installed functions only, and `bind-analysed!` does not mark its bindings installed. Not probed.
2. **Cache keys.** F3. The note's admission is reproduced and is broader: it is not only "not re-keyed"; the branch's
   caller is analysed before the branch's macro is installed, and the agent path does not re-analyse at all.
3. **The `in-ns` fix.** The stated risk is still uncertain (F4b); probing it found F4.
4. **Removed catch.** F5.
5. **Cost.** Re-measured with the cost test's own probes (`seon.cost-estimate-test/program-change-pass` twice,
   then nine `seon.db` forms, then the hub behavior change, then `my.program/similar` after the embed fill), one
   pair of throwaway check JVMs running at the same time: `--at` the change plus the probe, and `--at` the parent
   (`011edf42f^`) plus the probe. MB allocated / calls / ms (`tmp/runtime-review/out-cost/`):

   | Row | parent | change | ratio (MB) |
   |---|---|---|---|
   | `:similar-by-name-warm` | 25.9 / 7,335 / 60-67 | 26.3 / 7,354 / 40-44 | 1.02x |
   | `:hub-behavior-change` | 876.2 / 198,307 / 1,441 | 989.3 / 211,588 / 1,275 | **1.13x** (calls 1.07x) |
   | `:seon-db-nine-forms` | 1,191.5 / 145,942 / 1,253 | 1,203.9 / 145,977 / 1,060 | 1.01x |
   | `:reuse-one-unchanged-test` | 29.4 / 7,775 / 39 | 30.3 / 7,885 / 42 | 1.03x |
   | `:unchanged-request` | 18.0 / 6,432 / 28 | 18.4 / 6,449 / 27 | 1.02x |

   Supported, with one correction: the hub row is 1.13x in this sequence (the commit's 1.03x ran the hub after the
   docstring row; this one runs it right after the nine forms), still under the 1.3x bar and under the wire's
   1,037 MB. One pair only; wall time is approximate under concurrent checks, allocation and calls are not.
6. **The randomized test.** F6.

## Issues to file (not filed by this lane: its assignment commits only this note)

- `seon.sci.eval/an-agent-defn-in-seon-cluster-agent-never-answers` (F4). Proof: the two requests above under
  `with-running-agent`, answered within the default bound.
- `seon.sci.eval/value-definitions-hold-main-functions-on-a-branch` (F1). Proof: a row of
  `a-branch-helper-change-is-live-for-its-src-callers-on-that-branch` with the `seon.problems/missing-models` forms.
- `seon.sci.eval/host-bound-callers-run-main-helpers-on-a-branch` (F2). Proof: the `seon.ref/words` forms.
- `seon.sci.eval/a-changed-macro-is-not-live-for-its-callers` (F3). Proof: the `bucket-of` table, both rows.
- `seon.sci.eval/bind-analysed-binds-half-a-change-when-an-analysis-throws` (F5).
- `seon.sci.runtime-test/the-differential-test-drives-a-copy-of-the-binder` (F6).
- `seon.cache/derivations-keyed-without-their-definition-cross-branches` (F7).
