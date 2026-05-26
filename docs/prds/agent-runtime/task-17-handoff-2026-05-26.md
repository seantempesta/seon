---
type: prd
status: completed
tags: [prd, agent, log, pod, cljs]
---

# task-17 handoff — seon.log: file logging + ring buffer + tail

**Date:** 2026-05-26
**Branch:** `feature/agent-runtime`
**Scope:** Replace DB-backed `:seon.log/entry` rows with a proper file/ring stack. Agent-readable file, in-memory `tail` for the renderer, no DB pressure.

## Design decisions

1. **Two files, two purposes.**
   - `logs/pod.log` (supervisor-captured stdout/stderr) — keeps the existing human-readable structured-line format from `console!`. No format change.
   - `logs/pod-events.log` (new, structured) — NDJSON-EDN, one `pr-str`'d entry map per line. Grep-friendly (`grep ':seon.log/level :error' logs/pod-events.log`), readable by `cljs.reader/read-string`.

2. **Single shared file, not per-agent.**
   Per-agent files would mean N handles + N rotation states + a multi-file merge inside `tail`. The `:seon.log/agent` field is filtered at read time. Simpler, and consumes the same wallclock at typical agent counts (N ≤ 20).

3. **Ring buffer 1000 entries, FIFO.**
   ~250KB at typical entry size. Newest entries at the end of the vector (`conj`); `tail` reverses on read so callers always get newest-first. Cap configurable via `(log/configure! {:seon.log/ring-cap N})`.

4. **Internal size-based rotation, 5 MB cap, last 3 kept.**
   Triggered on append. `pod-events.log` → `.log.1` → `.log.2` → `.log.3`; the 4th gets unlinked. No time-based rotation, no compression. ~30 LOC.

5. **`tail` reads ring only.**
   File lookback is out of scope — agents read `logs/pod-events.log` via `seon.fs/read-file` if they need it. Keeps the `tail` happy-path sub-millisecond and avoids parsing entire log files on every render.

6. **`seon.log` writes via `node:fs` directly, bypassing `seon.fs` policy.**
   Logging is infrastructure; gating it on the fs allowlist would couple two concerns. Agents reading `logs/pod-events.log` through `seon.fs` requires the consumer to add `logs/` to allowed-roots — documented in the ns docstring. No default change to `seon.fs`.

7. **Schemas retained for entry shape validation; DB attrs dropped.**
   `:seon.log/{at,level,source,agent,message,stack,data}` stay registered (useful for `:malli/schema` on `error!`). `:seon.log/entry` is the map schema. `:seon.log/dismissed-at` removed (no dismiss flow — entries are ephemeral now). Removed from `seon.client/agent-bootstrap-attrs` (no longer DB attrs).

## File layout chosen

```
logs/
├── pod.log              ← raw stdout/stderr (supervisor managed, unchanged)
├── pod-events.log       ← structured NDJSON-EDN (this task) — one entry/line
├── pod-events.log.1     ← rotated, most recent
├── pod-events.log.2
└── pod-events.log.3
```

Sample line:

```edn
{:seon.log/source :cljs.user/probe, :seon.log/message "test 1", :seon.log/at #inst "2026-05-26T08:31:15.956-00:00", :seon.log/level :error}
```

## REPL verification (all passing)

1. **Basic tail with filters** (3 entries: error, info, error+agent):
   ```clojure
   {:total 3, :only-error 2, :only-a1 1, :newest "test 3"}
   ```

2. **NDJSON-EDN file parses cleanly**:
   ```clojure
   {:line-count 3,
    :first-line-parses {:seon.log/source :cljs.user/probe,
                        :seon.log/message "test 1",
                        :seon.log/at #inst "2026-05-26T08:31:15.956-00:00",
                        :seon.log/level :error}}
   ```

3. **Ring cap (write 1500 with cap=1000)**:
   ```clojure
   {:count 1000, :newest "burst 1499"}
   ```

