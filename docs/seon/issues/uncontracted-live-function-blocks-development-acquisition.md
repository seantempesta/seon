---
type: issue
status: open
severity: blocker
tags: [issue, sci, operator, wave/context-fixes]
---

# Development acquisition refuses an uncontracted live function

Observed on main-root `default`, 2026-09-08, during the turn-cut lane's
`bin/seon init --dev default --changed src/seon/cluster/loop.clj`.
Publication and development program reconciliation completed. Development
SCI acquisition then refused committed definitions at
`seon.sci.eval/install-row!`, called by acquisition at `eval.clj:1650`.
The refusal names `[:seon.fn/sym "my.agents.juniper/shared-hello"]`.

A subsequent live pull returned:

```clojure
{:seon.fn/sym "my.agents.juniper/shared-hello"
 :seon.fn/source "(defn shared-hello [] 42)"
 :seon.fn/ns {:seon.ns/name 'my.agents.juniper}}
```

The pull requested `:seon.fn/spec`; it was absent. The namespace ref itself
is valid. This row was not authored by turn-cut: that lane's submitted
function was named `shared-inc`, in a uniquely named probe agent namespace,
and its source carries a Malli contract. The owner subsequently identified
this as the orchestrator's probe row and reforked default to drop it.

The lane did not retract the row or alter another session. Its assignment
requires stopping when another workstream's live state blocks verification.
The acquisition refusal log was 4,511,770 bytes; the relevant boundary is
recorded here so diagnosis does not depend on that unbounded envelope.

Acceptance: resolve the live row through its owning workstream, then prove
development adoption completes and records convergence. Separately verify
that the program writer cannot admit a function without its required
contract through an ordinary agent installation.

The resumed turn-cut repair removes uncontracted program rows at the gate,
adds the source-derived REPL note, and supplies a real-proc regression.
Verification remains incomplete: the first gate was interrupted and the next
hit the runner's empty-array expansion before test startup. The first edit's
adoption converged; final post-probe convergence is still outstanding.
