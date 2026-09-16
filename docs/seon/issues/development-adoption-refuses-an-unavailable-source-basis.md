---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, publication, provenance]
---

# Development adoption refuses an unavailable source basis

During S1 verification, publication completed but default's adoption refused
because its previously adopted source commit could not be read. The cause
of that missing commit has not been established.

The operator returned:

```clojure
{:seon.source/commit-id #uuid "6aaad49e-a55e-52ad-981d-4df826c4bb8f"
 :seon.error/kind :seon.cluster.source/refused
 :seon.cluster.source/refused :seon.cluster.source/source-absent
 :seon.cluster.source/rule :seon.cluster.source/source-absent}
```

The exception message was `the adopted source commit is unavailable`, from
`src/seon/cluster/source.clj`'s `refuse!`. The request was:

```sh
bin/seon init --dev default --changed src/seon/fn.clj --changed src/seon/program.cljc --changed src/seon/sci/eval.clj --changed test/seon/program_test.clj
```

Default remained PID 53320. Earlier attempts had reported source changes
during adoption; one exhausted the operator's 900000 ms lock-hold bound.
The S1 lane did not reset default, edit cluster custody code, or operate
another lane's session. It continued verification against the definitions
already loaded in the development JVM. The operator owner must resolve this
source-basis boundary before a converged adoption can be claimed.
