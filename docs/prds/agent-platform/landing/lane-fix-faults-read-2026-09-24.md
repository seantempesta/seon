---
type: landing
status: current
created: 2026-09-24
scope: Lane fix-faults-read. The agent's fault opener becomes a short call to seon.error/for-agent, and a capped occurrence renders its recorded message.
---
# Lane fix-faults-read

The opener used to print a query. It now prints a call: `seon.error/faults-form`
emits `(seon.error/for-agent (seon.db/db) "<agent>")`. `for-agent` is a
contracted, docstringed function that runs the same two-ref query as before, with
the observation selector pulled inside the query. `render-faults-html` now calls
the same function, so its separate copy of the query and its per-reference pull
are gone. Report: `docs/research/agent-platform/agent-context-render-pipeline-2026-09-24.md` §0 finding 1.

## Broken or unproven (read first)

- **The regression and the edited test have not run.** Default (pid 90471) runs
  the committed archive `data/source/ce738468…` with hook publication off, so
  none of these edits can reach its JVM. `bin/test-check default --ns seon.error-test`
  would therefore test the old program. **RESET NEEDED**: root must run
  `bin/seon start --head` (or its equivalent) after this commit, then
  `bin/test-check default --ns seon.error-test`. That run covers the new
  `agent-fault-read-is-a-short-call-that-shows-each-recorded-message` and the
  converted assertion in `error-identity-and-occurrences-are-owned-by-the-writer`.
- The SCI path has not been observed live: no agent turn has yet evaluated
  `(seon.error/for-agent (seon.db/db) "root")`. What was checked instead:
  - the elided `(seon.db/db)` form is already how opening reads supply the database (`src/seon/issue/opening.clj:88-91`);
  - the function's contract compiles against the live projection;
  - its live output validates against its declared output.
- Two of the five stored opener evaluations (2026-09-23T16:42Z) failed in SCI
  before the read ran: `Cannot interpret seon.cluster.reload-measure/-main: Host-bound
  declaration …` at `seon.sci.eval/install-row!`. This is a foreign producer that
  belongs to the adoption-window defect in
  `docs/seon/issues/development-adoption-writes-program-rows-seconds-before-its-record.md`.
- A foreign defect seen while probing: a throwing MCP JVM evaluation projects as
  `seon.cluster/exception-summary refused return value at [:seon.error/message]:
  expected a string, got a string`. It belongs to
  `docs/seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md`
  (`src/seon/cluster.clj`, not owned here).
- Out of scope: through `seon.db`, `pull-many` of three errors costs 6.3 ms and
  17 MB. The same selector pulled inside `seon.db/q` costs 1.4 ms and 4 MB. The
  owner is `src/seon/db.clj`. No issue was filed.

## Why the old read showed `[]` or "Error evidence is unavailable"

The five stored opener evaluations on default (`:seon.cluster.eval/source`
containing the query) produced three different results:

1. **`[]` (2026-09-23T13:47Z) was true at its basis.** Rerunning the proof query
   as of its `:seon.cluster.eval/read-basis-transaction` 536871293 returns `[]`.
   Root's three errors were recorded after that point. The captured prompt keeps
   this historical unit and appends the refresh after it.
2. **`["Error evidence is unavailable. Occurrences: 7."]` was a defect, and it is fixed in this file.**
   - The producer is `seon.error/render-ai`. When the fact carries
     `:seon.error/data-edn` (capped evidence), `source` is the admitted
     remainder `{:seon.sci.admit/remainder …}`.
   - `(:seon.error/message (or source value))` then read the remainder, which has
     no message, and ignored the recorded
     `:seon.error.occurrence/message` ("class clojure.lang.PersistentList cannot be
     cast to class clojure.lang.Symbol …") that `latest-fact` had merged.
   - Fix: take the source's message, then the value's message, before the
     "unavailable" fallback.
3. Host-bound SCI refusal: foreign, described above.

## Evidence (live default, JVM mode, db value at 2026-09-24T14:1xZ)

| Probe | Before | After |
|---|---|---|
| Proof 1: `(count (pr-str (:seon.repl/form (faults-form …root…))))` | 81,805 | 42 (`(seon.error/for-agent (seon.db/db) "root")`) |
| Opener source text (`render-faults-ai`) | 109,624 chars | about 115 chars (comment plus call) |
| Proof 2: signatures of `for-agent` vs the proof query | no function | same 3: `b006bef4…`, `8ec40f22…`, `ab4f3435…` |
| Warm read, mean of 10 | old query 1.27 ms, 3.8 MB | `for-agent` 1.36 ms, 4.0 MB (+7%, +0.09 ms) |
| `ab4f3435…` rendered | "Error evidence is unavailable. Occurrences: 7." | "class clojure.lang.PersistentList cannot be cast …" |

- The "after" rows were measured on a scratch namespace (`tmp.fix-faults-read`,
  since removed) holding the committed `for-agent` body. The first draft used
  `pull-many` and measured 6.3 ms and 17 MB. That draft was rejected under the
  20% rule.
- The contract `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:vector
  :seon.db/pulled-entity] :seon.db/error-result]]` compiles against the live
  projection's registry. Both `[]` and root's live result validate against its output.
- HEAD plus this diff was extracted with `git archive`, with `reference-code`
  linked. `(require 'seon.error-test)` loads there in 32.6 s: a cold JVM, only a
  load proof. `for-agent` and the new test resolve.
- clj-kondo: 0 errors, 68 warnings, the same set as HEAD.

## Changes

- `src/seon/error.clj` (+28 −32):
  - `agent-faults-query` finds eids;
  - new `for-agent`;
  - `faults-form` emits the call, with a `;; ` comment;
  - `render-faults-html` reuses `for-agent`;
  - `render-ai` message fallback.
- `test/seon/error_test.clj` (+32 −2):
  - one class regression: a capped occurrence is read by a short call, the
    signatures match the query, and the recorded message is rendered;
  - the old form-destructuring assertion is converted to the call.
- Net: −4 source lines, +30 test lines.
