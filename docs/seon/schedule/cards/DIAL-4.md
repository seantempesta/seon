---
title: "DIAL-4: No evaluation time-limit fallback"
type: reference
status: waits on EN-4
created: 2026-10-08
---

# DIAL-4: No evaluation time-limit fallback

Ledger row DIAL-4 (group B). Source: `dials/dials.md` D10. Model: Sonnet. Host-bound (the one REPL path):
throwaway check.

## Sites (verified at `1fcdbf47a`)

- `seon.turn.world/agent-world`: `:seon.sci.eval/time-limit-ms (or limit 30000)` (`src/seon/turn/world.clj:60`).
- `src/seon/turn/batch.clj:794`, `:799`, `:926`: three `(or limit 30000)`.

The dial exists: `:seon.config.eval/time-limit-ms` (`resources/seon/schemas/seon.config.eval.edn`). The literal
silently replaces a missing limit, which CLAUDE.md forbids ("a missing required member refuses by name").

## Change

- Each entrant's world constructor receives the limit from its caller's effective configuration (the agent's
  acquisition reads `(config/effective db cluster)`; follow `rg -n "agent-world|jvm-world|test-agent-world" src`
  to the callers and pass `:seon.sci.eval/time-limit-ms` from the effective row they read, or read it there).
- `agent-world`'s `opts` contract makes `:seon.sci.eval/time-limit-ms` required (`[:seon.sci.eval/time-limit-ms [:int {:min 1}]]`);
  the `(or … 30000)`s go. `turn/batch.clj`'s three sites read the world's value; a world without one refuses at
  its constructor's contract, by name.

## Files

- `src/seon/turn/world.clj`, `src/seon/turn/batch.clj`, and the world constructors' callers the `rg` names.

## Proof

Existing `seon.turn-test`, `seon.repl-differential-test`, `seon.mcp-test` green; D: `rg -n "30000" src/seon/turn/world.clj src/seon/turn/batch.clj`
is empty. New `seon.turn-test/a-world-without-a-time-limit-refuses-by-name`: `(seon.turn.world/agent-world execution {})`
answers the contract refusal naming `:seon.sci.eval/time-limit-ms`.

`--test seon.turn-test --test seon.repl-differential-test --test seon.mcp-test`

## Cost

D: none.

## Commit

`bin/lane commit <lane> -m "DIAL-4: the evaluation time limit always comes from config; a world without one refuses by name" --test seon.turn-test --test seon.repl-differential-test --test seon.mcp-test src/seon/turn/world.clj src/seon/turn/batch.clj` (plus the callers changed)

## Dependencies

EN-4 (`turn/world.clj`), the RP rows rewriting `turn/batch.clj` (land with or after them). UL-10 also edits
`turn/world.clj`.
