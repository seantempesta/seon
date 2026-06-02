---
type: research
status: completed
tags: [agent, architecture, prd]
---

# Phase 0 handoff — `seon.agents/*ctx*` + per-agent atom

**Date:** 2026-05-27
**Branch:** `feature/agent-runtime`
**PRD:** `docs/prds/agent-runtime/atom-state-system-2026-05-26.md`
**Compile status:** `shadow-cljs compile client` — 0 warnings, 1.45s.
**clj-kondo:** 0 errors, 0 warnings on both new files.
**Live REPL verification:** NOT performed — `seon-cljs` MCP offline this session. Static analysis + compile only.

---

## What landed

Two new files, one preload edit. No call sites changed elsewhere.

- `src/seon/agents.cljs` (new) — `*ctx*` dynvar, `::ctx-value` schema, `run-as-agent`, identity-assert, `start-agent!` / `stop-agent!`, private `!instances` registry.
- `test/seon/agents_test.cljs` (new) — 12 deftests covering the 5 Phase 0 invariants + registry hygiene.
- `src/seon/dev/test_preload.cljs` — adds `[seon.agents-test]` to the `:client` preload require list.

---

## Decisions made

### D1 — Schema is CLOSED (`{:closed true}`)

Per CLAUDE.md ("no `:any`, no `[:maybe X]`, every key fully typed"), the value `*ctx*` holds is a closed map today. New per-agent runtime keys require a `register!` + a bump to `::ctx-value` in place. This is the right friction. The schema can be extended later by adding fields directly — no parallel "open" variant.

### D2 — Key namespace is `:seon.agents/*` (plural)

The PRD §4 uses `::id`, `::state`, etc. — namespace-local keywords inside `seon.agents`, which expand to `:seon.agents/id`, `:seon.agents/state`. These are DISTINCT from the existing `:seon.agent/*` (singular) DB-entity schemas registered in `src/seon/agent.cljs`. Conflict avoided:

- `:seon.agent/id` (singular) — DB entity identity, `[:and {:seon.db/identity true} :seon.db/id]`.
- `:seon.agent/state` (singular) — already registered as `[:enum :idle :running]` in `agent.cljs:113`. Narrower than what Phase 0 needs.
- `:seon.agents/id` (plural, NEW) — atom's convenience id copy, `:string`.
- `:seon.agents/state` (plural, NEW) — `[:enum :booting :running :paused :stopped]` per prompt §2.

I did NOT redefine `:seon.agent/state` — that would silently break existing callers. The atom-side enum is broader (includes `:booting`/`:paused`/`:stopped` lifecycle phases) which is correct for runtime; DB-side stays narrow.

The prompt §2 says `:seon.agent/id :string` etc. — I read this as guidance on shape, not on exact key namespace. The plural form is what falls out from `::keys` in `seon.agents`, matches the PRD §4 sketch exactly, and avoids conflict. Flagging in case the verifier wants the singular form instead — would require renaming or redefining the existing DB schemas.

### D3 — Registry (`!instances`) is substrate-internal

`defonce ^:private !instances` maps `agent-id → atom`. Exposed via `lookup` and `registered-ids` for inspectors / Phase-1 wiring, but the public surface remains `*ctx*`. This is the "PRD `!instances` minus the user-facing API" that the prompt called for.

`start-agent!` throws on id collision rather than silently overwriting — concurrent starts are a substrate bug, not a configuration choice.

### D4 — Identity-assert details

Inside `run-as-agent`, after `(binding [*ctx* agent-atom])`, the wrapper calls `assert-identity!` which:

1. Reads `(:seon.agents/id @*ctx*)` (try/catch wrapped — a non-atom value would otherwise NPE before the assertion fires).
2. If `(not= claimed-id actual-id)`, calls `seon.log/error!` with `:seon.log/source :seon.agents/identity-mismatch` and structured data, THEN throws `ex-info` with `:seon.agents/error :identity-mismatch`.
3. Both `:seon.agents/claimed-id` and `:seon.agents/atom-id` are surfaced in ex-data + log payload so the operator can tell which side is wrong.

