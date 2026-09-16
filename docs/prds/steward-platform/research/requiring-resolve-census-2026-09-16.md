---
type: research
status: active; census complete, class (a)+(b)+(d) applied in every free file
created: 2026-09-16
tags: [research, workarounds, dissolution, requiring-resolve, load-cycles, program-graph]
---

# `requiring-resolve` census — 147 text hits, 145 call sites, five classes

Answers ranked item #25 of
[workaround-inventory-2026-09-16](workaround-inventory-2026-09-16.md) §2:
*"146 `requiring-resolve` sites ... per-call resolution is the
fetch-at-call-time defect AGENTS §2.1 names as the recurring performance
killer; `declare` closes the forward references for free."*

Ground: `steward-platform` at `0dec68dc4`. Read end to end for this note:
[AGENTS.md](../../../../AGENTS.md) §0–§3, §5–§7;
[workaround-inventory-2026-09-16](workaround-inventory-2026-09-16.md) §2 and
the ranked list.

## Method

`rg -c requiring-resolve src/` reports **147** hits across 34 files. Two are
prose, not call sites — `src/seon/sci/eval.clj:1045` (docstring) and
`src/seon/test/runner.clj:3806` (comment about `REQUIRE_LOCK`) — leaving
**145 real call sites**.

The cycle evidence is a require graph built from every `ns` form under
`src/` (107 namespaces, reader conditionals allowed), with the transitive
closure of each namespace's `:require` set. For a site in namespace `C`
naming target namespace `T`:

- `T = C` → **class (a)**, a forward reference inside one namespace.
- `C`'s `ns` form **already requires** `T` → **class (d)**: the resolution
  buys nothing; call the var.
- `T`'s transitive requires **contain `C`** → **class (b)**, a genuine load
  cycle. Adding `C → T` would close it.
- `T` is outside `src/` (`test/`, `script/`, `resources/`) → **class (c)**,
  an optional dependency deliberately loaded late.
- `T` is third-party, or in `src/` and does not reach `C` → **class (d)**: no
  cycle exists; a plain `:require` is admissible.
- The symbol is **computed** (a handler, detector, predicate, projection,
  populate/activation or coercion symbol carried as a fact or argument) →
  **class (e)**.

Class (e) is not a workaround. It is the symbols-as-data idiom AGENTS §3
requires — `resources/seon/schemas` stores a symbol, and `requiring-resolve`
is how a stored symbol becomes the var it names
(`src/seon/schema/edn.clj:454` states this in its own docstring). All 21 are
legitimate and none is touched.

## Counts

| class | what it is | sites | disposition |
|---|---|---|---|
| **a** | forward reference within one namespace | **1** | `declare` |
| **b** | genuine load cycle between two namespaces | **35** | one resolution per boundary, held in a `delay` |
| **c** | optional dependency loaded late (outside `src/`) | **11** | keep, one-line comment naming why |
| **d** | dodge for a cycle that does not exist | **79** | plain `:require` / direct call |
| **e** | dynamic resolution of a stored symbol | **21** | **legitimate — leave** |
| — | prose, not a call site | 2 | — |

The single largest sub-population is one file: **`src/seon/turn.clj` holds 14
class (d) sites naming `seon.sci.eval`, whose own `ns` form already reads
`[seon.sci.eval :as sci.eval]` (`src/seon/turn.clj:33`).** Every one of them
resolves a var, per call, that the alias names for free.

The class (c) targets are exactly three namespaces:
`seon.operator.runtime` (5 sites — it lives in
`resources/seon/operator/runtime.clj`, off the source path),
`seon.test-support` (5 sites, `test/`), and `seon.fresh-operator` (1 site,
`script/`). The five `seon.operator.runtime/root-executors` sites are class
(c) by load but still per-call: they resolve a process-global executor
registry on every dispatch (`src/seon/effect.clj:465`,
`src/seon/flow.clj:672,1092,1135,1246`), which is both the fetch-at-call-time
defect and the registry the inventory §9 already flags.

