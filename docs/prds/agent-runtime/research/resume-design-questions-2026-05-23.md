---
type: research
status: draft
tags: [research, pod, cljs, agent, datahike]
---

# Resume phase — design questions to answer BEFORE implementation

v1.md §7.4 specs a `resume!` that re-evals program-graph entities (`:seon.ns` / `:seon.fn` / `:seon.schema`) in tx-id order after pod restart. The spec's central claim — "datahike tx-ids are monotonic and lookup-refs only resolve to already-written entities, so tx-id order IS a valid topological order — no DAG construction needed" — is plausible but underverified for several edge cases that matter in practice.

The Platform agent was about to ship a straightforward walker based on the v1.md sketch (commit 5786247 landed the eval-batch! refactor that resume depends on). **Sean paused and asked for a deep-dive research agent first** — resume touches enough hidden state (analyzer cache, current-ns, var stashes, schema registry, cross-ns deps) that "just re-eval each entity's source in tx-id order" is likely too naive. We don't want a hacked-together resume; we want one designed against the actual semantics of the substrate.

This file is **a queued task for the next Platform agent.** Dispatch the research prompt at the bottom, get the findings, then design the resume implementation from those findings. Do NOT implement resume from the v1.md sketch alone.

## Why this matters

End-to-end smoke test for v1 (per MVP's planned sequence):

1. Agent evals `(defn foo [x] x)` → detect-and-tee writes a `:seon.fn` entity with `:seon.fn/source "(defn foo [x] x)"`.
2. Pod restarts.
3. Resume walker re-evals the entity's `:source` → `alice/foo` is back in the live runtime.
4. Next user message → agent calls `(alice/foo 42)` and it works.

For step 4 to work, resume needs to:

- Put the `foo` var back in the analyzer cache so the next eval-batch sees it as defined
- Put the compiled fn back at the right munged globalThis path so calls resolve
- Be in the right ns when re-eval'ing (or `(in-ns 'alice)` first) so the def lands in the right ns
- Have any `:require`'d dependencies already re-loaded
- Have any schemas the fn references already re-registered in `seon.schema`

A naive `(doseq [e entities] (raw-eval (:source e)))` won't satisfy these unless the substrate is more forgiving than I assume. Hence the research.

## Open questions (research agent fills in findings)

### Q1. Datahike replay semantics under our config

- Is the "tx-id is monotonic" property genuinely guaranteed under `:keep-history? true` with the `:memory` backend? With on-disk LMDB?
- When the agent retracts-and-re-adds an entity (the recommended pattern for component-many overrides per v1.md §5.4), what does tx-id ordering give us — the retract's tx-id or the re-add's? Does that matter for replay correctness?
- Are there transactor-internal txes (e.g., schema migration, index rebuild) with tx-ids interspersed with agent-action txes? If so, can our query filter them out?
- Does `d/q` see retracted entities by default? If a `:seon.fn` was forgotten via `forget!`, is it still in the resume walk's result set?
- What happens when an entity has an identity-attr (`:seon.fn/sym`) and is upserted multiple times? `:source` is last-write-wins — but does the entity ID stay constant, or does each upsert create a new tx?

Source to read: `/Users/sean/src/seon/reference-code/datahike` (full datahike source). Specifically:
- `src/datahike/api.cljc` — transact + query surface
- `src/datahike/db.cljc` — index + history semantics
- `src/datahike/middleware/*` — any tx-id manipulation

### Q2. CLJS bootstrap analyzer + runtime state on re-eval

- Re-evaling `(defn foo [] ...)` — what state changes? Does the old var's `:meta` / `:fn-var?` info in `:cljs.analyzer/namespaces` get overwritten cleanly? Does cljs.js emit a JS reassignment that overwrites the globalThis path, or does it accumulate?
- Re-evaling `(ns foo (:require seon.bar))` — does this re-run the `:require` chain (potentially re-loading `seon.bar` from bundled analysis-cache)? Or does cljs.js short-circuit when the ns is already known?
- Re-evaling a `(deftype Foo ...)` or `(defrecord Foo ...)` — these create JS constructor fns. Does re-eval clobber them safely? Existing instances of the OLD type — what happens to them?
- Re-evaling `(defprotocol P ...)` — protocols register methods on the prototype. Does re-eval add or replace?
- Re-evaling `(defmacro m ...)` — macros are compile-time. Bootstrap CLJS handles macros via the compiler-state. Re-eval into the same compiler-state — what's the behavior?

Source to read: `/Users/sean/src/seon/reference-code/cljs.js` (bootstrap entry point) + `/Users/sean/src/seon/reference-code/cljs.analyzer` (analyzer internals). Particularly:
- `cljs.js/eval-str` — the public entry, how it threads through the compiler-state
- `cljs.analyzer/analyze` — how var defs get recorded in `:cljs.analyzer/namespaces`
- `cljs.compiler/emit` — how the analyzed form becomes JS, including munged paths

### Q3. Cross-namespace dependency ordering

The v1.md claim is that tx-id order IS topological because "an entity can only reference another via lookup-ref if the referenced entity already exists at write time." This is true for datahike refs, but is it true for CLJS dependencies?

- If agent first evals `(ns seon.foo)` then `(ns seon.bar (:require [seon.foo :as f]))` then `(defn bar/g [] (f/x))`, the SOURCE of bar/g references foo/x. But at tx-id time, what's the order of those entities?
  - `(ns seon.foo)` writes a `:seon.ns` entity for foo
  - `(ns seon.bar)` writes a `:seon.ns` for bar
  - `(defn bar/g ...)` writes a `:seon.fn` for g with `:seon.fn/ns [:seon.ns/name :seon.bar]`
- Tx-id order: foo-ns < bar-ns < bar/g. Replay in that order: re-eval foo-ns (re-establishes foo), re-eval bar-ns (re-requires foo — should resolve since foo was just re-eval'd), re-eval bar/g (resolves f/x since bar's require chain ran). OK, this works *if* re-evaling an ns form actually triggers the require chain.
- Edge case: what if `(defn foo/x [] ...)` is defined AFTER `(ns seon.bar (:require seon.foo))`? Agent did:
  - `(ns seon.foo)`
  - `(ns seon.bar (:require seon.foo))`
  - `(in-ns 'seon.foo)` `(defn x [] ...)` — but in-ns isn't supported in bootstrap; agent uses `(ns seon.foo)` to switch.
  - Now foo/x's tx-id > bar's require tx-id. Replay order: foo-ns, bar-ns (re-requires foo, but foo/x doesn't exist yet at this replay moment), foo/x.
  - Is this a problem? Bar's require chain failing to find foo/x would only cause issues if bar's FORMS at require-time also try to use foo/x. The ns macro just sets up aliases; it doesn't call foo/x. So bar's load works even if foo/x doesn't exist yet at bar's load time.
  - When is the failure mode real? Only if a top-level form in seon.bar's source calls foo/x directly. Top-level fn refs are deferred (the fn body isn't executed at load time, just compiled). So this might be fine in practice.
- BUT: top-level `(def x foo/x)` — this DOES execute at load time. If foo/x isn't defined at replay-time-of-bar, this errors.
- Open question: does this edge case matter enough to warrant DAG ordering, or is tx-id ordering "close enough"?

### Q4. Which compiler-state to replay against?

- Replay needs a compiler-state with the SUBSTRATE analysis-cache already loaded (cljs.core, datahike-cljs, seon.db, seon.eval, etc.). Otherwise re-evaling agent code that calls `seon.db/transact!` fails because the analyzer doesn't know about it.
- Current `seon.repl/!compile-state` (defonce'd at boot) IS the substrate compile-state after `init-bootstrap!` ran. Good.
- But: should replay use a FRESH compile-state, or reuse the existing one?
  - Fresh: cleaner state, but loses any vars defined during boot (`setup-agent-ns!`'s home-ns priming).
  - Reuse: faster, but if a prior replay attempt left half-defined vars, those persist.
- Recommendation depends on the bootstrap flow. Need to verify how `setup-agent-ns!` interacts.

### Q5. Tx-meta on replay txes

- The v1.md sketch wraps replay in `(with-tx-context {:seon.db/origin :replay :seon.db/replay? true})`. But replay is RE-EVALING existing source, not transacting new data.
- The eval itself wouldn't normally transact anything (it's idempotent — `(defn foo ...)` just defines a var, no DB write). The only thing that COULD write is if the source had side effects (e.g. `(seon.db/transact! ...)` inside the def — bad agent practice but possible).
- Should replay block side-effect transacts? Or let them through with `:replay? true` tagging so the agent sees they happened?
- If the agent's defn includes a `(register-warning! ...)` call that transacts, that's "code that runs on every boot" — replay should let it run.
- If it includes `(transact! some-tx)` — same deal. The agent is responsible for idempotent boot code.

### Q6. Failure isolation

- Per v1.md §7.4: "If an eval throws during replay, record a new `:seon.eval` entry with `:ok? false` and `:replay? true` ... doesn't block resume."
- The fail-then-continue semantics are clear. Question: should the new eval entry attach to a `:seon.turn`? Today's eval-batch! requires turn-id. Replay isn't inside a turn — it runs before any turn opens.
- Options:
  - Create a synthetic "replay turn" entity per resume run, attach replay eval entries to it.
  - Allow `:seon.eval` entities without a parent turn (component-optional). Schema change.
  - Don't create eval entries at all on replay failure — just log to `:seon.log`.
- Recommendation depends on whether the agent NEEDS to see replay failures in its eval-history view. If yes, replay-turn approach. If just for diagnostics, log entry suffices.

### Q7. Interaction with persistent backend

- V0 conn is `:memory`. Every restart = fresh DB. Resume runs but finds zero entities → no-op.
- For resume to actually fire end-to-end:
  - Flip the `seon.client/open-agent-conn!` config from `{:store {:backend :memory ...}}` to `{:store {:backend :file :path "..."} ...}` (or LMDB equivalent).
  - Decide data-dir layout (per-agent? shared?).
  - Address single-writer constraint (one pod per data-dir or single-pod-per-agent-id assumption).
  - Handle initial-write vs replay-then-write — the schema needs to be re-transacted on connect if the store has data; datahike's behavior here matters.
- This is a SEPARATE Platform conversation but resume's value is gated on it. The research should note where the backend question intersects.

### Q8. What about `seon.schema` registrations?

- Agent code transacts `(schema/register! ::ticker :string)` — that updates the mutable Malli registry in `seon.schema`. The registration ITSELF is in-memory state, not in datahike.
- The `:seon.schema` entity captures the source string of the registration. Replay re-evals the source → registration re-runs → registry repopulates.
- But: `:seon.schema/key` is the keyword (identity attr). On replay, the registration form runs and adds back to the Malli registry.
- Order matters: if `:seon.fn/sym my/foo` has `:malli/schema` metadata referencing `:my/ticker` (a registered schema), that schema's registration must run BEFORE the fn's def re-eval (otherwise instrumentation throws on def).
- Tx-id order should handle this if the agent registered the schema before defining the fn. But Q3's edge cases apply.

## Recommended research approach

ONE deep-dive agent, full context. NOT parallel sub-queries (per CLAUDE.md "one agent, full context"). The questions above are interrelated — same body of source code answers most of them. Don't split into N agents.

Source code to survey (cited in questions above):

- `/Users/sean/src/seon/reference-code/datahike/` — tx-id semantics, history, query, retraction
- `/Users/sean/src/seon/reference-code/cljs.js/` — bootstrap eval surface
- `/Users/sean/src/seon/reference-code/cljs.analyzer/` — analyzer state internals
- `/Users/sean/src/seon/reference-code/cljs.compiler/` — emit + munge logic

Plus our own:

- `/Users/sean/src/seon/src/seon/eval.cljs` — current eval surface (`eval`, `raw-eval`, `eval-batch!`, `record-eval!`, `setup-agent-ns!`, `init-bootstrap!`, `load-all-analysis-caches!`)
- `/Users/sean/src/seon/src/seon/repl.cljs` — `!compile-state`, `ensure-bootstrap!`
- `/Users/sean/src/seon/src/seon/client.cljs` — `open-agent-conn!`, `start-agent!` (the entry point that needs the resume wiring)
- `/Users/sean/src/seon/docs/prds/agent-runtime/v1.md` §7.4 — the spec
- `/Users/sean/src/seon/docs/prds/agent-runtime/v1.md` §1, §2.2, §4 — context for what entities exist + how the eval pipeline writes them

## Deliverable from the research agent

File: `/Users/sean/src/seon/docs/prds/agent-runtime/research/resume-findings-<date>.md`

Frontmatter:
```yaml
---
type: research
status: active
tags: [research, pod, cljs, agent, datahike]
---
```

Sections:

1. TL;DR — 1-paragraph synthesis + 3 sentence-length recommendations (which approach to ship, what to verify on the live pod first, what to defer).
2. Findings keyed by Q1-Q8 above with `file:line` refs into the surveyed source code for every non-obvious claim.
3. **Recommended implementation sketch** — pseudocode for `seon.client/replay-program-graph!` (or whatever the right factoring is) that the next Platform agent can implement directly.
4. Risks + sequencing notes — what to ship first, what depends on the persistent backend conversation, what's testable against the current `:memory` backend (e.g. by writing entities then immediately replaying them in the same pod session).
5. Open questions the source code didn't fully answer — flag these so the next Platform agent knows what to probe with live MCP eval before committing.

## How to dispatch this research

The next Platform agent reads this file, copies the prompt block below into an Agent tool call (`subagent_type: general-purpose`, `run_in_background: true`), and continues with other work while it runs. Findings land in the file named in the deliverable section.

---

## The research prompt (copy verbatim into Agent tool call)

```
Research task — write findings to disk per CLAUDE.md research-agent
policy. The deliverable is a single file at
`/Users/sean/src/seon/docs/prds/agent-runtime/research/resume-findings-<date>.md`
with frontmatter, TL;DR, findings keyed by question, recommended
implementation sketch, risks, and open questions. Chat reply under
500 words summarizing the recommendation; full reasoning in the
file. Take your time — the user explicitly asked for depth over
speed; this is foundational substrate.

## Context

Project root: `/Users/sean/src/seon`. PRD root:
`docs/prds/agent-runtime/`. Branch: `feature/agent-runtime`.
Two-agent workflow (Platform + MVP).

The substrate has reached the point where v1's resume phase
(`v1.md §7.4`) needs to be implemented. The spec sketches a
straightforward walker that re-evals `:seon.ns` / `:seon.fn` /
`:seon.schema` entities in tx-id order, justified by "datahike
tx-ids are monotonic AND lookup-refs only resolve to already-
written entities, so tx-id order IS topological."

The Platform agent (me, in a prior session) was about to implement
this directly from the sketch. Sean paused and asked for a deep
research pass first — the sketch has hidden assumptions about
CLJS bootstrap analyzer state, namespace dependency ordering,
schema-registry timing, and datahike replay semantics that haven't
been validated against the actual implementations.

The full set of questions is in
`docs/prds/agent-runtime/research/resume-design-questions-2026-05-23.md`
under "Open questions (research agent fills in findings)" — Q1
through Q8. Read that file first; it has the precise questions +
file:line pointers into the source code to survey.

## Source code available

In-repo (read these as the authoritative spec for current behavior):
- `src/seon/eval.cljs`
- `src/seon/repl.cljs`
- `src/seon/client.cljs`
- `src/seon/db.cljs`
- `src/seon/parse.cljc`

Reference checkouts (these are the libraries we're embedded against;
treat as canonical for "how does CLJS bootstrap actually work" /
"how does datahike actually replay"):
- `/Users/sean/src/seon/reference-code/datahike/`
- `/Users/sean/src/seon/reference-code/cljs.js/`
- `/Users/sean/src/seon/reference-code/cljs.analyzer/`
- `/Users/sean/src/seon/reference-code/cljs.compiler/`

If reference checkouts are missing, look under `/Users/sean/src/`
for `datahike`, `clojurescript`, etc. — Sean has many libraries
checked out at top level.

## Constraints on the recommendation

- "Slow is fast" — don't propose a clever-but-fragile design. Verify
  every load-bearing claim against actual source.
- Per Sean's reliability directive: substrate fns should return
  data, not throw. Resume's failure modes should land as logged
  events / eval entries / tx-meta, never as uncaught exceptions
  that kill the pod.
- The implementation lives in `seon.client.cljs` (Platform's lane);
  it does NOT touch `seon.agent.cljs`, `seon.eval.cljs`'s eval-
  batch! body, or `seon.parse.cljc` (those are either MVP's lane
  or stable Platform code that shouldn't churn).
- The current `:memory` backend means resume can't be tested
  end-to-end against a real pod restart without ALSO flipping
  to an on-disk backend. The research should call out this gating
  dependency and propose a same-pod-session test pattern that
  exercises the walker without requiring backend changes.

## Deliverable

See "Deliverable from the research agent" in the design-questions
file. File format, sections, frontmatter all spelled out there.

Chat reply: ~300-500 words summarizing the recommendation. Full
reasoning in the file.
```

---

## Status of related work

- **What's shipped** (commits leading up to this pause):
  - `676baf0` parse-forms rewrite + corpus
  - `190de3b` MCP retry on status-only errors
  - `615a120` single-card ref dispatch (Item 1)
  - `5786247` eval-batch! refactor (Item 2 + with-tx-context + duration-ms)
- **What's queued behind this research**:
  - `seon.client/replay-program-graph!` implementation
  - The wiring into `start-agent!`
  - Any test scaffolding
- **What's also queued but independent** (the next Platform agent can pick these up while the research runs):
  - `bin/seon log-stream` SSE endpoint (per error-envelope research,
    `docs/prds/agent-runtime/research/error-envelope-and-log-stream-2026-05-23.md`)
  - The "cheap sequence-first" envelope items (`log/error-console!` →
    `log/error!` in serve + broadcast). Doesn't touch eval.cljs or
    agent.cljs.
- **Separate-but-related conversation**: persistent backend
  (`:memory` → on-disk). Sean owns this decision; resume's value is
  gated on it.
