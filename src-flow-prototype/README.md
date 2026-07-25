# src-flow-prototype

The standalone `seon.flow` prototype and its adversarial suite, built and
attacked on 2026-07-25. **Not wired into the runtime** — it runs against its own
Datahike store and its own SCI context, so no runner picks it up.

Findings: `docs/prds/sci-execution-runtime/research/flow-prototype-2026-07-25.md`.
Design under test: `../flow-design-2026-07-25.md` in the same directory —
**which contains three sentences the prototype falsified.** Read the findings
first.

## Why it is here and not in a scratchpad

Owner ruling 2026-07-25: never work in a session scratchpad or system temp
directory (`AGENTS.md`, *Operating the system*). This suite is the standing
regression for defects D1–D16; it was written in a directory that gets deleted
without warning, and it is checked in so it survives.

Precedent: `src-inspect-ai/` — run-again code gets a real top-level package,
never a PRD directory.

## Layout

- `src/flow/` — the prototype: `interrupt` (the one `:interrupt-fn`), `ctx`
  (base + fork), `eval` (bounded evaluation), `driver` (claim → step → commit),
  plus `store`, `program`, `crash`, `crashee`, `demo`.
- `attack-crash/` — kill harness. Spawns a **real second JVM** and
  `destroyForcibly`s it at each of six kill positions plus a double kill.
- `attack-resource/` — limit escapes: runaway loops, uncatchable-marker
  attempts, single-host-call allocation, stack overflow.
- `attack-concurrency/` — fork isolation, claim races, base-var mutation leaks.

## What it proved

- Form granularity is **forced**: the same form answered 0 against the turn's
  opening basis and 9 against the step's.
- Read-your-own-writes costs nothing — each step's basis is the previous
  commit's `:db-after`.
- Containment on the interpreted path: killed `:time` at 503ms /
  107,533,312 fn entries; killed `:memory` at 67,324,048 bytes (0.32% over).
- Crash resume correct at all six kill positions plus a double kill.
- Datahike durability: SIGKILL inside `d/transact` at 8 points, zero torn writes.
- Commit path is the cost centre: ~104 ms/step against 0–5 ms of eval.

## What it broke

Sixteen defects, several fatal — including `read-string` honouring
`*read-eval*` (a form wrote to disk before SCI ever saw it), sampling every
1024 entries failing to bound anything (9,825ms against a 500ms limit; 99 GB
against a 64 MB cap, outcome `:ok`), and receipts keyed by `(run, index)`
letting one step execute 704 times while the receipts read clean.

**Do not implement from the design. Implement from the defect ledger.**

## Status

Prototype, not a product. It has no Malli schemas, no `:seon/error`
discipline, and its `:agent/*` / `:run/*` / `:step/*` keys are **not** proposed
vocabulary. Its permanent home is an owner decision — this location follows the
`src-inspect-ai/` precedent but has not been ruled on.