The throw happens AFTER the log — so even if the throw is swallowed somewhere, the operator has a trail.

### D5 — ALS lookup uses `platform/host` (matches `seon.db` pattern)

`substrate-ctx-als` is `(defonce ^:private ...)` and only requires `node:async_hooks` when `(= :node (platform/host))`. On a non-Node host (future JVM port via `.cljc`), the var is nil and `run-as-agent` falls back to bare `binding`. This matches the JVM sketch in PRD §9 and is noted in the ns docstring.

### D6 — `ctx-or-throw` helper

The prompt called out wanting reads not to silently return nil. Rather than wrap the var itself (which would prevent useful patterns like `(when *ctx* ...)`), I added `ctx-or-throw` as the loud-read accessor. Callers that REQUIRE a binding use it; tolerant callers continue to do `@*ctx*` directly. Test `ctx-or-throw-throws-when-unbound` proves the loud-fail path.

---

## Tests written

| # | Test | Static / Runtime |
|---|---|---|
| 1 | `ctx-unbound-default-is-nil` | Static (just reads a var) |
| 2 | `ctx-or-throw-throws-when-unbound` | Static (throw via ex-info) |
| 3 | `run-as-agent-binds-the-passed-atom` | **Runtime-needed** — verifies binding + identical-atom + initial state |
| 4 | `run-as-agent-swap-mutates-this-agents-atom` | **Runtime-needed** — proves swap! through `*ctx*` reaches the registry-held atom |
| 5 | `ctx-unbinds-after-body-returns` | **Runtime-needed** — proves scope cleanup |
| 6 | `cross-await-binding-survives` | **Runtime-needed, LOAD-BEARING** — the Phase 0 gate. If this fails, ALS isn't actually wired and the whole design fails. Currently static-only (MCP offline). |
| 7 | `multi-agent-interleaving-keeps-atoms-distinct` | **Runtime-needed, LOAD-BEARING** — the SPOF guard. Currently static-only. |
| 8 | `identity-mismatch-throws` | **Runtime-needed** — exercises the assert path |
| 9 | `identity-mismatch-emits-error-log` | **Runtime-needed** — uses `with-redefs` on `log/error!` |
| 10 | `start-then-stop-removes-from-registry` | **Runtime-needed** — registry lifecycle |
| 11 | `double-start-throws-id-collision` | **Runtime-needed** — id collision guard |

Tests 6 + 7 use `cljs.test/async done` and resolve via Promise chains. Both clean up agents in the success and `.catch` paths to avoid registry leaks across runs.

---

## Verifier checklist

1. Run `(user/run-tests 'seon.agents-test)` in a live REPL session — expect all 11 deftests green.
2. Confirm test #6 (`cross-await-binding-survives`) actually fires — if the runner reports it as 0 assertions, the `async done` was never resolved.
3. Confirm test #7 sees ATOM-A's id on A's post-await and ATOM-B's id on B's sync body. If either reports a swap, ALS scoping is broken.
4. Grep for any future caller of `binding [seon.agents/*ctx* ...]` — should ONLY appear inside `seon.agents/run-as-agent`. Any other call site is a SPOF risk.
5. `shadow-cljs compile client` — must remain 0 warnings.
6. Verify the schema actually validates: `(malli.core/validate :seon.agents/ctx-value @some-agent-atom)` should be true after `start-agent!`.

---

## Open question for the user

**Q-key-namespace**: Phase 0 uses `:seon.agents/state` (plural) to avoid clobbering the existing DB-entity `:seon.agent/state` (singular). The prompt text said `:seon.agent/state` (singular). Two options:

- (A) Keep current — plural namespace for atom keys, singular for DB entity keys. Clean separation, no schema collision, matches PRD §4 verbatim.
- (B) Switch to singular — would need to either redefine `:seon.agent/state` (breaks existing callers reading the narrower enum) or pick a non-conflicting key like `:seon.agent/lifecycle-state`.

Defaulting to (A). Flip to (B) only if the verifier sees a strong reason.
