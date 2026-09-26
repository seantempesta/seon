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

## Evidence 2026-09-26 (dogfood-repl-opus): `(do (in-ns 'x) (defn …))` is refused by the definition authority

Default archive 33ea69298, MCP SCI branch `dogfood-repl-opus-2`, one form, since the MCP tool description said
a branch run takes exactly one form (it takes several, see the dogfood report):

```clojure
(do (in-ns 'my.issue) (defn status "…" {:malli/schema …} [request] (seon.issue/status request)))
```

answered: `This form changed my.issue/status, which its analysis declares no definition for at the place it
ran: an eval, a macro expansion, … Define it with its own defn, or remove it first with a top-level ns-unmap.`
With `issue/status` (the alias `my.issue` has) instead of the qualified name, it answered `Cannot classify
host binding: clj-kondo holds no analysis of issue`. Analysis attributed the whole `do` to `user`. Top-level
`(in-ns 'my.issue)` followed by the `defn` in the same multi-form run works. Plain SCI accepts both shapes.

## Re-verified open 2026-09-26 (dogfood-repl-opus-2, cycle 10, archive 6ccf12a5a)

`(do (in-ns 'dogfood.c4b) (clojure.core/defn f1 [] 1))` → "This form changed dogfood.c4b/f1, which its analysis declares
no definition for at the place it ran: an eval, a macro expansion, … Define it with its own defn, or remove it first with a
top-level ns-unmap." The new message lists causes, none of which is the actual one (`in-ns` inside `do`).
