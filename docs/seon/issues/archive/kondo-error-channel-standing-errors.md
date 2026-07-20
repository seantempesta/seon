---
type: issue
status: resolved
severity: friction
tags: [issue, flow]
---

# clj-kondo ERROR channel carried standing first-party errors

## Problem

`bin/lint` reported standing ERROR-level findings that buried real ones:
`src/seon/config.cljs:1378` used `clojure.string/lower-case` without a
`:require` (worked only because clojure.string was loaded elsewhere);
`src/seon/eval.cljs` required `seon.instrument` twice in one ns form (a
misindented merge remnant); and `with-authority`
(`test/seon/db/remote_contract_test.clj`) had no `:lint-as`, so its
binding produced a false unresolved-symbol that trained readers to ignore
the error channel.

## Fix

- `config.cljs`: added `[clojure.string :as str]` and switched the call to
  `str/lower-case`.
- `eval.cljs`: removed the duplicate `[seon.instrument :as instrument]`
  and re-indented the stray `[seon.error :as error]` line.
- `.clj-kondo/config.edn`: `:lint-as` maps `with-authority` to
  `clj-kondo.lint-as/def-catch-all` (it binds only the first element of a
  3-element vector; no builtin binding shape applies).

## Proof

`bin/lint --kondo src script test` now reports zero errors in
lane-owned files. Remaining errors are outside this unit:
`src/seon/instrument.cljc:14` (kondo reader-conditional splice
limitation, detector report item 11) and
`test/seon/error_record_test.cljs:235` (in-flight concurrent-lane edit).
Focused proofs: `bin/test-cljs --test=seon.config-test` 27 tests /
127 assertions green; `seon.eval.require-test` green.
