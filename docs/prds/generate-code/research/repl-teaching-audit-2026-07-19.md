---
type: research
status: active
tags: [research, agent, cljs, architecture]
---

# REPL and generated-development context audit

## Question

Do the instructions an agent actually receives agree with the landed
multi-namespace parser/evaluator, and what additional context does
`seon.ai/generate-code!` require?

## Landed execution contract

Ordinary `:batch` replies already use the multi-namespace path. The turn reads
the reply once with `seon.repl.internal/parse-program`; the projection groups
namespace sections, recognizes `schema/register!` through real aliases and
refers, orders generated namespace requirements, promotes schemas before the
remaining forms in their namespace, and retains ordered entries and spans.
`seon.eval/eval-batch!` evaluates the projection sequentially, awaits each
top-level Promise, skips generated dependents whose prerequisite failed, and
continues independent namespace sections.

This behavior does not apply to `:stream`, which deliberately closes the
provider stream after the first complete top-level form. Therefore a planning
agent must resolve `:batch` from its own database-backed launch configuration;
the cluster singleton cannot decide this for every named model variant.

## Instruction drift

The active manifest text, source fallback, transcript masthead, and REPL skill
do not currently form one accurate doctrine:

- neither always-on instruction source teaches a valid multi-namespace batch,
  dependency order, or schema promotion;
- the source fallback says Markdown fences fail even though the parser strips
  fence lines;
- its `#code` example closes the sentinel on the same line as other syntax,
  while the parser requires a line containing exactly the sentinel;
- it says executable input must begin a line with `(`, but a parenthesized
  form embedded after bare prose is still executable;
- it says current and required namespaces are full, while required namespaces
  render compact unless selected by the block's `full-source` presence-set;
- the REPL skill treats one-form diagnosis as universal and says leading
  syntax quote executes, while the parser deliberately classifies leading
  quote/unquote scaffolding as prose; and
- the architecture says batch execution is source ordered, while generated
  namespace sections are dependency ordered and schema-first.

The correct split is not another instruction system. One canonical always-on
floor teaches the grammar shared by ordinary REPL turns. The additive
`:generate-code` block teaches the specialized whole-program assignment and
renders only while the current run cause belongs to generated work.

## Generated-development assignment

`generate-code` is the public operation and `:generate-code` is the derived
context block; there is no stored mode flag. The planner remains responsible
for the difficult design:

- choose existing or new namespace boundaries;
- define schemas and data ownership before dependent functions;
- define behavioral contracts and tests without pinning incidental
  implementation details;
- compose calls through explicit `:require` edges; and
- emit the best executable ordinary ClojureScript first pass.

Downstream namespace agents do not justify placeholders or knowingly invalid
code. They receive the original contract, accepted prefix, localized failures,
and relevant source so they can validate and repair mistakes that remain after
the planner's best pass.

The ordinary `:namespaces` block remains the only source renderer. While a
generated assignment is active, orchestration replaces its `full-source`
presence-set with the exact selected owners and assignment namespaces. An
explicit `.internal` symbol may override ordinary hidden-namespace filtering;
test namespaces remain hidden, and an incidental prefix match never widens
selection. Inactive and ordinary agents retain ordinary block configuration.

## Blocking seams

1. Named model variants copy provider/model attributes but not REPL mode, and
   prompt acquisition still reads only the cluster singleton. Planning and
   generated repair variants must select `:batch` per agent.
2. No progressive `:generate-code` block is installed yet.
3. Explicitly pinned `.internal` namespaces are filtered before
   `full-source` selection.
4. Persistent full-source replacement is unsafe if a namespace resident can
   receive another assignment while its prior run remains active. Assignment
   availability and context publication need the same durable fence.
5. Delimiter repair happens after program projection. A broken namespace
   declaration that later becomes readable is not reprojected with its
   following forms. The MVP must either repair before projection or describe
   this limit honestly; it cannot claim those forms were mechanically
   recovered as one namespace unit.

## Acceptance

- A planning variant resolves `:batch` without changing the cluster default.
- One example reply contains two real namespace declarations, schemas,
  requirements, functions, behavioral tests, a top-level Promise, an update,
  and supported deletion behavior, and its teaching matches execution.
- Exact full-source pins reveal selected `.internal` source while ordinary
  rendering still hides it and always hides test namespaces.
- The source fallback, active manifest text, transcript masthead, REPL skill,
  and architecture make no contradictory claims.
- One generated assignment publishes its exact source context before its
  atomic addressed message can wake the resident, and a second assignment
  cannot overwrite the first assignment's active context.
