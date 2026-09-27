---
type: research
status: full data-model plan after owner rulings 2026-09-27; for Astra review, then the owner; no source changed
created: 2026-09-27
branch: refactor/agent-platform
source-pin: a318b2d38 (checkout); live default pid 22787, commit 6ab93d43, basis 536879662
lane: design-repl-session-opus
supersedes: the `:seon.agent/cluster` proposal of config-read-scope-design-2026-09-27.md (10192e1ee), folded into §6
---

# The agent's record as one story: session, runs, evaluations, namespaces and settings

What exists: the agent row carries three components created empty at birth (`:seon.agent/plan`,
`:seon.agent/settings`, `:seon.agent/runtime`, `cluster/agent.clj:217-241`). Runs point to the agent and are also
listed on the runtime. Evaluations point to their run. Namespaces are program rows. Settings are a per-agent overlay
merged over a cluster config row that each reader finds by guessing the cluster name. The data flow the context
needs is always the same walk outward from the agent, but today it is taken three different ways, and several facts
are stored twice. Principal Clojure approach: one fact, one place, one writer, one direction. Context is a ref walk
from the agent. Everything a run changes about the agent is written in the run's one settlement transaction.

The plan in five lines (details in §5–§7):

1. **`:seon.agent/runtime` becomes `:seon.agent/sci`**, the agent's SCI world. It holds one fact,
   `:seon.agent.sci/ns`: the namespace SCI was in after the last settled form. It is written only by settlement,
   and by a request that asks to move the agent. The runtime's other attributes are bookkeeping; they are deleted
   or moved (§2).
2. **History is the reverse of `:seon.turn/agent`.** The duplicate list `:seon.runtime/turns` and the copy
   `:seon.runtime/trigger` go.
3. **Each evaluation keeps its own `:seon.cluster.eval/ns` ref, written once, by settlement.** The settlement also
   upserts the namespace. `:seon.turn/starting-ns`, the stored `:seon.sci.eval/ending-ns` and every scan-based
   "where is the agent" reader go.
4. **Settings: the agent's overlay links to its cluster's config row with `:seon.config/base`.** That gives one
   story, base config → agent override → effective settings, read by one function, `seon.ai/agent-settings`, and
   used by the 18 read sites that guess a cluster today. A render shows the important dials, and the full map is
   one requery away.
5. **Namespace information stays on namespaces.** The namespace half of context is selected by `:seon.agent.sci/ns`
   and is the same for every agent there. The agent half is reached from the agent and follows it everywhere.

---

## 1. What `:seon.agent/runtime` holds today, and the name (ruling 1)

Declaration: `resources/seon/schemas/seon.runtime.edn:1-16`. Live on default: root's runtime (eid 34520) holds `agent`,
`trigger` and 59 `turns`. No runtime on default has `listens` (0 datoms).

| Attribute | Type | Writer (file:line) | What it is | SCI world? |
|---|---|---|---|---|
| `:seon.runtime/agent` | identity ref → agent | `cluster/agent.clj:237`; `turn.clj:466, 1622` | Back-reference used only as the upsert identity of the nested map | no: plumbing, and the agent → runtime relation stored in reverse |
| `:seon.runtime/turns` | component set → runs | `open-call` `turn.clj:465-467`; `record-evaluated-call :1622-1623` | The run list. Duplicates `:seon.turn/agent` | no: run bookkeeping |
| `:seon.runtime/trigger` | ref → message | `open-call` `turn.clj:468` | Copy of the latest run's `:seon.turn/trigger` | no: a mirror |
| `:seon.runtime/listens` | component set → listen patterns | `issue.clj:1468`; read by `cluster/wake.clj:427-429`, `issue.clj:1456` | What wakes the agent | no: a wake subscription |

None of it is SCI. Once the bookkeeping is deleted and listens move to the agent, the component's only fact is the
current namespace, which is SCI's `*ns*`. The truest name is therefore **`:seon.agent/sci`**, which is also the
owner's 2026-09-07 ruled name ("`sci` (it is SCI data)", `docs/prds/context-generation/plan/agent-record-and-repl-response-prd-2026-09-07.md`
§3: "`:seon.agent/sci` … current namespace, surviving defs"). "Surviving defs" stay in memory by the §15 ruling
(results and private defs are not serialized), so the entity holds `:seon.agent.sci/ns` and nothing else today. It
is the place for a future durable SCI fact. It is not a holder of items, so it satisfies the one-hop rule: the
component *is* the data.

Renames and moves, each with retirement (all adopt in place, §8):

| Today | Becomes | Why |
|---|---|---|
| `:seon.agent/runtime` (component) | `:seon.agent/sci` (component, one attribute) | It is the SCI session |
| `:seon.runtime/listens` | `:seon.agent/listens` (component set directly on the agent) | A wake subscription is its own component on the agent (owner rule 2026-09-07: "separate wake components") |
| `:seon.runtime/turns`, `:seon.runtime/trigger`, `:seon.runtime/agent` | deleted | duplicate, mirror, back-ref |
| (new) | `:seon.agent.sci/ns` ref → `:seon.ns` | The current namespace |

