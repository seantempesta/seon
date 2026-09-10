---
type: research
status: complete
tags: [agent, render, root, performance]
---

# Root prompt page — 2026-09-09

Read AGENTS.md and its verbatim turn PRD §10 lane rules, the assigned
[issue](../../../seon/issues/archive/root-prompt-page-takes-18-seconds-and-carries-an-old-generation-opening.md),
and the roadmap README and working edge end to end. Also read binding
turn PRD §§13–15 and the prior root-cluster landing evidence. No nested
AGENTS.md exists in the affected source, test, or resource directories.

## Dependency ledger and measured cause

- `src/seon/render/web.clj:704`: `debug-prompt` calls the ordinary
  `seon.turn/system-turn` with `write? false`. The stored history is not
  the slow work.
- `src/seon/cluster/status.clj:46`: the status form called
  `seon.operator.state/footprint` synchronously. Its owner at
  `resources/seon/operator/state.clj:1209` recursively opens directories
  and stats every file. This was an explicit operator diagnostic put on
  an ordinary read path. Removed that call; no cache, timer, scanner, or
  copied measurement was added. Store bytes now return a typed unavailable
  observation directing the operator to `bin/seon status --verbose`.
- `src/seon/turn.clj:1875`, `:1950`, `:4433`: creation and compaction now
  use the same declared source and distinct-read planner. Creation retains
  the source bytes directly instead of reading and `pr-str`-printing them.
  The latter had expanded reader quotes and skipped duplicate-read removal.
- `src/seon/repl.clj:20` already delegates source printing to Clojure's
  code dispatcher. Dependency `reference-code/clojure/src/clj/clojure/pprint/dispatch.clj:46`
  owns quote shorthand. No new source formatter was necessary.
- `resources/seon/schemas/seon.config.edn:3`: the addressable settings
  component now declares the existing `seon.agent/render-settings-ai` and
  `render-settings-html` pair. An empty override map has this schema even
  when it has no dial attributes. The canonical schema/renderer selection
  regression exercises precisely that case.
- SCI execution and read evidence use the real cluster context. The
  runtime regression uses the canonical database fixture, armed contracts,
  actual per-agent Flow graph, and bounded event waits; no provider call.

Default was PID **23557**, port **7994**, throughout. Initial MCP status
and JVM evaluation answered. Untracked `build/`, `workers/`, and
`config/virtual-turns.edn` were preserved. No other lane's process or
files were operated.