4. **Rotation (file-cap=512, keep=3, write 12 large entries)**:
   ```clojure
   ("rot.log" "rot.log.1" "rot.log.2" "rot.log.3")
   ```

5. **Self-tests** (`seon.test.runner-test` + `seon.test.fixture-support-test` + `seon.test.async-fixture-test`):
   `Ran 9 tests containing 33 assertions. 0 failures, 0 errors.`

6. **`seon.db-test`** (sanity check, post-refactor):
   `Ran 29 tests containing 220 assertions. 0 failures, 0 errors.`

7. **New `seon.log-test`** (this task):
   `Ran 8 tests containing 22 assertions. 0 failures, 0 errors.`

8. **`seon.render-test`** (uses the new ring-based `recent-errors`):
   `Ran 15 tests containing 29 assertions. 0 failures, 0 errors.`

Pre-existing failures observed in `seon.boot.preconditions-test` (busy-loop deadline pattern doesn't await async open-conn in CLJS). Not caused by this task; standalone reproduction confirmed.

## Files changed

| File | Change |
|------|--------|
| `src/seon/log.cljs` | Rewritten. Kept `console!` helpers. New: `tail`, `configure!`, `clear-ring!`, ring buffer, NDJSON file writer, size-based rotation. Dropped `db/transact!` path entirely. |
| `src/seon/web/broadcast.cljs` | Removed `log-only-tx?` skip (no longer needed — log no longer fires DB txes, so no self-trigger loop). Docstring + comments updated. `log/error!` call site unchanged at the API level. |
| `src/seon/render/default.cljs` | `recent-errors` now calls `seon.log/tail`. Added `[seon.log :as log]` require. Dropped the `:data-on-click__post "/log/dismiss?id=..."` button (no DB id to dismiss against). Comment updated on `recent-errors-block`. |
| `src/seon/client.cljs` | `log-replay-failure!` switched from `db/transact!` to `log/warn!`. Removed `:seon.log/*` keys from `agent-bootstrap-attrs` (no longer DB-persisted). |
| `src/seon/dev/test_preload.cljs` | Added `[seon.log-test]` so the runner can discover it. |
| `test/seon/log_test.cljs` | New. 8 tests / 22 assertions: ring cap, tail filters (level/agent/source), NDJSON file write, rotation trigger, soft-fail. |

## spec-05 references

No `spec-05*.md` file exists in `docs/` — the "spec-05 §15.4a" phrasing in the prior `seon.log` docstring + in `seon.web.broadcast` was folklore carried over from earlier specs. Cleaned up both references in passing (`seon.web.broadcast` ns docstring now points generically at `docs/prds/agent-runtime/`). No external doc needed updating.

## Callers audited

- `seon.web.broadcast/render-agent!` — `log/error!` site, still works (signature unchanged).
- `seon.client/log-replay-failure!` — was a raw `db/transact!`, migrated to `log/warn!`.
- `seon.render.default/recent-errors` — was a Datalog query, now `log/tail`.
- `seon.render.default/view` — was rendering `:db/id` for dismiss button; removed (no stable id on ring entries).
- All other `log/info-console!`/`log/warn-console!`/`log/error-console!` callers untouched — they always wrote to stdout/stderr only.

## Not done / deferred

- File-source `tail` (`{::source :file}`) — agents can `(seon.fs/read-file {:seon.fs/path "logs/pod-events.log"})` and parse line-by-line themselves if they need deeper-than-ring history. Add only if the agent ergonomics suffer.
- Time-based or compressed rotation — defer; size-based fits the substrate's process-lifetime profile.
- Per-agent log files — explicitly considered, rejected: filter-on-read is sufficient at the agent counts the substrate runs at.
- Pre-existing `seon.boot.preconditions-test` failures — unrelated to this task, flagged for separate cleanup.

## Revision — ring buffer removed (2026-05-26)

User pushback: *"tail is not magic — it should literally read the log file."* The ring buffer was a second source of truth and exactly the kind of accidental complexity the user wanted gone. This revision deletes it.

### What came out

- `seon.log/!ring`, `ring-conj!`, `clear-ring!`, all `:seon.log/ring-cap` config knobs and tests.
- The `log!` third-write (was console + ring + file; now console + file).
- The `:seon.log/dir` + `:seon.log/filename` split — replaced by a single path.
- `ring-buffer-fills-and-tail-returns-newest-first` and `ring-buffer-respects-cap` test cases.

### What stayed

- The two real sinks: stdout/stderr (`console!`) and the NDJSON-EDN file.
- File-size rotation (`:seon.log/file-cap`, `:seon.log/keep`).
- The `tail` API and the `:seon.log/tail-request` / `:seon.log/tail-response` schemas — same signature, file-backed implementation.
- All callers (`seon.render.default/recent-errors`, `seon.web.broadcast`, `seon.client/log-replay-failure!`) — unchanged.

### Path config — single source of truth

`seon.log/*log-file*` is a `^:dynamic` var (default `"logs/pod-events.log"`). Both writers and `tail` resolve through it. The WASI sidecar migration is now one line:

```clojure
(binding [seon.log/*log-file* "/scratch/pod-events.log"] ...)
;; or
(seon.log/configure! {:seon.log/file "/scratch/pod-events.log"})
```

`configure!` does `set! *log-file*` on the root binding, mirroring the existing pattern in `seon.client` (`set! db/*conn* conn`). No literal `"logs/pod-events.log"` strings sprinkled anywhere else.

### REPL evidence

```
(binding [log/*log-file* probe-path]
  (log/error! {:seon.log/source ::probe :seon.log/message "e1"})
  (log/info!  {:seon.log/source ::probe :seon.log/message "i1"})
  (log/error! {:seon.log/source ::probe :seon.log/message "e2" :seon.log/agent "a1"}))

(log/tail {:seon.log/n 5})                             ; → 3 entries
(log/tail {:seon.log/n 10 :seon.log/level :error})     ; → 2 errors
(log/tail {:seon.log/n 10 :seon.log/agent "a1"})       ; → 1 entry

;; agent reads its own log as a file
(fs/configure! {:seon.fs/allowed-roots [(dirname probe-path)]})
(:seon.fs/content (fs/read-file {:seon.fs/path probe-path}))
; → "{:seon.log/source :seon.log-test/probe ...}\n..."   ← 3 NDJSON lines
```

All 10 tests in `seon.log-test` pass (27 assertions, 0 failures). The `tail-survives-simulated-pod-restart` test exercises the key claim: with no in-memory ring, a fresh dynamic-binding scope (the V0 simulation of a process restart) sees every entry the prior scope wrote, because the file IS the buffer.

### Rotation vs tail — honest tradeoff

`tail` reads ONLY the active file. After rotation, entries that lived in `pod-events.log` now live in `pod-events.log.1` and are invisible to `tail`. With the default `:keep 3` and the default 5 MB cap, that's still ~15 MB of history available on disk — but the agent has to enumerate `pod-events.log.{1,2,3}` itself via `seon.fs/walk-dir` or repeated `read-file` calls to access it. Documented in the `tail` docstring.

Tradeoff is intentional: making `tail` glue the rotated files together would re-add the "magic" the user objected to. If deeper-history-from-tail becomes a recurring need, the cleaner answer is to bump `:seon.log/file-cap` and `:seon.log/keep` rather than re-introducing buffer logic.

### Agent-readable verified

`seon.fs/read-file` returns the exact bytes `log/error!` wrote. The fs allowlist enforcement still applies — the consumer overlay (or `bin/seon` boot) must `seon.fs/configure!` the log directory as an allowed root, or the WASI pod's `/scratch` preopen takes care of it natively.
