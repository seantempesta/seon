---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, sci, custody, isolation, program-installation]
---

# Agent SCI reaches the private generator context

`seon.sci.eval/generator-ctx` (`src/seon/sci/eval.clj:183`, `(defonce ^:private generator-ctx …)`) is a
private, non-function custody root: a whole SCI context. It is installed into the cluster's SCI context, so any
agent evaluation can take it with `@@#'seon.sci.eval/generator-ctx` and receive a raw `sci.impl.opts.Ctx`,
namespaces and all. The other custody roots (`seon.cluster/running-instances`, `root-store-holder`,
`seon.cluster.store/held-flocks`) stay unresolvable, as they must.

## Evidence (default 33ea69298, isolated branch, 2026-09-26 ~02:50Z)

`(sci/namespace-interns (support/fork-cluster-ctx c))` holds `seon.sci.eval/generator-ctx`. Evaluating
`"@@#'seon.sci.eval/generator-ctx"` through `seon.sci.eval/evaluate` returns
`#sci.impl.opts.Ctx{:bindings {}, :env #object[clojure.lang.Atom … {:namespaces {user … clojure.core …`.
`(type #'seon.sci.eval/generator-ctx)` is `sci.lang.Var`.

Red: `seon.custody-stability-test/non-function-private-custody-roots-and-operator-state-stay-unreachable`
(2 assertions). The test is right. It is the file's only red after its conversion to the current world, in a
diagnostic REPL run.

## Likely producer

Program installation now installs every top-level def as a declaration, private non-function defs included
(the P2-A "every top-level def is a declaration" work). Installation should not bind a private non-function
Var whose value is custody (a context, a connection, a store). The test already names the class: private
custody roots and operator state stay unreachable.
