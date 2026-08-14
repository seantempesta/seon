---
type: issue
status: open
severity: blocker
tags: [issue, render, agent, class/n1, wave/strict-repl-display, wave/live-drive-render]
---

# An entity pull must return its attributes, not a summary sentence

## Problem

When an agent pulls an entity whose family declares a `:seon.render/ai`
producer, the queried value is DISCARDED and the producer's English summary is
delivered in result position instead. The agent asked the database a question
and received a paragraph about the answer.

This is the same defect already filed for the run family
([run-renderer-narrates-forms-and-receipts](run-renderer-narrates-forms-and-receipts.md)),
but it is not a run-family defect: the substitution happens in the shared
selection construction, so it fires for the cluster, config, message, error,
problems and schedule families too. Fixing the run renderer alone leaves the
class alive.

## Evidence

Observed live 2026-08-14 in the Drive 1 stored capture facts
(`tmp/drive-1-root`). It accounts for **74 of 210 result positions** across the
six captures that carry a prompt.

The information loss, measured live in the drive JVM. The pull genuinely
returns **6,596 characters across 11 attributes** —
`:seon.cluster.run/forms`, `/trigger`, `/plan-digest`, `/opening-commit-id`,
`:seon.cluster.work/situation` among them. The agent received **79 characters**
naming three: **98.8% of the queried data never reached the agent that asked
for it.**

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.cluster.run/id "bootstrap:root"])
Run bootstrap:root, opened #inst "2026-08-14T11:24:27.135-00:00". It is running now, held by 69568-1786706658408.
```

A pull of a FUNCTION ROW returns an operational instruction about the JVM
instead of the row's spec, doc, or arities:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.fn/sym "seon.operator/collect!"])
Restart the JVM to remove stale loaded Var seon.operator/collect!; it is absent from the published program graph.
```

A pull of a CONFIG ROW returns a settings paragraph whose every number was a
queryable, joinable fact:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.config/cluster "default"])
Configuration default · manifest 637c5f03a6ad.
Model deepseek-v4-flash (thinking disabled, max 65536 output tokens); evaluation 30000 ms; Flow 18 compute / 64 I/O; core faults panic.
```

Attribution is verbatim, not inferred — live in the drive JVM:

```clojure
(run/render-ai (assoc (d/pull db '[*] [:seon.cluster.run/id "bootstrap:root"])
                      :seon.db/db db))
;;=> "Run bootstrap:root, opened #inst \"2026-08-14T11:24:27.135-00:00\". It completed."

(problems/stale-var-ai {:seon.fn/sym "seon.operator/collect!"})
;;=> "Restart the JVM to remove stale loaded Var seon.operator/collect!; it is
;;    absent from the published program graph."
```

Both match the capture byte for byte.

Producing seams, by count: `seon.cluster.run/render-ai`
(`src/seon/cluster/run.clj:1913-1966`, 16); `seon.problems/stale-var-ai`
(`src/seon/problems.clj:434-438`, 15); `seon.cluster.message/render-ai`
(`src/seon/cluster/message.clj:460-471`, 13); the error prose at
`src/seon/error.clj:604-627` (10); `seon.cluster/render-ai`
(`src/seon/cluster.clj:155-168`, 6) and the config family's twin (6); the
already-filed form renderer (4) and the eval error face (4). All are selected
through `seon.render/project-node*` (`src/seon/render.clj:445-495`).

A re-narration can also lie about WHICH entity it describes, which a data
result structurally cannot — see
[a-run-history-entry-can-name-a-different-run-than-its-form-pulled](a-run-history-entry-can-name-a-different-run-than-its-form-pulled.md),
corroborated three more times in this capture.

Full walk and counts:
[results-as-data audit](../../prds/context-generation/research/results-as-data-audit-2026-08-14.md).

## Owner

`seon.render`'s one selection/projection construction owns the class; each
named family renderer owns its own arm.

## Acceptance

A value in result position renders as data. The `/ai` projection is for a
context BLOCK, never for a value an agent's own form just computed. Each family
arm returns its facts: a run returns its pulled attributes and derives its
disposition from `:seon.cluster.run/closed-at` presence; a stale Var returns the
`:seon.fn` row, or a flat `:seon.error` VALUE naming the stale symbol when no
row exists; a message returns the message map; an error returns the
`:seon.error` value `seon.error/diagnostic` already constructs. One regression
per family asserts the pulled attributes are present in the result, not an
English template.
