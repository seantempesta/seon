---
type: research
status: complete
tags: [render, performance, agent]
---

# Root walk — 2026-09-10

Bounded root-walk assignment. Default remains PID 23557, port 7994; this
lane neither stops nor reforks it. Unrelated edits and untracked `build/`,
`workers/`, and `config/virtual-turns.edn` are preserved.

## Grounding and decision

Read AGENTS.md, the assigned issue, turn PRD §§13–16, the complete walk,
web acquire-root/refresh-root/derive-page, schedule and blob-retention
owners, and the previous root-page landing note end to end. Read the
roadmap entry and working edge, UI architecture, and the Clojure, REPL,
Datahike, web UI, and testing skills. There are no nested AGENTS.md files
under the affected source, resource, or test directories.

Dependency ledger:

- `resources/seon/schemas/seon.agent.edn`: `:seon.render/units` names
  plan, inbox, settings, notes, namespace, runtime, and routed faults.
  `seon.cluster.status.edn` declares root's additional cluster concern.
- `src/seon/render/walk.clj`, `declared-acquisition`: these declarations
  already selected rendered blocks, but acquisition ignored them. Both
  stages now use the same `declared-concerns` derivation.
- `src/seon/render/block.clj`: `surface-id` owns block addresses; it does
  not declare or discover membership. The actual declaration consumer is
  the walk above, contrary to the assignment's suggested source location.
- `reference-code/datahike/src/datahike/pull_api.cljc`: explicit nested
  selectors avoid implicit component expansion and retain concrete read
  plans. The walk acquires shallow entities and expands declared concerns
  itself; recursive selector construction was unnecessary.
- `src/seon/db.clj`: pull evidence records forward entity/attribute and
  reverse attribute/value index patterns. The existing evidence comparison
  and `src/seon/render.clj` shared cache remain unchanged.
- `src/seon/schedule.clj`, `fire-call` and `fire-due!`: each nominal firing
  records a fire, maintenance request, and execution outcome. These are
  operational facts, absent from agent page declarations.
- `src/seon/blob/retention.clj`, `reclaim!`: retention concerns binary
  blobs under the existing byte-budget fact. It does not reclaim schedule
  datoms. Page membership does not justify changing that retention policy.

Decision: exclude undeclared operational reverse refs at acquisition. No
new cap, retention setting, cache, timer, or scheduling behavior is added.
Forward references remain available as identities in an entity's own
value; traversal follows components and declared units. Namespace requires
continues in both directions. Empty reverse concerns retain read evidence.

Final review narrowed reverse reads per entity, too: acquisition first
pulls its own attributes, matches the same schemas used by block rendering,
then reads their declared reverse refs. Both reads settle into the same
existing entity-cache entry and ordinary read evidence. A reverse concern
declared for a different entity schema cannot add a read dependency here.
The regression points an inbox edge at a synthetic non-agent root and
verifies unchanged root data and current evidence; this would fail with
a global union of declared reverse attributes.

## Before measurements

Default's initial MCP health request timed out and reported unknown.
The next bounded JVM evaluation succeeded in 425 ms. A later probe omitted
the handed projection and timed out; subsequent projected JVM forms worked.
This is recorded on the assigned issue, not interpreted as healthy status.

After clearing the existing disposable render cache, `GET /` took
**7.555628 s**, **19,727 bytes**. The shared cache contained **2,277**
`:seon.render.walk/entity-pull` keys, representing **2,276 distinct eids**.
The count included a root lookup and its numeric eid. There were **737**
schedule fires. Repeated requests measured **9.113162**, **9.740613**,
**11.349787**, and **11.125932 s** (19,644 / 19,657 / 19,655 / 19,768 bytes).
Juniper's first and next requests were **0.215642 / 0.010742 s**, both
**19,568 bytes**. Curl's own `time_total` avoids the original sampler's
one-second polling floor.

The supplied `tmp/root-probe/run.sh` and `log.txt` were read. Its first
eight sampled root requests were 1,141 / 1,106 / 16,608 / 13,404 / 9,775 /
1,083 / 1,123 / 10,870 ms, including the polling/dump overhead. The issue's
virtual-thread evidence identifies entity pulls in the walk. This lane's
cleared-cache measurement independently counts those entities.

At fixed database basis **536872767**, the original acquisition retained
**2,278 members**. The final acquisition retains **23**. Rendering both
through the same current page function and retained render-call outputs
produced **21,576 bytes** each and identical HTML text in identical order.
Text is **6,688 UTF-8 bytes**, **284 nonblank HTML text nodes**, SHA-256
`fd2496953c855c2884483cebc59cdd8feba8f4d81a2e981fb7b101b2f8fbcca6`
on both sides. Markup differs in the namespace block's traversal-derived address. Without
retaining call outputs, the live heap measurement also changes between
calls; no stored page concern differs.

