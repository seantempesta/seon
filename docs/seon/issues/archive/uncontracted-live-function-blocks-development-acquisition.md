---
type: issue
status: resolved
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

## Resolution (2026-09-15 triage)

surface: adoption-publication

Fix `e1c55abee` survives in the current owner. At HEAD `a5f3d7565`, `src/seon/turn.clj:3093–3098` admits the function installation gate only with a spec, and `:3166–3168` strips an uncontracted function's program row before settlement. `src/seon/repl.clj:92–113` supplies the not-installed explanation; `test/seon/cluster/agent_test.clj:361–399` checks an ordinary real-proc uncontracted definition never enters the program. Acquisition at `src/seon/sci/eval.clj:785–810` no longer unconditionally installs a missing contract. Verified these committed branches. The note already records the owner's removal of the old probe row by refork; no new adoption or default mutation was performed.