The session component carries **no back-ref identity**. Its only writers are transaction functions (settlement, the
move request) that address it through the agent's own edge: `[:seon.agent/id a]` → `:seon.agent/sci` → eid, or a
new tempid attached by `[:db/add agent :seon.agent/sci tempid]`. That is the "one direction" rule. A component
with an owning edge is not an owner-less row, so the writer's completeness check admits it
(`docs/seon/architecture/data-modeling-guide.md` §4).

## 2. The graph, before and after

### Before (today)

```
agent [:seon.agent/id]
├ :seon.agent/branch · archived-tx · admission-closed-tx
├ :seon.agent/namespace ─────► ns (HOME)       ← also used as "current" (5 readers) and as ownership (2 readers)
├ :seon.agent/plan ──comp──► plan  ◄── :my.plan/agent        (both directions)
├ :seon.agent/settings ─comp► overlay ◄── :seon.config/agent  (both directions; no base link: readers guess the cluster)
├ :seon.agent/runtime ──comp► runtime ◄── :seon.runtime/agent (both directions)
│                               ├ :seon.runtime/turns ──► run*      ═ duplicate of run → agent
│                               ├ :seon.runtime/trigger ─► message   ═ copy of latest run's trigger
│                               └ :seon.runtime/listens ─► pattern*
◄ run* :seon.turn/agent                                  (history walk 1)
    ├ :seon.turn/starting-ns ─► ns                        ═ copy of ordinal-0 evaluation's ns
    ├ :seon.turn/trigger ─► message · :seon.turn/handled ─► message*
    ◄ evaluation* :seon.cluster.eval/run
         ├ :seon.cluster.eval/ns ─► ns                    written twice: plan (a guess) and settlement
         ├ :seon.sci.eval/ending-ns  (symbol)             ═ next form's ns, or the session
         ├ :seon.eval/renderer (symbol) + :seon.eval/renderer-fn (ref)   ═ one fact two ways
         └ :seon.cluster.eval/read-evidence ─comp─► plan strings up to 1.5 MB
◄ error.occurrence* :seon.error.occurrence/agent (+ /turn: never written together on default)
◄ message* :seon.message/to
cluster [:seon.cluster/name "default"] ─ :seon.cluster/config ─► config row [:seon.config/cluster "default"]
                                                                   ═ the cluster name stored twice
```

### After

```
agent [:seon.agent/id]                                    EVERY TURN (agent half)             ON DEMAND
├ :seon.agent/branch · archived-tx · admission-closed-tx   identity line (id, home, branch)
├ :seon.agent/namespace ─► ns (HOME, assignment)           in identity line
├ :seon.agent/sci ─comp─► {:seon.agent.sci/ns ─► ns}        the prompt `ns=>`; selects NAMESPACE HALF
├ :seon.agent/settings ─comp─► overlay {per-agent dials,    settings summary (declared shown dials)  full effective map
│                               :seon.config/base ─► config row of its cluster}
├ :seon.agent/plan ─comp─► plan                            plan block
├ :seon.agent/listens ─comp─► pattern*                     (plumbing; not rendered)
◄ run* :seon.turn/agent                                    latest runs' evaluations            older runs
    ├ :seon.turn/trigger ─► message · :seon.turn/handled ─► message*
    ◄ evaluation* :seon.cluster.eval/run
         ├ :seon.cluster.eval/ns ─► ns   (settlement only)  its `ns=>` prompt
         ├ :seon.eval/renderer (symbol)
         └ :seon.cluster.eval/read-evidence (bounded, P1)   never rendered
◄ message* :seon.message/to                                unanswered wakes
◄ error.occurrence* (agent, or turn when in a run)         faults routed to me
◄ task/issue assignments :seon.task/agent, :seon.issue/agent  open tasks

ns [:seon.ns/name]  ── NAMESPACE HALF, same for every agent there: dir/doc, contracts, tests, :seon.ns/steward
cluster [:seon.cluster/name] ─ :seon.cluster/config ─► config row (82 effective dials)
```

The renderer walks from the agent: pull agent one hop (identity, sci → ns, settings → base, plan), then reverse
`:seon.turn/agent` → reverse `:seon.cluster.eval/run` for history (AVET on refs, proportional to this agent). Then it
takes `:seon.agent.sci/ns` → the namespace's render for the namespace half. No reader guesses a cluster, scans for
a maximum eid, or falls back through a chain.

## 3. Per-fact audit (ruling 2)

Legend: **K** keep · **M** move · **D** delete · **N** new. "Owner" is the namespace that owns the writer.

### Agent and its components

