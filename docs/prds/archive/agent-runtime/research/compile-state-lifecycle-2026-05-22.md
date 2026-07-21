---
type: research
status: active
tags: [research, pod, cljs, agent]
---

# `!compile-state` lifecycle — actual shape vs spec framing

Triage notes for KI-2 + KI-5 in [[agent-repl-mvp-pre-2026-05-22#known-issues]].
Captured 2026-05-22 while implementing the fix.

## TL;DR

- **There are two independent `defonce !compile-state` atoms in the
  V0 pod**, not one racing pair: `seon.client/!compile-state`
  (`client.cljs:170`) and `seon.repl/!compile-state` (`repl.cljs:158`).
  Both call the same `seval/init-bootstrap!` factory but store the
  result in separate vars.
- The MVP-agent symptom in KI-5 is therefore **silent state
  divergence**, not "won't re-init because already populated."
  `start-agent!` primes the agent's home ns (`seon.agent.seon` —
  `!session-id`/`!current-ns`/`(result …)`) on `seon.client/!compile-state`
  via `setup-agent-ns!` at `client.cljs:352-355`. `dev-init!` builds
  a fresh state on `seon.repl/!compile-state` (`repl.cljs:165-168`)
  that has **none of those primings**. Subsequent
  `(seon.eval/eval @seon.repl/!compile-state ...)` calls see an
  un-primed compile-state.
- Platform spec already names the canonical store: `seon.repl/!compile-state`
  (`platform.md:64`, "Where state goes" table). `seon.client/!compile-state`
  is the duplicate to remove.
- KI-2's hot-reload-staleness story still applies — even with one
  atom, a `^:dev/before-load` on the ns that owns the atom needs to
  decide between caching across hot-reloads (faster iteration but
  may hold pre-fix state) and nil-ing on reload (always fresh, slower).
  A version-stamp on the atom value lets both win.

## Source-grounded evidence

### Two atoms exist

```
src/seon/repl.cljs:158:   (defonce !compile-state (atom nil))
src/seon/client.cljs:170: (defonce !compile-state (atom nil))

```

The `seon.repl/!compile-state` doc strings (`repl.cljs:14-50`)
describe it as the "iteration-surface" atom — what an MCP eval
caller reaches via `@seon.repl/!compile-state`. The `seon.client/!compile-state`
has no docstring; it's just `(defonce !compile-state (atom nil))`.

Both are written by independent code paths:

- `seon.repl/ensure-bootstrap!` (`repl.cljs:161-168`):

  ```clojure
  (defn ^:async ^:private ensure-bootstrap! []
    (or @!compile-state
        (let [state (await (seval/init-bootstrap!))]
          (reset! !compile-state state)
          state)))

  ```

- `seon.client/start-agent!` (`client.cljs:338-348`):

  ```clojure
  compile-state (or @!compile-state
                    (let [s (await (seval/init-bootstrap!))]
                      (reset! !compile-state s)
                      (render/use-compile-state! !compile-state)
                      s))

  ```

The `@!compile-state` inside each ns resolves to that ns's own var,
so neither path sees the other's atom.

### Setup-agent-ns! lands on whichever state is passed in

`seon.eval/setup-agent-ns!` (`eval.cljs:395-430`) takes
`compile-state` as a parameter and writes the agent's ns onto **that
specific state**. `start-agent!` passes `seon.client/!compile-state`'s
state at `client.cljs:352-355`:

```clojure
_ (await (seval/setup-agent-ns!
          compile-state         ;; ← from @seon.client/!compile-state
          agent/default-ns
          agent/default-id))

```

`dev-init!` does NOT call `setup-agent-ns!` — it stops at bootstrap.
The state on `seon.repl/!compile-state` therefore has cljs.core +
analyzer caches but no `seon.agent.seon` home ns.

### Render uses a third indirection

`seon.render/!compile-state-ref` (`render.cljs:80-88`) is YET ANOTHER
atom — actually an atom holding an atom-ref. `start-agent!` wires it
via `(render/use-compile-state! !compile-state)` at `client.cljs:347`.
Pointed at `seon.client/!compile-state`. So renderer resolution
also can't see anything that lived on `seon.repl/!compile-state`.

### Three atoms total counting WASM smoke

`src/seon/wasm_eval_smoke.cljs:45` declares its own
`(defonce !compile-state (atom nil))`. This one is appropriate as a
separate variable — it lives in a different runtime context (per
M2 findings, each wasmtime CLI invocation is a fresh component
instance, so the atom resets every call anyway). Leave it alone in
this fix.

## Why KI-5's spec framing reads wrong

[[agent-repl-mvp-pre-2026-05-22#known-issues]] §KI-5 says:

> The pod's `seon.client/start-agent!` runs at module load and uses
> its own init path. If an iteration session wants a clean
> `init-bootstrap!` (e.g. after editing eval.cljs), `dev-init!`
> won't run a fresh init because `!compile-state` is already
> populated by start-agent's path (see KI-2).

But `start-agent!` populates `seon.client/!compile-state`, and
`dev-init!` checks `seon.repl/!compile-state` — they're different
atoms. The actual failure mode is the OPPOSITE: `dev-init!` DOES
init fresh (because its atom IS nil), but its fresh state lacks
`setup-agent-ns!`'s primings. Agent code that references
`seon.agent.seon/!current-ns` from a `dev-init!`-rooted eval
returns "undeclared var" — not because the state is stale, but
because the agent ns was set up on a SIBLING state.

KI-2's spec framing is correct: a single defonce DOES hold
pre-fix state across hot-reloads of the init code. That's a
real KI-2-the-name-describes problem and still wants a fix.

## Fix design

### One atom, one init path

Make `seon.repl/!compile-state` canonical. Delete
`seon.client/!compile-state`. `start-agent!` calls
`seon.repl/ensure-bootstrap!`, then `setup-agent-ns!` on the
returned state — same state any subsequent `dev-init!` will hand
back. Renderer points at `seon.repl/!compile-state`. One atom; the
spec table already names it.

Why repl, not client: per `platform.md:64` ("Where state goes"
table) and the long docstring on `seon.repl` (`repl.cljs:13-50`),
the iteration surface owns this var. `seon.client` is the agent-
boot orchestrator; it should consume substrate state, not own it.

### Init-version stamp on the atom value

To address KI-2 (hot-reload staleness) without losing the cross-
reload cache benefit:

```clojure
;; in seon.eval:
(def ^:private init-version
  "Stamped at code-eval time. Hot-reload of this ns produces a new
   symbol; older atom values carry the old symbol and re-init."
  (gensym "init-v_"))

(defn ^:async init-bootstrap! []
  ;; ... existing body ...
  (let [state ...]
    (specify! state IInitVersion (-init-version [_] init-version))
    ;; OR — wrap state in a 2-tuple {:version sym :state state}
    state))

;; in seon.repl/ensure-bootstrap!:
(defn ^:async ^:private ensure-bootstrap! []
  (let [cached @!compile-state]
    (if (and cached (= (current-init-version cached)
                       seval/init-version))
      cached
      (let [state (await (seval/init-bootstrap!))]
        (reset! !compile-state state)
        state))))

```

Trade-off: a hot-reload of `seon.eval`'s `init-bootstrap!` body
(which is what we'd be reloading when iterating on the substrate
itself) bumps the version, so the next `ensure-bootstrap!` re-runs
init. A hot-reload of unrelated code keeps the version, atom stays
warm, agent state preserved.

### `setup-agent-ns!` becomes its own re-runnable verb

Currently `start-agent!` calls `setup-agent-ns!` only inside the
fresh-init branch. When we collapse to one atom, the cached path
also needs the agent ns. Either:

- Always call `setup-agent-ns!` after `ensure-bootstrap!`
  (idempotent already per docstring at `eval.cljs:396-411`); OR
- Stamp the state with a per-agent-ns flag and call
  `setup-agent-ns!` only when missing.

First option is simpler and `setup-agent-ns!`'s existing idempotence
makes it cheap to re-run.

## Implementation steps

1. Add `init-version` def + version-stamping wrap in `seon.eval`.
2. Rewrite `seon.repl/ensure-bootstrap!` to version-check.
3. Delete `seon.client/!compile-state`. Change `start-agent!` to
   call `seon.repl/ensure-bootstrap!` then `setup-agent-ns!`.
4. Update `render/use-compile-state!` call site at `client.cljs:347`
   to point at `seon.repl/!compile-state`.
5. Verify by `mcp__seon_cljs__eval` round-trip: `start-agent!` →
   `dev-init!` → `(seon.eval/eval @seon.repl/!compile-state
     "@seon.agent.seon/!current-ns")` returns the agent ns symbol.

## What this fix does NOT solve

- **KI-3 (4-deep error envelope)** — separate work, separate
  research note.
- **Bootstrap macros workaround** (`bin/fix-bootstrap-macros`) —
  defensive, unrelated.
- **KI-4 (shadow watcher fragility after restart cycles)** —
  upstream shadow-cljs runtime-tracker issue; not a substrate fix.

## Reference

- KI-2 / KI-5 framing: [[agent-repl-mvp-pre-2026-05-22#known-issues]]
- "Where state goes" rubric: [[../platform#where-state-goes-pick-the-narrowest-scope-that-fits]]
- Iteration surface intent: `src/seon/repl.cljs:13-50` docstring
- Three-atom inventory:
  - `seon.repl/!compile-state` (`repl.cljs:158`) — canonical, keep
  - `seon.client/!compile-state` (`client.cljs:170`) — duplicate, delete
  - `seon.wasm-eval-smoke/!compile-state` (`wasm_eval_smoke.cljs:45`)
    — separate runtime context, keep independent
