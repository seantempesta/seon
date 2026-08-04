---
type: issue
status: resolved
severity: friction
tags: [issue, sci, repl, architecture]
---

# Make SCI `doc` print documentation without comment syntax

## Problem

The acquired program-graph `doc` macro models each docstring line as a
single-`;` source comment. Owner decision 11 requires strict REPL fidelity in
displayed forms: comments remain an input/source-writing convention, while
`doc` output is ordinary printed output followed by the form's actual value.

## Evidence

`program-doc-var` in `src/seon/sci/eval.clj:838-855` prefixes every acquired
docstring line with `"; "`. `test/seon/sci/eval_test.clj:703-738` freezes that
shape and then proves the comment lines disappear when the output is read as
forms. The superseding ruling is decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

## Owner

`seon.sci.eval` owns the acquired program-graph `doc` macro and its REPL output.

## Acceptance

An acquired `(doc qualified/function)` displays its ordinary documentation
without `;` prefixes, then exposes the macro's actual computed value exactly as
the session exposes any other form result. The recurring SCI eval and REPL
parity tests assert the plain output and contain no comment-as-result fixture.

## Resolution

`seon.sci.eval` now prints the acquired docstring with
`clojure.repl/print-doc` parity instead of source-comment syntax. The same face
appends resolved input and output schema keys and bounded forms for every
ordered arity, while functions without schema refs retain their existing
shape. The recurring structural test covers plain output, contracted and
uncontracted functions, multi-arity order, the compact standard error arm, and
a giant schema form through the ordinary print floor. The error shorthand is
selected from the acquired projection's core-admission fact; a non-core arm
keeps its resolved form.