| Fact | Verdict | Owner / writer (file:line) | Note |
|---|---|---|---|
| `:seon.agent/id` | K | `seon.cluster.agent/creation-tx` `cluster/agent.clj:233` | identity |
| `:seon.agent/branch` | K | `branch-call` `cluster/agent.clj:205-215` | custody; a hosted agent's row on default names another branch, so it is not derivable from the value |
| `:seon.agent/archived-tx`, `admission-closed-tx` | K | `seon.agent/archive-call` `agent.clj:58-72`; host retirement | lifecycle |
| `:seon.agent/namespace` | K | `creation-tx :234` | assignment (home). Stop reading it as current (`turn.clj:2233, 4745, 5334, 5478, 5549`; `sci/eval.clj:4536-4541`; `render/walk.clj:603-612`) and as ownership (`form-owner turn.clj:2599-2624`; `render.clj:1736-1746` → `:seon.ns/steward`) |
| `:seon.agent/plan` | K | `creation-tx :235`; `my.plan` writers | component |
| `:my.plan/agent` | D (P6) | `creation-tx :235`; 19 source mentions | back-ref identity; writers address the plan through `:seon.agent/plan` |
| `:seon.agent/settings` | K | `creation-tx :236`; `settings!` `agent.clj:87-120` | overlay component |
| `:seon.config/agent` | D (P6) | `creation-tx :236`; `update-settings-call agent.clj:101`; 19 mentions | back-ref identity; same treatment |
| `:seon.config/base` | N | `creation-tx` (from the `:seon.cluster/name` the creation request already carries, `seon.agent.edn:84-89`) | overlay → its cluster's config row (§6) |
| `:seon.agent/runtime` | M → `:seon.agent/sci` | `creation-tx :237` | §1 |
| `:seon.runtime/agent` | D | `creation-tx :237`; `turn.clj:466, 1622` | back-ref |
| `:seon.runtime/turns` | D | `turn.clj:465-467, 1622-1623`; readers `turn.clj:575, 2059`, `eval.clj:49`, `cluster/status.clj:137-142`, `cluster/agent.clj:335-342`, `render/web.clj:3211`, `render/transcript.clj:1149, 2431` | duplicate of `:seon.turn/agent`. Readers walk the reverse edge; `next-id` counts runs by `:seon.turn/agent` |
| `:seon.runtime/trigger` | D | `open-call turn.clj:468`; reader `transcript.clj:1040-1060, 2431-2455` | mirror; "latest trigger" is the newest run's `:seon.turn/trigger` |
| `:seon.runtime/listens` | M → `:seon.agent/listens` | `issue.clj:1468`; readers `cluster/wake.clj:427-429`, `issue.clj:1456` | wake component |
| `:seon.agent.sci/ns` | N | settlement (`settle-batch!` `turn.clj:3857-3920`, `settle!` `:4168`, refusal arm `:4074-4152`, generated-append settlement, `record-evaluated-call :1561`); a move request's open (`open-call :433`, `system-run-call :792`) | the one current-namespace fact |

### Run

| Fact | Verdict | Writer | Note |
|---|---|---|---|
| `:seon.turn/id`, `opened-tx`, `closed-tx` | K | `open-call :462`, `close-call :480-501` | |
| `:seon.turn/agent` | K | `open-call :462`; `record-evaluated-call :1583` | the one run ↔ agent edge |
| `:seon.turn/starting-ns` | D | `open-call :463`, `plan-call :731-732`, `system-run-plan :787`, `generated-run-tx :912`, `record-evaluated-call :1620`; callers `cluster/agent.clj:750`, `bootstrap.clj:316, 343`, `issue.clj:1407` | copy of ordinal 0's ns; the start is the session value |
| `:seon.turn/trigger`, `handled` | K | `open-call`, `close-call` | cause and claims |
| reply, reply-blob, reply-size, attempts, disposition, result | K | `plan-call`, provider path, settlement | |
| `:seon.turn.work/situation` | K (flagged) | `open-call :462` | the turn PRD §2 lists this stamp as removed; readers `plan-call :727`, `latest-evaluations`. Out of scope; issue candidate |

### Evaluation

| Fact | Verdict | Writer | Note |
|---|---|---|---|
| id, run, ordinal, at, author, source, comment | K | `source-rows turn.clj:639-675`, `receipt-row :954-975` | |
| `:seon.cluster.eval/ns` | K, one writer | settlement `evaluation-facts turn.clj:152-153` | plan-time write deleted (`source-rows :666-669`): segmentation's guess is not a fact. Settlement upserts the namespace, fixing task a318b2d38 |
| `:seon.sci.eval/ending-ns` (stored) | D | `evaluation-facts :154-156`; `receipt-terminal-attributes :1441` | stays as the in-memory evaluation key (`seon.sci.eval.edn:207`) the loop and settlement read |
| `:seon.eval/renderer` (symbol) | K | `evaluation-facts :130-131` | the renderer is an observed name that history must show after the function is deleted: a token, so a value (data-modeling skill, "statement or token?") |
| `:seon.eval/renderer-fn` (ref) | D | `evaluation-facts :132`; readers `transcript.clj:52, 287, 1495, 1563, 1728`, `repl.clj:475-477`, `turn.clj:1539` | the same fact as a ref; readers already fall back to the symbol |
| shown, error, diagnostics, output, costs | K | settlement | the §15 observation |
| `:seon.cluster.eval/faults` | K | settlement `with-fault-windows turn.clj:3790-3800` | the delivered-errors observation: what the agent was shown |
| read-evidence components | K, bounded (P1) | `receipt-read-evidence-tx :1463-1480` | plans hold 17.6M characters on default; never rendered |

### Namespace, settings, cluster, messages, errors, tasks, context

