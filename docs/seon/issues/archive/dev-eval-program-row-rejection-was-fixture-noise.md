---
type: issue
status: resolved
severity: cleanup
tags: [issue, cljs, pod, database]
---

# Dev/MCP eval "program row rejected" was receipt-test fixture noise

## Problem (as reported)

The live-system detector audit
([[../../prds/source-cleanup/research/live-system-detectors-2026-07-20]],
needs-triage item 2) ranked "every dev/MCP eval's program-row persist is
rejected" as a live defect: 27x error-level
`[:seon.eval/record-eval] tx FAILED: program row rejected — source: (+ 1 2)`
lines in the retained log, the last five correlating 1:1 with the audit's
own MCP evals. The sci-execution-runtime U4 brief carried it as R2 with the
claim that the noise-routing fix `1846a3ff` "did not fix the rejection".

## Root cause (2026-07-20, U4 lane)

There was never a live rejection. The exact string "program row rejected"
exists nowhere in production source; it is the STUBBED transaction error in
`test/seon/eval/receipt_test.cljs`
(`failed-program-publication-does-not-commit-a-transcript`), whose fixture
source is literally `"(+ 1 2)"`. Every `bin/test-cljs` / edit-hook run of
that deftest wrote one line through `record-eval!`'s error branch into the
pod's shared `logs/pod-events.log` — the shared-file defect `1846a3ff`
fixed by making unconfigured processes console-only. The "1:1 with our MCP
evals" correlation was with the edit-hook test runs the audit's own edits
triggered, not with the evals: the dev/MCP funnel rides
`js/SHADOW_NODE_EVAL` (compiled-JS eval) and never calls
`seon.eval/record-eval!` at all, so it cannot produce that log line.

## Evidence

- All 35 fixture lines in `logs/pod-events.log` fall in
  2026-07-20T14:06–18:36Z; the noise-routing fix committed 18:47Z; zero
  lines after it (checked 23:27Z with live evals in between).
- `git log -S "program row rejected"` — the phrase appears only in the
  receipt test (as a stub), the log-noise regression test, and audit docs.
- Live falsifier, default cluster: a real agent eval turn records
  (`run-turn!` closed `:done`; the eval row reads back
  `["(+ 20 22)" true "42"]`). Legitimate eval history records.
- A defn evaluated through the dev/MCP funnel (`cljs.user`) yields no
  `:seon.fn` row — correct twice over: the funnel is not an agent eval
  path, and `cljs.user` is a transient scratch ns (C14, owner-ruled).

## Disposition

No production change required for R2 itself; the log-noise root cause was
already fixed in `1846a3ff` and regression-guarded by
`unconfigured-process-has-no-file-sink` in `test/seon/log_test.cljs`. The
detector research doc's item 2 should be read with this correction. The
genuinely missing recording — HOST-tier turns writing no eval/corpus rows —
was the real gap and is closed by U4
(`docs/prds/sci-execution-runtime/roadmap.md`, U4 section).
