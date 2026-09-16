---
type: issue
status: open
severity: blocker
tags: [issue, render, transcript, no-crash, class/total-render]
---

# The agent history render throws on a string where it casts an instant

## Observed live

Default cluster, 2026-09-16, pid 63433 (started 12:46Z, after the store reset),
through `mcp__seon__eval_clj` in `jvm` mode:

```clojure
(seon.render.transcript/render-ai
 {:seon.db/db @(seon.operator/connection "default")
  :seon.sci.eval/ctx nil :seon.sci.eval/time-limit-ms 1000
  :seon.config/on-core-error :record :seon.agent/id "root"
  :seon.sci.admit/caps (seon.config/result-caps (seon.config/defaults))})
```

throws

```
java.lang.ClassCastException: class java.lang.String cannot be cast to
class java.util.Date
  at seon.render.transcript$candidate_entity_ids$fn__421880 (transcript.clj:151)
```

`candidate-entity-ids` sorts message rows and evaluation rows together and
casts each row's third element with `(.getTime ^java.util.Date at)`
(`src/seon/render/transcript.clj:151-155`). One of those rows carries a string.
Message rows take `at` from `:db/txInstant` (`src/seon/render/transcript.clj:101`);
evaluation rows take it from `:seon.cluster.eval/at`, declared `:inst`
(`resources/seon/schemas/seon.cluster.eval.edn:202`).

## Why it is a blocker

This is the agent's own history render — the walk that produces its context —
and an outward render must be total (AGENTS.md §2.4: renders never throw and
never refuse an ordinary value). A type hint that assumes an instant is also a
pre-read of data the database already declares: the offending row either
violates the declared attribute (a writer defect) or the query returns a
different column than the sort expects (a render defect). Both answers are
worth having; the cast must not be the place either one surfaces.

## Not established

Which row and which attribute. The follow-up query was cut short: the
default cluster's io-prepl stopped serving sessions
(`Connection reset`, then `Cluster closed the io-prepl session.`) while the
JVM itself stayed alive. That degradation is reported separately to the
orchestrator and was not investigated from this thread, which is not permitted
to restart `default`.
