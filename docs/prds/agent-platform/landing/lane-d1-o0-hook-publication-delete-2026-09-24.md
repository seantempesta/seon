---
type: landing
status: committed-green-pending
lane: d1-o0-hook-publication-delete
created: 2026-09-24
scope: D1 O0. The per-save hook publication is deleted. Saves only lint, and default changes only through root integration.
---
# Lane d1-o0-hook-publication-delete (2026-09-24)

D1 §9 row O0: the per-save hook publication is deleted; program changes reach
default only through root integration (`bin/seon init --dev default --changed ...`),
which this lane did not touch.

## Broken or open

- Nothing known broken. Not run by this lane (LOAD RULE): the JVM test request below.
- Added lines are 44, over the ~30 target: the 32 test lines replace the retired
  publication-terminal test (52 lines) with the save-never-publishes regression.
- `derived-write-feedback` still computes `:seon.hook/paths`, which no caller reads now;
  it and the session-digest scan leave in O6, per the plan.
- `:current-source {:root :cluster}` stays in `.claude/seon-hook.edn`: schema admission
  and contract lint still read it (`bin/seon-hook` 497, 596, 598) until B1 converts them.

## Change

- `bin/seon-hook`: deleted `beneath?`, `source-index-path?`, `publish-source-paths` and
  `current-source-feedback` (the plan's 1615–1705 span), plus both callers (the
  pathless branch and the PostToolUse feedback). The header now says the hook never
  publishes. Named-path clj-kondo, Markdown, docstring, schema and contract lint and the
  syntax check are unchanged.
- `.claude/seon-hook.edn`: removed `:enabled`, `:check-tests` and `:timeout-seconds`
  under `:current-source`, the publication timeout bound, and the paused/resumed
  history. What remains is a one-line note that publication happens only through root
  integration (D1). Checked with `bb` `edn/read-string`, and `echo '{}' | bb bin/seon-hook`
  printed `{"continue":true}` with exit 0.
- `test/seon/dev/hook_test.clj`: `named-and-pathless-saves-never-publish` replaces
  `one-request-returns-terminal-evidence-and-pause-sends-nothing`. It runs PostToolUse
  saves for a named source file (`src/seon/id.clj`), a schema resource
  (`resources/seon/schemas/error.edn`), a host-bound path (`src/seon/cluster/boot.clj`)
  and a pathless `Bash` save. The test replaces every route to default
  (`operator/advertisement`, `prepl-value!`, `live-root-value!`) with a function that
  records the call. It asserts that no call was recorded and that
  `current-source-feedback` no longer resolves.

## Evidence

- Current hook, bb probe (the test's form run directly): `:calls []`, `:publisher nil`,
  4 replies, 383 ms wall time for all four saves including clj-kondo. Save
  publication time is 0 ms, because no request is made.
- Falsified against the pre-change hook (copy at `$scratchpad/seon-hook.before`) with
  `:current-source :enabled true`: `:calls [:advertisement :advertisement :advertisement]`.
  All three named saves (source, schema and host-bound) tried to publish, and
  `:publisher #'user/current-source-feedback`.
- `clj-kondo --lint bin/seon-hook test/seon/dev/hook_test.clj`: 0 errors. It gives
  1 warning, the `future` in the unchanged `output-does-not-extend-the-hook-terminal-bound`.

## Lines

| path | added | deleted |
|---|---:|---:|
| bin/seon-hook | 5 | 101 |
| .claude/seon-hook.edn | 7 | 34 |
| test/seon/dev/hook_test.clj | 32 | 52 |
| **total** | **44** | **187** (net −143) |

## Root runs

    bin/test-check default --ns seon.dev.hook-test
