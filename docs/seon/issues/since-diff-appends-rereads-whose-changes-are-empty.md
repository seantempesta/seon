---
type: issue
status: open
severity: blocker
tags: [turn, since-diff, context, live-test]
created: 2026-09-15
---

# The since-diff appends re-reads whose change map is empty, and re-reads the agent's own erroring forms

## Observed (live run 5, default, 2026-09-15 06:02–06:06Z; the model's account in `research/explain_probe_run5_2026_09_15.edn`)

After the agent saved a note, a system turn appended ";; changed since your
last turn" re-reads of `(help)`, `(dir my.agents.juniper)`, a `(seon.db/q …)`,
`(->> result/… (get-in []))`, `(doc my.plan/current!)`, and even the
agent's own erroring reply `Simplest:` — each with the response
`{:seon.repl/changes {}}`. The model: "It's unclear why forms I ran many
turns ago are being re-surfaced as changes … `{:seon.repl/changes {}}`,
i.e. no actual change. This is disorienting — it looks like activity but
carries none." Eight system turns in run 5 (run 3: 2).

## Why

1. Read evidence is pattern-level: a write that touches an attribute a
   read's pattern covers (the note write, an adoption) marks the read
   stale even when its value is unchanged. The change-only rendering then
   computes an EMPTY diff and still appends an emission.
2. Agent-authored evaluations are promoted into generated reads when their
   evidence is turn-independent — including forms that ERRORED
   (`Simplest:` is an unresolved symbol) and one-off inspections
   (`(doc …)`, `(get-in result/… [])`).

## Wanted

- An empty change is not a change: when the re-evaluated shown value
  equals the previous one, nothing is appended (the evidence is refreshed
  silently). Regression: a write that touches a read's pattern without
  changing its value appends zero emissions.
- Only reads that succeeded are candidates for re-reading; an evaluation
  with `:error` is never a generated read. Regression with an unresolved
  symbol reply.
- Report the count of "stale-but-unchanged" reads as a problems-panel
  check so evidence coarseness stays visible.
