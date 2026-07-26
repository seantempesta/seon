---
type: prd
status: active
tags: [prd, agent, architecture, testing]
---

# The nucleus handbook — read this to GET IT

[README.md](README.md) owns every ruling and the one ordering. This file
owns the UNDERSTANDING: why we work this way, how the construction loop
actually runs, and the mentality a fresh agent needs so the owner never
has to re-teach it. It sequences nothing.

## Why we are rewriting from a fresh tree

State A works but carries its history: five processes, wire protocols on
the agent path, duplicate mechanisms, schemas scattered through load
order, machinery for problems the current design no longer has. We spent
2026-07-26 proving the biggest example end to end: a full effect-replay
identity layer, built and proven live against a real kill — then DELETED
the same night, because the owner's crash-model question revealed the
design didn't need it. The lesson is structural: **the cheapest place to
delete code is before it exists, and the way to find out is to build the
smallest real thing and let live falsifiers + owner questions attack the
design while it is still a decision.**

So: a fresh `src/` grown from zero, with State A as the QUARRY and git
as the archive — not zero knowledge, zero *baggage*. We know roughly
where State B is; the fresh tree forces every piece to re-earn its
place instead of being grandfathered in.

## The construction loop (this is the experiment)

Per nucleus namespace, always the same cycle:

1. **The orchestrator authors the contract package**: the data model
   (schemas), the function contracts (`:malli/schema` on defn stubs
   whose bodies throw "awaits implementation"), and the SEALED
   acceptance tests — generative properties first, examples only as
   teaching docs. Identity/effect/custody contracts get a crash-walk
   table (kill at every point, one row each) BEFORE sealing — we sealed
   one contract without it and shipped two defects.
2. **One sol lane implements that one namespace** until the tests are
   green. It may not touch a schema or a test. **Stop-on-friction is the
   heart of the model**: if a contract seems wrong or unimplementable,
   the lane STOPS and reports the exact friction. This worked on cycle
   one — the lane caught the author's own fixture defect and refused to
   hack around it.
3. **The orchestrator reviews the diff** against the sealed surface and
   the standing bars (below), revises the package as its author when
   friction is real, reseals, relaunches. Friction reports are the
   experiment's primary data — record them.
4. **A live falsifier** (not a fixture) proves the rung on the real
   system before the next rung builds on it.

Lane mechanics: `bin/codex-agent run <name> "<spec>"` background-tracked;
`status` BEFORE any `resume`; a completed task with no summary file means
the turn ended mid-work, not that the lane finished; never sandbox a
lane; specs name owned paths and demand path-limited commits.

## The feedback loop (never rebuild to think)

The nucleus NEVER touches the operator/artifact machinery during
development. The instant loop is a plain source-classpath JVM:

```bash
clojure -M:writer:host:writer-test -e \
  "(require 'seon.cluster.run-test) (clojure.test/run-tests 'seon.cluster.run-test)"
```

Seconds per cycle, in-memory Datahike, no AOT, no bin/seon. A REPL
(`clojure -M:writer:host`) for probe-first development. Know the traps:
the OLD writer runs from an AOT jar, so `eval_clj` + `:reload` there
pulls stale code; the AOT/CDS publication goes stale on every JVM source
edit (a ~45s rebuild tax) — both dissolve when the dev/publish split
lands, and until then the nucleus loop above avoids them entirely.

## The mentality (each of these is an owner ruling; violating one is a bug)

- **Fail loud and hard in dev; never fail in production; agents always
  get proper errors** — flat `:seon.error` values that steer. One config
  dial decides dev/prod. Nothing throws into an agent loop.
- **Crashes are rare and NOTHING re-executes.** Recovery = reopen the
  database, mark dangling receipts `:interrupted`, the agent adapts from
  derived context. Absence is the one representation a dead process
  cannot corrupt — which is also why state is derived from primitives
  (`open?` = no `closed-at`), never stored as flags. A boolean field is
  legitimate only when someone genuinely ASSERTS the false.
- **Generative properties guide the design.** The acceptance surface is
  properties over the domain. The edge-case tripwire: catching yourself
  writing point tests to fence edge cases is a DESIGN VERDICT — stop,
  find the construction that makes the class unrepresentable.
- **Every [:fn] schema carries an honest generator** (its output domain
  a subset of the predicate's acceptance, covering real partitions).
  Malli never validates generator overrides — a dishonest one
  green-washes everything downstream.
- **Shed, not port.** The port manifest's default verdict is `dead`; a
  rung that ports more than it sheds is suspect. Ask of every survivor:
  is this simpler than it was?
- **No hand lists, no name-prefix rules, no magic numbers.** Every
  classification is computed from facts; every constant lives in the
  defaults document with units and provenance; `(or x 60000)` is the
  banned shape.
- **Smart defaults everywhere.** `bin/seon start` just works; everything
  optional; `default` is just a name; multi-cluster and per-instance
  REPL reachability from day 0; no ambient-one-cluster singletons.
- **The recurring failure class of the whole program is checks that read
  ABSENCE OF SIGNAL as health** (a log-name glob, a d/q on a wire
  descriptor, a regression walking less than the writer admits — all
  found 2026-07-26 by running the real thing). When you write a check,
  ask what it reports when the subject is absent; if the answer is
  "fine", the check is worse than nothing.

## Where things are defined

- **Attribute/entity schemas**: PROVISIONAL direction (owner leaning
  yes, seal pending in [unsettled.md](unsettled.md)): EDN data files,
  one per attribute namespace (`schema/<attr-ns>.edn`), admitted as ONE
  validated population at boot — every reference must resolve at
  admission, killing the load-order/dangling-ref class that bit three
  times on 2026-07-26. Until sealed, N2's in-code `register!` calls
  stand.
- **Function contracts**: `:malli/schema` metadata ON the defn — they
  describe the function and travel with the var into the program graph.
- **Named predicates**: code, `register-core-predicate!`, referenced by
  symbol from schema forms.
- **Config**: two phases — a closed, tiny bootstrap schema (pre-store),
  everything else reconciled into database facts (explicit apply,
  converged = zero writes). The shipped default config is THE defaults
  document.
- **Tests**: fresh `test/` holds only nucleus acceptance suites after
  R0; `ls test/` is the honest list of what is proven.

## The metrics at every rung review

Blocks compose (next rung uses only public contracts) · the data model
tightens (attribute count, every attribute earning its place) · the
codebase shrinks (`src-old/` only ever shrinks; growth without
retirement is rejected) · properties over examples (a flat property
count with growing examples fails).

## What State A still teaches (the quarry map)

The proven pieces the nucleus adopts as libraries: Datahike (`:self`
writer, CAS, one write connection per store), `seon.schema` + the malli
bridge, `seon.sci.eval` (one guarded eval: fork + `:interrupt-fn` +
time-limit as the only limit, computed binding table, home-require
exposure), `seon.flow` (flow.spi), the JVM indexer + pages + template
store (build-time publish machinery), `seon.repl.parse`. The measured
laws (L1–L20 in README §4) are still the walls of the maze — they were
paid for with real experiments and do not reset with the tree.
