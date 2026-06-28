---
type: issue
status: superseded
tags: [issue]
---

# Lint-hook clj-kondo OOM — shared 2 GB heap, not a separate cap

## Symptom

During an `Edit` to `src/seon/ai/deepseek.cljs`, the edit-time lint hook
failed once with:

```
clj-kondo failed: java.lang.OutOfMemoryError: Java heap space
```

A retry ~30 s later succeeded. System RAM is plentiful; this is a JVM
`-Xmx` / heap-ceiling problem, not OS memory.

## Root cause — in-JVM shared heap, chronically at the ceiling

The edit-time clj-kondo lint runs **in-process in the running seon JVM**,
sharing its heap with datahike, the corpus, Malli instrumentation, and any
concurrent agent analysis. It is NOT a separate clj-kondo process with its
own small cap, and it is not a slow leak — it is a too-small ceiling under
accumulated steady-state load.

Call path (every Edit/Write to a `.clj/.cljs/.cljc/.bb/.edn` file):

1. `bin/seon-hook` (thin Babashka script) sends the hook event over
   nREPL to the running seon JVM on port 7888
   (`process-via-nrepl!`, `bin/seon-hook:327-364`). `.cljs` is treated as a
   Clojure file, so it does NOT take the `should-skip-nrepl?` fast path.
2. The seon JVM runs `seon.dev.hook/process-hook-event!`. On `PreToolUse`
   it calls `seon.dev.lint/validate-for-write` →
   `validate-clojure` → `lint-source` (`src/seon/dev/lint.clj:262-310`).
3. `lint-source` calls `clj-kondo.core/run!` **in-process** — clj-kondo is a
   required library (`src/seon/dev/lint.clj:29`), not a shelled-out binary.
   Its working allocation comes from the seon JVM heap.
4. The OOM is caught by the `(catch Exception e ...)` at
   `src/seon/dev/lint.clj:302-309`, which returns the finding
   `"clj-kondo failed: <message>"` — exactly the observed text. Because it is
   caught, this particular event need not crash the JVM, which is why the
   retry succeeded after G1GC reclaimed transient garbage.

## Heap config

- Launch: `bin/run` → `exec clj -M:dev:run` → `:run` alias.
- `:run` alias `:jvm-opts` (`deps.edn:116-120`):
  `-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:TieredStopAtLevel=1`.
  The `:nrepl` alias (`deps.edn:255-256`) is identical at `-Xmx2g`.
- Live JVM confirms it: `RuntimeMXBean` input args =
  `[-XX:-OmitStackTraceInFastThrow -Xms512m -Xmx2g -XX:+UseG1GC
   -XX:MaxGCPauseMillis=200 -XX:TieredStopAtLevel=1 ...]`.
- **Max heap = 2048 MB.** No `-XX:+HeapDumpOnOutOfMemoryError`, no GC logging,
  no `-XX:+ExitOnOutOfMemoryError` on this JVM. (The legacy `:agent-jvm-pool`
  alias DOES set heap-dump + exit-on-OOM, `deps.edn:175-179` — the main dev
  JVM does not.)

## Current heap pressure (read live, read-only)

`java.lang.management.MemoryMXBean` heap usage on the running JVM:

```
max-mb 2048, total-committed-mb 2048, free-mb 1, used-mb 2046, heap-used-mb 2045
```

The heap is **99.9% full (2045 / 2048 MB), 1 MB free.** clj-kondo asking for
a few MB of working space at that point throws `OutOfMemoryError`. This is the
mechanism: a 2 GB ceiling that steady-state load (datahike + corpus + Malli
instrumentation + agent analysis) already nearly fills, so the lint allocation
tips it over. Falsifier: if free heap were comfortable, clj-kondo (which lints
a single in-memory string) would never OOM — it is cheap. The OOM is purely a
ceiling-vs-resident problem.

## Logging available + how to view it

- `logs/jvm.log` — full `OutOfMemoryError: Java heap space` stack traces
  (multiple). View:
  `grep -n "OutOfMemoryError\|Java heap space" logs/jvm.log`
