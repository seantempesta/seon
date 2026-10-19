---
type: issue
status: open
severity: defect
tags: [issue, sci, runtime, macro]
---

# A compiled program macro expands with main's values on a branch, and nothing names it

Program macros have no runtime row (`seon.sci.eval/runtime-rows!` excludes `:seon.fn/macro?`), so every runtime that
does not redefine the macro itself holds the compiled macro, whose body reads the JVM's Vars at expansion. An
analysis is keyed by the macro's digest (`::seon.sci.runtime/macro-digests`), never by what the macro reads.

Reproduced 2026-10-19 (lane runtime-rereview, [runtime-rereview](../../research/agent-platform/2026-10-19/runtime-rereview.md)
R3): `seon.profile/bucket-of` expands `~(dec buckets)`. A branch replacing `(def buckets … 40)` with 3 answers
`(seon.profile/bucket 5)` = 3, on the database path (`eval/base-ctx`, `bucket` analysed) and through MCP
(`(in-ns 'seon.profile) (def buckets "…" 3)`); a fresh interpretation answers 2. The host-bound report does not name
`bucket-of`.

Owner: `seon.sci.eval/bind-analysed!` and `runtime-rows!`. Proof to add: a row of
`seon.sci.eval-test/a-branch-changed-macro-is-expanded-by-its-callers` changing `seon.profile/buckets`.
