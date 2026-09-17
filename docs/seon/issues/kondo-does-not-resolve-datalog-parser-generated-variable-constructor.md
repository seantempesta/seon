---
type: issue
status: open
severity: friction
tags: [issue, clj-kondo, dependency, static-analysis]
---

# Kondo does not resolve datalog-parser's generated Variable constructor

2026-09-17 reset Tier 1 verification: standalone clj-kondo reports
`Unresolved var: parser.type/->Variable` at candidate `src/seon/db.clj:580` (the unchanged call is at HEAD line 579).
Repopulating with the production classpath using
`clj-kondo --lint "$(clojure -Spath)" --dependencies --skip-lint --copy-configs`
and re-linting preserves that finding. No other error is reported in the
integrator's five source/test paths.

The dependency defines `Variable` using its `deftrecord` macro at
`reference-code/datalog-parser/src/datalog/parser/type.cljc:40`; the macro
expands to `defrecord` at line 18. The constructor is real, and the canonical
fast JVM loads this namespace and arms its contracts. Do not rewrite the
correct constructor use to satisfy the analyzer.

Completion: teach the dependency analysis this macro's definition semantics
at its owning kondo configuration, then prove that a fresh-cache analysis
resolves the constructor and still rejects a nonexistent constructor.
