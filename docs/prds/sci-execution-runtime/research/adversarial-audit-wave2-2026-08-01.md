---
type: research
status: active
tags: [research, audit, sci, repl, database, tooling]
---

# Adversarial audit of landing wave 2 — 2026-08-01

## Verdict

The wave is materially simpler and much of it is sound, but the 1A and 1C
claims are not closed.

- **1A — FALSIFIED as a whole.** Fork-per-run is gone and refused terminal
  transactions correctly leave evaluated definitions live, but two nominally
  independent cluster contexts still share 17 writable SCI Vars. The already
  open cross-cluster blocker remains real.
- **1C/1C-prime — FALSIFIED.** Store fidelity is computed honestly and the SCI
  host-interop observer covers the tested interop syntax. The claimed purity
  proof is not a purity proof: an unstorable closure over `gensym` is replayed
  to a different value. A definition executed before a later throw is also
  omitted from the session delta entirely, so it disappears after process loss
  without an `unrestorable` statement.
- **Print path — CONFIRMED.** The exact schema generator covers all 23 grammar
  faces under the recurring P-TOTAL seed, both sinks consume the same sealed
  tree, turn semantics consume the admitted semantic value rather than
  reparsing presentation bytes, and the new parity sentinel fails on a deleted
  row by construction.
- **MCP — FALSIFIED at two edges.** Namespace-to-frame classification is
  content-derived, but it starts from a duplicated `["src" "test"]` roster.
  The captured `ProcessHandle.onExit` also has a PID-reuse window on the exact
  JDK runtime because its non-child reaper does not receive the captured start
  time.

Four new issues are filed: two blockers in stateless resume and two MCP
frictions. The existing 17-Var cross-cluster blocker remains open. The parity
cardinality issue is independently confirmed fixed and archived.

## Scope, freeze, and dependency ledger

The audit boundary is every commit after `b114ac29d`, with the claimed source
wave ending at `9d56f4002`. The two later commits `8d79b3f6b` and
`cf60dffcc` modify only the high-level ruling ledger. No lane report or
`plan/unsettled.md` addendum was treated as evidence.

The frozen build-input paths remained read-only. The only probes added were
gitignored scripts under `tmp/`; the only tracked changes from this audit are
this report and `docs/seon/issues/**`.

Exact dependency owners used:

- SCI `reference-code/sci@47f6c8b5a557`.
  The observer commit is the current checkout, not an API assumption.
- Datahike
  `reference-code/datahike@9b3be9d59cb0`.
- Clojure
  `reference-code/clojure@b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`.
- clj-kondo
  `reference-code/clj-kondo@57252e07975710aa579b24f0d1b2b1e04195caa2`.
- OpenJDK 26.0.1, the Homebrew runtime that executes the gate and MCP server.

Disposable evaluator probes used `clojure -M:dev:test`,
`seon.test-support/with-database`, and fresh cluster contexts. They mutated
only in-memory test databases. The reusable scripts are
`tmp/adversarial-wave2-probe.clj` and
`tmp/adversarial-print-generator-probe.clj`.

## Claim 1 — live per-cluster context

### 1A.1 Fork-per-run is gone — CONFIRMED

`seon.cluster/start!` constructs one context after cluster configuration and
stores it on the cluster instance
(`src/seon/cluster.clj:1333-1344`). The turn fold reads that exact context
once at `src/seon/cluster/loop.cljc:1139-1168` and passes it to every
evaluation at `:1217-1231`. `seon.sci.eval/evaluate` uses a supplied context
as given (`src/seon/sci/eval.clj:1165-1169`).

The only remaining turn-time `sci/fork` is the narrow `ns-unmap` isolation
at `src/seon/sci/eval.clj:1183-1192`; it is not ordinary per-form or per-run
acquisition. Search found no `acquire!` or general fork in the live fold.

### 1A.2 Two clusters share nothing — FALSIFIED

An independent fresh JVM constructed two base contexts, selected SCI Vars that
were identical across both contexts and lacked `:sci/built-in`, and counted
17. The exact set was:

`clojure.core/*assert*`, `*clojure-version*`, `*data-readers*`,
`*default-data-reader-fn*`, `*file*`, `*ns*`, `*read-eval*`,
`*reader-resolver*`, `*suppress-read*`, `*unchecked-math*`,
`*warn-on-reflection*`, `unquote`; `clojure.walk/macroexpand-all`; and
`clojure.lang/IAtom`, `IAtom2`, `IDeref`, `IFn`.

Root-rebinding `clojure.walk/macroexpand-all` through context A made context B
read `:crossed`. The per-cluster env atoms and ordinary program definitions
are distinct; these shared Vars are the reproduced residue. No commit in the
scope applies the metadata correction queued in
`plan/refactor-wave-2026-08-01.md:465-485`. The independent evidence is added
to the existing blocker
`docs/seon/issues/one-program-graph-is-shared-across-clusters.md`.

### 1A.3 A refused terminal transaction leaves the definition live — CONFIRMED

For a declaration, evaluation mutates the live context before persistence
(`src/seon/sci/eval.clj:1262-1277`) and marks the row already evaluated
(`:1304-1308`). The turn installs a committed program row only when the
terminal transaction succeeds
(`src/seon/cluster/loop.cljc:1369-1375`); there is no rollback of the live
definition on refusal. The regression at
`test/seon/cluster/turn_test.clj:1445-1508` injects the transaction refusal,
asserts no database function row, then calls the definition from a second
agent. Source and behavior agree with ruling #30.

## Claim 2 — stateless resume and SCI observation

### 1C.1 Store-faithful is computed — CONFIRMED

`store-faithful-edn` performs the actual `pr-str`/EDN read round trip with
metadata printing and requires equality, identical class, and identical
metadata (`src/seon/sci/eval.clj:448-465`). It contains no face or type
enumeration. The tests include tagged values, custom-comparator collections,
metadata, functions nested in data, and lazy sequences. This is the right
computed predicate.

### 1C.2 The replay purity proof fails closed — FALSIFIED

`unproven-called-vars` excludes every `:sci/built-in` Var
(`src/seon/sci/eval.clj:517-535`). `capability-free-references?` rejects a
missing program row only for that already-filtered set, while a missing row
reached through `referenced-vars` has neither a workload nor calls and
therefore passes (`src/seon/cluster/loop.cljc:296-322`). Zero observed host
interop plus that reachability result is called `pure?` and preserves source
for cold replay (`:339-393`).

The disposable-database reproduction was:

`(def replay-symbol (let [x (gensym "x")] (fn [] x)))`

The function value is not store-faithful. Its session candidate named
`clojure.core/gensym` but had an empty unproven-call set, so the terminal
session row retained source instead of stating `unrestorable`. Before cold
restore, `replay-symbol` returned `x238416`; after the row was committed and
a fresh cluster context was constructed, it returned `x238423`.
`:replay-equal?` was false. The same predicate admits a closure whose defining
form calls `println`.

Filed blocker:
`docs/seon/issues/session-replay-treats-effectful-sci-builtins-as-pure.md`.

### 1C.3 Unstorable and unproven state is always stated — FALSIFIED

The probe evaluated:

`(do (def silent-drop (fn [] 9)) (throw (ex-info "boom" {})))`

The result was `:seon.sci.eval/evaluation-failed`, but `silent-drop` resolved
and remained callable in the live context. The result contained no
`:seon.sci.eval/session-defs`. This follows directly from
`src/seon/sci/eval.clj:1315-1338`: the intern diff is calculated only after
`eval-form!` returns. The catch path at `:1342-1365` never snapshots changed
interns. A restart therefore drops the definition with no value, source,
deletion, or `unrestorable` fact.

Filed blocker:
`docs/seon/issues/failed-eval-definitions-have-no-session-image-delta.md`.

### 1C.4 SCI host-interop observation — CONFIRMED for the named escape syntax

SCI commit `47f6c8b` threads `:host-interop-observer` through init and fork,
then calls it in direct dot analysis, `.method` expansion, allowed
constructors, resolved interop Vars, and constructor Vars
(`reference-code/sci/src/sci/impl/analyzer.cljc:57-60` and the commit diff).

Independent evaluation counts were:

| Syntax | Count |
|---|---:|
| direct dot | 1 |
| `doto` | 1 |
| `->` | 1 |
| `->>` | 1 |
| allowed constructor | 1 |

The direct, `doto`, and threading cases converge through macroexpansion to an
observed analyzer arm. An unadmitted static class was refused before execution;
it did not escape with a zero count. Reader tags are a separate closed boundary:
`src/seon/sci/reader.cljc:28-34,102-120` admits the default data readers,
refuses unknown tags, and always refuses `#=`. No reader-tag or macroexpanded
host interop escape was found. This confirmation does not repair the distinct
SCI-built-in replay defect above.

## Claim 3 — the admitted print path

### P-TOTAL generator honesty — CONFIRMED

`resources/seon/schema/print.edn` declares one closed 23-face node grammar.
P-TOTAL generates the exact compiled `:seon.print/node` schema and validates
every generated node before testing both sinks and the readable round trip
(`test/seon/print_test.clj:147-167`). P-TEE uses the same schema generator and
compares both sink projections (`:169-182`).

The independent generator probe produced 2,000 fixed-seed/size samples. Every
sample validated and all 23 faces occurred. More importantly, repeating
P-TOTAL's exact seed `202608010301` for its exact 200 trials also observed all
23 faces; `:p-total-missing` was empty. The recurring property is not green
because a grammar partition is unreachable today.

### Semantic admit value remains the semantic input — CONFIRMED

`seon.sci.admit/admit` builds the finite print node once, derives the bounded
semantic value from that tree, and serializes the same tree separately
(`src/seon/sci/admit.clj:382-416,418-466`). The turn reads
`:seon.sci.admit/value` for disposition, messages, and error kind
(`src/seon/cluster/loop.cljc:1238,1255,1322-1328`);
`seon.problems/form-problem` reads the same value
(`src/seon/problems.clj:169`). `result-edn` is consumed only by settlement,
blob storage, and later presentation in this path. No semantic turn consumer
reparses it.

### Cardinality sentinel fails on a deleted row — CONFIRMED

`test/seon/repl_parity_test.clj:174-206` derives the expected identity set
from the nine family cardinalities, then asserts total count, exact identities,
and per-family counts. The once fixture invokes it before row execution
(`:218-234`). Deleting any executable or pending row lowers the total and
removes an identity, so at least two assertions fail. Replacing a row with a
duplicate can preserve total count but still fails exact identity and usually
family cardinality. The old absence-as-health issue is resolved by
`050fff5c7` and has been archived.

## Claim 4 — known failure-mode sweep

**FALSIFIED overall by the two session blockers and two MCP frictions.** The
session purity docstring promises a proof that the implementation does not
provide; failed evaluation reads an absent session delta as if there were no
live change; and MCP retains a duplicated source-root hand list.

The rest of the sweep calibrated cleanly:

- No second printer or turn semantic codec was added. `seon.print` owns the
  one sink traversal; `seon.render.value` delegates to `print/emit-both`.
- No new production clock or timeout appears in the scoped source diff. The
  MCP change deletes the old five-second `Thread/sleep`; new `TimeUnit`
  deadlines are test backstops.
- Session source/value rows are the ruled durable session facts, not a stored
  render or derived status projection.
- No name/prefix classification was found in the new runtime owners outside
  MCP's class-name translation. MCP's namespace set is content-derived, but its
  source-root input is duplicated as filed below.
- The print parity sentinel is a genuine presence check; it no longer treats a
  missing test row as health.

## Claim 5 — MCP fixes

### First-party frame derivation — FALSIFIED at the inventory boundary

The old namespace-prefix allowlist is gone. MCP walks source files without
following symlinks, parses their `ns` forms with read-eval disabled, munges
those namespaces, and accepts only exact class roots or their `$`-delimited
generated classes (`script/seon/dev/mcp.clj:560-611`). That classifier is
derived and the boundary avoids false textual prefixes.

However, `script/seon/dev/mcp.clj:34` introduces
`first-party-source-directories ["src" "test"]`, duplicating the program
graph's authoritative `seon.fn/source-roots` at
`src/seon/fn.clj:19-21`. A new first-party root can enter the program graph
and remain absent from MCP exception projection. Filed friction:
`docs/seon/issues/mcp-frame-provenance-duplicates-the-program-source-root-roster.md`.

