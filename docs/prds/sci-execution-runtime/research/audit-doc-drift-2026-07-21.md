---
type: research
status: active
tags: [research, agent]
---

# Doc-drift audit — every surface that teaches the wrong thing (2026-07-21)

Audited against the settled target: (a) agents are sci contexts on hosts (JVM
`seon.host`, single-tier verdict); the Bun per-agent execution child fleet and
the `cljs.js` self-host engine are deleted at cutover (U11); the sync idiom
replaces `^:async`/`await` for agent-authored code (U8). (b) Generate-code
teaching contract (owner, 2026-07-21): data modeling first, then dependency
functions/namespaces, then main namespaces; any authoring order is fine — the
parser loads in dependency order; mistakes are fixed by overwriting — last
version wins. (c) DeepSeek-first worker economics, Kimi K3 planning.

Verdicts are timed: **REWRITE\@U8** = must change when the sync-idiom steering
unit lands (before cutover); **REWRITE\@U11** / **DELETE\@U11** = changes with
the children-retirement/deletion commit; unmarked verdicts can act now.

## Surface 1 — renders into live agent context (highest stakes)

| # | file:line | Currently teaches | Verdict | One-line fix |
|---|---|---|---|---|
| 1 | `config/system.edn:236-269` (`:seon.config/system-text`) | "live ClojureScript REPL in your own supervised Bun execution child; sibling agents and the pod run in other processes"; "The runtime awaits a Promise returned by one WHOLE top-level form … await each dependency inside a `^:async` function" | REWRITE\@U8 | Reword to sci-context-on-host + sync idiom; drop the Bun-child/pod sentence and the whole async-forms paragraph; re-verify the restart-persistence lines (defn/deftest/register! survive) against host semantics |
| 2 | `src/seon/agent/ctx.cljs:775-887` (shipped fallback `system-text`) | "It is ClojureScript in a long-running Bun process: you have full js/ interop … but NO JVM — no java.*"; "ASYNC FORMS. The runtime awaits a Promise … bare top-level (await x) throws"; "a bare (def x 42) does NOT survive … (a self-host limitation)" | REWRITE\@U8 | Interop teaching INVERTS on the JVM host (java.* yes, js/ no — or per-tier if a Bun tier survives the U11 audit); delete the ASYNC FORMS block; re-verify the bare-def limitation, which is a self-host artifact |
| 3 | `src/my/plan/internal.cljc:1764-1786` (`development-teaching`) | "Emit ordinary valid ClojureScript forms … the batch evaluator … awaits top-level Promises" | REWRITE\@U8 | Say Clojure, drop "awaits top-level Promises"; the rest already matches the 2026-07-21 contract (data model first, batch derives require order, redefinition replaces = last-version-wins) — consider adding the explicit "any order is fine; overwrite to fix" sentence |
| 4 | `seon-skills/clojurescript/SKILL.md` (all 187 lines; byte-identical copy at `.agents/skills/clojurescript/SKILL.md`) | The pod is a Bun CLJS process; `cljs.js` self-host compiler; `^:async`/`await` "the core feature"; self-host verdict; auto-await mechanism | DELETE\@U11 (or full REWRITE if a Bun sci tier survives) | The engine it documents is deleted; replace with a host-tier (sci-on-JVM) skill teaching sync idiom and interop boundaries |
| 5 | `seon-skills/datahike/SKILL.md:3,11-35,113-118,203-218,375-377` | "ACTIVE runtime is the CLJS pod (a long-running Bun process)"; "transact! is `^:async` … Promise ENVELOPE (auto-awaited at the REPL top level; inside an ^:async fn you await it)"; "Don't write `await` on `transact!`" | REWRITE\@U8 | Query/transact semantics survive; rewrite the async/envelope mechanics to the sync idiom and retire pod vocabulary |
| 6 | `seon-skills/data-oriented-clojure/SKILL.md:3,17,121,167,182-188,249,257` | Section "Async: CLJS native `^:async`/`await`, never core.async in the pod"; cross-links to the clojurescript skill for self-host | REWRITE\@U8 | Delete/replace the async section and the self-host cross-links; the mindset content is current |
| 7 | `seon-skills/data-modeling/SKILL.md:247` | "On the **active pod (CLJS)** …" | REWRITE\@U8 | One-line vocabulary fix (host/client) |
| 8 | `seon-skills/ui-canvas/SKILL.md:32-38` | Canonical canvas renderer example is `(defn ^:async dashboard … (await …))` | REWRITE\@U8 | Re-cut the example in the sync idiom |
| 9 | `seon-skills/repl/SKILL.md` (whole) | parse-forms/parinfer-repair/#code heredoc/forms-vs-prose mechanics | KEEP | Contract-compatible with the 2026-07-21 ruling (batch, real reader, per-form errors); verify the parser/repair layer carries to the host tier unchanged |
| 10 | `src/seon/warn.cljs:646` | Example prose: "First, query for the eid as its OWN top-level form (the runtime awaits it and returns the rows)" | REWRITE\@U8 | Drop the awaits clause; the guidance itself (eid over lookup-ref) is current |
| 11 | `src/my/blob.cljs:671-672`; `src/my/kb.cljc:109`; `src/my/skills.cljc:183` | Comments/docstrings teaching "put! is ^:async (it AWAITS the datom write)", "transact! returns the envelope Promise (await it in a fn)", "the eval path auto-awaits the ^:async ones" — agents read core source, so these render | REWRITE\@U8 | Reword to sync-envelope semantics when the toolkit port's fn signatures settle |
| 12 | `src/seon/agent.cljs:895,1067,1145` | "the pod hosts it from the committed transaction" (docstrings on birth/delegate) | REWRITE | "pod" retired (source-cleanup owner ruling); say the cluster/host observes the committed birth |
| 13 | `src/seon/embed.cljs:2-37` | "Embedding search API for the pod … Native `^:async`/`await` throughout (the pod is core.async-free)" | REWRITE\@U8 | Vocabulary + async framing |
| 14 | `src/seon/execution.cljs`, `src/seon/eval.cljs` + `src/seon/eval/`, `src/seon/worker_eval.cljs`, `src/seon/worker_validator.cljs`, `src/seon/subprocess.cljs`, `src/seon/repl.cljs:93-123` (bootstrap init) | Child-fleet / self-host bootstrap mechanics throughout docstrings | DELETE\@U11 | These namespaces are the U11 deletion set; their teaching dies with them — no separate doc fix needed, but do not cite them in refreshed docs |

