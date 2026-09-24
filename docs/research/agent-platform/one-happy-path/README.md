---
type: research-synthesis
status: census complete 2026-09-24 (8 reviewers: 7 Opus by area, 1 Astra cross-cutting); nothing deleted yet
created: 2026-09-24
---

# One happy path: every concern gets one owner

Owner, 2026-09-24: "We wrote our own fucking parser. Why? Make sure this design identifies all the right solutions and
marks all the rolled our own versions we need to delete." Eight read-only censuses of an 82,225-line `src/` against the
vendored dependencies found about **13,400 deletable lines** (about 2,400 of them UNVERIFIED), ranked below. Each area
file holds the evidence (file:line, measurements) and its rows as raised v0.3 tasks. Totals overlap slightly where two
reviewers named one mechanism (fault paths, SCI cache keys, Datalog rule grounding); the per-area numbers are theirs.

| Area | Deletable (≈ lines) | File |
|---|---:|---|
| Schemas and contracts | 5,100 | [contracts](contracts-2026-09-24.md) |
| Printing and rendering | 2,590 (1,150 unverified) | [rendering](rendering-2026-09-24.md) |
| Execution, errors, operations | 1,190 + 505 unverified | [execution](execution-2026-09-24.md) |
| Database layer | 1,420 (290 unverified) | [database](database-2026-09-24.md) |
| Reading and analysis | 920 (320 after fork fixes) | [reading](reading-2026-09-24.md) |
| SCI evaluation and contexts | 900 (110 unverified) | [sci](sci-2026-09-24.md) |
| Tests and proofs | 840 + ~900 test-sweep lines | [testing](testing-2026-09-24.md) |
| Cross-cutting (Astra) | 414 | [cross-cutting](cross-cutting-astra-2026-09-24.md) |

## Correctness defects the census found (fix before deleting around them)

1. **Declared refusal bypass:** four hand matchers decide what a definition is (`sci/reader.cljc:245,299,323`,
   `fn/signature.cljc:62`, `repl.clj:118`, `turn.clj:1941`); `(do (defn …))`, `let`, `when`, `(def k (fn …))` and macros skip the
   entry gate. clj-kondo cannot see macro-generated definitions either, so the gate observes the evaluation (lane running,
   option A); clj-kondo stays the one analyzer for rows and edges; the matchers are deleted.
2. **Identity depends on print bindings:** `seon.id/digest` hashes printed text; `*print-length* 1` changes `[1 2 3]`'s identity.
   Installed `hasch.core/edn-hash` is order- and print-independent (cross-cutting row 4).
3. **SCI attribute cache keyed by a colliding fingerprint:** two unequal schema populations share `1422904893` (row 2).
4. **Reach leaves omit fixture metadata:** a fixture-only change leaves the reuse key equal (row 9) — possible stale green.
5. **A repeated failed fault write bypasses `:panic`** (Flow's `already-reported?` arm), and a proc fault during work can be
   dropped by two buffers (execution row 1).
6. **A 105 MB failure-message datom rides every new-entity commit** (106 MB written per commit; store 61 GB) — three producers
   being fixed (lane fix-unbounded-failure-text), then default is reset.
7. **Two namespace-clause parsers both mis-read prefix lists** (`[clojure [edn :as e]]`); clj-kondo reads them correctly.
8. **Printed agent output has no storage bound** (20,000 prints → 200,744 chars; lane running).

## One owner per concern

| Concern | Keep (the one owner) | Delete (rolled our own / second paths) |
|---|---|---|
| What a definition is | evaluation observation at the SCI entrance; clj-kondo for rows/edges | the four hand matchers; `analyzer/analyze-forms` family (dead) |
| Namespace clauses, positions | clj-kondo (files), SCI (REPL) | two clause parsers; four offset calculators; two span-containment rules |
| Schema projection | Malli lazy registry over rows (12.4 ms vs 483 ms today) | hand build/fingerprint/delta/incremental/row loader (~1,900 with dynamic transport) |
| Predicates in schemas | Malli fork `m/eval` | three resolvers + 51 `register-core-predicate!` |
| Contract arming and refusals | one wrapper in `seon.instrument` over `m/-instrument` | per-call projection switching; double `m/explain`; test-only state/restore; call-preparation cache |
| Values printed for agents | `clojure.pprint` code-dispatch (prints `'x`, `#'v`, `#:ns{}`); one elision value | `seon.print` pretty-printer, second bounded writer, double budget cut, `render-default-ai-source` |
| A session's history | `walk/history` + `prompt/select` units | `seon.render.transcript`'s projection (11.9 s in the profile) |
| Exceptions shown | `Throwable->map` via `seon.error.refusal/chain` | three renderings; test-local chain text |
| Content identity | `seon.id` delegating to `hasch` (EDN) and `sha-256` (bytes) | framed canonical encoder; two SHA engines |
| Read currency | Datahike revision keys (fork seam exposed) | history-scan + replay + hash (~420); three hand revision memos |
| Projection on a db value | `carried-projection` (0.062 ms) | `carry-derived-projection` stamp (477 ms, identical result) |
| Datalog planner gap | fix in our Datahike fork (lane running) | caller-side `ground-attribute-rules` |
| SCI contexts | `sci/fork` of one base with host Vars installed directly (a JVM reload is visible without recopying) | seven context constructors; base cache keyed by attribute revisions + fault-recorder fn; hand topo sort |
| Running tests | `seon.test/run` | `seon.test.arm` (dead, 254); install-gate runner; `with-database` second world (826 tests); `accretion/auto-check` (Malli's function checker) |
| Core faults | `seon.fault/fault!` from the `var-process` wrapper | committer graph, fan-out, dropping buffers, first-ever dedup (~470); `log-line`/`log-report` |
| Background work | core.async.flow `futurize` on a bounded executor | the launcher's dead compute half (275) and the rest (400, unverified) |
| Configuration | `seon.config/effective` with explicit cluster + db | four re-discovery readers with different fallbacks |
| Process trees | `seon.cluster.process` over `babashka.process/destroy-tree` | shell's own tree identity/force |

## Fixes that belong in our forks, not beside them

Datahike: bound-attribute-variable scan (running); a public revision-currency seam; a total comparator for any value type
(then ~600 more codec lines go); merge base; revisions on `as-of` values. Malli: a seed for the function checker; an
instrument `:report` that can return. SCI: `bind-root!` on host Vars. core.async.flow: errors thrown while a proc stops are
dropped. clj-kondo: cache content check, in-memory text linting, `:rename`/`:as-alias` in analysis. edamame: ex-data for
duplicate keys and bad metadata.

## Order (waves, one owner per file, ≤100 added lines per slice)

1. **Correctness first:** defects 1, 2, 4, 5, 6, 8 above; reset default after 6.
2. **Pure deletions of dead code** (~600 lines, no design): `seon.test.arm`, `analyze-forms`, dead boot functions, the launcher's
   compute half, `log-line`/`log-report`, dead snapshot fields.
3. **Replace with the dependency, in each file's owner:** Malli lazy registry; `clojure.pprint` printer; `Throwable->map`; `hasch`;
   Datahike revision seam; `sci/fork` + host Vars (the checkpoint model, after its review corrections).
4. **Collapse second paths:** one session projection, one test runner, one fault route, one config read, one projection handoff.

Every slice measures its probe before and after, proves results equal, and deletes the callers' old path in the same commit.
