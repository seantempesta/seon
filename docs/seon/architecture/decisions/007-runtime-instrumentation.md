---
type: decision
status: implemented
date: 2026-02-20
tags: [decision, architecture, schema]
---

# ADR 007: Always-On Runtime Instrumentation

> **Revision 2026-07-05 (C44/C46 — the pod-era coverage contract).** The
> decision stands (always-on, default-validated), but the absolute wording
> below ("all fns", "no off switch") predates the CLJS pod and overclaims.
> The real semantics on the active track: instrumentation rides the
> **program graph** (`seon.instrument/instrument-from-db!` at boot /
> `start-agent!`, re-asserted after every hot reload via
> `seon.client/after-reload`; the eval-tee wraps agent fns inline).
> **Structural opt-out** (`async-unwrappable?`, computed — never a name
> list): `^:async` fns with non-simple shapes (`seon.db/transact!`,
> `seon.eval/eval`, `seon.client/mem-db`) register no wrapper — their own
> body validates and returns an error ENVELOPE (never-throw-into-the-loop).
> `*.internal` fns are deliberately unspecced. A `SEON_INSTRUMENT`
> kill-switch exists as an emergency bail-out only. Coverage is a
> **derived invariant**: the root agent view's `:instrumentation-gaps` section
> (`seon.instrument/coverage-gaps`) recomputes the census per render and
> surfaces any specced fn whose live var lost its wrapper. The Integrant
> component described below is the paused JVM track's machinery.

## Context

Seon is infrastructure for AI agents to write reliable software. Agents generate and modify code continuously, and the traditional safety net of "a developer eyeballs the diff" does not exist. When an agent writes a function that accepts a map but the caller passes a string, the bug needs to surface immediately -- not silently corrupt data or fail downstream with an unrelated error.

Malli schemas on public functions serve as contracts: they declare what goes in, what comes out, and what arities are valid. But schemas are inert metadata unless something enforces them. Without runtime instrumentation, a schema is documentation that may or may not reflect reality.

The question was whether to enforce these contracts at runtime, and if so, when and where.

## Decision

**All public functions with `:malli/schema` metadata are instrumented at runtime. Every call validates inputs, outputs, and arity. There is no off switch.**

Implementation details:

- **Integrant component** (`:seon.dev/instrumentation`) manages lifecycle. Survives `(user/reset)` via suspend/resume. Automatically re-instruments after code reload via `refresh!`.
- **Per-namespace error isolation.** Schema collection catches errors per namespace so one broken schema does not block instrumentation of all other namespaces.
- **Agent-friendly error messages.** The custom `agent-reporter` throws `ExceptionInfo` with structured diagnostics: which argument failed, the expanded schema, an example valid call (generated via `malli.generator`), and the function's docstring. Agents can read these errors and self-correct.
- **Introspection.** The `status` function queries Malli's internal registries to report how many functions are instrumented, how many have schemas, and how many namespaces have collection errors.

Every public function must follow map-in/map-out convention with a correct `:malli/schema`. Wrong schemas are runtime errors, caught on the first call.

## Alternatives Considered

### Dev-only instrumentation

Instrument in development, strip in production. Rejected because the distinction does not apply to Seon. Agents run in what would traditionally be called "production" -- they write code, transact data, and call functions in the live system. A bug that slips past dev-time checks causes real damage. The environment where agents operate is the environment that needs validation.

### Test-time-only validation

Validate schemas only when tests run. Rejected because tests do not cover all call patterns. An agent may call a function with arguments no test anticipated. Runtime instrumentation catches novel misuse at the point of call, not after the fact.

### Optional instrumentation (configurable per-function or per-environment)

Allow functions to opt out, or disable instrumentation via config. Rejected because the complexity of "sometimes validated" is worse than always-on overhead. When instrumentation is optional, every bug report requires asking "was validation on?" Consistency eliminates that question. If a function is too hot for validation, the answer is to optimize the schema check, not to skip it.

## Consequences

**Benefits:**

- Schema violations surface immediately with actionable error messages, not downstream as data corruption
- Agents can self-correct: the error message includes the expected schema, an example valid call, and the docstring
- Schemas stay honest -- a wrong schema breaks on the first call, so schemas track reality
- Map-in/map-out convention is enforced by practice, not just documentation

**Costs:**

- Every public function MUST have a correct `:malli/schema` -- wrong schemas are runtime errors, not warnings
- Performance overhead on every public function call (Malli validation is fast, typically under 1ms per call, acceptable for Seon's workload)
- Schema collection errors in one namespace can mask instrumentation gaps if not monitored (mitigated by the `status` introspection function and per-namespace error logging)

## Related

- [[components/dev-tools]]
- [[components/schema-system]]
