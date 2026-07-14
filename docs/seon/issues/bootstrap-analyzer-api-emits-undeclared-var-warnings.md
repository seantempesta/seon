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

## Owner

The selected ClojureScript analyzer source and the one `:bootstrap` build in
`shadow-cljs.edn`, with `bin/fix-bootstrap-macros` only if executable evidence
shows the generated cache metadata is involved.

## Acceptance

- Reproduce the warning with the smallest bootstrap entry/source combination
  against the exact selected ClojureScript and Shadow sources.
- Establish whether the symbols are valid CLJ branches leaking into self-host
  analysis or genuinely unresolved runtime references.
- Fix or precisely suppress the owning upstream/build condition; do not blanket
  disable `:undeclared-var` warnings.
- A clean bootstrap compile and complete pod gate pass with zero unexpected
  warnings and unchanged analyzer API behavior.