The first HTTP request after the new walk loaded took **0.344686 s**.
The concurrent runtime-HTML lane changed the runtime renderer, so raw
before/after HTTP pages across that adoption are not an isolated content
comparison. The fixed-basis acquisition comparison above isolates this
lane's change.

## Final default measurements

Implementation commit: `d6d399561`.

Root's live page now includes 120 turns, versus the issue's original 14;
the concurrent owner trial changed those facts. A cleared shared cache
followed by cold and immediate warm GETs gives:

| Page | Cold seconds | Warm seconds | HTTP bytes | Entity-pull cache keys | Distinct eids |
| --- | ---: | ---: | ---: | ---: | ---: |
| root | 0.748105 | 0.050616 | 55,630 | 132 | 132 |
| juniper | 0.106697 | 0.009211 | 22,545 | 21 | 21 |

The first root GET immediately after adoption had taken **1.375656 s**,
then **0.149022 s** warm. That observation is retained rather than hidden;
the table is the following explicitly cleared-cache census.

The committed [sampler](../../../../test/seon/render/root_walk_probe.py)
uses the supplied method: bounded curl plus virtual-thread dumps and a
one-second sampling interval. It also records curl's own HTTP duration so
the polling floor is not mistaken for request latency. With the shared
cache cleared before round 0, six rounds 25 seconds apart produced:

| Round | Root first seconds | Root immediate warm | Juniper first | Juniper immediate warm |
| --- | ---: | ---: | ---: | ---: |
| 0 | 0.524952 | 0.051657 | 0.070350 | 0.010319 |
| 1 | 0.536099 | 0.082493 | 0.062954 | 0.007921 |
| 2 | 0.081151 | 0.164832 | 0.020634 | 0.010677 |
| 3 | 0.167624 | 0.087337 | 0.027451 | 0.010029 |
| 4 | 0.578141 | 0.378654 | 0.065503 | 0.066763 |
| 5 | 0.344542 | 0.335110 | 0.079731 | 0.070484 |

Only round 0 is deliberately cold. Root's immediate-warm median is
**0.1260845 s**; Juniper's is **0.010498 s**. Every root response is
**55,630 bytes** and every Juniper response **22,545 bytes**. Juniper's
text hash is unchanged throughout:
`8256dcd696a71278bbe2f4131920787b8755b04f4ce9d32ef7c4b2529efcc979`.
The root text diff contains only changed live `plumbing passes` counts;
all concern text and its order remain unchanged. The fixed-basis comparison
above additionally isolates before/after implementation with those live
outputs retained.

The schedule fire count was **757** before sampling and **759** after its
two-minute span; subsequent verification observed **760**. A saved snapshot
of **149** shared root/Juniper entity-cache entries at fire **758** still
had **149 current read-evidence results** and **149 identical output
objects** at fire **760**. Two actual scheduled firings therefore neither
expanded these pages nor made their acquired entities cold. The canonical
schedule regression independently verifies the same property and checks
that a real inbox insertion still invalidates.

The strict 0.3-second warm target is **not met by every sample**: rounds
4 and 5 exceed it. The round-4 virtual-thread stack is in
`web/candidate-call-ids` → `db/read-evidence-current?` → `db/replay-read` →
Datahike `pull-plan-with-evidence`, not a new `acquired-tree` expansion.
The assignment preserves that evidence mechanism. This residual observation
is tracked in [the warm-evidence follow-up](../../../seon/issues/root-page-warm-read-evidence-replay-exceeds-300ms.md);
the traversal and operational-invalidation defect is resolved.

Reproduce the HTTP sample after clearing the disposable render cache:

```sh
python3 test/seon/render/root_walk_probe.py --url http://127.0.0.1:7994 --pid 23557 --output tmp/root-walk/samples --rounds 6 --interval 25
```

The JVM census uses the existing cache, with its environment's projection
handed explicitly; it creates no alternate counter or cache:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      handle (:seon.turn.loop/cluster instance)
      ctx (:seon.sci.eval/ctx handle)
      projection (seon.sci.kernel/context-projection ctx)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [entries (filter
                    (fn [[k _]]
                      (and (vector? k)
                           (= :seon.render.walk/entity-pull (first k))))
                    @(seon.render/shared-cache ctx))]
       {:entity-pull-keys (count entries)
        :distinct-entities
        (count (set (keep (fn [[_ v]]
                            (get-in v [:seon.render.call/output :db/id]))
                          entries)))}))))
