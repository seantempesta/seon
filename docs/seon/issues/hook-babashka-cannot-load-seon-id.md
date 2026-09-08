---
type: issue
status: resolved
severity: blocker
date: 2026-09-08
tags: [dev, tooling, runtime]
---

# Hook publication cannot load seon.id in Babashka

Observed by turn-cut on 2026-09-08 while deleting the retired curation
owner. Publication `66903633-5a9c-4364-b954-bd5e01982c12` refused before
adoption. `logs/current-source-failure.log` reports:

```text
java.io.FileNotFoundException
Could not locate seon/id.bb, seon/id.clj or seon/id.cljc on classpath.
Location: script/seon/dev/clj_kondo.clj:3:3
```

The hook helper requires `seon.id` at its namespace declaration. Its
Babashka execution must receive that dependency through the operator's
classpath, or use a dependency already available at that execution boundary.
The helper is concurrently edited by another lane; turn-cut preserves it
and gates its own paths in the isolated runner. This failure means source
edits have not been proven adopted by the default cluster.

Resolved by operator commit `81575eb5c`: `bin/seon` includes `src` alongside
`script` and `resources` in the Babashka classpath. Turn-cut subsequently ran
`bin/seon status` successfully through the operator entry point. Later
publication failures have different diagnostics and are not this classpath
failure.