- `logs/app.log` — two uncaught-OOM entries from `seon.core:47`
  (2026-05-22, 2026-06-03), each followed by a JVM shutdown/restart — prior
  hard crashes from the same ceiling. View:
  `grep -n "OutOfMemoryError\|Java heap space" logs/app.log`
- `logs/hook-debug.log` — every hook invocation. The deepseek.cljs sequence is
  recorded: `PreToolUse 11:43:01` (the failed lint), retry `PreToolUse 11:43:31`,
  `PostToolUse 11:43:48`. View:
  `grep deepseek logs/hook-debug.log | tail`
- `logs/error.log` — does NOT capture these (0 matches). Not useful here.
- A caught lint OOM surfaces to the agent as the lint finding text
  `clj-kondo failed: ...`; it is not separately persisted beyond the hook
  feedback.
- NOT enabled: heap dumps (no `*.hprof`), GC logs (no `-Xlog:gc`),
  exit-on-OOM. So a future caught-and-recovered OOM leaves no post-mortem
  artifact beyond the lint-finding text.

## Reproducibility

- Reproducible **in principle** and consistent with evidence: it occurs
  whenever resident heap is near the 2 GB ceiling at the moment clj-kondo
  needs working memory. Right now the live heap is at 2045/2048 MB, so another
  lint OOM is likely imminent on this JVM.
- NOT safely reproducible against the shared JVM without risking a real crash
  that would destabilize the concurrent (Track 2) work, so it was not forced.
- A standalone `clj-kondo --lint` of deepseek.cljs in a fresh small-heap
  process would NOT reproduce it — clj-kondo linting one file is a few-MB
  operation. That is the point: the file is not large; the resident heap is
  the variable. Reproduction requires the loaded, near-full JVM, which is
  precisely the condition to avoid forcing.

## Recommended fix

1. **Raise the dev JVM ceiling** in the `:run` and `:nrepl` aliases
   (`deps.edn:116-120`, `255-256`): `-Xmx2g` → `-Xmx4g` (or `-Xmx6g`). The
   machine has ample RAM; the cap is the only constraint. This is the
   one-line, highest-leverage fix and directly addresses the observed near-100%
   heap. Requires a JVM restart to take effect (coordinate — do not restart
   while Track 2 is mid-task).
2. **Add post-mortem instrumentation** to the same aliases:
   `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs/`
   and GC logging `-Xlog:gc*:file=logs/gc.log:time,uptime:filecount=5,filesize=20m`.
   Then the next OOM leaves a heap dump showing what is resident (datahike vs
   corpus vs agent analysis) — turning "probably the ceiling" into proof, and
   distinguishing a future genuine leak from steady-state pressure.
3. **Defense in depth — move heavy lint off the shared heap.** The deeper fix,
   consistent with the project pivot (offload heavy analysis off the dev JVM):
   run clj-kondo for the PreToolUse gate in a bounded child process (the system
   `clj-kondo` binary, already installed per `deps.edn:152`) instead of
   in-process `clj-kondo.core/run!`. That isolates lint memory from the
   datahike/corpus heap entirely, so a lint spike can never starve the live
   system, and a lint OOM is bounded to its own small process. Trade-off:
   ~native-binary startup latency per edit vs in-process call.

Recommended order: (1)+(2) immediately (cheap, restart-only), (3) as the
durable architectural fix.

## Smell flagged (separate issue)

`bin/seon-hook` session resolution (`get-port-for-session`, lines 121-136)
and the MCP `list_sessions`/`create_session` paths still call
`seon.orchestrator.session`, which no longer exists on the classpath
(`Could not locate seon/orchestrator/session`). That namespace was retired in
the reactive-agent topology rename. Harmless for the orchestrator port (the
code falls back to 7888), but `list_sessions` / `create_session` are broken and
isolated agent REPL sessions cannot be created via the MCP. Out of scope for
this OOM fix; flagging per the report-smells rule.

## Superseded (2026-06-28 audit)

Described the dev JVM -Xmx2g + in-process clj-kondo; the active hook path is the pod. Revisit if the JVM track resumes.
