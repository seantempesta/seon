---
type: issue
status: resolved
severity: blocker
tags: [issue, cljs, agent, pod]
---

# Ordinary eval statement context dropped its result

## Evidence

On 2026-07-18, a real agent in the relocated source-free Bun package executed
`(+ 20 22)` successfully 25 times, but each committed eval rendered `nil`. The
agent consequently repeated the form until the run deadline instead of seeing
`42` and replying.

`seon.eval/raw-eval` passed ordinary forms to `cljs.js/eval-str` with
`:context :statement`. ClojureScript documents the callback value in that mode
as not meaningful; its compiler emits a returned value in `:expr` context.
Seon selected `:expr` only for a bare `result/<id>` read even though the same
source comment already recorded that def, ns, and defn forms work there.

## Owner and acceptance

`seon.eval/raw-eval` is the one self-host evaluator. It must compile every form
as a REPL expression and preserve its successful value through the Bun child,
the eval transaction, transcript rendering, and a completed real-agent reply.
A focused semantic test must fail when an ordinary scalar is reduced to nil.

## Resolution

Resolved by `35cd07ac`. `raw-eval` now uses ClojureScript `:expr` context for
every REPL form. The focused Bun test returned `42`, and relocated release
`114dad14…` committed and rendered `(+ 20 22) ⟹ 42` to a real agent.
