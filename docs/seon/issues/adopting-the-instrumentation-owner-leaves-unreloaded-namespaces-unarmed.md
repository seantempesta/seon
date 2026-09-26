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

## One reloader proven (2026-09-26, lane fix-eval-schema-reds-opus)

`seon.schema/converged-predicate-var` reloaded any loaded namespace whose copy
lacked a declared predicate, with every error swallowed, whenever a compile
named one. Reproduction on default (pid 25654), with a scratch namespace
`fixevalreds.probe` loaded from a scratch classloader root: its Var root was
replaced by a marker (as arming does). Compiling
`[:fn fixevalreds.probe/absent?]` then refused anyway ("no admitted callable"),
and the Var afterwards held its source root, not the marker: the wrapper was
gone. At HEAD 052552039 (pid 19443), `seon.schema` had 0 of 103 contracted Vars
armed while every other src namespace was fully armed, and all 103 had profile
cells with calls, so they had been armed at boot. The function is deleted:
compilation refuses by name (`:seon.schema/predicate-namespace`) and never
reloads (landing fix-eval-schema-reds-2026-09-26). Whether it was also the
reloader in the 2026-09-23 observation above is not established. The
recommended regression below still stands.

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
