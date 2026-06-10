---
type: prd
status: draft
tags: [prd, agent]
---

# Context v3 — code-first (2026-06-10)

User direction (verbatim intent, 2026-06-10 evening): hide internals and
plumbing by default; show FULL CODE for all agent-relevant namespaces,
including the full datahike API (querying is core); **remove all the hacky
context that isn't really helping in favor of clear code with comments and
docs and normal ways of expressing ideas via Clojure code.**

## The rule (replaces tiers, sections-as-prose, and six filters)

A namespace is either **relevant → full source rendered in context** or
**internal → not rendered** (still indexed; one taught query away). One
config set, one full-index query, one classifier, one renderer. The
teaching itself becomes CODE: docstrings on the real fns + a worked-example
namespace, not handcrafted prompt prose.

## What renders (the relevant set)

| Source | chars | Notes |
|---|---:|---|
| `seon.db` (post-split API) | ~12k est | transact!/query/pull/entity/with-agent + envelope schemas |
| `seon.schema` (post-split API) | ~8k est | register! + the rules, as docstrings |
| datahike API surface | ~8–33k | from var metadata / `api.specification` — unit measures + trims |
| `seon.fs` | 18.1k | nodejs integration exemplar |
| `seon.search` + test | 26.6k | npm wrapper + the model test ns |
| `seon.todo` + test | 18.5k | store/retrieve arc + resume |
| `seon.repl` | 5.3k | |
| `seon.recipes` (new) | ~6k target | the capabilities teaching REWRITTEN as runnable, tested, commented code: consult-findings-first, register→transact→query, finding storage with provenance, reply discipline |

Dynamic derived blocks stay (they are data, not prose): domain attrs,
stored finding claims, open todos, warnings, transcript, prompt line.
Prose that survives: SOUL/system identity only.

## Units

- **V3-A — `seon.db` API split** (precedes everything): public surface
  stays in `seon.db`; DIS/wire/conn plumbing moves to `seon.db.internal`,
  which the public ns requires. **Convention (user, 2026-06-10): complex
  namespaces keep a clear public face + a `*.internal` sub-namespace for
  plumbing; `*.internal` is never rendered to agents — the ns name IS the
  filter.** Atomic refactor, suite + replica probes + live boot as oracles.
  `seon.schema` split follows once the S-21 lane frees the file.
- **V3-B — `seon.recipes`**: capabilities prose → runnable commented code
  with tests. Each recipe is a real fn/comment block an agent can copy AND
  execute. The capabilities section then renders this file instead of
  handcrafted text.
- **V3-C — context-model unification**: ONE full-index query → ONE
  classifier (relevant-set config + agent-authored detection derived at
  render time, no stamping) → dumb renderers. Deletes: internal-attr-ns?
  regex (after S-21's interim fix), substrate-ns-name? heuristic,
  per-section queries/gating, count lines, signature blocks.
- **V3-D — datahike API block**: render the query API from var metadata
  (docstrings live on the vars; code-as-data, no dep-file reads). Budget
  guard; trim to the querying surface (q, pull, entity, datoms, history…).
- **V3-E — delete the hacks**: remove superseded taught-prose sections;
  gym trio (S-01/S-32/S-21/S-12) re-run is the oracle for every unit; the
  agreement property test (all surfaces classify identically) lands here.

Budget: turn-0 ≈ 105–130k chars ≈ 27–33k tokens, byte-stable prefix.
User: token cost is acceptable; correctness of the lesson > size.
