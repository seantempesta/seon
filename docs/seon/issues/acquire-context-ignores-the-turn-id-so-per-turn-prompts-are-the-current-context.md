---
type: issue
status: open
severity: blocker
tags: [render, context, debug-page, faithfulness, turn]
created: 2026-09-14
---

# `acquire-context!` with an early turn id returns the CURRENT context, so "what the model saw at turn N" is not what it saw

## Observed (default, 2026-09-14, `research/explain_probe_2026_09_14.clj`)

`render/acquire-context!` called with `:seon.turn/id "404bfad994bc"` (turn 2
of run 2) and with `"a51f8821e5be"` (turn 40) both returned **178,089 bytes**
— the full 61-turn context — and the model answering the turn-2 probe wrote
"in the actual transcript, the agent burned ~20 turns", which it could not
have seen at turn 2. The attempt row for turn 2 records 3,222 prompt tokens;
the reconstruction is ~63,700.

## Why it matters

The debug page's "Context at turn N" (lane debug-turns) and the explain
probe both rely on this call to show exactly what the model saw at a turn.
Today it shows the present, labelled as the past. Faithfulness is the whole
point of that view.

## Wanted

- The context at turn N is the fold over the evaluations the model saw
  when turn N's attempt was made: evaluations of turns ≤ N (the attempt's
  own reply evaluations excluded), rendered from their stored shown text —
  or the same fold over `as-of` the attempt's basis. State which and assert
  it: the reconstruction's token estimate must be within the provider's
  reported `prompt_tokens` for that attempt (± the 64-token cache block and
  the operator question), for every provider turn of run 2.
- The debug page shows that number next to the reconstruction: "rebuilt
  3,190 tokens · provider billed 3,222".
