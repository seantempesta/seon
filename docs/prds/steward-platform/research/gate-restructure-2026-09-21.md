---
type: research
status: in progress
created: 2026-09-21
tags: [testing, performance, datahike]
---

# Gate restructuring

The assignment spec was read end to end, together with AGENTS.md §5 and
the data-oriented-clojure, clojure-testing and datahike skills. The current
publication and results-reuse closeouts retain their admission and cold-proof
limitations. Native lane status exposed only this session; the current working
edge records publication landed and SCI/program queued after `e10797ad5`.
Owned runner paths were clean before editing. Foreign dirty paths are excluded.

## Identity correction

The retained manifest contains 2,076 symbol test identities. All 868 complete-log
task names match as symbols, zero as strings, and zero are genuinely absent.
The full-file census reproduced both raw-log SHA-256 values in the spec:
complete 868 BEGIN / 868 END / 868 timed; interrupted 121 / 120 / 68.
Complete serial samples: median 914 ms, p90 4,461 ms, sum 3,286,611 ms.
Interrupted serial samples: median 4,969.5 ms, p90 37,346 ms, sum 1,060,202 ms.
All three pools have zero timed tasks in both logs. These are historical
execution durations, not fixture measurements or a successful durable tally.

Task construction, timeout/exchange/exhaustion results, and confirmation
selection now retain symbols. The positive regression reads the canonical
manifest and constructs tasks from an actual published test Var; one absent
identity remains unresolved. Existing task fixtures use symbols too.
Schema inventory: `resources/seon/schemas/seon.test.runner.edn` already declares
captured results through `:seon.test/sym`; no separate internal task-symbols
schema exists. No schema resource changed in this slice.

## Verification boundary

`tmp/gate-restructure/identity-fast.log`, snapshot `run.iqeY8n`, HEAD
`4510d4ef9`, armed 1,484 contracts (1,479 program-armable), then refused
snapshot admission before executing any tests. The live recording authority
rejects optional `:seon.error/offending` in
`:seon.test.runner/invalid-marker-reason-error`: “A stored error member must
have a storable registered attribute.” The publication closeout identifies
the held `src/seon/schema/internal.cljc` admission seam. No bridge, admission,
or recording change is included. Executed count: zero; unchanged count and
durable tally: unavailable. This is not a green iteration.

The serial precommit JVM load passed (exit 0): `clojure -M -e
"(require 'seon.test.runner) (println :gate-restructure-identity-load)"`.
This verifies namespace loading only. The edit hook reported existing runner
warnings and unrelated Markdown pin errors in the two wave-3 specs; neither
foreign document was edited.

Default remained untouched. Its startup health read failed on a missing
`:seon.error/at` in `seon.problems/problems`; the existing health issue records
that exact observation. Cold process-lifetime integration and platform proof
remain the orchestrator's responsibility.

## Dependency and lifetime grounding

Datahike `reference-code/datahike/src/datahike/versioning.cljc:212` owns
same-store `branch!`: reuse primary roots and copy-on-write secondary indexes,
then publish the branch head and roster. This differs from `fork-database`
at :550, which copies to another store. The ordinary fixture's
`with-branched-database` carries the base projection but acquires branch-local
connections and projection state, releasing connections before deleting the
branch and releasing the base hold.

`src/seon/test/cache.clj:318` already owns checkout creation, called by
`start-worker!`. Its child preparation has the existing declared bound.
`run-task-pool!` owns the existing queue; `worker-exchange!` owns command
deadlines and retirement; `stop-worker!` owns process-tree exit observation.
The lifecycle change will use those owners after selection, reserve one first
task per child, and close each drainer before joining serial work.
