---
type: issue
status: open
severity: blocker
created: 2026-09-18
tags: [issue, operator, dev-cache, adoption, reset-needed]
---

# The default JVM loaded dependency classes the current cache no longer selects

## Problem

`bin/seon init --dev default` now clears its preflight and refuses one phase
later:

```
● init phase=init elapsed-ms=2311 log=data/operator/operations/init-init-969.log
✗ init phase=init failed; The JVM loaded a different dependency cache;
  adding URLs cannot replace its classes.
{:seon.error/diagnostic-operation seon.test/test-loader,
 :seon.error/diagnostic-member :seon.dev-cache/digest,
 :seon.error/diagnostic-expected "25e1db910881ca5c7ca86d808c9e7239e537c0b01cc0585ca89e7ceb43b3bc46",
 :seon.error/diagnostic-offending "b87685a9ad5536c7456e62aa996cc99ced0fc0debf7b11499458b3979db52b0e",
 :seon.error/kind :seon.test/classpath-incompatible,
 :seon.fresh-operator/phase :init}
```

The refusal is honest and the check (`src/seon/test.clj:114`, `:1162`) predates
every uncommitted edit in the tree — it is present at HEAD.

## Evidence

- `target/dev-dependency-cache-current.edn` selects
  `25e1db910881…`, last written **Sep 17 14:56**.
- The `default` JVM (pid 80593) started **Sep 17 11:50:55** (`ps -o lstart=`),
  so it loaded the previous cache `b87685a9ad55…` and cannot be handed the
  new classes by adding URLs.
- Measured 2026-09-17 23:20: `curl -s -o /dev/null -w "%{time_total}
  %{size_download}" http://127.0.0.1:7994/ns/seon.id` → `3.865300 27533857`.
  The namespace-page fix in `src/seon/render/ns.clj` and
  `src/seon/render/walk.clj` is still unadopted because of this refusal, so
  the page remains 27.5 MB.

## What is needed

This is the ruled RESET NEEDED case: a lane never stops, reforks, or restarts
`default`. The orchestrator restarts the default cluster so its JVM loads the
currently selected dependency cache, then re-runs

```
bin/seon init --dev default --changed src/seon/render/ns.clj src/seon/render/walk.clj
```

and re-measures the page. The preflight that previously blocked this adoption
is fixed (`script/seon/fresh_operator.clj:244`,
`script/seon/dev/clj_kondo.clj:14`): the same command now reports
`changed-source boot lint checked: 19 files in 1593 ms; dependency cache
warmed in 9227 ms` and passes.