| Fact | Verdict | Writer | Note |
|---|---|---|---|
| `:seon.ns/name` identity-only rows | K | `namespace-rows turn.clj:619-637`, called from settlement | program-partition entity (`seon.ns.edn:10-18`); no digest, so never merged or written back (`program.cljc:614-655`) |
| `:seon.ns/steward` | K | `steward-call` | namespace information (§4) |
| per-agent dials on the overlay | K | `settings!` `agent.clj:106-120` | the override |
| `:seon.cluster/config` | K | `ensure-cluster-entity!` `cluster.clj:3272`, called at `:3363` | cluster → config row |
| `:seon.config/cluster` (string identity of the config row) | Q2 | `config/compile-manifest`, `apply-compiled!` | the cluster's name stored a second time |
| `:seon.config/overlay-keys` | K | `apply-compiled!` | the held cluster-level decisions |
| message to/from/id/content | K | `cluster/message.clj` | wake edge |
| `:seon.error.occurrence/agent` + `/turn` | K, one of them per occurrence (P6) | `error.clj:1492-1493` | write `turn` when the occurrence happened in a run, else `agent`; readers join through the run (`error.clj:2066`). Default holds 11 agent-only and 0 turn occurrences |
| `:seon.task/agent`, `:seon.issue/agent`, created-by | K | task/issue writers | assignment, not duplicated |
| `:seon.context.capture/*` | K | `context/capture-tx context.clj:359-411` | the exact prompt sent, kept for calibration |
| `:seon.context.contribution/agent`, `/evaluations` and `seon.context/selection`, `append-tx`, `compact-tx` | D (P5) | `context.clj:76-256`, no caller outside the file | dead selection family |

Readers that re-derive agent state by scan, all replaced by reading `:seon.agent.sci/ns`:
`previous-ending-namespace` (`turn.clj:597-610`), `fold-namespace` (`:4533-4546`), the resume chain (`:5330-5334`),
the submit path's lookup (`cluster/agent.clj:697-722`), and the transcript chain from namespace to run to agent to
home to `'user` (`render/transcript.clj:278-283`).

## 4. Context: the agent half and the namespace half

| Half | Selected by | Holds | Follows |
|---|---|---|---|
| Agent | refs from the agent (§2) | its evaluations in every namespace (each prompt `ns=>` from its own ref), plan, settings summary, unanswered messages, routed faults, open tasks, namespaces it stewards (reverse `:seon.ns/steward`) | the agent |
| Namespace | `:seon.agent.sci/ns` | that namespace's `dir`/`doc`: definitions, contracts, schemas, tests, docs, steward | the namespace; the same for every agent in it |

How today's builder mixes them: the opening (`declared-sources turn.clj:1979-2030`) is a neighbourhood walk from the
agent (`render/walk.clj:698`) whose namespace member is the **home** namespace (`acquired-root-namespace-eid
walk.clj:603-612`). The agent's render units list `:seon.agent/namespace` (`seon.agent.edn:36-45`). So the
namespace half never follows `in-ns`.

Target: under additive context (turn PRD §14), the namespace half is the generated read `(dir <current-ns>)`.
After an `in-ns` it is a new distinct read form, so the existing refresh appends it once, with no new mechanism.
Returning to a namespace whose `dir` has not changed appends nothing.

`:seon.ns/steward` stays on the namespace. It answers "where do faults and requests about this namespace go", it is
the same for every viewer, and `steward-call` writes it at creation, not the steward's runs. `:seon.agent/namespace`
stays on the agent as its assignment. No namespace information is copied onto agent records.

## 5. The session namespace: writes and reads

Settlement adds, inside the run's one settlement transaction, through a transaction function that addresses the
component from the agent:

```clojure
{:db/id "namespace:probe.eta" :seon.ns/name 'probe.eta}      ; upsert: 0 datoms if the row exists
[:db.fn/call #'seon.turn/session-call "root" "namespace:probe.eta"]
;; session-call: pull [:db/id {:seon.agent/sci [:db/id]}] [:seon.agent/id "root"];
;; assert :seon.agent.sci/ns on that component, or create it with [:db/add agent :seon.agent/sci "sci"].
```

The namespace is the in-memory `:seon.sci.eval/ending-ns` of the last evaluation in the batch. The loop threads it
from SCI's `*ns*` after each form (`turn.clj:5184`; captured at `sci/eval.clj:4755`), so a nested `in-ns` is
covered. Every evaluated form's `ns` goes through the same `namespace-rows` upsert. That fixes task `a318b2d38`
(P0.1 confirmed: a nested `in-ns` loses the whole run because settlement asserts a lookup ref to an unwritten
namespace).

**Moving the agent (ruling 3).** A request (submission, MCP SCI `namespace`, bootstrap, issue worker, provider
turn) may carry an optional `:seon.ns/name`. The run's open transaction (`open-call`, `system-run-call`) writes it to
`:seon.agent.sci/ns` with the same upsert. The run then starts from the session, like every run. There is no
override and no third source; the value is the fact the next run already reads. Without the key, nothing is
written at open.

Every start reads one helper, `(or sci-ns home)`. That covers `plan-call`, `system-run-plan`, generated runs,
resume, provider segmentation (`freeze! turn.clj:4745`), system turns (`:2233, :5478`), appended generated forms
(`:5549`) and the submit path. Home is used only before an agent's first settlement, and when the namespace was
retracted: the ref is optional, so `my.program/remove-ns!` sweeps it.