## The table

| file:line | caller | target | class | evidence | tree |
|---|---|---|---|---|---|
| `src/seon/ai.clj:594` | `seon.ai` | `coercion` | e | symbol comes from a fact/argument |  |
| `src/seon/bootstrap.clj:133` | `seon.bootstrap` | `seon.turn/episode-runs` | d | ns form already requires seon.turn |  |
| `src/seon/bootstrap.clj:141` | `seon.bootstrap` | `seon.turn/unanswered-triggers` | d | ns form already requires seon.turn |  |
| `src/seon/call_preparation.clj:963` | `seon.call-preparation` | `symbol-name` | e | symbol comes from a fact/argument |  |
| `src/seon/cluster.clj:2292` | `seon.cluster` | `seon.issue/adopt!` | d | seon.issue does not reach seon.cluster | held |
| `src/seon/cluster/agent.clj:218` | `seon.cluster.agent` | `seon.issue.opening/source` | d | seon.issue.opening does not reach seon.cluster.agent |  |
| `src/seon/cluster/source.clj:206` | `seon.cluster.source` | `populate` | e | symbol comes from a fact/argument | held |
| `src/seon/cluster/source.clj:216` | `seon.cluster.source` | `activation` | e | symbol comes from a fact/argument | held |
| `src/seon/cluster/source.clj:509` | `seon.cluster.source` | `seon.test.runner/commit-results!` | b | seon.test.runner transitively requires seon.cluster.source | held |
| `src/seon/cluster/source.clj:568` | `seon.cluster.source` | `seon.issue/index!` | d | seon.issue does not reach seon.cluster.source | held |
| `src/seon/cluster/source.clj:570` | `seon.cluster.source` | `seon.issue/notes` | d | seon.issue does not reach seon.cluster.source | held |
| `src/seon/db.clj:121` | `seon.db` | `seon.error/diagnostic` | b | seon.error transitively requires seon.db |  |
| `src/seon/db.clj:691` | `seon.db` | `datahike.pull-api/pull-plan-selector` | d | ns form already requires datahike.pull-api |  |
| `src/seon/db.clj:842` | `seon.db` | `datahike.pull-api/pull-plan-with-evidence` | d | ns form already requires datahike.pull-api |  |
| `src/seon/db.clj:847` | `seon.db` | `datahike.pull-api/pull-many-plan-with-evidence` | d | ns form already requires datahike.pull-api |  |
| `src/seon/db.clj:1476` | `seon.db` | `datahike.pull-api/pull-plan-spec` | d | ns form already requires datahike.pull-api |  |
| `src/seon/db.clj:2215` | `seon.db` | `seon.call-preparation/snapshot` | b | seon.call-preparation transitively requires seon.db |  |
| `src/seon/db.clj:2220` | `seon.db` | `seon.call-preparation/plan-for` | b | seon.call-preparation transitively requires seon.db |  |
| `src/seon/db.clj:2721` | `seon.db` | `seon.error/explain-problem` | b | seon.error transitively requires seon.db |  |
| `src/seon/db.clj:3308` | `seon.db` | `seon.error/render-ai` | b | seon.error transitively requires seon.db |  |
| `src/seon/db.clj:3315` | `seon.db` | `seon.error/render-html` | b | seon.error transitively requires seon.db |  |
| `src/seon/effect.clj:465` | `seon.effect` | `seon.operator.runtime/root-executors` | c | target outside src/ (test/, script/, resources/) |  |
| `src/seon/effect.clj:696` | `seon.effect` | `deref` | e | symbol comes from a fact/argument |  |
| `src/seon/error.clj:826` | `seon.error` | `seon.call-preparation/supplied-map-entries` | d | seon.call-preparation does not reach seon.error |  |
| `src/seon/error.clj:942` | `seon.error` | `seon.sci.eval/docstring-parts` | b | seon.sci.eval transitively requires seon.error |  |
| `src/seon/flow.clj:672` | `seon.flow` | `seon.operator.runtime/root-executors` | c | target outside src/ (test/, script/, resources/) |  |
| `src/seon/flow.clj:1092` | `seon.flow` | `seon.operator.runtime/root-executors` | c | target outside src/ (test/, script/, resources/) |  |
| `src/seon/flow.clj:1135` | `seon.flow` | `seon.operator.runtime/root-executors` | c | target outside src/ (test/, script/, resources/) |  |
| `src/seon/flow.clj:1246` | `seon.flow` | `seon.operator.runtime/root-executors` | c | target outside src/ (test/, script/, resources/) |  |
| `src/seon/fn.clj:949` | `seon.fn` | `seon.error/diagnostic` | d | seon.error does not reach seon.fn |  |
| `src/seon/instrument.clj:442` | `seon.instrument` | `deref` | e | symbol comes from a fact/argument |  |
| `src/seon/issue.clj:467` | `seon.issue` | `(computed)` | e | symbol comes from a fact/argument |  |
| `src/seon/issue.clj:627` | `seon.issue` | `seon.test/verified?` | d | seon.test does not reach seon.issue |  |
| `src/seon/issue.clj:635` | `seon.issue` | `seon.turn/turns-left` | d | seon.turn does not reach seon.issue |  |
| `src/seon/issue.clj:851` | `seon.issue` | `seon.cluster.agent/creation-tx` | d | seon.cluster.agent does not reach seon.issue |  |
| `src/seon/issue.clj:879` | `seon.issue` | `seon.turn/generated-run-tx` | d | seon.turn does not reach seon.issue |  |
| `src/seon/issue.clj:912` | `seon.issue` | `seon.turn/next-id` | d | seon.turn does not reach seon.issue |  |
| `src/seon/issue.clj:928` | `seon.issue` | `seon.ai/agent-overlay` | d | seon.ai does not reach seon.issue |  |
| `src/seon/issue.clj:935` | `seon.issue` | `seon.turn/open-call` | d | seon.turn does not reach seon.issue |  |
| `src/seon/issue.clj:952` | `seon.issue` | `seon.turn/turns-left` | d | seon.turn does not reach seon.issue |  |
| `src/seon/issue.clj:954` | `seon.issue` | `seon.turn/episode-runs` | d | seon.turn does not reach seon.issue |  |
| `src/seon/issue.clj:957` | `seon.issue` | `seon.cluster.message/delivery` | d | seon.cluster.message does not reach seon.issue |  |
| `src/seon/maintenance.clj:56` | `seon.maintenance` | `projection` | e | symbol comes from a fact/argument |  |
| `src/seon/plan.clj:583` | `seon.plan` | `seon.issue/done-query` | d | seon.issue does not reach seon.plan |  |
| `src/seon/plan.clj:602` | `seon.plan` | `seon.test/stale` | d | seon.test does not reach seon.plan |  |
| `src/seon/plan.clj:624` | `seon.plan` | `seon.test.runner/provenance` | d | seon.test.runner does not reach seon.plan |  |
| `src/seon/plan.clj:625` | `seon.plan` | `seon.cluster.agent/acquire-context!` | b | seon.cluster.agent transitively requires seon.plan |  |
| `src/seon/plan.clj:633` | `seon.plan` | `sci.core/resolve` | d | third-party; cannot require seon |  |
| `src/seon/plan.clj:636` | `seon.plan` | `sci.core/new-var` | d | third-party; cannot require seon |  |
| `src/seon/plan.clj:641` | `seon.plan` | `sci.core/create-ns` | d | third-party; cannot require seon |  |
| `src/seon/plan.clj:654` | `seon.plan` | `seon.sci.kernel/arm` | d | seon.sci.kernel does not reach seon.plan |  |
| `src/seon/plan.clj:657` | `seon.plan` | `seon.test/run` | d | seon.test does not reach seon.plan |  |
| `src/seon/plan.clj:666` | `seon.plan` | `seon.test.runner/commit-results!` | d | seon.test.runner does not reach seon.plan |  |
| `src/seon/plan.clj:734` | `seon.plan` | `seon.issue/exhaust-tx` | d | seon.issue does not reach seon.plan |  |
| `src/seon/program.cljc:172` | `seon.program` | `seon.schema.edn/declaration-stamp` | d | seon.schema.edn does not reach seon.program | held |
| `src/seon/program.cljc:176` | `seon.program` | `seon.schema.edn/packaged-forms` | d | seon.schema.edn does not reach seon.program | held |
| `src/seon/render.clj:1555` | `seon.render` | `seon.repl/frame` | d | seon.repl does not reach seon.render |  |
| `src/seon/render.clj:1574` | `seon.render` | `seon.render.web/derive-context!` | b | seon.render.web transitively requires seon.render |  |
| `src/seon/render.clj:1578` | `seon.render` | `seon.turn/opening-db` | b | seon.turn transitively requires seon.render |  |
| `src/seon/render.clj:1581` | `seon.render` | `seon.render.web/derive-context!` | b | seon.render.web transitively requires seon.render |  |
| `src/seon/render.clj:1755` | `seon.render` | `seon.render.walk/neighborhood` | b | seon.render.walk transitively requires seon.render |  |
| `src/seon/render/transcript.clj:1645` | `seon.render.transcript` | `seon.sci.kernel/context-projection` | d | seon.sci.kernel does not reach seon.render.transcript | held |
| `src/seon/render/transcript.clj:1659` | `seon.render.transcript` | `seon.cluster.prompt/agent-calibration` | d | seon.cluster.prompt does not reach seon.render.transcript | held |
| `src/seon/render/transcript.clj:1940` | `seon.render.transcript` | `seon.plan/plan` | d | seon.plan does not reach seon.render.transcript | held |
| `src/seon/render/value.clj:161` | `seon.render.value` | `seon.render/agent-render-profile` | b | seon.render transitively requires seon.render.value |  |
| `src/seon/render/value.clj:162` | `seon.render.value` | `seon.config/defaults` | b | seon.config transitively requires seon.render.value |  |
| `src/seon/render/value.clj:250` | `seon.render.value` | `seon.db/pull` | d | ns form already requires seon.db |  |
| `src/seon/render/value.clj:325` | `seon.render.value` | `seon.render/project-node` | b | seon.render transitively requires seon.render.value |  |
| `src/seon/render/walk.clj:365` | `seon.render.walk` | `datahike.pull-api/compile-pull-plan` | d | third-party; cannot require seon |  |
| `src/seon/render/web.clj:688` | `seon.render.web` | `seon.error/diagnostic` | d | seon.error does not reach seon.render.web |  |
| `src/seon/render/web.clj:703` | `seon.render.web` | `function` | e | symbol comes from a fact/argument |  |
| `src/seon/render/web.clj:1937` | `seon.render.web` | `seon.render.walk/root-pull-plan` | d | ns form already requires seon.render.walk |  |
| `src/seon/render/web.clj:1942` | `seon.render.web` | `seon.render.walk/root-acquisition` | d | ns form already requires seon.render.walk |  |
| `src/seon/render/web.clj:2975` | `seon.render.web` | `seon.cluster/ensure-entity!` | b | seon.cluster transitively requires seon.render.web |  |
| `src/seon/repl.clj:55` | `seon.repl` | `seon.ai/agent-overlay` | d | seon.ai does not reach seon.repl |  |
| `src/seon/repl.clj:58` | `seon.repl` | `seon.db/q` | d | seon.db does not reach seon.repl |  |
| `src/seon/repl.clj:63` | `seon.repl` | `seon.turn/episode-runs` | b | seon.turn transitively requires seon.repl |  |
| `src/seon/repl.clj:371` | `seon.repl` | `seon.db/pull` | d | seon.db does not reach seon.repl |  |
| `src/seon/repl.clj:379` | `seon.repl` | `seon.db/pull` | d | seon.db does not reach seon.repl |  |
| `src/seon/repl.clj:386` | `seon.repl` | `seon.db/q` | d | seon.db does not reach seon.repl |  |
| `src/seon/repl.clj:457` | `seon.repl` | `seon.cluster.agent/armed` | b | seon.cluster.agent transitively requires seon.repl |  |
| `src/seon/schedule.clj:582` | `seon.schedule` | `(computed)` | e | symbol comes from a fact/argument | held |
| `src/seon/schema.clj:133` | `seon.schema` | `predicate` | e | symbol comes from a fact/argument | held |
| `src/seon/schema.clj:187` | `seon.schema` | `deref` | e | symbol comes from a fact/argument | held |
| `src/seon/schema.clj:907` | `seon.schema` | `seon.schema.edn/packaged-forms` | b | seon.schema.edn transitively requires seon.schema | held |
| `src/seon/schema.clj:1287` | `seon.schema` | `clojure.core.reducers/fold` | d | third-party; cannot require seon | held |
| `src/seon/schema.clj:1596` | `seon.schema` | `seon.error/diagnostic` | b | seon.error transitively requires seon.schema | held |
| `src/seon/schema.clj:2954` | `seon.schema` | `seon.schema.datahike/storable-properties-in` | b | seon.schema.datahike transitively requires seon.schema | held |
| `src/seon/schema.clj:2987` | `seon.schema` | `seon.schema.datahike/database-attributes-in` | b | seon.schema.datahike transitively requires seon.schema | held |
| `src/seon/schema.clj:3172` | `seon.schema` | `projection-symbol` | e | symbol comes from a fact/argument | held |
| `src/seon/schema/datahike.clj:14` | `seon.schema.datahike` | `seon.schema.edn/packaged-forms` | d | seon.schema.edn does not reach seon.schema.datahike | held |
| `src/seon/schema/edn.clj:454` | `seon.schema.edn` | `(computed)` | e | symbol comes from a fact/argument | held |
| `src/seon/sci/admit.clj:401` | `seon.sci.admit` | `seon.sci.kernel/interrupted?` | b | seon.sci.kernel transitively requires seon.sci.admit |  |
| `src/seon/sci/eval.clj:198` | `seon.sci.eval` | `qualified` | e | symbol comes from a fact/argument | held |
| `src/seon/sci/eval.clj:1045` | `seon.sci.eval` | `(computed)` | e | symbol comes from a fact/argument | held |
| `src/seon/test.clj:124` | `seon.test` | `seon.test-support/event-backstop-seconds` | c | target outside src/ (test/, script/, resources/) |  |
| `src/seon/test.clj:601` | `seon.test` | `(computed)` | e | symbol comes from a fact/argument |  |
| `src/seon/test.clj:747` | `seon.test` | `seon.test-support/database-base` | c | target outside src/ (test/, script/, resources/) |  |
| `src/seon/test/accretion.clj:225` | `seon.test.accretion` | `seon.test/failure-message` | b | seon.test transitively requires seon.test.accretion |  |
| `src/seon/test/arm.clj:30` | `seon.test.arm` | `candidate` | e | symbol comes from a fact/argument |  |
| `src/seon/test/arm.clj:36` | `seon.test.arm` | `seon.schema.edn/packaged-forms` | d | seon.schema.edn does not reach seon.test.arm |  |
| `src/seon/test/arm.clj:185` | `seon.test.arm` | `seon.instrument/apply!` | d | seon.instrument does not reach seon.test.arm |  |
| `src/seon/test/arm.clj:207` | `seon.test.arm` | `seon.instrument/armable` | d | seon.instrument does not reach seon.test.arm |  |
| `src/seon/test/arm.clj:208` | `seon.test.arm` | `seon.instrument/instrumented` | d | seon.instrument does not reach seon.test.arm |  |
| `src/seon/test/runner.clj:97` | `seon.test.runner` | `seon.render/agent-render-profile` | d | seon.render does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:702` | `seon.test.runner` | `seon.fn/source-roots` | d | seon.fn does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:1394` | `seon.test.runner` | `seon.instrument/instrumented` | d | seon.instrument does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:1572` | `seon.test.runner` | `candidate` | e | symbol comes from a fact/argument | held |
| `src/seon/test/runner.clj:1578` | `seon.test.runner` | `seon.schema.edn/packaged-forms` | d | seon.schema.edn does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:1681` | `seon.test.runner` | `seon.test.arm/arm-contracts!` | d | seon.test.arm does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:1686` | `seon.test.runner` | `seon.test.arm/initialize-contracts!` | d | seon.test.arm does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:1708` | `seon.test.runner` | `seon.instrument/instrumented` | d | seon.instrument does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:1821` | `seon.test.runner` | `seon.test-support/database-base` | c | target outside src/ (test/, script/, resources/) | held |
| `src/seon/test/runner.clj:2287` | `seon.test.runner` | `seon.schema.edn/packaged-forms` | d | seon.schema.edn does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:2455` | `seon.test.runner` | `seon.cluster/start!` | b | seon.cluster transitively requires seon.test.runner | held |
| `src/seon/test/runner.clj:2456` | `seon.test.runner` | `seon.cluster/stop!` | b | seon.cluster transitively requires seon.test.runner | held |
| `src/seon/test/runner.clj:2471` | `seon.test.runner` | `seon.test-support/with-database` | c | target outside src/ (test/, script/, resources/) | held |
| `src/seon/test/runner.clj:2518` | `seon.test.runner` | `seon.cluster/stop!` | b | seon.cluster transitively requires seon.test.runner | held |
| `src/seon/test/runner.clj:2523` | `seon.test.runner` | `seon.cluster.source/record-results!` | d | ns form already requires seon.cluster.source | held |
| `src/seon/test/runner.clj:2625` | `seon.test.runner` | `seon.fresh-operator/live-root-value!` | c | target outside src/ (test/, script/, resources/) | held |
| `src/seon/test/runner.clj:2857` | `seon.test.runner` | `seon.test-support/event-backstop-seconds` | c | target outside src/ (test/, script/, resources/) | held |
| `src/seon/test/runner.clj:3727` | `seon.test.runner` | `seon.fn/build-manifest` | d | seon.fn does not reach seon.test.runner | held |
| `src/seon/test/runner.clj:3806` | `seon.test.runner` | `(computed)` | e | symbol comes from a fact/argument | held |
| `src/seon/test/runner.clj:3958` | `seon.test.runner` | `seon.cluster/refresh-source!` | b | seon.cluster transitively requires seon.test.runner | held |
| `src/seon/turn.clj:2025` | `seon.turn` | `seon.turn/planned-sources` | a | target is this namespace |  |
| `src/seon/turn.clj:2200` | `seon.turn` | `seon.sci.eval/agent-namespace` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:2225` | `seon.turn` | `seon.cluster.agent/acquire-context!` | b | seon.cluster.agent transitively requires seon.turn |  |
| `src/seon/turn.clj:2356` | `seon.turn` | `seon.sci.eval/bind-result!` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:2414` | `seon.turn` | `seon.cluster.agent/submit-source!` | b | seon.cluster.agent transitively requires seon.turn |  |
| `src/seon/turn.clj:3281` | `seon.turn` | `seon.sci.eval/evaluate-candidate` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:3331` | `seon.turn` | `seon.sci.eval/accept-candidate!` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:3335` | `seon.turn` | `seon.sci.eval/refuse-install` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:3359` | `seon.turn` | `seon.sci.eval/unrun-evaluation` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:3430` | `seon.turn` | `seon.problems/assignment-value` | b | seon.problems transitively requires seon.turn |  |
| `src/seon/turn.clj:4315` | `seon.turn` | `seon.sci.eval/agent-namespace` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:4383` | `seon.turn` | `seon.cluster.prompt/prompt` | b | seon.cluster.prompt transitively requires seon.turn |  |
| `src/seon/turn.clj:4409` | `seon.turn` | `seon.context/capture-tx` | b | seon.context transitively requires seon.turn |  |
| `src/seon/turn.clj:4675` | `seon.turn` | `seon.sci.eval/bind-result!` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:4707` | `seon.turn` | `seon.sci.eval/fork-for-turn` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:4737` | `seon.turn` | `seon.sci.eval/fork-for-turn` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:4770` | `seon.turn` | `seon.sci.eval/agent-namespace` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:4839` | `seon.turn` | `seon.problems/form-problem` | b | seon.problems transitively requires seon.turn |  |
| `src/seon/turn.clj:4865` | `seon.turn` | `seon.sci.eval/install-evaluated-rows!` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:4874` | `seon.turn` | `seon.sci.eval/committed-row?` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:4916` | `seon.turn` | `seon.sci.eval/agent-namespace` | d | ns form already requires seon.sci.eval |  |
| `src/seon/turn.clj:4967` | `seon.turn` | `seon.sci.eval/agent-namespace` | d | ns form already requires seon.sci.eval |  |
| `src/seon/web/jvm.clj:391` | `seon.web.jvm` | `deref` | e | symbol comes from a fact/argument |  |

(`tree` = `held` when another lane holds uncommitted edits in that file at the
time of this census; those files are skipped by this lane.)

## Verification boundary

The classification is **static**: `ns` forms under `src/` and their transitive
closure, plus the source at each cited line. No JVM, no prepl, no cluster.

Two limits are stated rather than papered over:

1. The graph covers `src/` only. A class (d) verdict says *no cycle exists
   through source requires today*; it does not prove that adding all 79
   requires at once stays acyclic, because each added edge changes the graph.
   Every applied change is therefore re-checked against the graph as it
   stands, and proven by loading.
2. The **cost** claim from the inventory ("the recurring performance killer")
   is still unmeasured. `requiring-resolve` serializes on `REQUIRE_LOCK` —
   `src/seon/test/runner.clj:3806` records a real interleaving incident caused
   by exactly that — but no benchmark in this tree measures a hot-path cost,
   and this note does not add one.

## What was applied

Nineteen namespaces, one path-limited commit each (`7bdd299a2` … `a00e73e49`).
`rg -c requiring-resolve src/` goes from **147 to 100** text hits, of which
**33 are now `delay`-held definitions** and 12 are prose; **55 per-call or
dynamic sites remain**, and every one of those is either class (e) or sits in
a file another lane holds.

| namespace | a | b | c | d | note |
|---|---|---|---|---|---|
| `seon.turn` | 1 | 6 | — | 15 | `declare planned-sources`; 15 sites aliased to `sci.eval` |
| `seon.db` | — | 6 | — | 4 | `pull-api` was already required |
| `seon.issue` | — | 6 | — | 4 | see the correction below |
| `seon.plan` | — | 1 | — | 8 | `seon.issue` made an explicit require |
| `seon.render` | — | 4 | — | 1 | |
| `seon.repl` | — | 2 | — | 5 | |
| `seon.render.value` | — | 3 | — | 1 | |
| `seon.render.web` | — | 1 | — | 3 | |
| `seon.test.arm` | — | — | — | 4 | |
| `seon.flow` | — | — | 4 | — | one resolution, was four per-call |
| `seon.effect` | — | — | 1 | — | |
| `seon.error` | — | 1 | — | 1 | |
| `seon.fn`, `seon.cluster.agent`, `seon.render.walk`, `seon.bootstrap` | — | — | — | 1 each | |
| `seon.sci.admit`, `seon.test.accretion` | — | 1 each | — | — | |
| `seon.test` | — | — | 2 | — | comment only |

Every class (b) and (c) site now resolves **once**, in a `defonce`-held
`delay` carrying a comment that names the cycle or the late load. No site
resolves a var on every call any more outside the held files.

## One correction to the table above

Six `seon.issue` sites naming `seon.turn` and `seon.cluster.agent` are
classified **d** in the table and are in fact **b**. The require graph misses
them because the edge that closes the cycle is not a `:require`: `seon.plan`
**derefs** `seon.issue/done-query` at load
(`src/seon/plan.clj:599`, formerly `@(requiring-resolve 'seon.issue/done-query)`),
so loading `seon.plan` loads `seon.issue`, and `seon.turn` requires
`seon.plan`. A load-time `requiring-resolve` outside a function body is a
require the graph cannot see.

That hidden edge is now stated: `seon.plan` requires `seon.issue` in its `ns`
form, and the six `seon.issue` sites are `delay`-held. **The underlying defect
is `seon.plan/issue-done-query` itself** — a mirror, in the low-level
namespace, of a query the high-level namespace owns, inverting the dependency
direction. Dissolving it (the plan step reads the query from `seon.issue`, or
the query moves to whichever namespace owns the fact) would let `seon.issue`
require `seon.turn` outright and close all six. Out of scope here; recorded so
the next lane does not rediscover it.

## Cycle proof

After every edit the graph was rebuilt from the edited `ns` forms and checked
for a namespace reachable from its own requires. The answer is the empty set:
no `:require` cycle exists in `src/`.

## Verification boundary — what this lane proved and what it did not

**Proved.** `clj-kondo --lint src` is clean of new findings: one error before
the work (`src/seon/db.clj:579` `Unresolved var: parser.type/->Variable`, a
pre-existing stale dependency-cache finding in a line this lane did not
touch), one after; warnings go 366 → 365. The require graph rebuilt from the
edited `ns` forms is acyclic.

**Not proved by this lane.** No JVM loaded the edited program. `clojure -M:dev
-e "(require 'the.ns)"` and `(require … :reload)` on the development cluster
were both out of bounds for this assignment, so the load proof is the gate,
and at the time of writing both `bin/_test-slot` slots were held by other
lanes' gates with 21 `bin/test` processes live. A `bin/test-fast` invocation
launched earlier hit the liveness watchdog at 30 minutes without reaching a
test (`exit 124`, slot contention, not a failure of these edits).

**What could still be wrong, named exactly.** A `defonce`-held `delay` changes
*when* a var is resolved, not *whether*: a target var that never existed would
previously fail at the first call and now fails at the first deref, in the same
place. The real risk of the class (d) changes is load ORDER, which only a JVM
answers — and the acyclic graph is necessary but not sufficient, because a
namespace that reads a var at load time (exactly the `seon.plan` case found
above) forms an edge no `ns` form declares. The remaining such reads in the
tree are `src/seon/test/runner.clj:1681`
(`(def ^:private arm-contracts! (requiring-resolve 'seon.test.arm/arm-contracts!))`)
and `src/seon/test/runner.clj:702`; both are in a held file and unchanged.

## Skipped: files another lane held

Checked with `git status` before each commit. These files keep their sites and
their classes; the table above carries the row for each.

| file | sites left | classes |
|---|---|---|
| `src/seon/test/runner.clj` | 20 | 2×c, 2×e, 16×d/b |
| `src/seon/schema.clj` | 8 | 3×e, 1×d (third-party), 4×b |
| `src/seon/cluster/source.clj` | 5 | 2×e, 2×d, 1×b |
| `src/seon/render/transcript.clj` | 3 | 3×d |
| `src/seon/program.cljc` | 2 | 2×d |
| `src/seon/cluster.clj` | 1 | 1×d (`seon.issue/adopt!` — recheck after the `seon.plan` mirror is dissolved) |
| `src/seon/schema/datahike.clj` | 1 | 1×d |
| `src/seon/schema/edn.clj`, `src/seon/sci/eval.clj`, `src/seon/schedule.clj` | 1 each | e (legitimate) |
