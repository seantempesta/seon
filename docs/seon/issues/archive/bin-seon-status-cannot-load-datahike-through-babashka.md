---
type: issue
status: resolved
severity: blocker
tags: [issue, tooling, operator]
---

# `bin/seon status` cannot load Datahike through Babashka

## Problem

`bin/seon status` fails while loading `seon.dev.cli` — its Babashka path
cannot load `datahike.api`. `bin/seon-fresh status` works and reports
correctly. Every agent (and every skill or instruction that teaches
`bin/seon status`) hits this on the first command it runs, so it reads as
"the system is broken" when the system is fine.

## Evidence

```text
$ bin/seon status
seon.dev.cli   - /Users/sean/src/seon/script/seon/dev/cli.clj:3:3
user           - NO_SOURCE_PATH:1:10

$ bin/seon-fresh status
default   61316 alive   62125 http://127.0.0.1:7994
1/1 clusters alive
```

Confirmed independently by the `skills-independent-verify` lane while
trying to follow skill guidance (2026-07-29 evening).

Related: [[babashka-default-classpath-exposes-src-old]] — the hygiene lane
deliberately did NOT switch `bb.edn` to fresh-only because that breaks
maintained hook/operator consumers. This is the other half of that
dependency: the operator's Babashka entry cannot see the JVM-only
Datahike coordinate.

## Owner

The `bin/seon` Babashka entry and `script/seon/dev/cli.clj`'s load
requirements — either the status path stops needing `datahike.api` (it is
reading advertisements and process facts, which need no database), or the
operator entry runs on the JVM classpath like `bin/seon-fresh`.

## Acceptance

`bin/seon status` reports the same as `bin/seon-fresh status` on a clean
checkout, and the two entries are reconciled to one (the standing
one-mechanism rule) rather than left as a working and a broken twin.

## Note for skills and instructions

Guidance may teach `bin/seon status` after `c073093e2`. Guidance that still
teaches old process verbs remains stale; see the resolution inventory below.

## Resolution 2026-07-29

Resolved by `c073093e2`. `bin/seon` now unconditionally invokes
`seon.fresh-operator`; `bin/seon-fresh` is a thin alias back to `bin/seon`, kept
only for muscle memory. The fresh operator's help names the one entry point.

Live proof used an isolated repository-local operator root and cluster
`operator-collapse-20260729`, never the owner's default cluster or PID 61316:

```text
$ bin/seon start operator-collapse-20260729
● operator-collapse-20260729 http://127.0.0.1:7836 prepl=54465

$ bin/seon status
operator-collapse-20260729 6284 alive 54465 http://127.0.0.1:7836
1/1 clusters alive

$ bin/seon logs operator-collapse-20260729
seon operator-collapse-20260729 ready — instrumented 359 vars

$ bin/seon stop operator-collapse-20260729
● operator-collapse-20260729 stop path=prepl
● empty JVM pid 6284 exited
```

The remaining dispatch arms were exercised without touching a user browser:
`config apply` with an empty sparse manifest converged with zero operations,
and `open` rejected a second positional argument through the fresh operator's
own `Use open [NAME]` validation.

The focused JVM gate passed 8 tests and 34 assertions:

```bash
bin/test seon.dev.fresh-operator-test seon.dev.mcp-bridge-test
```

### Old-operator consumer inventory

| Consumer | Judgment | Evidence and disposition |
|---|---|---|
| `bin/seon` lifecycle verbs | Fresh owner already covered them | `seon.fresh-operator/-main` implements `start`, `config`, `status`, `open`, `stop`, and `logs`; every verb now takes that path. |
| `bin/seon-fresh` | Duplicate implementation | Replaced with a compatibility alias to `bin/seon`; no second Babashka invocation remains. |
| `bb operator-test` | Dead and broken | The task named `seon.dev.test-runner`, which lives only under `test-old/` and was absent from the default classpath. Removed; the fresh JVM gate above owns these tests. |
| `bin/seon test changed` | Dead wrapper, not the edit hook | It existed only inside unreachable `seon.dev.cli`. The maintained hook calls `seon.dev.changed-test` directly from `bin/seon-hook:737-748`. |
| `bin/acme` delegation | Tabled downstream surface | It delegates to `bin/seon`, so it now sees only fresh verbs. The active roadmap explicitly tables ACME; no old operator route survives for it. |
| Release operator packaging | Old-system residue | `script/seon/dev/release.clj:975-985` still builds an uberjar whose main is `seon.dev.cli`; no live `bin/seon` verb reaches it. Delete it with the old release wave or replace it with the fresh release owner. |
| Old CLI tests | Tests pinning a deleted path | `test-old/seon/dev/cli_test.clj` directly requires `seon.dev.cli`. It is quarry coverage, not a surviving gate. |
| Writer and CLJS gates | Explicit quarry gates | `bin/test-writer:17-44` and `bin/test-cljs:273-382` still load old artifact/config tooling through `bb.edn`. Fresh correctness is owned by `bin/test`; retire these with their old runtimes. |
| Edit-hook linters | Genuine retained blocker | `bin/seon-hook:246-299` still loads quarry `seon.dev.markdown` and `seon.dev.docstring`; the real post-edit proof logged `MARKDOWN_LINT_ERROR` and `DOCSTRING_LINT_ERROR` after a mixed dependency-graph conflict. |
| Changed-test operator boundary | Genuine retained blocker | Generation 1175 selected `seon.dev.fresh-operator-test` and `seon.dev.mcp-bridge-test`, then failed because `script/seon/dev/changed_test.clj:483-486` launches the old Babashka test runner instead of `bin/test`. |

After the routing commit, a non-document `rg` finds no live caller of
`seon.dev.cli`: only its definition, old release packaging, and its old test
remain. The retained hook and changed-test seams, plus removal of the ambient
`src-old` Babashka path, are tracked together in
[[../finish-deleting-the-old-operator-classpath-from-retained-tooling]].
