---
title: "live run 6: five fresh render chains, default JVM ran out of heap 3 minutes after boot; no chain observed"
type: evidence
status: "run did not happen: default (pid 88281) died of java.lang.OutOfMemoryError at 09:14:32 UTC, 3 min 12 s after readiness; 0 of 5 chains observable; nothing merged as far as any log shows"
---

# Live run 6: five fresh chains, observer read-only, JVM dead

Lane live-run-6 (Sonnet), observer only, 0 hand acts (no SCI request, no `run!`, no message, no restart). HEAD
e7a5ac2f1. I could not take the measurements the assignment asks for: the live JVM was gone when I first looked
(09:14:41 UTC) and was never restarted during my 10-minute poll (to 09:24 UTC; the assignment allowed 45, I stopped
because nothing in the state can change without a restart, which is root's act).

## Broken things first

1. **Default died of `java.lang.OutOfMemoryError: Java heap space` at 09:14:32 UTC, 3 min 12 s after it published
   readiness.** Evidence: `data/clusters/default/logs/seon.log` tail: `seon default view` line 09:11:20.774, then
   `SEON MEMORY PRESSURE: This JVM is under memory pressure: new evaluations refuse until its tenured pool falls below the
   resume bound.`, `java.lang.OutOfMemoryError: Java heap space`, `Dumping heap to .../data/heap/java_pid88281.hprof`,
   `Heap dump file created [7667843383 bytes in 4.674 secs]`, `Terminating due to java.lang.OutOfMemoryError`. Operator:
   `operator-start-20261016T161124.931.edn` (pid 88281, start-instant 09:10:58.142), then
   `operator-status-20261016T161432.304.edn`: `bin/seon status` exit 5, "PREPL closed without a terminal result; outcome
   unknown", disposition `:client-failed`. MCP `eval_clj` answers "No live Seon JVM for cluster default". The store was
   written until 09:14:30 (51,262 files under `data/` newer than 09:11, last at 16:14:30 local), so the five chains
   were running and writing when it died.
2. **The heap dump is 7.67 GB against the 5 GB cap documented in the JVM flags (`-Xmx5g`).** It is the second such dump
   (`java_pid84529.hprof`, 4.37 GB, 2026-10-15 09:43). Both sit in `data/heap/`; this is the already-filed class in
   `docs/seon/issues/oom-heap-dumps-accumulate-in-the-data-footprint.md`,
   `default-ran-out-of-heap-while-a-timed-out-test-member-stayed-live.md` and
   `cohosted-clusters-share-one-unbounded-agent-heap.md`. I did not read them against this death or open the dump (7.6 GB);
   the cause of this OOM is therefore not known. Observed fact only: the process lived 3 min under five concurrent chain
   starts (five agent SCI contexts, five test-world overlays, each with the full 64/9/64/4/12-member runs run 5 saw).
3. **Nothing about the five chains' ends, calls, wall, members, merges or verdicts is available.** The captured heads
   in `operator-start-20261016T161124.931.edn` hold the old chains (`chain-fddd9dcbfe66`, `chain-ce9d664c1be0`,
   `chain-42e0e4c46b71`, `chain-91a8e6815ef2`, `chain-fd3ba8395135` and the four merged) and none of
   `chain-8e74dcd6c179`, `-0abc2294ecb9`, `-973b7482e3de`, `-858d9ed4db0e`, `-8bb19f9ee7a6`: they were created after boot, in
   memory and store files of the dead process. I did not open the store from a second JVM: it holds the store lock
   contract and that would be reading around root's restart, not observing.
4. **Nothing reruns by itself**, as designed: no new JVM, no operator start after 09:11:24 (latest
   `operator-*` file is the 09:14:32 status failure). Root must restart default and re-run.

## Per-chain table

| chain | task | end | calls | wall | members | test errors | done = merge | in-ns | verdict |
|---|---|---|---|---|---|---|---|---|---|
| `8e74dcd6c179` | `seon.background/render-ai-returns-data-fresh` | not observable (JVM died 09:14:32) | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| `0abc2294ecb9` | `seon.effect/render-ai-returns-data-fresh` | same | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| `973b7482e3de` | `seon.error/elision-prose-returns-data-fresh` | same | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| `858d9ed4db0e` | `seon.render.transcript/render-run-ai-returns-data-fresh` | same | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| `8bb19f9ee7a6` | `seon.repl/render-ai-returns-data-fresh` | same | n/a | n/a | n/a | n/a | n/a | n/a | n/a |

Unavailable evidence is not a pass: the in-ns share, red-reaching-test handling, trailers, `:stopped`, unmerged entries,
platform-JVM use and merge cost are all unmeasured. Whether 51af877a8's throw recording works and whether the
digest conversion removed run 5's `:request-threw` is also unknown.

## Cumulative tally of the nine render tasks

Unchanged from run 5: 4 merged (`dcc8`, `419a`, `8d9e`, `05db`), 5 open and held (`fddd`, `ce9d`, `42e0`, `91a8`,
`fd3b`), plus five fresh `-fresh` chains of run 6 with no recorded outcome (abandoned old chains: the five held ones, per
the assignment). Model calls 620 in runs 2 to 5; run 6 adds an unknown number.

## Post-run check

`bin/seon check --at HEAD --test seon.babashka-load-test/declared-namespaces-load-in-babashka --include-long`: run
`4c02b1c38856`, executed 1, pass 3, fail 0, error 0, 957 ms, `passed? true`, total 58.6 s (boot ready 4.4 s). There are
no merged proofs of this run to check. (This check booted a throwaway JVM from the archive; default stayed dead.)

## Defects and wasted-turn classes

- **Default OOM within minutes of starting five chains** (Broken 1 and 2): cost to this run, all of it. Reproduce: start
  default at e7a5ac2f1 and start the five `-fresh` chains; read `seon.log` tail. Needs the dump or a heap histogram taken
  by root before the next start to name the retainer; the dump is `data/heap/java_pid88281.hprof`.
- **Memory-pressure refusal is not delivered**: the log line "new evaluations refuse until its tenured pool falls below
  the resume bound" appeared once and the JVM died; no error occurrence, fault or problem task could be read afterwards
  by me (no JVM). Whether any was recorded before death is unknown.
- Wasted-turn classes: not rankable (0 forms observed); run 4's ranking (reading sources 53%, hunting how to run a test,
  `in-ns` 15%, `step-verdict` polling, wrong API shape) stands untested against the one-member-list change.

## Top three fixes the evidence supports

1. **Find what holds the heap and bound it before any further live run.** Open or histogram `java_pid88281.hprof` (and
   compare `java_pid84529.hprof`); the per-agent state, test-world overlay and run-member retention of five concurrent chains
   is the first suspect, since run 5's five chains on the previous JVM (pid 80515) survived their 3 to 9 s stalls.
   Proof: five concurrent chain starts keep the tenured pool under the resume bound for the length of a run.
2. **Make memory pressure a delivered error that stops admission before the process dies**, with the bound named: the
   refusal already prints; it must also be recorded where root and the problem task can read it, and the OOM dump must
   not exceed the declared cap (7.67 GB against 5 GB).
3. **Restart default and run the five chains again, one or two at a time first**, with root watching `runtime_status`
   heap; run 6's intended measurements (done = merge member lists, no `:request-threw`, recorded test errors,
   `in-ns` share against 10 to 15%) are still outstanding.
