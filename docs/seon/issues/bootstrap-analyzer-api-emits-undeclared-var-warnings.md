---
type: issue
status: open
severity: friction
tags: [issue, agent, cljs]
---

# Remove undeclared-var warnings from the self-host bootstrap build

## Problem

Every canonical reset/restart compiles the self-host bootstrap successfully but
emits three `:undeclared-var` warnings from `cljs/analyzer/api.cljc:227` for
`cljs.analyzer.api/clojure`, `cljs.analyzer.api/java`, and `cljs.core/class`.
The repeated noise prevents the build transcript from serving as a useful
zero-warning regression signal and may conceal a real analyzer/bootstrap
environment mismatch.

## Evidence

Both the destructive default reset and the following public restart on
2026-07-14 compiled 89 bootstrap files with exactly the same three warnings.
`shadow-cljs.edn` deliberately includes `cljs.analyzer.api` in the self-host
entries because agent and platform code inspect analyzer state. The subsequent
`bin/fix-bootstrap-macros` step reports no broken symbols, so macro repair does
not explain or eliminate these analyzer warnings.

The selected dependency is ClojureScript `1.12.145`, but the
`reference-code/clojurescript` checkout at `946d75f…` identifies itself as
`1.12.41` in `pom.xml`; the exact selected dependency source is not currently
mirrored. That ClojureScript checkout's
`cljs.env/with-compiler-env` macro expands to CLJ-only
`clojure.lang.Atom`, `IllegalArgumentException`, and `class` forms. The
unconditional `cljs.analyzer.api/resolve-extern` function invokes that macro,
so compiling the API into the self-host CLJS bootstrap analyzes those host
forms as unresolved CLJS symbols. Active Seon source does not call
`resolve-extern`, explaining why readiness and tests remain green, but the
compiled public function cannot be assumed valid merely because it is unused.

## Owner

The selected ClojureScript analyzer source and the one `:bootstrap` build in
`shadow-cljs.edn`, with `bin/fix-bootstrap-macros` only if executable evidence
shows the generated cache metadata is involved.

## Acceptance

- Mirror and read the exact selected ClojureScript `1.12.145` source, then
  reproduce the warning with the smallest bootstrap entry/source combination
  against the exact selected ClojureScript and Shadow `3.4.10` sources.
- Add the smallest executable `resolve-extern` self-host probe and establish
  whether every expanded host branch is reachable.
- Make the maintained ClojureScript macro/function expansion portable or guard
  the API at its real platform boundary; do not blanket-disable
  `:undeclared-var` warnings or patch generated bootstrap output.
- A clean bootstrap compile and complete pod gate pass with zero unexpected
  warnings and unchanged analyzer API behavior.
