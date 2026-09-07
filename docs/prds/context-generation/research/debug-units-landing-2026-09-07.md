---
type: research
status: active
tags: [context, render, web, schema, debug]
---

# Debug units landing — the page as the entity's declared attributes

Migration step 2 of
[the entity curation PRD](../plan/entity-debug-curation-prd-2026-09-06.md),
plus the count proof step 3 asks for. Everything below was observed on the
development cluster `juniper-context` under `tmp/juniper-context-live`
(page `http://127.0.0.1:7766/ns/my.agents.juniper/debug?subject=34620`,
Juniper is entity 34620) or in `test/seon/render/web_test.clj`.

## What the page is now

The section list is the unit list `:seon.render/units` declares on the
matching entity schema, in declared order, then any attribute the entity
still carries, then one "Other references" heading. Read live, in order:

```text
:seon.cluster.agent/id
:seon.cluster.agent/namespace
:seon.cluster.agent/cluster
:seon.cluster.agent/run              (absent — empty section)
:my.plan/steps                       (absent — empty section)
:my.plan/current-step                (absent — empty section)
:seon.cluster.message/_to
:seon.cluster.run/_agent
:seon.context.contribution/_agent
:seon.error/_agent
:my.plan/anchor                      (present, undeclared)
Other references                     (:my.plan.item/agent, :seon.db/user, …)
```

Each section carries the exact attribute key, its authored Malli
`:description`, links to the entities it references, the paired AI and HTML
outputs, then "Raw data and schema" and "Applicable render functions" under
disclosure.

## The declaration is read, never written in Clojure

`declared-entity-units` matches the pulled entity against the schema
projection with `seon.schema/matching-shapes-in` — the same shape discovery
the renderer's schema stage uses — and reads `:seon.render/units` from the
matching schemas' own properties. No entity shape is named in
`src/seon/render/web.clj`; any entity that declares units gets them. The
regression covers the agent's real declaration, an entity whose schemas
declare none, and a value that is not an entity.

## The description seam

Descriptions were never missing; the page read them from the wrong side.
`:seon.schema/form` is stored as a STRING, and
`seon.schema.form/attr-form-properties` answers `nil` for anything that is
not a vector, so every attribute reported "no description". The projection
already holds the PARSED form:

```clojure
(-> (get-in projection [:seon.schema.projection/forms attribute])
    seon.schema.form/attr-form-properties
    :description)
```

Four agent attributes gained the descriptions they lacked
(`resources/seon/schemas/seon.cluster.agent.edn`): id, namespace, cluster,
run.

## Refs, and why one unit refused

A pull returns a ref as `{:db/id n}`, which no render function can say
anything about — the namespace unit rendered `#:db{:id 34619}` and the HTML
column said `{} 1 keys`. Each unit now carries the connected entities the
page's own bounded observation already acquired, and each declared reverse
relationship is pulled as its entities (`{unit [*]}`), one pull per unit so a
large relationship refuses alone. Measured at the same page:

| unit | before | after |
|---|---|---|
| `:seon.cluster.agent/namespace` | `#:db{:id 34619}` | the real `ns` form plus its namespace card |
| `:seon.context.contribution/_agent` | a `:db/id` table | the contributions themselves |
| `:seon.error/_agent` | a `:db/id` table | the five faults with kind and message |
| `:seon.cluster.run/_agent` | 193 KB of `:db/id` table rows | 1.7 KB typed `datahike query-work budget exceeded` |
| whole paint | ~300 KB | ~150 KB |

Juniper's 609 runs exceed the declared query-work bound, so that unit reports
the bound it hit. That is the honest state until the history render function
(PRD step 4) gives the relationship its own newest-first bound and elision
value; a wall of 609 bare entity ids was worse than a refusal that names why.

## Previews stay in memory (step 3 count proof)

Ten loads of the live page, counted with `seon.db/q` before and after:

| count | before | after |
|---|---|---|
| runs (`:seon.cluster.run/agent` → juniper) | 609 | 609 |
| evaluations | 606 | 606 |
| run forms | 606 | 606 |
| faults (`:seon.error/agent` → juniper) | 5 | 5 |

The 548 → 609 growth recorded on 2026-09-06 does not recur. The focused
regression `inspecting-the-page-writes-no-run-evaluation-or-fault-facts`
holds the class: with the real evaluator in the fixture, three further
inspections leave the run, evaluation, form, and fault counts identical.

## Cost, and one mechanism deleted

The units page renders N sections instead of one, so per-unit work is the
whole cost model. Rendering every compatible alternative beside the selected
value multiplied each page by the candidate count — and for the AI projection
each of those is an evaluation. The page now renders ONE producer per unit
and projection, the selected one, and names the alternatives instead.
"Other references" likewise stopped reading the reference index a second time;
it is the incoming datoms of the observation the page already holds, which is
also what restored the retained-observation reuse across an unrelated commit.

## The suite's own reader was the clock

Two debug-feed regressions on a namespace subject stopped receiving their
first paint. The derivation was not the cause: every render call completed
(the slowest was 150 ms, the whole page about 0.5 s) and no proc error was
recorded. A raw byte probe read the same feed for 45 s and got exactly one
complete event of 949 459 bytes.

`read-patches!` rebuilt the entire accumulated buffer and re-ran a regex over
it after EVERY byte — quadratic in the page, invisible while pages were small.
The units page for `seon.flow` is most of a megabyte, which put the read past
the declared 20-second backstop. The helper now decides at each event
boundary instead of each byte.

## What this exposed

- [A render exception stops the render proc and every page then hangs
  silently](../../../seon/issues/a-render-exception-stops-the-cluster-render-proc-and-every-page-hangs.md).
  A `(into [] distinct coll)` in this lane's own code — the function where the
  transducer belongs — threw `ClassCastException` inside one page derivation
  and ended the cluster's only render proc. Every page then returned its shell
  with HTTP 200 and never painted; `curl -N` on the feed held an open socket
  and wrote zero bytes for 120 s; the proc row read `unknown`, which is what a
  never-started proc reads too.
- A preview whose environment names no `:seon.cluster.loop/evaluate` threw
  `IllegalArgumentException` out of `requiring-resolve` deep inside the shared
  evaluator — the same proc-killing shape. It is now a typed refusal naming
  the missing member, and the web fixture supplies the evaluator production
  always has, so its previews really execute.

## Still open, by owner

- `:seon.cluster.run/_agent` needs its bounded newest-first render function
  (PRD step 4); today it reports the query-work bound.
- `:seon.cluster.message/_to` and `:my.plan/anchor` render their AI projection
  as source at the namespace prompt; `:seon.cluster.agent/id` renders the bare
  string through the attribute's own declared producer. Whether the identity
  unit should instead render `whoami` is a render-declaration decision, not a
  page one.
- The debug request still parses a `path` cursor that no longer selects
  anything to render; the header shows it. The units replaced the drill.

## Screenshots

- `tmp/debug-units-shots/desktop-1440.png` — 1440 px: header, units, the
  reference graph rail.
- `tmp/debug-units-shots/narrow-700.png` — 700 px: the paired columns stack
  under one attribute heading.
