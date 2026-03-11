# Test Timing and Storage Research Findings

**Date:** 2025-12-05
**Researcher:** Claude (auto-test-hook feature)

## Executive Summary

After researching test timing strategies across multiple ecosystems and Clojure test result storage conventions, I recommend:

1. **Timing Strategy:** 100-150ms debounce (trailing edge) with batch collection
2. **Storage Location:** `target/test-results/` for full output, summary in context
3. **Format:** Plain text logs + optional JUnit XML (via kaocha-junit-xml plugin)

---

## 1. Test Timing Strategies

### Tools Researched

#### Watchexec (Rust-based file watcher)

- **Default debounce:** 50ms (trailing edge)
- **Historical evolution:** Started at 500ms → 300ms → 150ms → 100ms → 50ms
- **Trailing edge behavior:** Collects all events during the debounce window, triggers action only after quiet period
- **Why 50ms works:** Modern SSDs allow very fast debounce without missing batched edits
- **Configuration:** `--debounce <ms>` flag allows custom intervals

**Sources:**
- [Watchexec Issue #168 - Decreasing default debounce](https://github.com/watchexec/watchexec/issues/168)
- [Watchexec Discussion #897 - Debounce behavior](https://github.com/watchexec/watchexec/discussions/897)
- [TIL: Watchexec blog post](https://tech.stonecharioteer.com/posts/2025/til-watchexec/)

#### Kaocha Watch Mode

- **Mechanism:** Uses Beholder (previously Hawk) for filesystem watching
- **Debounce:** No explicit debounce in documentation, but handles rapid changes via tools.namespace dependency tracking
- **Smart reloading:** Unloads dependent namespaces first, then reloads from scratch
- **Re-run strategy:** Failed tests run first on next change; full suite only after failures pass
- **Manual trigger:** Press Enter to force re-run all tests
- **Gotcha:** Some editors (vim/neovim) trigger duplicate events (`:delete` + `:create`) unless `backupcopy=yes` is set

**Sources:**
- [Kaocha Watch Mode Documentation](https://cljdoc.org/d/lambdaisland/kaocha/1.88.1376/doc/7-watch-mode)
- [Kaocha Issue #277 - Watch mode with neovim](https://github.com/lambdaisland/kaocha/issues/277)

#### Node.js Watch Mode

- **Problem:** Originally no debounce - restarted on every file change during build
- **Example:** TypeScript compiling 50 files → 50 restarts
- **Solution:** PR #51992 (May 2024) added batch file restarts
- **Third-party alternatives:**
  - `@bscotch/debounce-watch` - guarantees all events in batch captured
  - chokidar delay options - approximate batching
  - Typical debounce: 100ms works well for manual changes

**Sources:**
- [Node.js Issue #51954 - Watch should debounce](https://github.com/nodejs/node/issues/51954)
- [Node.js PR #51992 - Batch file restarts](https://github.com/nodejs/node/pull/51992)
- [thisDaveJ - File watching in Node.js](https://thisdavej.com/how-to-watch-for-file-changes-in-node-js/)

#### Shadow-cljs Hot Reload

- **Lifecycle hooks:** `^:dev/before-load` and `^:dev/after-load` metadata on functions
- **Process:** Watch filesystem → compile changed namespaces → call before-load hooks → load new code → call after-load hooks
- **Smart compilation:** Only recompiles direct dependents (not transitive) by default
- **Caching:** Aggressive compilation caching for fast incremental rebuilds
- **Async support:** Async variants of hooks for async work before reload proceeds

**Sources:**
- [Shadow-cljs User Guide](https://shadow-cljs.github.io/docs/UsersGuide.html)
- [Hot Reload in ClojureScript blog post](https://code.thheller.com/blog/shadow-cljs/2019/08/25/hot-reload-in-clojurescript.html)

#### Guard (Ruby file watcher)

- **No native debounce:** Guard doesn't have built-in debounce
- **Manual workaround:** Track last rebuild timestamp, compare with current time
- **Typical interval:** 2 seconds in user examples
- **Problem:** Editors like Vim trigger duplicate events (modify event fires twice on save)

**Sources:**
- [Stack Overflow - Guard batch watch notifications](https://stackoverflow.com/questions/15721402/guard-batch-watch-notifications)

#### Jest (JavaScript test runner)

- **Watch mode:** Not much documentation on internal debouncing
- **Testing debounced functions:** Jest provides timer mocks (`jest.useFakeTimers()`, `jest.advanceTimersByTime()`)
- **Note:** Search results focused on testing debounced code, not Jest's internal watch debouncing

**Sources:**
- [Jest Timer Mocks Documentation](https://jestjs.io/docs/timer-mocks)

---

## 2. Recommended Test Timing Approach

### Strategy: 100-150ms Trailing Edge Debounce

**Rationale:**
1. **Agent edit patterns:** Agents often make 2-5 related file changes in quick succession (e.g., editing implementation + test file, or fixing multiple namespace requires)
2. **Modern SSD performance:** 50ms (watchexec default) might be too aggressive for agent bulk edits
3. **Human tolerance:** 100-150ms is imperceptible to humans but allows proper batching
4. **Safety margin:** Gives enough time for clj-paren-repair hook to run after file saves

**Implementation Details:**

```clojure
;; Pseudocode for debounce logic
(defonce debounce-ms 100)  ;; configurable
(defonce pending-edits (atom #{}))
(defonce timer (atom nil))

(defn on-file-edited [file-path]
  ;; Cancel existing timer
  (when-let [t @timer]
    (cancel-timer t))

  ;; Add file to pending edits
  (swap! pending-edits conj file-path)

  ;; Start new timer (trailing edge)
  (reset! timer
    (schedule-after debounce-ms
      (fn []
        (let [files @pending-edits]
          (reset! pending-edits #{})
          (run-tests files))))))

```

**Why NOT 50ms:**
- clj-paren-repair hook runs after each file edit
- Agent might save file 1 → pause to think → save file 2 (120ms later)
- 50ms would trigger tests after file 1, fail, then run again after file 2

**Why NOT 500ms+:**
- Feels sluggish to users watching output
- No benefit once batch window passes

---

## 3. Test Result Storage Conventions

### Kaocha JUnit XML Plugin

Kaocha has an official plugin for JUnit XML output:

**Plugin:** `lambdaisland/kaocha-junit-xml` (latest: 1.17.101)

**Configuration in `tests.edn`:**

```clojure
#kaocha/v1
{:plugins [:kaocha.plugin/junit-xml
           :kaocha.plugin/profiling      ;; Required for timing info
           :kaocha.plugin/capture-output] ;; Required for <system-out>

 :kaocha.plugin.junit-xml/target-file "target/test-results/junit.xml"
 :kaocha.plugin.junit-xml/omit-system-out? false
 :kaocha.plugin.junit-xml/add-location-metadata? true}  ;; GitHub Actions annotations

```

**Command line:**

```bash
clj -M:test -m kaocha.runner --plugin kaocha.plugin/junit-xml --junit-xml-file target/test-results/junit.xml

```

**Features:**
- Timestamps and running time (requires profiling plugin)
- System output capture (requires capture-output plugin)
- Test location metadata for GitHub Actions annotations
- Compatible with CircleCI, Azure DevOps, GitLab CI

**Sources:**
- [Kaocha JUnit XML Plugin GitHub](https://github.com/lambdaisland/kaocha-junit-xml)
- [Kaocha JUnit XML Documentation](https://cljdoc.org/d/lambdaisland/kaocha-junit-xml/1.17.101/doc/readme)

### Standard Clojure Test Output Locations

**Convention:** `target/test-results/`

**Evidence:**
1. **circleci.test library:** Uses `target/test-results` as default `:test-results-dir`
2. **Cloverage:** Uses `target/coverage` for coverage reports
3. **CircleCI docs:** Expects test results in `target/test-results` for `store_test_results` step
4. **Best practice:** Add test results directory to `.gitignore`

**Current project status:**
- `.gitignore` already ignores `/target/`
- `.gitignore` has `/test-output/` and `/coverage/` (legacy?)
- No test result storage currently configured in `tests.edn`

**Sources:**
- [CircleCI.test GitHub](https://github.com/circleci/circleci.test)
- [CircleCI Test Data Collection Docs](https://circleci.com/docs/collect-test-data)
- [Cloverage GitHub](https://github.com/cloverage/cloverage)

### clojure.test Native Behavior

**Default output:** Prints to `*test-out*` (normally same as `*out*`)

**Customization:** Can rebind `*test-out*` to any PrintWriter:

```clojure
(require '[clojure.java.io :as io])

(binding [clojure.test/*test-out* (io/writer "target/test-results/output.txt")]
  (clojure.test/run-tests))

```

**Custom reporters:** Override `clojure.test/report` multimethod for custom formats (TAP, JUnit, etc.)

**Sources:**
- [clojure.test API Documentation](https://clojure.github.io/clojure/clojure.test-api.html)

---

## 4. Recommended Storage Strategy

### Two-Tier Approach

#### Tier 1: Summary in Agent Context (Immediate Feedback)

Store concise summary for agent to see:

```
✓ 45 tests passed
✗ 3 tests failed:
  - ml-options.web.handlers-test/test-import-start
  - ml-options.db.queries-test/test-empty-results
  - ml-options.data.ingest-test/test-invalid-symbol

Full output: target/test-results/2025-12-05T14-23-15.txt

```

**Benefits:**
- Agent sees what failed without reading 200 lines
- Agent can jump to full output if needed
- Keeps context window manageable

#### Tier 2: Full Output on Disk (Debugging)

Store complete test output with:
- Timestamped filename: `target/test-results/2025-12-05T14-23-15.txt`
- All test output (stdout, stderr, stack traces)
- Metadata header (files edited, test command run, duration)

**Format:**

```
=== Auto-Test Run ===
Timestamp: 2025-12-05T14:23:15Z
Trigger: file-edit
Files edited:
  - src/ml_options/web/handlers.clj
  - test/ml_options/web/handlers_test.clj
Command: clj -M:test -m kaocha.runner
Duration: 2.3s

[Full test output follows...]

```

### Directory Structure

```
target/
├── test-results/
│   ├── latest.txt -> 2025-12-05T14-23-15.txt  # Symlink
│   ├── 2025-12-05T14-23-15.txt
│   ├── 2025-12-05T14-21-02.txt
│   ├── junit.xml  # Optional, for CI
│   └── summary.edn  # Optional, machine-readable

```

### File Retention

- Keep last 10 test runs (auto-delete older)
- OR Keep last 24 hours of test runs
- Always keep `latest.txt` symlink

---

## 5. Implementation Notes

### Debounce Interval Configuration

Make debounce interval configurable via environment variable:

```bash
export AUTOTEST_DEBOUNCE_MS=150

```

Default to 100ms if not set.

### Handling Running Tests

**Problem:** What if tests are running when new edit occurs?

**Options:**
1. **Cancel and restart** (watchexec style) - Could waste work
2. **Queue next run** (Kaocha style) - Finish current, then run again
3. **Ignore during run** - Drop edits that occur while tests running

**Recommendation:** Option 2 (Queue next run)
- Less wasteful than cancelling
- Ensures every edit batch gets tested
- Simple to implement with atom flag

```clojure
(defonce test-running? (atom false))
(defonce run-queued? (atom false))

(defn run-tests-with-queue [files]
  (if @test-running?
    (reset! run-queued? true)  ;; Queue next run
    (do
      (reset! test-running? true)
      (try
        (run-tests-impl files)
        (finally
          (reset! test-running? false)
          (when @run-queued?
            (reset! run-queued? false)
            (recur files)))))))  ;; Run queued test

```

### Test Command to Run

**Current project:** `clj -M:test -m kaocha.runner`

**Smart mode:** Only run tests for affected namespaces
- Track which test files correspond to edited source files
- Run only those test namespaces
- Fallback to full suite if mapping unclear

**Example:**

```clojure
;; Edit: src/ml_options/web/handlers.clj
;; Run:  clj -M:test -m kaocha.runner --focus ml-options.web.handlers-test

```

### Integration with clj-paren-repair

**Current hook:** Runs after every Clojure file edit

**Coordination:**
1. clj-paren-repair runs (synchronous)
2. File save completes
3. Auto-test hook detects file change
4. Debounce timer starts

**Important:** Ensure auto-test hook sees the REPAIRED file, not the broken one.

### Test Output Capture

**Simple approach:** Shell redirection

```bash
clj -M:test -m kaocha.runner 2>&1 | tee target/test-results/$(date +%Y-%m-%dT%H-%M-%S).txt

```

**Better approach:** Programmatic capture

```clojure
(require '[clojure.java.io :as io])

(defn run-tests-with-capture [output-file]
  (let [baos (java.io.ByteArrayOutputStream.)
        ps (java.io.PrintStream. baos)]
    (binding [*out* ps
              *err* ps]
      (try
        (kaocha.runner/-main)
        (finally
          (spit output-file (.toString baos)))))))

```

### Summary Extraction

Parse Kaocha output for summary line:

```
45 tests, 127 assertions, 3 failures.

```

Extract:
- Total tests
- Assertions
- Failures/errors
- Failed test names (from output)

**Regex patterns:**

```clojure
(def summary-pattern #"(\d+) tests?, (\d+) assertions?, (\d+) failures?")
(def fail-pattern #"FAIL in ([\w\.\-/]+)")

```

---

## 6. Alternatives Considered

### Alternative 1: No Debounce (Run Immediately)

- **Pro:** Fastest feedback
- **Con:** Agent edits 5 files → 5 test runs → overwhelming output
- **Verdict:** Rejected

### Alternative 2: Manual Trigger Only

- **Pro:** Agent has full control
- **Con:** Requires explicit command, slows workflow
- **Verdict:** Could be fallback mode

### Alternative 3: Run on Integrant Reset

- **Pro:** Natural hook point after code reload
- **Con:** Tests might run too often (every code change)
- **Con:** Doesn't help when agent edits tests without reset
- **Verdict:** Complementary, not replacement

### Alternative 4: Long Debounce (1-2 seconds)

- **Pro:** Definitely captures all edits in batch
- **Con:** Feels sluggish, users notice delay
- **Verdict:** Too conservative

---

## 7. Comparison Summary

| Tool | Default Debounce | Trailing Edge? | Batch Collection? | Notes |
|------|------------------|----------------|-------------------|-------|
| **watchexec** | 50ms | ✓ | ✓ | Modern standard, aggressive |
| **Kaocha watch** | N/A (tools.namespace) | - | ✓ | Smart reloading, re-runs failures first |
| **Node.js watch** | Batched (no specific ms) | ✓ | ✓ | Fixed in 2024 |
| **shadow-cljs** | N/A (compile-driven) | - | ✓ | Lifecycle hooks, caching |
| **Guard (Ruby)** | Manual (2s typical) | ✓ | ✓ | No native support |
| **Jest** | Unknown | ? | ? | Undocumented |

---

## 8. Final Recommendations

### Test Timing

1. **Debounce interval:** 100ms (configurable via env var)
2. **Debounce type:** Trailing edge (collect all events, trigger after quiet)
3. **Queue strategy:** Queue next run if tests already running
4. **Smart targeting:** Run affected test namespaces when possible

### Test Storage

1. **Primary location:** `target/test-results/`
2. **File naming:** Timestamped `YYYY-MM-DDTHH-MM-SS.txt`
3. **Symlink:** `target/test-results/latest.txt` → most recent
4. **Retention:** Keep last 10 runs, auto-delete older
5. **Summary format:** Extract and show in agent context (concise)

### Optional Enhancements

1. **JUnit XML:** Add `kaocha-junit-xml` plugin for CI integration
2. **EDN output:** Store machine-readable summary in `summary.edn`
3. **Web UI integration:** Show recent test runs in dashboard (future)

### Configuration

```clojure
;; .env or config.edn
{:autotest {:debounce-ms 100
            :output-dir "target/test-results"
            :retention-count 10
            :queue-runs? true
            :smart-targeting? true}}

```

---

## References

### Documentation

- [Watchexec Documentation](https://github.com/watchexec/watchexec)
- [Kaocha Watch Mode](https://cljdoc.org/d/lambdaisland/kaocha/1.88.1376/doc/7-watch-mode)
- [Kaocha JUnit XML Plugin](https://github.com/lambdaisland/kaocha-junit-xml)
- [Shadow-cljs Hot Reload](https://code.thheller.com/blog/shadow-cljs/2019/08/25/hot-reload-in-clojurescript.html)
- [Node.js File Watching](https://thisdavej.com/how-to-watch-for-file-changes-in-node-js/)

### GitHub Issues/PRs

- [Watchexec Issue #168 - Debounce delay](https://github.com/watchexec/watchexec/issues/168)
- [Kaocha Issue #277 - Neovim watch](https://github.com/lambdaisland/kaocha/issues/277)
- [Node.js PR #51992 - Batch restarts](https://github.com/nodejs/node/pull/51992)

### Package Repositories

- [Kaocha JUnit XML on Clojars](https://clojars.org/lambdaisland/kaocha-junit-xml)
- [CircleCI.test GitHub](https://github.com/circleci/circleci.test)

---

**Next Steps:**
1. Prototype debounce mechanism in Clojure
2. Test with rapid file edits (simulate agent behavior)
3. Implement output capture and summary extraction
4. Consider adding kaocha-junit-xml to `tests.edn`
5. Create user-facing configuration in `.env` or `config.edn`