```

For a cold census, reset that same cache to `{}` immediately before the
HTTP request. For the two-firing identity check, retain those entries and
the fire count, then compare `identical?` on each output and call
`seon.db/read-evidence-current?` on each saved entry's evidence against the
new database value. The checks require nonzero entries and an increase of
at least two in the queried fire count.

## Verification

Fast iteration identified stale tests asserting every ref was a concern,
and history tests treating an unexecuted message as a saved evaluation.
Those expectations now use declared inbox relationships and components.
The schedule fixture's seed result was previously ignored; the regression
now asserts successful setup before claiming that absent firings are healthy.
Its synthetic handler rows lacked required namespace and admission provenance;
those facts now describe the real test handlers. The walk's HTML fixture now
seeds the canonical cluster environment so SCI supplies declared database
arguments. No mock SCI context or schema subset was substituted.

Final fast run: **27 tests / 158 assertions**, zero failures/errors.
Final isolated gate: **27 tests / 162 assertions**, zero failures/errors;
successful root `run.TDVab6` automatically removed. Final platform gate:
**84 tests / 505 assertions**, zero failures/errors; successful root
`run.AEw9vX` automatically removed. The two isolated gates used HEAD
`25de7055066e6dd95eea3eeb41b6dcc68e02dc58` plus only the four owned source
and test files below. Long tests were not selected; no `--all` or `--full`
was run.

```sh
bin/test-fast --paths src/seon/render/walk.clj test/seon/render/root_pull_test.clj test/seon/render/walk_test.clj test/seon/schedule_test.clj -- seon.render.root-pull-test seon.render.walk-test seon.schedule-test
SEON_TEST_WORKERS=3 bin/test --paths src/seon/render/walk.clj test/seon/render/root_pull_test.clj test/seon/render/walk_test.clj test/seon/schedule_test.clj -- seon.render.root-pull-test seon.render.walk-test seon.schedule-test
SEON_TEST_WORKERS=3 bin/test --paths src/seon/render/walk.clj test/seon/render/root_pull_test.clj test/seon/render/walk_test.clj test/seon/schedule_test.clj --platform
```

The real schedule regression executes two firings, verifies two fire rows,
two maintenance requests, and two completed outcomes, then proves unchanged
root/member/order values, current read evidence, and unchanged cache keys.
It finally inserts an inbox message and requires invalidation and the new
member: an absent subject cannot pass the check.

Browser boundary: CUA reports no browser surfaces; native Chrome returns
`cgWindowNotFound` (-10005), matching the existing
[browser observation issue](../../../seon/issues/browser-ui-observation-has-no-accessible-window.md).
This lane verifies served HTML and HTTP behavior, not browser paint.

All gates use HEAD plus explicitly owned paths. The initial syntax error
in this lane's edit was corrected before live loading. Default publication
waited on another lane's lifecycle lock and one adoption reported source
changes during adoption after loading definitions. The subsequent owned
`bin/seon init --dev default --changed src/seon/render/walk.clj` completed
successfully: development definitions, SCI acquisition, JVM instrumentation,
and cluster convergence at source commit
`6aa31d8a-2e99-5aa2-9247-b5e2d7bd7bd2`. This is in-place development
adoption in PID 23557, not a new fork or a default restart.

## Files and cleanup

The implementation commit `d6d399561f8821e0c384716ff68672c51d0324fb` owns:

- `src/seon/render/walk.clj`
- `test/seon/render/root_pull_test.clj`
- `test/seon/render/walk_test.clj`
- `test/seon/schedule_test.clj`
- `test/seon/render/root_walk_probe.py`

The documentation closure owns this landing, archives the resolved
`docs/seon/issues/root-page-walks-every-ref-attribute-and-schedule-fires-keep-it-cold.md`
under `docs/seon/issues/archive/`, and records
`docs/seon/issues/root-page-warm-read-evidence-replay-exceeds-300ms.md`.
No web, scheduling, retention, maintenance, or schema production file needed
changing. The issue index remains the orchestrator's schedule.

The scoped gates excluded concurrent turn/schema/test work. The concurrent
runtime-HTML change and live root trial are the content-comparison boundary,
addressed by the frozen database comparison above. No foreign session was
operated and no foreign file was edited. No scratch cluster or worktree was
created. All owned test and sampler shells exited; successful test roots
were removed by their runner. The 4.0 MB `tmp/root-walk` evidence directory
and the probe's Python bytecode are removed after recording the results and
committing its reproducible sampler. The two private host probe Vars were
unmapped. The owner's `tmp/root-probe` evidence is preserved.

Documentation verification boundary: the final Markdown hook could not
derive repository dependency-pin evidence because
`docs/seon/issues/the-loop-stops-after-one-accepted-reply.md` no longer
existed after its independent closure in `49aa05e88`. This lane did not
repair that foreign closure or edit the shared index. `git diff --check`
is clean; the source/test gates above completed before documentation closure.
