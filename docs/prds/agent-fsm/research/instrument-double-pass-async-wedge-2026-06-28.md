---
type: research
status: active
tags: [research, agent, flow]
---

# P0 — agent creation wedges the pod (double-instrument mis-detects async fns)

> **Found by the Lane-U DeepSeek drive (2026-06-28), routed to CORE.** This is in
> the shared instrumentation + agent-lifecycle path (`seon.instrument` /
> `seon.agent` `start-agent!`), NOT Lane-U's web/ui. It almost certainly affects
> the DEFAULT pod too, not just acme — the code path is shared. Diagnosis is
> precise; this doc preserves it so the fix is a one-shot.

## TL;DR

Creating an agent (`POST /agents/new` → `start-agent!`) runs
`seon.instrument/instrument-from-db!` a **second** time over the already-
instrumented fns. On that second pass `async-fn?` inspects the live var's
constructor and sees the **first pass's `async-fschema` wrapper** (a plain
function, not an `AsyncFunction`), returns `false`, and re-routes every `^:async`
fn through malli's **sync** output validator — which then rejects the Promise
return (`:malli.core/invalid-output`, `got-type "Promise"`). After one create the
**ticker errors every 30s** and the **wake loop throws for every agent** → the pod
can't take a turn. `bin/acme restart pod` (one clean boot-time single-pass
instrument) heals it.

## Root cause (precise)

- Boot instruments 314 fns once — `^:async` fns get wrapped so the runtime routes
  them to an **await-then-validate** path (validate the RESOLVED value), per
  [[library-grounding]] "Instrumentation … `^:async` fns".
- `start-agent!` calls `instrument-from-db!` AGAIN (re-instrument on create).
- Second pass: `async-fn?` (`src/seon/instrument.cljc` ~290–313, `register-target!`
  / `async-fn?`) checks the **current** var value's JS constructor to decide
  async-ness. But the current value is now the first pass's `async-fschema`
  wrapper — a plain `function`, whose constructor is `Function`, not
  `AsyncFunction`. So `async-fn?` → `false`.
- The fn is then wrapped with malli's **sync** output validator. At call time the
  `^:async` fn still returns a `Promise`; the sync validator checks the Promise
  against the resolved-value schema → `:malli.core/invalid-output got-type
  "Promise"`. Confirmed on nullary (`seed-default-ctx!`) and 1-arg (`install!`)
  async fns → it is ALL async fns, not arity-specific.

## Evidence

- Pod healthy from boot (01:41) until the create (01:50); the wedge started
  immediately after `POST /agents/new`. Preserved log:
  `scratchpad/deepseek-drive-podlog-wedge.txt` (14 KB).
- The partially-created agent's side effects (`seed-default-ctx!`'s ctx-block
  writes) had ALREADY landed before its return was rejected, so on restart the
  agent **resumed armed** — which is how the drive proceeded (drive the resumed
  agent; never re-hit the broken create path). The agent's own inline `defn`
  evals during the drive did NOT re-trigger the wedge — only
  `start-agent!`/`POST /agents/new` does.

## Why the suite misses it (#49-class)

Tests run **uninstrumented** and **single-pass**, so the double-instrument async
mis-detection never fires under test. Same class as the prior `sample`-nil bug
(#49) that a green suite missed because tests don't run instrumented.

## Fix candidates (CORE's call)

1. **Don't re-instrument on agent start.** Instrument once at boot;
   `start-agent!` should NOT re-run `instrument-from-db!` over already-wrapped
   fns. (Simplest; matches "instrument once" intent.)
2. **Make `async-fn?` idempotent under re-instrument.** Detect the
   already-wrapped case — record async-ness at first wrap (a marker / a set of
   FQ async syms) and read THAT on later passes instead of the live constructor,
   so a second pass re-derives the same routing. (Robust to any re-instrument.)
3. Combination: re-instrument only NEW fns (diff against the already-instrumented
   set), never re-wrap an existing wrapper.

Grounding to read first: `reference-code/malli/` instrument + the seon bridge
`src/seon/instrument.cljc:109-161` (`collect-registrations`/`collect!`),
`async-fn?`/`register-target!` ~290–313.

## Two smaller smells (also → Core, lower priority)

- **`:seon.eval/agent = nil`** on the agent's eval rows — zeG's evals weren't
  agent-linked via that attr (captured by time window instead). `/clear`'s eval
  query relies on this attr, so it may not find an agent's evals.
- **`:seon.fn/name` used as a lookup ref without `:db/unique`** — surfaced to the
  agent as a datahike error ("Lookup ref attribute should be marked as
  :db/unique") during the build. Either mark it identity/unique or don't look it
  up by ref.
