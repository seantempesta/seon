---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, program-graph, my-program, dogfood, contracts]
---

# `breaks` for a contract change plans a retraction, takes the contract as a string, and lists the subject as its own caller

Found by dogfood lane dogfood-maintainer-opus (MCP only, branch `dogfood-maintainer-opus-2`, default pid 19859).

## Observed

The agent wants to change `my.fs/write!` from `[request]` to `[request options]` and asks what breaks:

```clojure
(my.program/breaks {:seon.program/subject 'my.fs/write!
                    :seon.program/contract [:=> [:cat :my.fs/write-request :map]
                                            [:or :my.fs/write-result :my.fs/error]]})
;; => my.program/breaks refused argument 0 … at [:seon.program/contract]: expected a string (:string), got a vector …
```

With `(pr-str …)`, it answers:

- `:seon.program/plan` issues titled **"my.fs/write! must be repaired before retracting my.fs/write!"** and
  "seon.effect-test/effect-refs-its-evaluation-and-handler must be repaired before retracting my.fs/write!". Nothing
  is being retracted.
- `my.fs/write!` is listed as its own caller, because its body references `#'write!` for the effect request. It
  records no call arity.
- The call sites show `:call-arity 1` against `:declared-arities [{:min 1 :max 1}]`, which is the **current**
  arity. The proposed arity 2 appears nowhere, so the answer never says "these sites pass 1 argument; the
  proposed contract needs 2".
- A `:contract-shapes-unknown` gap says the referrers are "suspects". That is honest, but an arity mismatch is
  decidable from the recorded call arities.

## Which model is wrong

- The plan producer has one template, retraction, used for every `breaks` question.
- A contract is a native Malli value, and requiring its printed string breaks "Preserve native values".
- A Var self-reference (`#'write!`) is counted as a caller that must be repaired.

## Expected

- `breaks` accepts the contract as data.
- For a contract change, it compares each call site's recorded arity with the proposed arities and names the
  mismatched sites.
- Its plan says "adapt caller X to arity 2", not "before retracting".
- A self-reference is not a caller.

## Regression

A function with one caller at arity 1: `breaks` with a two-argument contract names that caller as an arity
mismatch, its plan text contains no "retract", and the subject is absent from `:seon.program/callers`.
