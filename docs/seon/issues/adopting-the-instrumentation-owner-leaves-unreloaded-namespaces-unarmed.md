---
type: issue
status: open
severity: high
created: 2026-09-23
tags: [issue, instrumentation, adoption, publication]
---

# Adopting the instrumentation owner leaves unreloaded namespaces unarmed

## Observation (lane wrapper-profiling, scratch root `tmp/wrapper-profiling/root`)

A development adoption through the hook path,
`(seon.cluster/refresh-source! root [my/note.clj seon.profile.edn instrument.clj cluster.clj instrument_test.clj] "wp" dir)`,
returned no error after 35,134 ms with `:seon.source/reloaded-namespaces`
`[my.note seon.profile seon.instrument seon.cluster seon.cluster.boot seon.artifact seon.eval.drive seon.bootstrap-drive]`
and 258 arming identities. Before it, `(count (seon.instrument/instrumented))`
was 1,800 = `(count (seon.instrument/armable (map ns-name (all-ns))))`. After it:
1,263 armed of 1,800 armable. Every armable Var of 22 namespaces that were
NOT in the reloaded list had lost its wrapper (plain fn, empty meta), whole
namespaces at a time: `seon.turn` 0/94, `seon.oversight` 0/17,
`seon.render.web` 0/37, `seon.issue` 0/28, `seon.test.runner` 0/41,
`seon.plan` 0/43, `seon.sci.eval` 1/59 (the one arming identity), etc.;
`seon.db` 95/95 and `seon.fn` 61/61 stayed armed.

A control adoption of `my/note.clj` alone on the same JVM (9,608 ms) kept
1,800/1,800 and replaced no watched original.

## Unknown

The cause is not identified. Candidates to check first: a reload the
adoption's reported list omits (the whole-namespace pattern suggests a
`require :reload`, for example `seon.schema/converged-predicate-var`'s
`(require namespace-name :reload)` with its `(catch Throwable _ nil)`,
`src/seon/schema.clj:220-224`), or the adoption reloading `seon.instrument`
itself. Record the originals' identity hashes before and after the same
adoption to decide reload versus unwrap.

## Wanted behavior

After any adoption, the armed set equals the armable set; a check that
compares them after adoption and refuses by name is the regression.
