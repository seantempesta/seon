---
type: issue
status: resolved
severity: blocker
tags: [sci, tests, program]
---

# Evaluated root transfer retained the previous test metadata

On 2026-09-17, the Stage 2 reuse regression redefined a passing SCI
`deftest` to assert false, committed its admitted source, and installed the
evaluated bindings. A fresh request executed the old passing body and the
following request reused that incorrect green result. Evidence:
`tmp/stage2-reuse-second-fast.log`, 58 tests, 428 assertions, 4 failures,
0 errors.

`reference-code/sci/src/sci/impl/namespaces.cljc:610` implements `sci-intern`:
the existing-Var path binds the root without adopting the symbol metadata.
`reference-code/sci/src/sci/impl/utils.cljc:362` copies an inherited Var
before binding and returns the destination Var. The test runner executes
the `:test` function in that Var's metadata.

`src/seon/sci/eval.clj`, `transfer-evaluated-roots!`, now installs the
evaluation's metadata on the returned destination Var, retaining its SCI
generation and namespace. This also transfers replacement contract facts;
it does not mutate the inherited Var or replay the source.

The recurring canonical regression is
`my.test-test/an-agents-own-test-reaches-its-cluster-through-the-elided-arity`:
changed source executes the new failing body, and repeated red requests
execute again. It uses real SCI evaluation, admission, installation and
the shared capture owner.
