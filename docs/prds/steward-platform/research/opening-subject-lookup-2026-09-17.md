---
type: research
status: current
created: 2026-09-17
tags: [research, steward, bootstrap, render-walk, contracts, absence-as-health]
---

# An opening candidate's subject is the entity lookup (2026-09-17)

Lane: opening-subject. Attribution and evidence:
[fixture-write-sweep-2026-09-17.md](fixture-write-sweep-2026-09-17.md),
"The two ERRORS, attributed", item 1. Owning commit of the defect:
`91f536c36` "Derive opening episode from symbol frontier".
Repair commits: `9107232d7` (the producer and its class regression),
`58bd7f4f3` (the walk's own episode fixtures).

## The defect, as the producer wrote it

`seon.bootstrap/direct-candidates` chose a candidate's subject with

```clojure
subject (or (namespace-subject lookup) lookup)
```

and `namespace-subject` returns the VALUE inside `[:seon.ns/name my.foo]` —
a bare symbol. `:seon.repl/subject` is declared
`:seon.render.walk/lookup` (`resources/seon/schemas/seon.repl.edn:17`,
`:79`), which is an entity lookup or a `[lookup attribute]` tuple
(`resources/seon/schemas/seon.render.walk.edn:18`). A symbol is neither, so
the armed `seon.render.walk/ordered-episode` refused its whole argument:

```
seon.render.walk/ordered-episode refused argument 0 (0-based) at
[:seon.repl/candidates 4 :seon.repl/subject]: expected an integer, got a
symbol. Contract: :seon.repl/pull-result.
```

The frontier `91f536c36` introduced is spelled in symbols, and the producer
followed the frontier's spelling into the subject. The subject is not a
frontier key: it is the entity the candidate is ABOUT, and
`seon.render.walk/reference-keys` (`src/seon/render/walk.clj:766`) already
relates a lookup to its symbol and string spellings for exactly this
matching. This is also not the result-handle question — the ruled
`result/e<id>` symbol lives on `:seon.repl/handle`.

The neighbouring producers were all already right:
`usage-demonstration-candidates` (`:432`) passes `subject-lookup`,
`listing-candidates` passes `(:seon.render.walk/lookup unit)`, and
`root-candidate` passes the request's lookup.

## Measured live on `default` (pid 88182, Juniper's real facts)

`seon.bootstrap/pull-result` for `[:seon.agent/id "juniper"]` at
distance 3, under the instance projection state:

| | candidates | subjects that are not a `:seon.render.walk/lookup` | `ordered-episode` |
|---|---|---|---|
| before | 272 | **252** | refused at `[:seon.repl/candidates 10 :seon.repl/subject]` |
| after | 272 | **0** | derives the episode |

The namespace candidate still names the namespace, through the entity:

```clojure
{:seon.repl/key     [[:seon.ns/name my.agents.juniper] 0]
 :seon.repl/subject [:seon.ns/name my.agents.juniper]
 :seon.repl/entry   {:seon.repl/form (dir my.agents.juniper)}}
```

With nothing settled the episode is exactly the root entry
(`(seon.db/pull '[*] [:seon.agent/id "juniper"])`), which is the
fail-closed behaviour `ordered-episode` documents.

Selection cannot narrow: `reference-keys` on `[:seon.ns/name my.foo]` is
`#{[:seon.ns/name my.foo] my.foo "my.foo"}`, a strict superset of the bare
symbol's `#{my.foo "my.foo"}`, and both the frontier test
(`introduced-subject?`) and `explained-symbol?` consult only that set.

## What changed

- `namespace-subject` is renamed `lookup-namespace-name`: it derives the
  namespace NAME, which is what the executable `(dir my.foo)` form and the
  demonstration-namespace membership test need, and it is never a subject.
  The misleading name is what carried the defect.
- `direct-candidates` hands `lookup`.
- `seon.bootstrap-test`'s expectation followed the producer's own ruling:
  `(= namespace-name (:seon.repl/subject namespace-candidate))` asserted the
  defect and now asserts `[:seon.ns/name namespace-name]`. The neighbouring
  `outside.pull` membership check compares the lookup spelling, because
  comparing a bare symbol is now a check nothing can fail.
- New class regression
  `every-opening-candidate-subject-is-an-entity-lookup`: it validates every
  candidate `seon.bootstrap` actually produces against
  `:seon.render.walk/lookup` through `seon.schema/valid-candidate-value?`,
  and then drives the armed `ordered-episode` over those same real
  candidates. 268 assertions, one per produced candidate.

## The same class, in the walk's own fixtures

`seon.render.episode-test` and `seon.render.history-test` hand-rostered
every `:seon.repl/subject` as a bare symbol, so `ordered-episode` refused
their pull results too — four tests red on this class in the namespace that
owns the function, none of them in the sweep's pass-4 table. Subjects are
now `[:seon.ns/name my.turn]` and `[:seon.fn/sym "my.turn/complete"]`; the
carried fact order the gates assert is unchanged, because `reference-keys`
relates those lookups to the symbol and string spellings the settled print
nodes introduce.

Two further reds surfaced behind that one, both the named class of a check
that reads absence as health:

- `episode-test` authored `{:seon.turn/id … :seon.turn/agent …}` directly.
  Only `seon.turn/open-tx` writes the agent's runtime turn edge
  (`src/seon/turn.clj:348`), so the write was refused and the test read an
  agent with no stored evaluation ("Key must be integer" two lines later).
  It opens through `open-tx` now, and its evaluation row carries the
  `:seon.cluster.eval/at` the writer requires.
- `history-test` handed `ordered-episode` a lazy sequence of candidates.

## In-process verification (default, pid 88182, canonical fixture, armed)

One test at a time on a daemon thread, `seon.test/run` with
`{:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db c))
  :seon.test/remaining-ms 100000}`, each test namespace reloaded through
`seon.test`'s own loader first.

| test | batch 75 | now |
|---|---|---|
| `seon.bootstrap-test/drive-free-generation-is-pure-deterministic-and-pull-gated` | 1 E | **8 / 0 / 0** |
| `seon.bootstrap-test/reborn-opening-retains-authored-namespace-membership` | 1 E | **5 / 0 / 0** |
| `seon.bootstrap-test/every-opening-candidate-subject-is-an-entity-lookup` | new | **268 / 0 / 0** |
| `seon.render.episode-test/saved-shown-text-is-a-settled-evaluation-without-a-print-node` | 0 / 1 / 1 | **4 / 0 / 0** |
| `seon.render.history-test/generated-episodes-have-two-independent-gates` | 0 / 0 / 1 | **4 / 0 / 0** |
| `seon.render.history-test/every-emitted-form-is-at-the-explained-set-fixed-point` | 0 / 0 / 1 | **2 / 0 / 0** |
| `seon.render.history-test/the-generated-prefix-stops-at-the-first-unsettled-entry` | 0 / 0 / 1 | **2 / 0 / 0** |

## Verification boundary

- The isolated gate is the orchestrator's:
  `tmp/orchestrator/gate-requests/opening-subject.txt` (untracked; `tmp/`
  is gitignored).
  Everything above is in-process iteration on the shared `default` JVM.
- **The generated opening does not drive on `default`.** `pull-result` and
  `next-entry` have no caller in `src/` — only tests call them — and
  Juniper's `seon.bootstrap/run-id` turn `f4c23ce4f31a` carries zero
  evaluations. The live proof is therefore the producer's own output over
  Juniper's real facts (the table above), not a regenerated system turn 0.
  Juniper's stored prompt comes from `seon.turn/system-turn`, a different
  path, and this defect never reached it. No provider call was made.
- `default` carries no recorded `:seon.error/message` at all, so the
  cluster's fault facts are not evidence either way here.
- **Adoption of `src/seon/bootstrap.clj` into `default` did not complete**
  during this lane: three other lanes were publishing concurrently and the
  first attempt ended
  `:stale-branch-head {:branch :current-src}`. The in-process runs above
  exercised the repaired definition, redefined at the JVM REPL before the
  file edit and identical to the committed source apart from the helper's
  name. Adoption freshness for `bootstrap.clj` on `default` is UNCLAIMED.
- `src/seon/fn.clj`, `src/seon/program.cljc`, `src/seon/sci/eval.clj`,
  `test/seon/fn_test.clj`, `test/seon/program_test.clj`,
  `test/seon/loop_proof_test.clj` and `test/seon/sci/eval_test.clj` carried
  other lanes' uncommitted edits throughout; none was read into, touched or
  committed by this lane.
