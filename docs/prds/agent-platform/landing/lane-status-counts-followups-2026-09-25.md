---
type: landing
status: landed; tests owed to root
lane: status-counts-followups
created: 2026-09-25
---

# Lane status-counts-followups (2026-09-25)

Follow-ups from `lane-fault-resolution-slices-2026-09-25.md` and
`lane-cluster-deadcode-and-mcp-bound-2026-09-25.md`.

## Broken or unverified first

- **No test ran.** The LOAD RULE applies. Hook publication is paused, and default still runs the
  pre-lane `seon.cluster`. It also lacks `seon.error/resolution` and the `:seon.error/resolution`
  schema; both were checked on default and both are missing. The `seon.problems.edn` reference
  compiles only when `seon.error.edn` (80958c50b) is adopted with it. Publish both together.
- **Nested strings are not bounded.** `bounded-face` cuts top-level strings and sequences only.
  Strings inside flow agent rows, `:seon.boot/recovered-runs` entries and the profile lines keep
  their length. The regression excludes flow and profile from its 300-character check for that
  reason.
- **The operator proc count covers only the shown agents.** `cluster-summary` now reports
  `:seon.operator/agents` as the whole count. It reads this from `:seon.dev.mcp/omitted` when the
  flow's agent list was cut. The proc and unanswered counts come from the 10 agents shown.
- **The regression uses `fault/fault!` 51 times** (the canonical writer, as in boot_test's
  status-faulted). Its duration against the 5 s bound has not been measured. Root must check it.
- The regression relies on a structural copy of the live instance, the same pattern as
  `boot_test/status-answers-faulted-health-and-lists-the-open-fault`.

## What changed

- `src/seon/cluster.clj` `mcp-runtime-observation`:
  - Adds `:seon.dev.mcp/resolved-fault-count`, the hunk from the fault-resolution landing.
  - Readiness and flow now pass through a new `bounded-face`. Each sequence over 10 entries
    keeps its first 10, and each string over 300 characters is cut to 300. Both sizes are named
    under `:seon.dev.mcp/omitted`.
  - Replaced roots are capped at 10. Each is put through `bounded-summary`, and the whole count
    goes in `:seon.dev.mcp/replaced-root-count`.
  - `bounded-summary` now drops collections and then calls `bounded-face`, so the string cut
    lives in one place.
- `script/seon/operator.clj` `cluster-summary`/`top`:
  - The open-fault count reads `:seon.dev.mcp/open-fault-count`, and the replaced-root count
    reads `:seon.dev.mcp/replaced-root-count`. Each falls back to the list length.
  - `omitted` is the whole count minus the entries shown.
  - Adds the resolved count and `:seon.operator/agents`.
- `resources/seon/schemas/seon.problems.edn` `:seon.problems/error-signature` now declares
  `[:seon.error/resolution {:optional true} :seon.error/resolution]`. The only declaration in
  the registry was the schema in `seon.error.edn:292`.
- `test/seon/cluster/mcp_test.clj` adds
  `runtime-observation-bounds-open-faults-and-counts-them-whole`. It records 51 faults through
  `fault/fault!`; the newest has a 1,000-character message. It resolves one with
  `problems/resolve!`, then asserts:
  - exactly 10 faults are shown
  - the open count is at least 50 and the resolved count at least 1 (the branch carries its
    parent's faults)
  - the resolved fault is not shown
  - the newest fault is shown with `omitted` message 1000
  - no string outside flow and profile is longer than 300 characters

## Evidence (MCP JVM on default, read-only, scratch namespace removed afterwards)

- Default's live observation before this lane measured:
  - readiness: 365 chars, and `recovered-runs` is its only collection
  - replaced roots: 1 entry, 234 chars
  - flow: 1 agent row and 2 plumbing rows, 1,257 chars
- Copies of `bounded-face` and `bounded-summary` in a scratch namespace:
  - A small refusal comes back unchanged (`=`), which keeps
    `runtime-observation-shows-unavailable-problems-with-their-cause` passing.
  - A 1.5M-character message with a 100,000-element offending value came back cut, with the
    sizes named.
  - Default's readiness and flow came back unchanged (`=`), because neither is over its bound.
  - Both calls together took 0.65 ms.
- One observation call on default took about 1 s during this probe. The JVM profile showed a
  concurrent test run (`with-database`, 194 s inclusive), so this is not a clean time. Root
  should time `mcp-runtime-observation "default"` after adoption, in a quiet window. The earlier
  figure was 416 ms.
- clj-kondo reports no new warnings in the touched ranges.

## Lines

Source +54 / −22 (cluster.clj +35/−13, operator.clj +17/−8, problems.edn +2/−1).
Test +42.

## Root runs (serially, after adopting cluster.clj + seon.error.edn + seon.problems.edn)

```
bin/test-check default --test seon.cluster.mcp-test/runtime-observation-bounds-open-faults-and-counts-them-whole
bin/test-check default --ns seon.cluster.mcp-test
bin/test-check default --ns seon.operator-test
bin/test-check default --test seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault
bin/test-check default --ns seon.problems-test
```

Then run MCP `runtime_status` on default. Check that `:seon.dev.mcp/resolved-fault-count` and
`:seon.dev.mcp/replaced-root-count` are present, and time the call.

Released: src/seon/cluster.clj, script/seon/operator.clj, resources/seon/schemas/seon.problems.edn,
test/seon/cluster/mcp_test.clj, this note. No branches, shells or JVMs were created.
