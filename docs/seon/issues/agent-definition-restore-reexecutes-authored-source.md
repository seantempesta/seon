---
type: issue
status: open
severity: blocker
tags: [issue, architecture, agent, sci, database]
---

# Restore agent definitions without re-executing authored source

## Problem

The agent's definitions are durable facts, but two restore paths reconstruct
function roots by evaluating the original agent-authored form. A later turn or
a cold process can therefore run authored code again even though the form
already has a settled receipt. This violates the ruled append-only REPL-history
model: agent-authored forms execute once and later forks read their results.

## Evidence

- `src/seon/cluster/loop.clj:220-305` deliberately prefers
  `:seon.def/source` over a store-faithful value for a successful definition.
- `src/seon/sci/eval.clj:1421-1503` queries those desk rows for every turn and
  calls `sci/eval-form` when a row has source.
- `test/seon/sci/desk_test.clj:232-329` pins both behaviors: a fresh turn
  restores `(def helper (fn ...))` from source, and the restore ladder asserts
  that an ordinary definition retains source while dropping its admitted
  value.
- `src/seon/sci/eval.clj:1367-1400` also calls `sci/eval-form` to restore an
  agent-authored contracted function from its durable program row after cold
  acquisition.
- SCI's fork is already copy-on-write: it creates a new generation over the
  inherited namespace map, and a later root mutation copies the inherited Var
  before binding it (`reference-code/sci/src/sci/core.cljc:344-350`;
  `reference-code/sci/src/sci/impl/utils.cljc:356-379`). The missing mechanism
  is a durable, fact-safe Var-root representation and native installer, not
  another interpreter context.

The ruled design and proposed owner are recorded in
`docs/prds/sci-execution-runtime/research/env-once-execution-design-2026-08-11.md`.

## Owner

The one definition settlement and SCI namespace-root installation mechanism
shared by `seon.cluster.loop`, `seon.sci.eval`, and the maintained SCI fork.

## Acceptance

- A side-effecting or nondeterministic wrapper around a returned function is
  observed exactly once, at the original receipt, across later turns and a
  cold JVM restart.
- Fresh forks install faithful values, atom snapshots, and supported function
  roots from database facts without calling `sci/eval-form` on the authored
  source.
- Unsupported roots settle with one flat, explicit unrestorable reason; no
  source-replay fallback exists.
- One recurring restart regression covers both an agent's desk definition and
  an agent-authored contracted function.