Before, measured against the inherited default (already compacted by the
owner before this lane's capture):

| GET | Seconds |
|---|---:|
| root debug | 0.183051 |
| Juniper debug with `prompt=true` | 0.353777 |
| root debug with `prompt=true` | 14.370897 |

All **six** JDK JSON samples captured the same request virtual thread,
tid **648**, in `state/footprint`, below `cluster.status/snapshot`, SCI
evaluation, and `debug-prompt`. Five were in `lstat`; one in directory
enumeration. [Exact sampled stacks](root-page-before-samples-2026-09-09.json).
The [reproduction script](scripts/root-page-profile-2026-09-09.py) runs
`jcmd PID Thread.dump_to_file -format=json` during a bounded GET and
retains the complete virtual-thread stacks, rather than platform-only
ThreadMXBean samples.

The first post-change GET was 1.992283 s during publication and test-base
preparation; a subsequent warm GET was **0.895449 s**. A diagnostic GET
with repeated thread dumps was 1.367115 s; its stacks showed ordinary
schema encoding, pulls, and temporal read checks, with no store traversal.
Final quiet measurements and gate verdicts are recorded below.

## Opening and page evidence

The [capture script](scripts/root-page-capture-2026-09-09.clj) calls the
provider path's own `seon.cluster.prompt/prompt` with the selected cluster's
projection, configuration, context, and current root turn. It only reads;
compaction was performed through the actual HTTP route beforehand.

| Root | POST compact | POST system-turn | HTTP results |
|---|---:|---:|---|
| scratch root-page, Juniper seeded | 0.171897 s | 0.981601 s | 204 / 204 |
| default, after final convergence | 0.241357 s | 1.093864 s | 204 / 204 |

The exact prompt captures were read end to end:

- [Default](root-page-default-prompt-2026-09-09.txt): **9,111 UTF-8 bytes**,
  11 evaluations, zero evaluation errors after final convergence.
- [Scratch after compaction](root-page-scratch-prompt-2026-09-09.txt):
  8,797 bytes, 11 evaluations, zero evaluation errors, with Juniper seeded.
- [Fresh scratch boot](root-page-scratch-boot-prompt-2026-09-09.txt):
  7,976 bytes, 11 evaluations, zero evaluation errors. This second fresh
  cluster proves the final creation path, including duplicate removal.

All start with `(help)` followed by bare help lines, use agent source
with reader quotes, and include `(seon.cluster.status/snapshot {})`.
The initial scratch boot exposed the duplicate runtime source, which is
why creation now uses the same planner as compaction; the final fresh
boot has one copy. Shown values differ across boot and compaction because
the database, open turns, accounting, and JVM observations differ.

The served root debug page selects identity, plan, settings, and runtime
AI/HTML pairs: **zero generic printer selections**. The settings pair
replaced the two original AI/HTML fallbacks. The cluster and agents
concerns remain their existing declared renderers.

The known [empty-plan result](../../../seon/issues/root-empty-plan-read-shows-nil.md)
still prints nil; default's empty inbox does too. These are empty-query
results from declared renderers, not missing render pairs. No fabricated
plan or message was added to hide them.

Browser paint is unavailable: CUA reports zero browsers and native Chrome
returns `cgWindowNotFound` (-10005). This repeats the existing
[browser observation issue](../../../seon/issues/browser-ui-observation-has-no-accessible-window.md).
Served HTML, HTTP actions, exact model prompt bytes, and new-fork execution
are verified separately; this note makes no screenshot claim.

## Gates and adoption

Workers are capped with `SEON_TEST_WORKERS=3`; no `--all` or `--full`.
The first scoped gate exposed a missing-input defect in the new settings
fixture (caps, time limit, error mode); corrected to supply the production
render request. The corrected settings fast run passed 2 tests / 29
assertions. The final root-creation fast run passed 4 tests / 146 assertions.

The edit hook loaded the new definitions but several adoption attempts
refused because another edit arrived during adoption. One attempt exceeded
the hook's bound. This is a publication boundary, not foreign breakage or
a reason to restart default. The final explicit adoption subsequently
converged, as verified below. Scratch development adoption likewise loaded the
definitions before its source-change refusal; the subsequent new branch
boot independently proves the final creation path.

### Final verification

Scoped gate: **9 tests / 198 assertions**, zero failures/errors; run
`run.NcGDJL` removed itself on success. Platform: **84 tests / 505
assertions**, zero failures/errors; run `run.gracH7` removed itself.
Both used the following six paths with `SEON_TEST_WORKERS=3`:

```text
src/seon/cluster/status.clj
src/seon/turn.clj
resources/seon/schemas/seon.config.edn
test/seon/cluster/status_test.clj
test/seon/loop_proof_test.clj
test/seon/render/page_settings_test.clj
```

Commands: `bin/test --paths <those six paths> -- seon.cluster.status-test
seon.loop-proof-test seon.render.page-settings-test`, then
`bin/test --paths <those six paths> --platform`. The root-creation
regression asserts exact planned versus saved source bytes, one copy of
each source, bare help, presence of the cluster form, and no evaluation
errors. Settings selection runs against a real empty component and
the complete canonical schema population.

Default completed in-place development adoption:
**adopted = current = `6aa22b9f-e649-5323-bef5-f621cb760981`**, verified
by the cluster fact and the source branch head through the same registry
head reader used by `seon.cluster.source/current`. PID remains **23557**.
Final MCP status reports observed health, all three plumbing procs reply,
and no problem counts. No reset was needed or performed on default.

After the test work ended and scratch was downed:

| GET | Seconds |
|---|---:|
| root debug | 0.195671 |
| Juniper debug with `prompt=true` | 0.360570 |
| root prompt, first request | **0.719017** |
| root prompt, four subsequent requests | 0.016398 / 0.015893 / 0.015612 / 0.015830 |
| root prompt, first request after final compaction | **0.959464** |

Thus the uncached request after a changed context is below one second,
and the existing page cache serves subsequent requests in about 16 ms.
The store scan is gone; cache behavior was not changed. The final saved
cluster evaluation takes **71 ms**, versus the owner's earlier 12,654 ms
stored evaluation and this lane's 14.37 s sampled request.

[Final served HTML](root-page-default-debug-2026-09-09.html) and
[timings, renderer selections, and post-change samples](root-page-after-evidence-2026-09-09.json)
are retained. The final page names eight selected renderers (four AI/HTML
pairs), none `seon.render.value/render-*`. Compatible alternatives in a
disclosure are not selected fallbacks.

Prompt SHA-256, over exact file bytes:

```text
default  ebe804db9849a5c68048c0ea593e0eb2348f9c4112fe3714206be853bc418f34
scratch  e0338ccb8583a7892857ccd923ab08241250186453b821cf27c309f1b5c38c64
boot     4150543b37ae62d5adc9a488fe7dd9c32a9aa9b1059864bb1a82b150cd04a3ad
```

Scratch `root-page` was seeded with `seon.context-blocks-fixture/install-running!`
through its actual graph and projection. Both scratch branches were stopped
with their owning operator's `down`; it confirmed process exit and free
store lock. The scratch root and owned failed test root `run.F8J7sO` were
deleted only after their recorded runners exited. Owned command shells
ended. Unrelated untracked files were preserved. No foreign verification
boundary was encountered.

Other touched paths are this note, its linked prompt/HTML/JSON artifacts,
the two reproduction scripts, the archived assigned issue, and the existing
browser-observation and empty-plan issue notes. No production file outside
the six gated paths changed.