**Rendering.** Each entry's `ns=>` prompt comes from its own `:seon.cluster.eval/ns`. `:seon.repl/ns` (`repl.clj:216`)
is dropped: the next entry's prompt and the next turn's opening show the move. MCP SCI replies add the session
namespace once per run.

## 6. Settings (ruling 4)

### 6a. How clusters get their config today (answering the owner)

- **Initial settings.** `config/default.edn` (653 lines) makes one explicit decision per registered dial. A selected
  sparse overlay (`config/development.edn` for development clusters: `:seon.config.ai/no-provider true`,
  `:seon.config.db/snapshot-window-ms 0`) and a typed environment map layer on top. The precedence is defaults →
  overlay → environment. `compile-manifest` compiles them into one desired row, which `apply-compiled!`
  exact-reconciles into the cluster's config row (`config.clj:1-14`, `:543`, `:736`). Boot applies it at
  `cluster/boot.clj:718` and `init --dev` at `:762`. A new cluster's row is written at `cluster.clj:3362`.
- **The row.** On default it is entity `[:seon.config/cluster "default"]`: 85 attributes, 82 effective dials
  (about 880 tokens), `overlay-keys` `[:seon.config.ai/no-provider :seon.config.db/snapshot-window-ms]`. The cluster
  entity `[:seon.cluster/name "default"]` refs it through `:seon.cluster/config`.
- **Changing a cluster on the fly.** `bin/seon config apply [NAME] PATH` (`script/seon/operator.clj:1538-1570`) →
  `config/apply!` (`config.clj:823-860`). A sparse manifest layers over the decisions the row already holds
  (`held-overlay :810`), so an apply changes only its own keys.
- **Changing one agent on the fly (for example, switching provider or model).** `seon.agent/settings!` (`agent.clj:106-120`) writes
  admitted per-agent dials (34 declared with `:seon.config/per-agent true`, 18 of them in `seon.config.ai.edn`)
  onto the agent's overlay component. The composition runs per prompt (`effective-ai-settings`,
  `cluster/prompt.clj:48-72` = `config/effective` then `ai/agent-overlay` then `ai/settings` merge, `ai.clj:321-327`),
  so the next turn uses the new provider. Today this reader takes the cluster name from the SCI environment
  (`prompt.clj:39-46`), and other readers guess it with `[:find ?name . :where [_ :seon.config/cluster ?name]]`
  (`agent.clj:131, 184` and the 18 sites below).
- **What the agent sees.** `render-settings-ai` (`agent.clj:161-165`) emits a comment plus `(seon.agent/settings)`,
  which shows only the overrides (root: none). `effective-settings` selects its summary dials from a hand list
  (`agent.clj:147-154`) beside the schema.

### 6b. Target: one story, base → override → effective

```clojure
;; seon.config.edn
#:seon.config{:base [:and {:description "The cluster config row these agent overrides layer over: the agent's
                           effective settings are this row's effective dials merged with the overlay's.
                           Written at creation from the creation request's cluster."} :seon.db/ref]}
;; the overlay entity (component of the agent through :seon.agent/settings) gains
[:seon.config/base :seon.config/base]      ; required on new rows (the creation writer always knows it)
;; each dial the summary shows declares it on its own schema, replacing the hand list at agent.clj:147-154
[:string {:seon.config/per-agent true :seon.config/shown true …}]
```

- **The link lives on the overlay, not on the agent row.** Its only meaning is "these overrides layer over that
  base". Its only reader is the composition. The agent reaches it in the same hop it already takes to its settings.
  This replaces 10192e1ee's `:seon.agent/cluster`. Nothing else on the agent needs the cluster.
- **One reader:** `seon.ai/agent-settings [db agent-id]` = pull `[{:seon.agent/settings [* {:seon.config/base
  [:seon.config/cluster]}]}]` → `config/effective` of the base's cluster → merge the overlay. It is today's
  `prompt.clj:48-72` moved, with the environment and guessed-name reads deleted. A missing base is its named
  refusal.
