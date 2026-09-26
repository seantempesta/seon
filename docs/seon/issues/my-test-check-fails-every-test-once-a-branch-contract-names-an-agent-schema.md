---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, test, schema, branch, dogfood]
---

# `my.test/check` fails every test on a branch once one of its contracts names a schema the agent registered

Found by dogfood lane dogfood-author-opus through MCP only. Reproduced on two JVMs: default pid 19859
(branch `dogfood-author-opus-1`) and pid 27723 on HEAD 6a8e46ec6 (branch `dogfood-author-opus-2`).

## Observed

On the branch, I registered `:notes-index/note` with `seon.schema/register!`. I defined functions whose
contracts name it, for example
`(defn tagged {:malli/schema [:=> [:cat …] [:vector :notes-index/note]]} …)`, and `deftest`s for them. Then:

```clojure
(my.test/check {:seon.test/changed '[notes-index/normalize-tags]})
;; Execution error (ExceptionInfo) at (REPL:1:9).
;; :malli.core/invalid-schema
```

It took 1.6–3.2 s, and once **17.3 s** (`seon.db/with-declarations` ×373,203, `seon.db/datoms` ×186,306,
`seon.fn/gate-set-in` ×87). The default JVM ran out of heap within a minute of the third call. Other test
requests were running at the same time, so I cannot attribute the OOM (see
[default-ran-out-of-heap-while-a-timed-out-test-member-stayed-live](default-ran-out-of-heap-while-a-timed-out-test-member-stayed-live.md)).

A control namespace on the same branch, `notes-probe`, has one function with the contract
`[:=> [:cat :int] :int]` and one test. `(my.test/check {:seon.test/changed '[notes-probe/twice]})` fails the
same way. **Every** check on the branch fails, not just the checks that touch the schema.

The first failed check recorded run `d671b118fc3c`. All four members show `began? false` and one error each.
The member report holds the cause that the REPL answer dropped:

```
Interrupted: request d671b118fc3c threw before this member finished.
clojure.lang.ExceptionInfo: ":malli.core/invalid-schema"
  :data {:schema :notes-index/note, :form :notes-index/note}
  at seon.sci.eval/install-function-contract! (eval.clj:1191)
  at seon.sci.eval/fork-cluster-ctx (eval.clj:3544)
  at seon.test/clean-request-handle/fn--306294 (test.clj:1881)
```

`(ex-data e)` caught at the REPL gives the same `:data`.

By contrast, the **install gate** runs the same tests correctly. A bad redefinition of `normalize-tags` on the
same branch was refused with `Gate: 2 tests · 1 passed · 1 failed`, and one of those tests reaches `add-note!`,
whose contract names `:notes-index/note`.

## Expected

`my.test/check` runs the reaching tests in forks of my commit. That commit holds my schema rows
(`(seon.db/q '[:find ?k :where [?e :seon.schema/key ?k] …])` lists them), so the fork's contracts compile.
A refusal names the schema, the function whose contract names it, and the projection it compiled against.
It is not a bare `:malli.core/invalid-schema` keyword.

## Model that is wrong

`fork-cluster-ctx`, used through `seon.test/clean-request-handle`, installs the context's function contracts
against a projection that does not include the branch's agent-registered schemas, probably the published or
loaded program's projection. The install gate uses the branch projection. There are two test-world
constructions with different projections, and one of them is wrong. The REPL error also prints only
`ex-message` and drops `:data`, which held the whole diagnosis (class: keep the whole cause).
