---
type: issue
status: resolved
severity: blocker
tags: [issue, skills, sci, program-graph, schema]
---

# Teach the durable SCI session image in the foundational data skills

## Problem

The three foundational data/schema skills still state that arbitrary evals and
scratch definitions remain only process/context-local and that receipts never
reconstruct code. That statement was overtaken by the August 1 live-context
and stateless-resume waves: terminal receipt transactions now exact-reconcile
`:seon.code.def` rows, persist faithful values or proven forms, record
unrestorable definitions explicitly, and cold cluster acquisition installs the
session image.

The distinction between contracted program rows and the session image remains
important, but the current wording erases the second mechanism completely.
An agent following it will design around lost definitions after restart or add
another replay/persistence path.

## Evidence

- `.agents/skills/data-oriented-clojure/SKILL.md:58-70`,
  `.agents/skills/data-modeling/SKILL.md:234-247`, and
  `.agents/skills/datahike/SKILL.md:197-210` repeat the same pre-session-image
  claim and stale `seon.sci.eval` line range.
- `src/seon/cluster/loop.cljc:340-430` derives exact session-image transaction
  data for faithful values, deterministic pure forms, and explicit
  `:seon.code.def/unrestorable` rows. Lines 1392-1405 put those changes beside
  the terminal receipt.
- `src/seon/sci/eval.clj:1130-1216` restores values/blobs and proven forms into
  one cold cluster ctx before returning it.
- `resources/seon/schema/program.edn:191-211` declares the durable
  `:seon.code.def` family. `test/seon/sci/session_image_test.clj:239-323`
  exercises cold restoration of large values, functions inside maps, metadata,
  source forms, blobs, and explicit unrestorable rows.
- Contracted agent-authored functions still publish `:seon.fn` program rows at
  the terminal seam; this issue is about the additional exact session-image
  facts, not permission to weaken function contracts.

The reader chain is unusually broad. Root `AGENTS.md:541,696,861-866` mandates
`data-oriented-clojure` before every Seon Clojure change and routes schema and
transaction work through `data-modeling` plus `datahike`. Their shared false
paragraph therefore poisons ordinary implementation, schema design, database
work, and tests through all three symlinked skill audiences.

No existing open issue records this documentation contradiction. The live-ctx
contract issue concerns enforcement behavior in source, while this note
concerns skills denying a now-built persistence contract.

## Owner

The source-indexing/runtime-publication sections of `data-oriented-clojure`,
`data-modeling`, and `datahike`, with the `repl` skill updated enough to make
the live cluster session and print/session evidence discoverable.

## Acceptance

- Each skill separates static build indexing, contracted program-row
  publication, the process-live cluster ctx, and durable `:seon.code.def`
  session-image facts without collapsing them into “process-local.”
- Cold restore rules distinguish faithful values, proven deterministic pure
  forms, and explicit unrestorable rows.
- The `repl` skill tells a probe author whether a form exercised raw io-prepl,
  the agent reply reader, or the agent's shared live SCI ctx and terminal
  session transaction.
- The three repeated paragraphs have one checked semantic source and cannot
  drift independently.

## Resolution

Resolved by `2ca66d484`, `677d67a30`, `d049ba5b1`, and `431f424bb`. The shared
semantic source is now
`.agents/skills/data-oriented-clojure/references/program-state.md`; the
data-oriented, data-modeling, and Datahike skills link to that one checked
four-boundary account, while the REPL skill distinguishes the reply reader,
agent turn in the live cluster ctx plus terminal transaction, io-prepl, and a
raw JVM REPL. The reference cites current indexing, terminal publication,
live-ctx, `:seon.code.def`, cold-acquisition, and recurring restoration-test
owners. All four affected skill packages pass skill validation.

The attempted session-image test gate was blocked outside this issue's owned
paths by concurrent protected-source breakage at
`src/seon/render/web.clj:282:10` (`No such namespace: transcript`). This repair
did not resume, message, or edit that lane; source and existing-test evidence
are recorded in the skill reference instead.
