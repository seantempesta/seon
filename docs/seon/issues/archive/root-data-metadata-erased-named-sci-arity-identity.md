---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, repl, error]
---

# Preserve named SCI identity in arity failures

## Problem

W1 root-data metadata wraps an interpreted function through Clojure's
`AFunction.withMeta`. SCI's ordinary arity-message rewrite compares the thrown
function with the generated implementation by identity, so the wrapper made a
named authored function report its generated host class instead of its
namespace and name.

## Evidence

REPL parity row E6 failed after W1 while its adjacent anonymous-HOF case
remained correct. The throwable still carried a structured SCI callstack: the
final interpreted frame had `:sci/generation`, `:ns`, and `:name`; built-ins
and anonymous higher-order frames do not carry that complete identity.

## Owner

The terminal failure projection in `src/seon/sci/eval.clj`.

## Resolution

Commit `76713561e` walks the throwable cause for the `ArityException` and uses
the final structured interpreted frame to restore the named error message. It
does not parse exception text. The existing E6 assertion is the class
regression, E7 independently protects anonymous wording, and the integrated
function/REPL proof passed 92 tests / 281 assertions with no failures or
errors.
