---
type: issue
status: open
severity: friction
tags: [issue, render, context, schema]
---

# Cluster, config, and bootstrap plan render as raw maps

## Problem

Three durable root-runtime entity schemas have no declared
`:seon.render/ai` or `:seon.render/html` producer:

- `:seon.cluster/cluster` (`resources/seon/schemas/seon.cluster.edn:2-10`);
- derived `:seon.config/entity` (`src/seon/schema/edn.clj:87-111`); and
- `:seon.bootstrap.plan/plan`
  (`resources/seon/schemas/seon.bootstrap.plan.edn:5-13`).

They are not obscure drill values. The root agent points at the cluster, and
the cluster requires refs to its config and bootstrap plan
(`resources/seon/schemas/seon.cluster.edn:5-10`). The ordinary walk follows
those refs and passes each pulled entity to the renderer
(`src/seon/render/walk.clj:338-345,387-403`). With no owning namespace or
schema property, selection ends at the value floor
(`src/seon/render.clj:115-133`).

## Evidence

A read-only prepl probe against the already-running `default` cluster on
2026-08-03 selected `seon.render.value/render-ai` for all three. The live
database had one row of each shape. The faces began:

```clojure
;; cluster
{:db/id 11158, :seon.cluster/bootstrap-plan #:db{:id 957},
 :seon.cluster/config #:db{:id 11156}, ...}

;; config
{:seon.print/level 8,
 :seon.config.ai/api-key-variable "DEEPSEEK_API_KEY",
 :seon.config.eval/time-limit-ms 30000, ...}

;; bootstrap plan
{:db/id 957, :seon.bootstrap.plan/digest "f182...d2e4",
 :seon.bootstrap.plan/forms
 [{:db/id 958, :seon.bootstrap.plan.form/context
   "You are an agent in a Seon cluster. This is a real Clojure REPL ..." ...}]}
```

The AI assembly places each selected unit's text into the agent walk
(`src/seon/render/walk.clj:541-644`), and the HTML page consumes the same walked
unit sequence (`src/seon/render/web.clj:306-390`). The floor itself correctly
tees one bounded print tree to both faces
(`src/seon/render/value.clj:205-223,399-423`); the missing important-schema
producers are the defect.

Full census and ranking:
[[render-coverage-audit-2026-08-03]].

The 2026-08-05 rename-pass Unit 6 gate found the next render defect after the
named producers landed. `seon.render-coverage-test` expected every important
AI face to stay within one to three lines, but the configuration face rendered
12 lines: its concise two-line cluster summary was followed by an "Available
models" catalog containing every model, price, and input modality. The gate
failed at `test/seon/render_coverage_test.clj:167`. This is readable but noisy
recurring context, and it still violates the bounded, decision-oriented
acceptance criterion below.

The bare 2026-08-05 gate reproduced the same var with the full configuration
summary plus four-model catalog, including prices and input modalities. A
focused run at pre-rename commit `401fd300e` failed identically. The 12-plus
line recurring face is therefore pre-existing render output, not rename
fallout.

## Owner

The three owning entity schemas and their owning namespaces:
`seon.cluster`, `seon.config`, and `seon.bootstrap`.

## Acceptance

- All three entity schemas declare named AI and HTML producers.
- A fresh depth-2 root walk selects those producers, not
  `seon.render.value/render-ai` or `/render-html`.
- The cluster face names the cluster and summarizes its declared connections
  without raw entity IDs.
- The config face is bounded and decision-oriented; it does not dump every
  effective dial into recurring context.
- The bootstrap-plan face names plan identity/digest and form purpose without
  copying full instruction/context payloads.
- AI context and the namespace page remain two projections of the same walk.
