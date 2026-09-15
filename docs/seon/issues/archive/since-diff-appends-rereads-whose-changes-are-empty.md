---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, render]
created: 2026-09-15
---

# The since-diff appends re-reads whose change map is empty, and re-reads the agent's own erroring forms

## Evidence (live run 5, default, 2026-09-15 06:02–06:06Z; the model's account in `research/explain_probe_run5_2026_09_15.edn`)

After the agent saved a note, a system turn appended ";; changed since your
last turn" re-reads of `(help)`, `(dir my.agents.juniper)`, a `(seon.db/q …)`,
`(->> result/… (get-in []))`, `(doc my.plan/current!)`, and even the
agent's own erroring reply `Simplest:` — each with the response
`{:seon.repl/changes {}}`. The model: "It's unclear why forms I ran many
turns ago are being re-surfaced as changes … `{:seon.repl/changes {}}`,
i.e. no actual change. This is disorienting — it looks like activity but
carries none." Eight system turns in run 5 (run 3: 2).

## Problem

1. Read evidence is pattern-level: a write that touches an attribute a
   read's pattern covers (the note write, an adoption) marks the read
   stale even when its value is unchanged. The change-only rendering then
   computes an EMPTY diff and still appends an emission.
2. Agent-authored evaluations are promoted into generated reads when their
   evidence is turn-independent — including forms that ERRORED
   (`Simplest:` is an unresolved symbol) and one-off inspections
   (`(doc …)`, `(get-in result/… [])`).

## Owner

`seon.turn/system-turn` and its read-only classification; the existing
`seon.render.transcript/session-problems` check collection.

## Acceptance

- An empty change is not a change: when the re-evaluated shown value
  equals the previous one, nothing is appended (the evidence is refreshed
  silently). Regression: a write that touches a read's pattern without
  changing its value appends zero emissions.
- Only reads that succeeded are candidates for re-reading; an evaluation
  with `:error` is never a generated read. Regression with an unresolved
  symbol reply.
- Report the count of "stale-but-unchanged" reads as a problems-panel
  check so evidence coarseness stays visible.

## Implementation and measured proof — 2026-09-15

- `60e4e13bb`: equal shown values append no evaluation or system turn;
  the prior row's evidence and read basis refresh under the existing writer
  history check. Preview does not write. Combined fast and isolated gates:
  12 tests / 455 assertions each, zero failures or errors.
- `2795a3f3b`: failed evaluations are excluded before promotion. Real SCI
  regressions cover unresolved forms, a read followed by an exception,
  contract refusal, reader error, and documentation invalidation through
  program facts. Focused fast and isolated gates: 2 / 53 each.
- The problems panel counts retained empty-change emissions and historical
  read-basis refreshes after initial settlement. No new counter or kind is
  stored. Canonical rendered-panel coverage includes multiple silent
  refreshes per evaluation and mixed silent/changed reads.

Final combined fast and isolated gates each pass **14 tests / 504
assertions**; plain platform passes **84 / 505**. All have zero failures
and errors. The loop proof retains zero added system bytes across three
idle turns.

At default basis 536874142, stored run 5 contains four empty-change
evaluations within one six-evaluation system turn. **Zero whole system
turns would have been silent**: the other two evaluations in that turn
changed. The read-only default HTTP page later showed the new check at
17 (four historical empty emissions plus thirteen silent refreshes).

Full commands, exact row counts, reproduction and verification boundaries:
[rereads-2 landing](../../../prds/context-generation/research/rereads-2-landing-2026-09-15.md).
