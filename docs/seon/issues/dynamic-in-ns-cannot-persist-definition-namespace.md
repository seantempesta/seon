---
type: issue
status: open
severity: friction
tags: [issue, sci, runtime, class/p3, wave/per-run-fork-context]
---

# Admit definitions after dynamically hidden namespace movement

## Problem

A dynamically nested `in-ns` changes the shared live SCI namespace but does
not establish the namespace database row needed by the following definition's
terminal transaction.

## Evidence

Two runs began with:

```clojure
(do (in-ns 'streams.shared) (streams.control/await-two))
```

The receipt recorded ending namespace `streams.shared`, and the following
`def` evaluated there. Settlement then returned `:seon.db/rejected` with
`Nothing found for entity id [:seon.ns/name streams.shared]`. Repeating the
scenario with top-level `(in-ns 'streams.shared)` created/attributed the
namespace correctly and both definitions survived.

The namespace-semantics reports intentionally identified dynamic namespace
movement as outside their proof. This collision supplies the missing durable
repro; full facts are in
[concurrency streams crossed](../../prds/sci-execution-runtime/research/concurrency-streams-crossed-2026-08-04.md).

## Owner

The reader event and ending-namespace database transition. There must remain
one parsed namespace mechanism, not a special `in-ns` compatibility path.

## Acceptance

- A form whose actual ending namespace differs from its statically attributed
  namespace makes that namespace durable before a following definition needs
  its lookup ref.
- The next definition settles normally and is attributable to the run.
- A regression covers namespace movement hidden inside `do` without regex or
  source-text classification.
