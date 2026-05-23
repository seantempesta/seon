---
type: concept
status: active
tags: [concept, architecture, agent, database]
---

# Reactive context — the default

**Agents see derived views of the database, not accumulated state. Sections are functions of the DB at render time. New ways to surface data are new section functions, not new mechanisms.**

This is the load-bearing architectural principle for how agents experience their world. It's why Seon avoids notification queues, separate event streams, mutable counters, or atom-backed registries: the database is the truth, and **every piece of context an agent should see is computed on demand from current DB state**.

## The principle

> Render-time queries over the database produce the agent's context. If the underlying state changes, the next render reflects it. If the state goes away, the surface goes away. No bookkeeping, no separate paths, no stale stored copies.

A new "thing the agent should see" — warnings, related work in other namespaces, system-wide errors, what other agents are doing — is just a new section function (or a new query inside an existing section). The composer already runs sections every turn; adding a query is additive and free.

## Why this matters

Three failure modes the principle prevents:

1. **Stale state.** A stored "last error" datom outlives the error. A counter that bumps on event X and resets on event Y drifts when the two events race. Derived sections vanish the moment the underlying state vanishes, because the section is just a query.

2. **Bifurcated architectures.** Every new "kind of thing" doesn't need a new system. "Show the agent failing tests" is a query. "Show the agent failing tests across other agents" is the same query without an agent-id filter. "Show the agent slow evals" is another query. **One mechanism (sections), N queries.**

3. **Hidden coupling.** When state is stored, multiple writers and multiple readers create implicit synchronization requirements. Derive at read time and the only thing that needs to be right is the source data — the eval log, the message log, the entity graph. There's nothing else to keep in sync.

## How sections work (the canonical pattern)

Each `:seon.ctx` entity in the agent's `:seon.agent/ctx` carries a `:seon.ctx/fn` symbol. At render time, the composer:

1. Pulls `:seon.agent/ctx` (sorted by priority).
2. Resolves each `:seon.ctx/fn` symbol to a live function via `seon.eval/lookup-value`.
3. Calls the function with `{:seon.db/db <current-db-value> :seon.agent/id <id> :seon.agent/ctx-entity <section-entity>}`.
4. Joins the non-blank string returns.

The function gets the **full DB value**. It can query anything — its own agent's state, other agents' state, system-wide entities. Cross-agent visibility is the default; scoping is opt-in (filter by `:seon.agent/id` in the query).

Example — a warnings section that surfaces problems anywhere in the system:

```clojure
(defn warnings-section
  "Render current problems across all agents. Empty when clean."
  [{:seon.db/keys [db]}]
  (let [failed (db/query
                 {:seon.db/db db
                  :seon.db/query
                  '[:find ?eid ?aid
                    :where
                    ;; Failed evals since the latest user message —
                    ;; "the agent had problems with what was just asked"
                    [?u :seon.message/role :user]
                    [?u :seon.message/at ?u-at]
                    [?u :seon.message/agent ?a]
                    [?a :seon.agent/id ?aid]
                    [?e :seon.eval/at ?e-at]
                    [(> ?e-at ?u-at)]
                    [?e :seon.eval/ok? false]
                    [?e :seon.eval/id ?eid]]})]
    (if (seq failed)
      (str "<warnings>\n"
           (count failed) " failed evals across agents since latest user msg\n"
           "</warnings>")
      "")))
```

When the agent (or another agent) fixes the form and re-evals, the failed entity stays in history but no new failed evals exist past the latest user message — the query returns empty — the section renders blank — the warning is invisible. No acknowledgement, no clearing, no notification. **The system is self-healing because nothing was ever stored.**

## Caching is the perf escape hatch (not a separate path)

If a derivation is expensive (deep traversal, heavy aggregation), cache the result, don't bifurcate the architecture into "stored fast path" + "derived slow path". The canonical pattern:

- Compute the derived value on demand.
- Memoize keyed on a cheap invalidation signal (latest tx-id, latest entity hash).
- Same call site, same return value, just faster on cache hit.

What this lets you avoid: never write `(if (recent-tx?) (derive) (read-stored))`. Don't store "the answer" in a datom that has to be kept in sync with reality. Compute, cache the computation, invalidate cleanly.

When caching is unjustified: most reads. Datahike queries against a `:memory` conn are sub-millisecond. The reduce that derives `current-ns` from a session's evals reads at most a few dozen datoms. Measure before caching.

## What this principle rules out

- **Storing fields derivable from the log.** `:seon.session/turns-since-user` is a count of turn entities relative to a message timestamp; that's a query, not an attribute.
- **Atom-backed registries** for derivable state. `!current-ns` (process-global), `!warning-predicates` (process-global) — both wrong. Atoms for legitimately stateful runtime artifacts (compile-state, DB conn) are fine.
- **Separate event/notification systems** for new context kinds. If agents need to "see warnings", that's a section. If agents need to "see what other agents wrote", that's another section (or the same one querying across agents). No new mechanism.
- **Acknowledgement bookkeeping.** "Mark this warning as seen" implies stored warnings. If the warning is a query result, fixing the underlying state makes it disappear. No acknowledgement needed.
- **Foreign-key counters.** Anywhere you have a `:foo/count` that must equal `(count …)` somewhere, the count is a query.

## What it does NOT rule out

- **Genuinely stateful artifacts** that aren't derived from the log: the bootstrap-CLJS compile-state cache, the datahike connection itself, the AsyncLocalStorage instance. These are runtime infrastructure, not domain data.
- **Identity attrs** for entity lookup (`:seon.eval/id`, `:seon.fn/sym`). Identifiers are stored; the entity's other attributes might be derived for some, persisted for others depending on whether they come from input or from analysis.
- **The eval log itself.** `:seon.eval/source`, `:seon.eval/ok?`, `:seon.eval/ns`, `:seon.eval/at` are the substrate of derivation; they're what gets queried. Same for messages, turns, sessions.
- **Caching of expensive derivations** (see above). Memoization is fine; bifurcation isn't.

## Cross-agent coordination falls out for free

Sean's goal: "agents should see system-wide issues that go beyond their immediate context — and coordinate through the database."

This goal is met without any additional infrastructure. A section function queries the full DB. If it doesn't filter by `:seon.agent/id`, it sees everything. Agent A's failed eval shows up in agent B's next render. Agent A's failing test shows up across all agents. No subscription, no notification, no event bus — just queries.

The contract is: **what's true in the DB is what's true in everyone's context, on the next render.**

## Section design checklist

When writing a new section function, ask:

1. **Is the value derivable from existing entities?** If yes — write a query. If no — verify it's genuinely stateful and that the state belongs to someone (typically the eval log or the entity it's tracking).
2. **Does it need to vanish when the underlying problem vanishes?** Almost always yes. If you find yourself writing "if newer-than-X, return empty", that's the system asking you to query for newness instead.
3. **Should other agents see this too?** Default to yes — don't filter unless there's a reason. Cross-agent visibility is part of the substrate.
4. **Is the derivation cheap?** Datahike `:memory` queries are sub-millisecond for small datom counts. Measure with `(.now js/Date)` deltas before adding caching.
5. **If you DO need to cache,** memoize with a cheap invalidation signal (latest tx-id) — don't store the derived value as a datom.

## Cross-references

- `docs/prds/agent-runtime/v1.md §5` — section composer + the six default sections
- `docs/prds/agent-runtime/research/derive-not-store-2026-05-23.md` — the audit + REPL evidence that produced this principle
- `CLAUDE.md` "Data Rules" — the broader data-shape principles this builds on
- `src/seon/agent.cljs` — `assemble-ctx` composer + the six default section functions