## Surface 2 — docs/seon/architecture

| file:line | Currently teaches | Verdict | One-line fix |
|---|---|---|---|
| `agent-runtime.md:360-400` | Crash recovery via "A fresh child first boots the trusted compiled artifact and its one `cljs.js` compiler"; "There is no SCI fallback, pod-side authored eval, second compiler" | REWRITE | Directly contradicts the settled single-tier sci verdict; recovery story must be re-grounded on host/context reconstruction |
| `agent-runtime.md:930-960` | "executes that contract as compiled ClojureScript inside one separately supervised Bun child per active agent"; "SCI is not the isolation mechanism"; Bun memory-pressure listener; immutable CLJS artifact image | REWRITE | The isolation-backend section is the inverse of the target; rewrite around sci contexts on `seon.host`, park/idle (U7), epoch re-link |
| `agent-runtime.md` general | The loop/run/turn, derived-state, fencing, bounds content | KEEP | Execution-substrate sections only are stale; the FSM/run model is unchanged |
| `architecture.md:30-52,214-247,258-314,336-448,530-591` | Three-role topology "JVM database authority / Bun UI host / Agent execution … separately supervised Bun children"; packaging = "immutable pod closure, self-host … production Bun runtime" | REWRITE | Re-draw the topology and packaging around the JVM sci host; sci-host architecture is currently reflected NOWHERE in `docs/seon/architecture/` (only two "SCI is not…" denials) |
| `ui.md` (13 Bun/async hits), `observability.md` (7), `data-model.md` (7) | Bun feed/async mechanics referenced in passing | REWRITE (sections) | Sweep during the U11 architecture-doc unit; none is load-bearing like agent-runtime.md |
| `context.md`, `toolkit.md`, `laws.md` | no Bun/self-host/async hits | KEEP | — |

