---
type: issue
status: open
severity: defect
tags: [issue, render, repl]
---

# An elided quoted form inside a map prints text that does not read back as EDN

The value printer cuts the argument of a `(quote x)` form and writes the cut as the elision marker beside its
continuation form, inside the enclosing map. The two forms take one value position, so the map literal has an odd
number of forms and the reply no longer reads as data.

Observed 2026-10-20 (lane spec-reds, `bin/seon check` run 73365b6846f1 on HEAD cb9568dea plus the lane's test
probe): the MCP reply of `(my.task/current {})` for an agent whose done rule refuses a red test printed the
refusal's operation as

```
(my.test/check
 {:seon.test/identities
 '…1 (drop 1 (get-in (nth (seq (get-in result/e68ef1f5dd683 [... :seon.render/operations 0])) 1) [:seon.test/identities]))})
```

and `seon.test-support/reply` answered `:cause "Map literal must contain an even number of forms"`. The value is
`(my.test/check {:seon.test/identities '#{tq.shb/identity-test}})`: the quoted set was cut to `'…1` and its
continuation placed after it.

The same step read through `(seon.context/current-state)` printed `'…1` alone and read back. So whether the reply
reads depends on which cut the budget picks.

Owner: `seon.print` elision of a reader-macro form (`quote`). A cut inside a collection must leave one form per
position, e.g. the continuation replaces the cut element instead of following it. This red was what
`seon.context-test/broken-tests-show-with-their-failures` actually reported. The lane moved that test's
comparison onto the two producers' data, so this defect now needs its own printer row (a red proof in the value
printer's tests) when it is filed as a task.
