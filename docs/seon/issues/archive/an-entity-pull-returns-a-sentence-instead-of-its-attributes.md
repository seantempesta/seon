---
type: issue
status: resolved
severity: blocker
tags: [issue, render, agent, class/n1, wave/strict-repl-display, wave/live-drive-render]
---

# An entity pull must return its attributes, not a summary sentence

## Resolution — 2026-09-15, n1-render-substitution

`563034709` removes AI block selection from the structural value renderer.
Read-only real SCI evaluations on default now render function/config/turn
pulls as attribute maps (851 / 540 / 296 estimated tokens); the turn is no
longer empty. All three preserve the actual pulled result. The canonical
class regression also checks a full effect entity, counts and executable
elision requery forms. Post-adoption run eid 67228: 68 assertions, zero
failures/errors. Explicit block calls remain separately verified.

[Exact evidence and gate boundary](../../../prds/context-generation/research/n1-render-substitution-2026-09-15.md).
The batched orchestrator gate is pending; this closure records the structural
change and live proof, not an unrun full gate.

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

Dir-own-fns follow-up, 2026-09-14: a JVM-mode MCP probe through Juniper's
retained SCI context evaluated
`(seon.db/pull [:seon.fn/sym :seon.fn/private? {:seon.fn/ns [:seon.ns/name]}]
[:seon.fn/sym "my.agents.juniper/largest-customer"])`. The actual value
contained the existing function identity, `:seon.fn/private? false`, and
namespace `my.agents.juniper`, but `:seon.eval/shown` was exactly:

```text
Restart the JVM to remove stale loaded Var my.agents.juniper/largest-customer; it is absent from the published program graph.
```

The probe took 79 ms on default PID 23557. No restart was performed. This
confirms the wrong stale-Var presentation for an existing agent-installed
function; it does not establish a reason to reset the JVM. The directory
repair uses explicit documentation data and is separate from this renderer
selection defect.

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
[results-as-data audit](../../../prds/context-generation/research/results-as-data-audit-2026-08-14.md).

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

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.render-simplification-test`, `seon.sci.eval-test`). Audited HEAD `7e35df213:src/seon/sci/eval.clj:1930-1953` passes results to value/prepare; `src/seon/render/value.clj:271-289` still allows a declared renderer to replace a map. Before the no-JVM correction, disposable SCI probe `(seon.db/pull [:seon.fn/sym :seon.fn/private?] [:seon.fn/sym "seon.db/q"])` returned MCP text beginning `#object[clojure.lang.ExceptionInfo "projection failed: seon.sci.kernel/invoke refused argument 0 (0-based) at [:seon.db/db]`, saying expected immutable Datahike database, got nil. It did not reach the historical sentence substitution. This is an outward projection failure, also recorded in `class-outward-values-bypass-total-render-contract.md`, not confirmation of the original cause. Need the armed result-selection tests with explicit database and SCI custody; retain blocker pending proof.

surface: context-generation

## Verified at HEAD (2026-09-16, N1 verification)

**CONFIRMED — still open, still a blocker.** The earlier re-verification could
not reach the substitution because its probe omitted
`:seon.render.value/root` and the invocation inputs. With a complete render
request (connection, database, projection, agent profile, SCI ctx, caps,
`:seon.config/on-core-error`, `:seon.sci.eval/time-limit-ms`,
`:seon.render.value/root`) the substitution reproduces on the live `default`
cluster (pid 69622), read-only, three pulled entities:

```text
(seon.db/pull database '[*] [:seon.fn/sym "seon.db/q"])
  ;; 13 attributes, 9,655 characters of real data
  => "Restart the JVM to remove stale loaded Var seon.db/q; it is absent from
      the published program graph."          ; 100 characters, and false

(seon.db/pull database '[*] [:seon.config/cluster "default"])
  ;; 80 attributes, 3,460 characters
  => "Configuration default · manifest e93ac823e733.
      Model deepseek-flash (thinking disabled, max 65536 output tokens);
      evaluation 30000 ms; Flow 18 compute / 64 I/O; core faults panic."
                                             ; 179 characters

(seon.db/q '[:find [(pull ?e [*]) ...] :where [?e :seon.turn/id]] database)
  ;; 11 attributes, real turn 45587
  => ""                                      ; the EMPTY STRING
```

The third result is new and worse than the filed symptom: a pulled turn
entity renders to nothing at all. The same value with
`:seon.render.value/structural? true` renders correctly as EDN
(`{:db/id 45587, :seon.turn.work/situation :call, :seon.turn/agent #:db{:id
42034}, …}`), so the loss is entirely in producer selection, not in the
value.

The `:seon.fn` case is also a correctness lie, not only a presentation one:
`seon.problems/stale-var-ai` (`src/seon/problems.clj:424-430`) is declared as
the render pair for `:seon.problems/stale-var` (`src/seon/problems.clj:355`),
whose shape is satisfied by ANY map carrying `:seon.fn/sym` — so every
function row in the program graph is presented as a stale Var.

surface: render-selection (`src/seon/render.clj` project-node selection;
`src/seon/render/value.clj:271-289` allows a declared renderer to replace a
map).

Fix sketch: a declared `:seon.render/ai` pair is a CONTEXT BLOCK projection,
not a result projection — carry the caller's intent as explicit request data
(`:seon.render.call/source-output?` already exists) and select a family
producer only for block rendering; a value in result position always renders
structurally. That also removes the accidental `:seon.problems/stale-var`
match on every `:seon.fn` row without needing a shape tweak.