## Surface 3 — docs/seon/reference

| file | status | Verdict | Note |
|---|---|---|---|
| `llm-adapters.md` | active | KEEP | Current: DeepSeek default worker economics, `kimi-k3` planning row, model catalog present |
| `flow-foundation.md` | active | MARK-COMPLETE | core.async/flow era; the flow topology was removed with the JVM application path |
| `separate-jvm-exploration.md` | active | MARK-COMPLETE | The exploration concluded — the JVM host is now the settled architecture; keep as historical evidence |
| `async-ui-patterns.md`, `hyperlith-patterns.md` | abandoned | KEEP | Already truthfully marked |
| `gemini-native-integration.md` | completed | KEEP | Truthful |
| `datastar-*.md`, `driving-codex-agents.md`, `linting-setup.md`, `third-party-*.md`, `hyperlith-comparison.md`, `durable-ctx-design.md` | active/draft | KEEP | No runtime-teaching drift found at grep depth |

## Surface 4 — PRD status hygiene

| dir | frontmatter | Verdict | Why |
|---|---|---|---|
| `_example-feature` | draft | KEEP | Template |
| `agent-canvas-interaction` | planned | KEEP | |
| `agent-ctx` | completed | KEEP | Truthful |
| `agent-fsm` | completed | KEEP | Truthful |
| `agent-runtime` | **active** (README.md) | MARK-COMPLETE | May-era index (Tauri/WASM tracks, "V0 CLJS pod that runs deepseek today", branch `feature/agent-runtime`); superseded by runtime-reliability + sci-execution-runtime |
| `agent-runtime-correctness` | planned | KEEP | |
| `agentic-tool-refinement` | **active** | MARK-COMPLETE (verify) | Display-v3-era refinement; its own text says gains are "integrated or superseded"; the tooling arc shipped 2026-07-03 |
| `bun-native-runtime-simplification` | **active** | MARK-COMPLETE, superseded-by sci-execution-runtime | Packages C/D shipped and live-proven; the Bun runtime it simplifies is itself retired at U11 |
| `database-authority-mesh` | active | KEEP (active) | Genuinely mid-flight recovery/graduation ledger |
| `database-browser` | planned | KEEP | |
| `database-lifecycle-recovery` | active | KEEP (active) | Remaining reopen/replay work named |
| `diffusion-dynamic-context` | **active** | REWRITE status → paused | Worker frozen, GPU-gated phases remain, direction pivoted to plan integration (loop OFF per owner) |
| `embeddings` | no roadmap (drafts) | MARK-COMPLETE | Shipped (`seon.embed`/Vertex path is the one mechanism); add a one-line completed index or archive |
| `frozen-turn-inputs` | **active** | MARK-COMPLETE (verify) | Its own text says G1-G7 graduated and consumers frozen |
| `generate-code` | active | KEEP (active) | Live graduation explicitly PARTIAL |
| `gym-v2` | **empty dir** | DELETE | Zero files |
| `independent-downstream-distribution` | **active** | MARK-COMPLETE (verify) + note | Proof narrative reads finished; its packaged artifact ("relocatable Bun runtime + pod closure") is invalidated at U11 — record the supersession |
| `inspect-autocomplete-evidence` | planned | KEEP | |
| `local-performance-graduation` | planned | KEEP | |
| `namespace-ui` | no roadmap (`archive/` + `research/` only) | MARK-COMPLETE | Already self-archived; give it a status-bearing index or fold into the archive |
| `reactive-render-units` | complete | KEEP | Truthful |
| `refinement` | no roadmap (23 loose docs, drafts) | MARK-COMPLETE, superseded-by runtime-reliability | Integrant-era plans (integrant-audit, plan-unified-runtime); Integrant path was deleted |
| `repl-autosuggest` | active | KEEP (active) | Owned by the other lane; internally states model work paused — accurate |
| `root-workspace-sessions` | planned | KEEP | |
| `runtime-reliability` | active | KEEP (active) | Current branch ledger |
| `sci-execution-runtime` | active | KEEP (active) | The transition ledger itself |
| `source-cleanup` | active | KEEP (active) | B-ledger mid-flight |
| `unified-flow` | no roadmap (3 draft design docs) | MARK-COMPLETE, superseded-by runtime-reliability | Flow-era architecture; flow topology removed |