- **Consumers (the config design's A-list, 10192e1ee §1):**
  - Agent-scoped reads call `agent-settings`: A1 `max-episode-runs` (`turn.clj:2887`), A4 `repl.clj:70-73`,
    A5 `bootstrap.clj:130-134`, A6 `issue.clj:1491` and A7 `cluster/message.clj:681-692` (both inside their writers,
    from the agent id they hold), A8 `plan.clj:646-649`, A9 `render/transcript.clj:1169-1170` (same opening db),
    A10 `agent.clj:131, 184`, A11 `cluster/agent.clj:285`, A12 `issue.clj:1338, 1447` (parent's record), A13
    `cluster.clj:3430` (created record).
  - Reads that already hold settings pass them through: A2 `turn.clj:4418-4421`, A3 `turn.clj:69-73`, A14
    `blob.clj:66-80`, A15 `test/runner.clj:1253-1276`.
  - Non-agent reads name their cluster from the world they hold: A16 `render/web.clj:2027-2034` (the service),
    A17 `sci/eval.clj:1319-1330` (the environment), A18 `problems.clj:363-372` (the request).
  - The reference selection in 10192e1ee §1 D (`turn.clj:4411-4416`) takes the overlay's base ref instead of "any
    config row".
  - The aggregate policies (10192e1ee §1 B) are unchanged.

### 6c. The settings render

- **AI render (`render-settings-ai`).** It returns one REPL entry, `(seon.agent/effective-settings)`, evaluated in
  the viewing agent's world. The value is data: the shown dials (those declaring `:seon.config/shown`, today's
  summary: model, no-provider, evaluation deadline, turn budget, retry) with their effective values, plus
  `:seon.config/overrides`, the set of those the agent overrides. Root measures 76 tokens today; about 90 with the
  override set.
- **Full data one requery away.** The floor's elision names the requery `(seon.ai/agent-settings (seon.db/db) "<id>")`,
  82 dials, about 880 tokens. The existing `:seon.config.agent/show-all-settings` dial is deleted: a per-agent
  switch that widens a render is presentation state, and the requery replaces it.
- **HTML render.** It keeps `render-settings-html`'s every-dial table with its source column (override, inherited,
  absent), reading the base through the link instead of a guessed cluster.

## 7. Cost per turn (estimated tokens) and per operation

Measured on default (root, 117 evaluations; §11). The agent half's history is 34,593 tokens stored. 86% of it is three
generated reads repeated every system turn: messages ~616 tokens/run, `seon.error/for-agent` ~325/run, and the
runtime trigger/listens read ~240/run.

| Item | Today | Target | Per-turn change |
|---|---|---|---|
| runtime trigger/listens refresh | ~240 tokens/run | deleted (no mirror to show) | **−240** |
| current namespace | not shown except via `:seon.repl/ns` | the `ns=>` prompt (already printed) | 0 |
| namespace half | home `dir` once at opening | `(dir <current-ns>)` once per namespace entered: 32 (`my.agents.root`) to 11,284 (`seon.turn`) tokens raw | +cost only when the agent moves |
| settings summary | `(seon.agent/settings)` → overrides only | shown dials + override set, ~90 tokens once at opening, again only when a dial changes | ~+90 once |
| settlement datoms | `ending-ns` 1 per evaluation + `starting-ns` 1 per run | session ns: 0 when unchanged, 1–2 when moved; namespace upsert 0–1 | fewer datoms per run |
| "where is the agent" read | `previous-ending-namespace` 0.10–0.17 ms (O(agent evaluations)) | two-hop pull 0.03 ms | O(1) |
| history acquisition | `of-agent` 1.55–1.82 s; `latest-evaluations` 1.56–1.60 s | the same walk without read evidence: 0.41 ms (P1) | the largest single cost in the loop |

## 8. Phased plan

