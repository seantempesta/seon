---
type: issue
status: open
severity: friction
tags: [sci, contracts, auto-check, errors, live-test]
created: 2026-09-14
---

# The defn auto-check reports ITS OWN contract violation to the agent as if the agent's schema were wrong

## Observed (run 2, turn 14; the model's account in `research/explain_probe_turn40_2026_09_14.edn`)

After `(defn largest-customer {:malli/schema [:=> [:cat [:vector :example/order-row]] [:map …]]} …)`
the evaluation's `:out` read: "No example test gates
my.agents.juniper/largest-customer; add one to teach intended behavior.
Auto-check skipped: seon.sci.kernel/invoke violated its contract
(invalid-input): invalid type at [[:seon.sci.eval/args]]". The function was
installed and worked; the checker's own call into `seon.sci.kernel/invoke`
failed its input contract. The model read this as "my defn did NOT persist"
and spent roughly five turns re-verifying.

## Wanted

- A core contract violation inside the checker is a core fault (fault
  committer, provenance), never text in the agent's `:out`.
- When the auto-check genuinely finds a violation of the AGENT's schema, the
  message names the argument, the schema, and the generated value.
- The "no example test gates …" sentence is fine but should not precede a
  checker failure as if both were about the agent's code.
