---
type: issue
status: resolved
severity: blocker
tags: [issue, cljs, flow]
---

# Test owner crash retained a Bun runner

## Problem

`bin/test-cljs` killed direct children from its exit trap, but an uncatchable
owner crash could not run that trap. Its Bun, `tee`, and output-filter pipeline
then became PID-1 children and continued retaining the complete test runtime.
The earlier changed-test descendant fix covered catchable owner shutdown; it did
not give the Bun test process a way to notice that the shell itself disappeared.

## Evidence

On 2026-07-18, PID `48176` (`bun out/test/test.js`) had PPID 1 and had survived
for more than an hour with 3.61 GB RSS. macOS physical-footprint evidence showed
690 MB current private physical memory and a 2.7 GB peak. Sibling PID-1 `tee`
and `awk` processes still held the abandoned pipeline. The Bun process was idle
in `kevent`, so this was retained memory and incomplete-run evidence rather than
an intended reusable test worker.

## Resolution

The one test runner now records the Bun PID explicitly instead of relying only
on a live parent-child scan. Output crosses one owned FIFO, so ordinary pass,
failure, TERM, and INT paths await the Bun process and complete output before
releasing the lock. The existing Shadow preload also monitors the owning shell
PID. If an uncatchable owner death reparents Bun, the child exits 143 itself and
closes the output pipeline. A generous 30-minute default deadline exits 124 when
an async suite never completes; callers may raise it through the existing test
environment without selecting another runner.

The abandoned PIDs were terminated normally. Focused proof passed one test / four
assertions. A real INT during Bun execution left no `bun out/test/test.js`,
`tee`, `awk`, FIFO, or lock. Supplying a nonexistent recorded owner made the
compiled test process exit 143 immediately. The complete ClojureScript suite
also passed 1,123 tests / 4,981 assertions through the new owned pipeline.