## Surface 5 — dev skill corpus (.agents/skills)

`.agents/skills/clojurescript/SKILL.md` and `.agents/skills/repl/SKILL.md` are
byte-identical to their `seon-skills/` twins (verdicts under surface 1, rows 4
and 9 apply to both copies — one edit must land in both, or the duplication
itself reconciled). Additional dev-only skills:

| file | Teaches | Verdict |
|---|---|---|
| `.agents/skills/clojure-testing/SKILL.md` | cljs.test/async, pod conn mechanics | REWRITE\@U11 (test surfaces move with the host) |
| `.agents/skills/datastar-web-ui/SKILL.md` | pod web UI | KEEP (UI host survives; sweep "pod" vocabulary with source-cleanup) |
| `.agents/skills/browser-automation`, `seon-context-config` | operational | KEEP |

## Surface 6 — AGENTS.md authorities

| file:line | Currently teaches | Verdict | One-line fix |
|---|---|---|---|
| `AGENTS.md:133-151` ("Current runtime and boundary") | "the Node ClojureScript pod owns agents, eval, context/rendering"; "The CLJS sandbox catches model mistakes" | REWRITE\@U11 | This section is explicitly "current runtime", so it is truthful until cutover; it is the U11 doc-unit's first edit |
| `AGENTS.md:399-401` | "`^:async`/`await` is valid only inside a `^:async` function. Agent-facing eval awaits returned Promises … Read the `clojurescript` skill before changing self-host eval" | REWRITE\@U8 | Replace with the sync-idiom contract |
| `AGENTS.md:302,362,429-499,538-565` | pod vocabulary in vocabulary table, db rules, operator runbook | REWRITE | Owned by source-cleanup vocabulary-unification |
| `src/seon/AGENTS.md:33-34,43-44,61-73` | Ownership rows: "Code execution = the per-agent `seon.execution` child … retained self-host compiler"; "Pod process lifecycle"; "`.cljs` = the JavaScript pod and Bun execution children" | REWRITE\@U11 | Re-point the code-execution row at `seon.host`; delete child rows with the U11 commit |
| `src/my/AGENTS.md` | no stale hits | KEEP | — |

## Notes

- The single highest-stakes pair is rows 1-2 (the two system-texts): every
  agent reads one of them every turn, and both currently assert Bun-child +
  async-forms mechanics. U8 cannot close without them.
- The generate-code teaching (`development-teaching`) is already ~90% aligned
  with the 2026-07-21 ruling; only "ClojureScript" and "awaits top-level
  Promises" drift, and the explicit "any order / last version wins" phrasing
  could be made literal.
- `docs/seon/architecture/` contains zero affirmative description of the sci
  host; the only SCI mentions are two explicit denials (agent-runtime.md:377,
  943) that now contradict the settled verdict — the U11 architecture-doc unit
  is a real rewrite, not a sweep.
- Kimi K3 planning + DeepSeek execution economics are already correct in both
  `config/system.edn` (`:seon.config/model-variants`) and
  `docs/seon/reference/llm-adapters.md` — no drift found on (c).
