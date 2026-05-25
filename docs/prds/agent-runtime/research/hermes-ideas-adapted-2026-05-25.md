---
type: research
status: completed
tags: [research, agent, design]
---

# Hermes ideas adapted — what to port and how it manifests in Seon (2026-05-25)

## TL;DR

After staring at Hermes' 4348-LOC orchestrator, its 1781-LOC curator, its 3279-LOC SQLite/FTS5 layer, and its 25+ platform gateways, only a small number of *ideas* actually translate to a substrate that has no sessions, no fixed tool catalog, and an agent that authors its own program graph. The five worth importing — **idle-time curator-as-LLM, progressive disclosure on the program graph, attention discipline on docstrings/descriptions, per-pattern capability approval, and self-observation via a "behavioral derivation" section** — share one property: they all manage the entropy that accumulates when an agent runs forever. Hermes manages this entropy with explicit artifact stores and explicit per-store curation logic; Seon already has the storage layer (the DB + program graph), so each idea collapses to "a query, a tx-listener, or a section function" rather than "a new subsystem". The single most load-bearing port is the curator — but the Seon curator is not curating skill markdown, it is *re-weighting and pruning the agent's own program graph based on usage observed in the eval log*.

The body of this doc walks each adopted idea through Hermes-mechanism → underlying need → no-sessions/self-specializing Seon shape → concrete native manifestation. Three ideas I considered and rejected are at the end with reasoning.

---

## 1. The curator — but for the program graph, not for markdown skills

**What Hermes does.** `agent/curator.py` (1781 LOC) is a background auxiliary-LLM process that wakes during idle time, reads agent-authored skill files (`created_by: "agent"` only — it refuses to touch user-authored skills), and decides whether to consolidate, archive, pin, re-tag, or absorb-into-sibling. It tracks its own actions in `.curator_state` with backups, classifies its own diff (`_classify_removed_skills`) to attribute "this skill was absorbed into X rather than deleted", and gates everything behind idle-timer + concurrency locks so it never fights the foreground agent. The hand-rolled YAML summaries it writes feed back into the next foreground turn's system prompt.

**Why it works there.** Hermes' skills are markdown files in `~/.hermes/skills/`. Nothing in the runtime knows whether a skill is actually useful — there is no callgraph, no test, no "this skill was referenced 0 times in the last 200 turns" signal. The curator exists because the substrate cannot answer "which of my artifacts are dead?" structurally; it has to ask an LLM to read them and judge.

**The underlying need.** Long-lived agents produce more artifacts than they consult. Without a pruning loop, the catalog drifts toward "everything ever tried", attention dilutes, and the agent becomes worse at picking. The need is *entropy reduction over agent-authored artifacts*, not "summarize markdown".

**How this shows up in no-sessions, self-specializing Seon.** A long-running agent that partitions itself into emergent roles will author hundreds of `:seon.fn` and `:seon.schema` entities, most of which will be exploratory — written, called twice, never called again. Detect-and-tee captures them all (correctly — code-as-data is not selective about what to log). The eval log captures every call site. Unlike Hermes, **Seon can answer "which fn was called zero times in the last N turns" with a Datalog query.** The reactive-context principle says: don't store "is-stale" — derive it.

But there's a job that needs an LLM, not a query: deciding *why* a fn is stale. Two near-duplicate fns that diverged because the agent forgot the first one exist is a different problem from a fn that solved a one-off task and will never be called again. A query knows the call count; an LLM knows the semantic relationship.

**Native Seon manifestation — `seon.curator`.** A namespace, not a separate process. It exposes one entry point that runs on an idle trigger (no foreground evals for N seconds, watched by the trigger system already in `seon.trigger.cljs`). Its loop:

```clojure
;; New schemas
(schema/register! :seon.curator/at         :inst)
(schema/register! :seon.curator/target     :seon.db/ref)         ;; the :seon.fn or :seon.schema
(schema/register! :seon.curator/action     [:enum :consolidate :archive :keep :flag])
(schema/register! :seon.curator/rationale  :string)
(schema/register! :seon.curator/absorbed-by :seon.db/ref)        ;; if :consolidate
;; entity identity is just :seon.curator/at + :seon.curator/target

(defn ^:async curate-tick
  "Wake on idle. Read stale fns from DB. Ask aux LLM to classify a batch.
   Write :seon.curator entities. Do NOT delete :seon.fn rows —
   archival is a derived state (no consumer of the section sees them)."
  {:malli/schema [:=> [:cat ::curate-tick-request] ::curate-tick-response]}
  [...])
```