### Parent-exit watchdog under PID reuse — FALSIFIED by runtime source

`script/seon/dev/mcp.clj:873-886` captures the parent `ProcessHandle` and
registers `onExit`; the ordinary integration test proves prompt exit when the
parent dies without PID reuse.

The installed OpenJDK 26.0.1 implementation does not make that captured handle
fully identity-safe for a non-child wait. Independent `javap -p -c` inspection
shows `ProcessHandleImpl` stores `pid` plus `startTime` and uses both in
`isAlive`, but `onExit` passes only `pid` to `completion(pid, false)`.
The non-child fallback in `ProcessHandleImpl$1` takes its first
`isAlive0(pid)` result as the baseline start time. Because MCP cannot
`waitpid` its own parent, a PID reused before that first sample is followed as
if it were the captured parent. The child can therefore leak until the
replacement exits. Filed friction:
`docs/seon/issues/mcp-parent-watchdog-can-follow-a-reused-pid.md`.

## Claim 6 — independent counts

Two cited counts were independently reconstructed from current source:

- **88 parity rows — CONFIRMED:** 69 executable plus 19 pending. The executable
  partition is 45 passing and 24 known divergences. Family counts are
  A=10, B=11, C=8, D=11, E=16, F=6, G=10, H=8, I=8.
- **10 promotions — CONFIRMED:** the exact `050fff5c7^` to `050fff5c7` diff
  changes B1, B4, B9, B10, A1, A9, E4, H1, H4, and H6 from
  `:known-divergence` to `:passing`.

One wording hazard remains in the ledger: 45 passing + 24 known + 19 pending is
the additive 88-row partition. A separate phrase such as "23 pending Lane 1"
cannot be another additive bucket and needs its overlap stated when cited.

The reported 39-test/168-assertion and 52-test/220-assertion focused runs were
not rerun during this audit. The frozen full gate already occupied the test
runtime, and the user required only two independent recounts. Neither suite
count is used as evidence for a conclusion here.

## Ranked issues

1. **Blocker — failed eval definitions have no session-image delta.**
   `docs/seon/issues/failed-eval-definitions-have-no-session-image-delta.md`.
2. **Blocker — replay treats effectful/nondeterministic SCI built-ins as pure.**
   `docs/seon/issues/session-replay-treats-effectful-sci-builtins-as-pure.md`.
3. **Existing blocker — 17 SCI Vars still cross cluster contexts.**
   `docs/seon/issues/one-program-graph-is-shared-across-clusters.md`.
4. **Friction — MCP parent exit can follow a reused PID.**
   `docs/seon/issues/mcp-parent-watchdog-can-follow-a-reused-pid.md`.
5. **Friction — MCP duplicates the program source-root roster.**
   `docs/seon/issues/mcp-frame-provenance-duplicates-the-program-source-root-roster.md`.

## Calibration — what is genuinely in good shape

- The ordinary turn path now has the intended single live context. The change
  deletes repeated acquisition rather than relocating it.
- Refused persistence does not lie about live REPL state: a definition is live
  once evaluated, and terminal refusal does not pretend to roll it back.
- Store fidelity is a short empirical predicate over the real serializer, with
  class and metadata included. There is no type roster to rot.
- The SCI observer is placed in analyzer owners rather than regexing source,
  survives fork, and caught direct, macroexpanded, threaded, and constructor
  interop in independent probes.
- The print grammar is closed, schema-generated, and genuinely reached by its
  property generator. One traversal feeds text and Hiccup, while semantics
  consume the separately derived bounded value.
- The parity inventory now has a real absence sentinel, and its 88-row and
  10-promotion claims independently recount.
- MCP removed both the writer fallback and the five-second parent poll. Its
  normal parent-exit behavior is event-driven and proven end to end; the filed
  defect is the narrower JDK non-child PID-reuse window.

## Frozen full gate

At the last observation while this report was being drafted, the owner-started
bare `bin/test` process was still running and had not published a verdict.
This audit does not depend on it.