Each slice is one loadable commit that converts its callers and tests in the same cut. Root runs the named tests
centrally. Schema additions adopt as accretion. Retirements use in-place retirement (`f9fc0e0d0` orders a retired
attribute's retraction after the program rows that convert its writers; `da10c8912` exercised it), so each
retirement lands in the same commit as its writer conversions.

| # | Slice | Owner files | Tests (root runs) | Adoption / RESET |
|---|---|---|---|---|
| P1 | **Read evidence off the history path.** Pull no dependency plans for history or refresh; store bounded plans. Needs its own design and review; task to file | `src/seon/db.clj` (read-evidence), `src/seon/eval.clj`, `src/seon/turn.clj` (`latest-evaluations`) | `seon.eval-test`, `seon.turn-test`, read-evidence currency tests; clock row for `of-agent` | no schema change |
| P2 | **Session namespace.** Add `:seon.agent/sci` + `:seon.agent.sci/ns`; the settlement and open writers (§5); the one start helper; the plan-time `ns` write deleted; retire `:seon.turn/starting-ns` and stored `:seon.sci.eval/ending-ns`; request key `:seon.ns/name` replaces `:seon.turn/starting-ns`; convert `bootstrap.clj:316, 343`, `issue.clj:1407`, `cluster/agent.clj:688-760` (also its false "assigned namespace" refusal), `repl.clj`, `render/transcript.clj`, `cluster.clj:389`, and the ~20 test files naming starting-ns/ending-ns | `seon.runtime.edn`→`seon.agent.sci.edn`, `seon.agent.edn`, `seon.turn.edn`, `seon.cluster.eval.edn`, `turn.clj`, `cluster/agent.clj`, `repl.clj`, `render/transcript.clj`, `bootstrap.clj`, `issue.clj`, `cluster.clj`, tests | R1–R6 (§9) in `test/seon/cluster/agent_test.clj`, `test/seon/turn_test.clj`; the converted namespaces; `seon.cluster.program-change-test` for retirement | accretion + retirement of 59 + 117 datoms; no reset (the first settlement writes the session; until then runs start at home, as they do today) |
| P3 | **Runtime → sci; one history edge.** Delete `:seon.runtime/turns`, `/trigger`, `/agent`; move listens to `:seon.agent/listens`; convert the 10 readers of the turns list and the trigger render; `next-id` counts through `:seon.turn/agent`; the agent's render units list `:seon.agent/sci` | `seon.runtime.edn` (retired), `seon.agent.edn`, `turn.clj`, `eval.clj`, `cluster/status.clj`, `cluster/agent.clj`, `cluster/wake.clj`, `issue.clj`, `render/web.clj`, `render/transcript.clj` | status, wake, transcript, web debug, turn tests | retirement of 59 turns + 1 trigger; listens 0 on default; no reset. Agent retraction no longer cascades runs; archival retains agents, so nothing loses its runs |
| P4 | **Namespace half from the session.** `acquired-root-namespace-eid` reads `:seon.agent.sci/ns` before home; the opening and system turns start at the session (`turn.clj:1979-2030, 2233, 5478, 5549`); `(dir <ns>)` generated read | `render/walk.clj`, `turn.clj` | R5 in `test/seon/cluster/prompt_test.clj` | opening bytes change once (turn PRD §16a) |
| P5 | **Settings link and render.** `:seon.config/base` on the overlay, written by `creation-tx`; `seon.ai/agent-settings`; the A-list consumers; `:seon.config/shown` replaces the hand list; `show-all-settings` retired | `seon.config.edn`, `seon.config.*.edn` (shown flags), `ai.clj`, `cluster/prompt.clj`, `agent.clj`, `cluster/agent.clj`, and the A-list owner files (turn, repl, bootstrap, issue, cluster/message, plan, render/transcript, cluster, blob, test/runner, render/web, sci/eval, problems) | `config_test` (:506-521 stays green), prompt, message, plan, issue, blob tests; hand-built agent rows in ~25 test files move to `creation-tx` | **RESET NEEDED** on default after landing: agents created before lack `:seon.config/base` and their settings reads refuse by name (data is disposable; no backfill migration). Root batches it with P2–P4's restart |
| P6 | **Remove remaining two-way and doubled facts.** Delete `:my.plan/agent` and `:seon.config/agent` identities (writers address components through the owner, like `session-call`); delete `:seon.eval/renderer-fn`; one of occurrence `agent`/`turn`; route ownership through `:seon.ns/steward` (`form-owner`, `render/namespace-owner`) | `seon.config.edn`, `my.plan.edn`, `seon.eval.edn`, `agent.clj`, `plan.clj` and `my.plan` writers, `cluster/agent.clj`, `turn.clj`, `render.clj`, `render/transcript.clj`, `repl.clj`, `error.clj` | plan, settings, transcript, repl, error, problem-routing tests | retirements (114 + 114 + 3 datoms); no reset |
| P7 | **Dead context selection.** Delete `context.clj:76-256` and the contribution `agent`/`evaluations` members, after a current-reference check | `context.clj`, `seon.context.contribution.edn` | context tests if present | retirement; no reset |

Order: P2 first (the owner's direct request and the confirmed data loss of a318b2d38), then P3 and P4 together (both
touch `turn.clj`, so serially in one owner), and P5 in the same restart batch so the single RESET covers it. P1 runs
in parallel once its design is reviewed; it shares `turn.clj`, so it waits for P2 to release the file. P6 and P7
come after.

Sizes (production, approximate):

| Slice | Lines |
|---|---|
| P2 | +45/−150 |
| P3 | +15/−70 |
| P4 | +10/−10 |
| P5 | +40/−60 (the moved reader nets ~0; the hand list and guess reads go) |
| P6 | +20/−70 |
| P7 | −190 |

No slice exceeds ~100 added lines.

Risks:
- **P2's test sweep is wide** (76 starting-ns and 47 ending-ns mentions). It must be one commit with its writers.
- **P3 changes cascade.** Runs stop being components of the agent. Compaction (`turn.clj:2393-2423`) retracts
  evaluations only, and archival retains the agent, so no run is orphaned. A future agent deletion must retract its
  runs explicitly or refuse.
- **P5 touches many owner files.** It may need splitting per file owner, as the config design's slicing already
  proposed. All of those pieces must land before the RESET.
- **The refusal arm writes the session.** SCI has already moved, so the session must record the move even when
  the settlement refuses (§10 Q4).

## 9. Class regressions

- **R1 top-level `in-ns` mid-run.** A 3-form run whose form 2 is a top-level `(in-ns 'probe.b)`: forms record their
  own `ns`; the session becomes `probe.b`; the next run's form 0 runs in `probe.b`.
- **R2 nested `in-ns` (task a318b2d38).** A 2-form run whose form 1 is `(do (in-ns 'probe.c))`: the settlement
  commits; form 2's `ns` resolves to an identity-only row; the session becomes `probe.c`.
- **R3 scratch namespace.** After R2, `program/changed-identities` from the run's base has no
  `[:seon.ns/name probe.c]`, and `prepare-merge!`'s delta has no namespace.
- **R4 continuation.** A submitted run and the following system turn both start at the session, not home. A
  request carrying `:seon.ns/name` moves the session before its run, and the move shows in the next run.
- **R5 two halves.** After `in-ns`, the next prompt appends `(dir probe.b)` and keeps the earlier evaluations with
  their own `ns=>` prompts.
- **R6 retraction and compaction.** `remove-ns!` of the session's namespace sweeps `:seon.agent.sci/ns`, and the next
  run starts at home. `compact!` leaves the session intact.
- **R7 settings (P5).** Switching the agent's model with `settings!` changes the next turn's provider request.
  Changing the cluster's base dial with `config/apply!` changes the effective settings of an agent without that
  override. An agent without a base refuses with the named error.

## 10. Owner questions (few, priced)

**Q1. Where the settings link lives.**
- (a) *Recommended*: `:seon.config/base` on the overlay component. About 5 lines. It reads as one story ("my
  overrides over this base"), and the agent reaches it in its existing settings hop.
- (b) A ref directly on the agent (`:seon.agent/config`). Same cost, one hop shorter for no reader, and it separates
  the base from the overrides it explains.

**Q2. The cluster name stored twice** (`:seon.cluster/name` on the cluster, `:seon.config/cluster` on its config
row, joined by `:seon.cluster/config`).
- (a) Keep both identities. 0 lines; one duplicate string stays.
- (b) *Recommended*: the config row loses its own name and is reached as `[:seon.cluster/name n]` →
  `:seon.cluster/config`. About 30 lines in `config.clj` (every `[:seon.config/cluster n]` lookup) plus a
  retirement. It needs a no-reset adoption proof on a branch, because config reconcile addresses the row by that
  identity.
- (c) Merge the dials onto the cluster entity. It deletes a hop, but mixes config's exact-reconcile scope with
  cluster seeding; I would not do it.

**Q3. Retracting a namespace an agent is in.**
- (a) *Recommended*: the optional ref sweeps and the next run starts at home. 0 lines.
- (b) A required ref: `remove-ns!` refuses while any agent sits there. A restriction without a correctness failure
  it prevents.

**Q4. A refused settlement.**
- (a) *Recommended*: the refusal arm still writes the session to where SCI ended, because the context already moved.
  About 3 lines.
- (b) Leave the session unchanged. The next run then starts where the SCI context no longer is.

**Q5. Outside-agent MCP forms are stored as `:author :system`** (117 of 117 on root).
- (a) *Recommended*: record `:agent`, since they are the agent's own forms. A small change in the submission path;
  it changes which forms the refresh treats as system-generated.
- (b) Keep `:system`. 0 lines; history keeps mislabelling.

## 11. Evidence

Read-only JVM probes on default (`read_only true`, explicit cluster), 2026-09-27. None transacted.

1. Counts: agents 113, runs 59, evaluations 117, `:seon.cluster.eval/ns` 117, `:seon.sci.eval/ending-ns` 117,
   `:seon.turn/starting-ns` 59, `:seon.ns/name` 487 (all with digests), `:seon.runtime/turns` 59, `/trigger` 1,
   `/listens` 0, `:my.plan/agent` 114, `:seon.config/agent` 114, `:seon.eval/renderer` 3, `/renderer-fn` 3,
   occurrence agent 11 / turn 0, context captures 0, `:seon.turn.work/situation` 35.
2. Datoms: runs 7–10; evaluations 11–20 plus read-evidence (1,353 datoms over 451 components).
   `:datahike.read/dependency-plan`: 17,572,807 characters over 451 values (286 distinct); max 1,501,080; median 409.
3. `seon.eval/of-agent d "root"`: 1,552.9–1,818.7 ms; with `[:seon.cluster.eval/source :seon.eval/shown]`
   0.41–0.51 ms warm. Raw `datahike.api/pull-many` with the full selector: 3.1 ms. `seon.db/pull [*]` of the
   evaluation holding the 1.5 MB plan: 126 ms (raw 0.08 ms). `turn/latest-evaluations`: 1,557–1,604 ms.
   `transcript/history` limit 200: 19–30 ms.
4. µs warm: two-hop `seon.db/pull` 30; `previous-ending-namespace` 103–118; `agent-namespace` 85–91;
   `[:seon.ns/name …]` lookup 16–18.
5. Stored history tokens (source + shown, chars/4): 34,593; messages read 15,413 over 25 runs;
   `seon.error/for-agent` 8,455 over 26; runtime read 6,000 over 25.
6. `directory-value` (chars/4 of `pr-str`): `my.agents.root` 32; `seon.repl` 2,726; `my.program` 4,857;
   `seon.cluster.agent` 4,448; `seon.turn` 11,284.
7. Config: row `[:seon.config/cluster "default"]` has 85 attributes; `config/effective` has 82 dials, ~879 tokens;
   the summary `seon.agent/effective-settings d "root"` is ~76 tokens; per-agent overlay attributes: 34. Root's
   overlay holds only its identity back-ref. The cluster entity refs the config row and lists 13 toolkit
   namespaces and 1 instruction.
8. Store roster: `:cluster-default` 1 cluster row / 113 agents; `:checkpoint-default-f311e0fc…` 1 / 0;
   `:current-src` 0 / 0; `:db` has neither attribute. Root reports P0.3 as: every production value holds one
   cluster row.
9. P0.1 was confirmed by root on branch `p0-probe` (task `a318b2d38`, `resources/seon/tasks/seon.turn.edn`).

Not measured: a full `seon.cluster.prompt/prompt` timing (it needs the agent's SCI context and writes the render
cache); test-harness worlds; any test run.