Three principles distinguish this from Hermes' curator:

1. **It writes its judgments as DB entities, never mutates the targets.** A `:seon.fn` archived by the curator still exists in Datahike; the *section function* that surfaces "your authored functions" filters out any fn with a `:seon.curator/action :archive` newer than the fn's latest `:seon.fn/created-at`. If the agent re-defines it, the new `:created-at` outdates the archive ruling and it surfaces again. Self-healing.
2. **It only runs against agent-authored entities** (`:seon.fn/ns` resolves to a `:seon.ns/name` matching the agent's home-ns pattern, `:seon.agent.<agent-id>.*`). Substrate `seon.*` nses are immune. This is the `created_by: "agent"` gate Hermes ships, expressed structurally.
3. **It is itself an agent capability.** `(seon.curator/curate-tick {...})` is callable from the foreground agent's eval. The auxiliary LLM is just another `seon.ai.deepseek/chat` call — no separate process, no separate model integration, no `.curator_state` file. Backup is what Datahike already gives you (history is bitemporal).

**What surfaces it.** A `curator-findings-section` queries `:seon.curator` entities newer than the last user message with action `:flag`, joins to the target fn's sym + source-summary, renders as part of context. When the agent (or another agent) deals with the flagged fn — by deleting, rewriting, or explicitly clearing — the next curator tick produces no new `:flag` for it, the section renders empty.

**Why bother vs. letting the substrate evolve naturally.** Without this, the program graph grows monotonically. Reactive context says "if no consumer registers interest, it doesn't render" — but the agent *is* the consumer of its own `(seon.graph/list-fns)` query, and that query will return everything ever defined. The curator is the part of the substrate that turns "list" into "list relevant" without baking relevance criteria into the query (which is what Hermes does badly via toolset configs). Self-specialization, in particular, depends on this: an agent that has emerged a "scheduler sub-role" needs the scheduler fns it owns to be prominent and the abandoned experiments around scheduling to be invisible. Without an LLM-in-the-loop pass, the system has no way to know the difference between "abandoned experiment" and "rarely-used but load-bearing utility".

---

## 2. Progressive disclosure over the program graph

**What Hermes does.** `tools/skills_tool.py` separates `skills_list` (metadata: name, description, tags, platforms — no body) from `skill_view` (full markdown with `references/`, `templates/`, `scripts/`). The cost discipline is explicit: tier 1 is cheap and goes into every system prompt, tier 2 is on-demand. The accompanying hardline on `description` — ≤60 chars, one sentence, ends with period, no marketing words — exists because Hermes discovered that long descriptions dilute attention when many skills load.

**Why it works there.** A 100-skill catalog inlined into the system prompt would blow the context budget and degrade selection. Two-tier disclosure is the only way to scale the catalog.

**The underlying need.** *Any* surface that can grow unboundedly needs cheap-summary vs. on-demand-body separation. Without it, the agent's context window becomes a function of the catalog's history rather than the current task.

**How this shows up in Seon.** The program graph is *the* unbounded surface. A self-specializing agent running for a month will have hundreds of `:seon.fn` entities. The naive section function "list all your authored functions" inlines source for everything; this is exactly the failure mode the principle names.

**Native manifestation.** Two section functions, not one, and a docstring constraint enforced by the analyzer:

```clojure
;; Section tier 1 — always rendered, costs ~one line per fn
(defn authored-functions-summary
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  ;; query :seon.fn entities for this agent, not archived by curator,
  ;; render as "(seon.agent.<id>.foo/bar [arg1 arg2]) — short doc."
  ...)

;; Section tier 2 — rendered ONLY when a :seon.ctx entity points at a
;; specific :seon.fn/sym (the agent transacted "I want to see this body")
(defn authored-function-body
  [{:seon.db/keys [db] :seon.agent/keys [id]
    :seon.agent/keys [ctx-entity]}]
  (let [target (:seon.ctx/fn-target ctx-entity)]
    (str "<fn-source sym=\"" target "\">" ...source... "</fn-source>")))
```

The expansion mechanism is itself reactive-context: the agent transacts `{:seon.ctx/name :inspect-fn :seon.ctx/fn-target "seon.agent.X/bar"}` to "open" a body; it transacts a retract to close it. No `skill_view` tool call needed — the section composer already runs every turn and picks up the new ctx entity.

**The docstring hardline ports as a Malli property + analyzer warning.** Register `:seon.fn/doc` with a `:max 120` constraint; the analyzer's detect-and-tee path adds a warning entity if a newly-authored fn's docstring exceeds it. The warning surfaces in the existing `warnings-section`. Same mechanism as everything else, no new infrastructure, no curator pass needed for this one — Hermes' rule becomes a registered constraint.

**Why bother vs. letting it evolve.** The section composer in `src/seon/agent.cljs` already inlines source for everything in `:seon.agent/ctx`. The first time the agent has 50 authored fns in a single render, context explodes. Building tiered disclosure *before* that's a problem is cheap (one schema, two sections); fixing it after the agent has been trained to expect everything inlined is expensive.

---

## 3. Self-observation as a derived view — the "behavioral mirror"

**What Hermes does.** `tools/skill_usage.py` tracks per-skill `use_count`, `view_count`, `patch_count`, `last_activity_at` in a sidecar JSON. The curator and the `skills_list` tool both read this to rank/prune. Session search (`tools/session_search_tool.py` + FTS5) is the other half — the agent can ask "when did I last do X?" against its own transcript.

**Why it works there.** Hermes' agent has no introspection into its own runtime — it cannot ask the Python process "how often did you call function X this week". The sidecar usage file + FTS5 transcript are how the agent observes itself.

**The underlying need.** The agent should be able to see *its own behavioral patterns over time*: which functions it leans on, which schemas it routes data through, which capabilities it requested approval for, where it failed and what it tried next. This is the substrate of self-specialization — an agent partitioning into roles requires noticing what role it has *already* been playing.

**How this shows up in no-sessions Seon.** The eval log already contains every call. `:seon.eval/source` is the form, `:seon.eval/at` the timestamp, `:seon.eval/ok?` the outcome, `:seon.eval/ns` the namespace context. Hermes' sidecar JSON exists to compensate for the absence of this log. Seon doesn't need the sidecar — it has the log natively. What it doesn't have is the *derived view*.

Critically: **no sessions means the "self-observation" surface cannot be "search by session id". It has to be "search by shape and time."** The eval log is the timeline; queries against it are how the agent knows itself.

**Native manifestation — `seon.agent.behavior` namespace.** Pure derivations, no new state. Each is a function over `db` returning a map:

```clojure
(defn most-called-fns
  "Top-N agent-authored fns by call count in the eval log over the
   last N evals. Pure DB query — counts derived, not stored."
  {:malli/schema [:=> [:cat ::most-called-fns-request]
                      ::most-called-fns-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]
    :keys [:seon.agent.behavior/window
           :seon.agent.behavior/top-n]}]
  ...)

(defn drift-since
  "Eval bursts grouped by ns, ordered in time. The agent reading this
   sees its own recent focus — `seon.agent.X.email` for 3 hours, then
   `seon.agent.X.calendar` for 1 hour. This IS emergent specialization
   becoming visible to the agent itself."
  ...)

(defn capability-prompt-history
  "Every WIT capability-prompt the host has been asked to approve,
   joined to the fn-eval that triggered it. The agent can see which
   capabilities its specialized sub-roles rely on most heavily."
  ...)
```

These get rendered as a section that the agent can choose to include in its ctx by transacting a `:seon.ctx` entity pointing at them. The default render is collapsed ("you've called `weather/forecast` 47 times this week, peak ns: `seon.agent.X.morning`"); the agent expands by transacting interest in the underlying entity, same progressive-disclosure mechanism as §2.

**FTS over `:seon.eval/source` and `:seon.message/content`.** This is the legitimate Hermes-validates-a-gap finding from the prior doc. Datahike has no FTS. The pragmatic answer for no-sessions Seon: maintain an off-DB FTS index (Lunr in the CLJS pod, or a Tantivy/SQLite-FTS5 sidecar in the Tauri host) as a derived projection of the eval+message log. It's the same architectural pattern as the disk-write debug mode — a derivation onto another substrate, gated by a flag, authority remains in the DB. The index is rebuilt from the log; it can be wiped and rebuilt without data loss. The agent calls `(seon.agent.behavior/search-evals "weather")` and gets eval-ids back, which it then pulls from the DB.

**Why this is load-bearing for self-specialization.** Without behavioral derivations, "emergent specialization" is a story the user tells about logs nobody reads. With them, the agent itself can notice "I have done 200 evals in `seon.agent.X.email` this week" and act on that — by promoting email-handling fns into more prominent sections, by asking the curator to consolidate stale non-email exploration, by writing helper fns specific to the role it has discovered itself playing. This is the difference between "the agent does work" and "the agent observes itself doing work and adapts". Hermes can't do the second one structurally; Seon can, and this is the section that operationalizes it.

---

## 4. Per-pattern capability approval — UX shape, not new mechanism

**What Hermes does.** `tools/approval.py` + `hermes setup`-collected patterns let the user pre-approve "any `read_file` under `~/Documents/notes/**`" without per-call clicks. The patterns are user-curated text, evaluated against the actual tool call.

**Why it works there.** Per-call approval is unworkable UX; allow-all is a security disaster. Patterns are the middle ground that scales.

**The underlying need.** Capability prompts that don't dilute into "always yes" or "always interrupt".

**How this shows up in Seon.** The WIT `capability-prompt` interface in `pod-host/wasm-tauri/` is the host-side hook. The pod calls `capability-prompt::request(...)`; the Rust/Tauri host decides whether to auto-approve, auto-deny, or surface to the user. The decision logic is *not* in the pod — capability authority lives in the host, by design.

**Native manifestation.** The pod side is just: every WIT-imported capability that needs gating goes through `capability-prompt::request` with a structured argument map (capability name, resource shape, calling fn-eval-id). On the *host* side, a `~/.seon/capability-policy.edn` (or equivalent in the Tauri config dir) holds user-authored patterns. The host evaluates patterns against requests; matches auto-resolve, misses prompt the user.

**The Seon-specific twist.** Every capability prompt — auto-approved or user-clicked — gets logged back into the pod's DB as a `:seon.capability-prompt` entity with the resolving decision and the fn-eval-id. This means **the behavioral mirror (§3) can show the agent its own capability footprint over time**, and the curator (§1) can flag fns whose capability usage drifted (a fn that historically only read paths under `~/notes/` and started reading `~/Library/` is a candidate for review). Hermes has the approval log; Seon makes the log a first-class input to the curator and the behavioral mirror.

**Why bother now.** Approval UX is the kind of thing that *has* to be designed before the capability surface is in production. Bolting it on after users have been auto-approving everything is how products end up with default-allow security postures.

---

## 5. The `MEMORY.md` frozen-snapshot discipline — prompt-cache hygiene

**What Hermes does.** `tools/memory_tool.py`'s docstring is explicit: mid-session writes update the file but do NOT change the system prompt for the remainder of that session — the snapshot is frozen at session start. This preserves the OpenAI/Anthropic prefix cache.

**Why it works there.** Cache invalidation per turn is the difference between a $0.10 conversation and a $5 conversation. Hermes' UX of "I told you about X earlier today, you should remember" is achieved by a fresh snapshot at the *next* session start, not by mutating the live system prompt.

**The underlying need.** Render-time derivation (Seon's reactive-context default) is on a collision course with prompt-caching economics. *Every change to the system-prompt prefix invalidates the cache for every turn from that point.*

**How this shows up in no-sessions Seon.** This is the most concerning Hermes finding for Seon's architecture, because Seon's principle is "render the context fresh every turn" and Hermes' principle is "freeze a snapshot per session for cache stability". These are not compatible at face value, and Seon doesn't have sessions to use as the freezing boundary.

**Native manifestation — separate the prompt into a stable prefix and a derived suffix, with the boundary cached at a coarse granularity.** Concretely:

- **Stable prefix.** Substrate-level rules (CLAUDE.md analogue baked into the agent's bootstrap), the agent's persistent role-charter section, the schema of available WIT capabilities. Recomputed only when one of those underlying entities transacts — a tx-listener watches the relevant attrs, invalidates a cached prefix string, otherwise re-uses it across many turns.
- **Derived suffix.** All the reactive sections — warnings, behavioral mirror, curator findings, recent eval results. Re-rendered every turn, lives below the cache boundary. Lower cache hit rate by design; the suffix is also the smallest part of the prompt.

The discipline is: **a new reactive section is added to the suffix by default. Promoting it to the prefix requires a deliberate decision and a tx-listener-driven invalidation, because it costs cache hits on every render upstream of it.** This is a rule the curator can enforce — flag any new `:seon.ctx` entity that landed in the prefix half without an accompanying invalidator.

In a no-sessions world, the "freezing boundary" is not session start but *the most recent tx-id of any entity feeding the prefix*. The cache is alive across an arbitrary number of turns as long as those entities are stable. This is strictly more flexible than Hermes' per-session freezing, but it requires Seon to actually distinguish prefix from suffix in the section composer — which it currently does not. That distinction is the work.

**Why bother now.** The current section composer treats all sections uniformly. The first time the substrate is run against a frontier model with caching enabled, the cache hit rate will be near zero and the bill will reflect it. The principle costs almost nothing to add now (one `:seon.ctx/cache-tier` enum: `:prefix | :suffix`) and is very expensive to add after every section has been authored assuming uniform treatment.

---

## Ideas considered and rejected

**Session search / FTS5 over conversation history.** Already addressed in §3 — Seon has no sessions, so "session search" doesn't translate. The legitimate need (text search over the eval+message log) is solved by a sidecar FTS index, not by adopting Hermes' session-centric model. Rejecting this also means rejecting the entire `hermes_state.py` schema-as-source-of-truth shape; Datahike is the source of truth, FTS is a derived view.

**Explicit subagent spawning (`delegate_task` with `role="leaf"` and `DELEGATE_BLOCKED_TOOLS`).** Tempting because the prior doc flagged it. Rejected for the *self-specializing single agent* case because spawning parallel pods to handle sub-roles is the opposite of "one agent partitioning itself" — it forks the program graph into N pods that can't see each other's `:seon.fn` entities without a propagation mechanism. Self-specialization within one pod, mediated by the curator promoting role-relevant sections and demoting role-irrelevant ones, is the cleaner shape and matches the user's stated hypothesis. Multi-pod *will* exist (the agent-id ALS dynvar work is the precondition), but it's a horizontal-scale answer, not an emergent-specialization answer.

**`MEMORY.md` and `USER.md` as parallel artifact stores.** Hermes ships these as separate text files because the substrate has no other place to put unstructured "things the agent knows". Seon has Datahike. A `:seon.user/<attr>` schema namespace is the native shape; "what the agent knows about the user" is just entity attributes on a user entity, queried by sections like everything else. No new stores, no markdown, no parallel surface. The only thing worth importing is the *discipline* of treating it as a separate concern — which §5's prefix/suffix split partly enforces.

**The platform-adapter pattern (`gateway/platforms/*.py`).** Rejected from this synthesis because it lives in the consumer product, not the substrate — already correctly identified in the prior doc's §3. It is the largest single piece of Hermes by LOC and the single piece Seon's hard rule says explicitly does not belong.

---

## Closing — the through-line

The five ports above share a property worth naming: **each one is the substrate's answer to a question that, in Hermes, required a new artifact store.** Hermes built the curator because skills are markdown files with no callgraph; Seon's curator runs against the program graph because the program graph IS the artifact store. Hermes built skill-usage tracking because the runtime can't introspect itself; Seon's behavioral mirror is a query over the eval log because the eval log IS the introspection surface. Hermes built session-search because session-state is opaque to the agent; Seon's FTS sidecar projects from the log because the log IS the timeline.

The pattern: *Hermes invented stores; Seon already has one store and adds derivations.* The five things worth porting are precisely the derivations Hermes had to invent stores to support. Skip the stores; keep the insights.

The piece that does *not* fall out for free, and is the highest-priority piece of new work surfaced by this exercise, is the **prefix/suffix split in the section composer with tx-listener-driven prefix invalidation** (§5). It is the only one that requires re-architecting an existing surface rather than adding a new section or a new query. It is also the only one that, left undone, makes the substrate uneconomic to operate at the model-cost layer. Build it before the cache bill arrives.

---

## Cross-references

- `docs/prds/agent-runtime/research/hermes-agent-comparison-2026-05-25.md` — the prior comparison this builds on
- `docs/seon/concepts/reactive-context.md` — the principle every section pattern here invokes
- `docs/seon/concepts/code-as-data-runtime.md` — why the curator targets DB entities not files
- `src/seon/agent.cljs` — the section composer + the six current default sections (the surface that gets the new tiers, the curator section, the behavioral mirror section)
- `src/seon/trigger.cljs` — the idle-trigger hook the curator wakes on
- `pod-host/wasm-tauri/` — host side of the per-pattern capability-policy work
- Hermes: `agent/curator.py` (the pattern), `tools/skills_tool.py` (progressive disclosure), `tools/skill_usage.py` (self-observation), `tools/approval.py` (per-pattern capability), `tools/memory_tool.py` docstring (frozen-snapshot discipline)
