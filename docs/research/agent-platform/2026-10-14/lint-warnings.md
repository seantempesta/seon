---
title: "Lint warnings inventory: HEAD 0691cb23c"
type: research
---

# Lint warnings inventory, 2026-10-14

Inventory only. No source was edited.

## What was run

- Source: `git archive HEAD .clj-kondo src script test` at HEAD `0691cb23c`, extracted to `tmp/lint-inv/` (own directory, not the working tree).
- Command, as `bin/lane` runs it in `extract_candidate` and `lint_candidate` (line 315): `clj-kondo --lint src script test --cache false --parallel`, from the extract root. `.clj-kondo/config.edn` came from the archive. No shared `.clj-kondo/.cache` or `tmp/orchestrator/lint-cache` was read or written.
- clj-kondo v2026.07.24. Reproduced the lane figure: `linting took 6861ms, errors: 0, warnings: 1665`.
- Per-finding linter types and files came from a second run with `--config '{:output {:format :json}}'`, parsed with Python.
- Recently changed files: `git log -50 --name-only`, union of paths (107 paths).

## Totals

- Warnings: **1665**. Errors: **0**. Info (summary) lines: 14, not counted.
- Warnings in 279 files: `test` 869, `src` 723, `script` 73.
- Stale-analysis signal: **zero** `unresolved-var`, `unresolved-namespace` or `unresolved-symbol` findings. The full lint runs with `--cache false`, so there is no stale cache in these numbers.

## Warnings by linter type

| Type | Count | Files | Class |
|---|---:|---:|---|
| discouraged-var | 742 | 202 | (c) policy style, Seon's own discouraged list |
| shadowed-var | 719 | 126 | (c) style |
| unused-namespace | 79 | 54 | (b) real dead requires (3 of 3 checked) |
| unused-binding | 59 | 36 | (b) real unused bindings |
| unused-private-var | 19 | 14 | (b) real dead private defs |
| redundant-let | 18 | 14 | (c) style |
| type-mismatch | 6 | | kondo local inference (config says it is not an admission proof) |
| duplicate-require | 5 | | (b) or (c) |
| redundant-do | 4 | | (c) |
| unused-value | 4 | | (b) |
| unused-import | 3 | | (b) |
| unused-referred-var | 3 | | (b) |
| single-logical-operand | 2 | | (c) |
| redundant-declare | 1 | | (c) |
| constant-condition | 1 | | (b) or (c) |

The top two types are 1,461 of 1,665 (88%).

### discouraged-var breakdown (742)

Messages by category, from the `:discouraged-var` text:

- "Never print a value outside the ONE value printer": 557
- "A future is a thread nobody joins": 99
- "A promise is a completion without a bound": 41
- "A monitor cannot be timed or interrupted": 17
- "Bare ..." messages: 28

These come from the project's `.clj-kondo/config.edn` discouraged list. They are policy warnings, not dead code. They are not stale analysis.

### shadowed-var breakdown (719)

- `clojure.core/identity`: 124
- `clojure.core/key`: 99
- `clojure.core/name`: 61
- `seon.error/value`: 31
- `clojure.core/symbol`: 19
- `seon.cluster.agent/routing`: 18
- `seon.cluster.source/database`: 17
- `clojure.core/agent`: 15
- `seon.db/entity`: 14
- `clojure.core/ns-name`: 13

Local bindings that shadow core names. Style only, but high volume: it is one rename sweep per name.

## Top 15 files (namespace files)

Each file holds one namespace, so these are the top 15 namespaces.

| Warnings | Namespace file |
|---:|---|
| 74 | src/seon/error.clj |
| 47 | src/seon/cluster/agent.clj |
| 44 | src/seon/cluster/source.clj |
| 41 | script/seon/operator.clj |
| 39 | src/seon/schema.clj |
| 39 | src/seon/sci/eval.clj |
| 39 | test/seon/cluster/boot_test.clj |
| 34 | src/seon/db.clj |
| 31 | src/seon/turn.clj |
| 30 | test/seon/render/web_test.clj |
| 29 | test/seon/cluster/db_flow_test.clj |
| 28 | src/seon/fn.clj |
| 28 | src/seon/program.cljc |
| 27 | test/seon/cluster/turn_test.clj |
| 27 | test/seon/program_test.clj |

## Verbatim examples (3 per top type)

discouraged-var:

- `script/seon/config_load_timing.clj` line 24: "Never print a value outside the ONE value printer (AGENTS.md Presentation): keep it as data (an error data key, a reply value) for the value printer to show; hash, compare and sort the data itself, never printed text."
- `script/seon/cost_acquire.clj` line 22: same message.
- `script/seon/cost_fresh.clj` line 51: same message.

shadowed-var:

- `script/seon/dev/docstring.clj` line 247: "Shadowed var: clojure.core/ns-name"
- `script/seon/operator.clj` line 141: "Shadowed var: clojure.core/identity"
- `script/seon/operator.clj` line 142: "Shadowed var: clojure.core/identity"

unused-namespace:

- `src/seon/cluster.clj` line 29: "namespace seon.fault is required but never used"
- `src/seon/cluster.clj` line 36: "namespace datahike.gc-guard is required but never used"
- `src/my/program.clj` line 19: "namespace seon.render.value is required but never used"

unused-binding:

- `src/seon/cluster.clj` line 1236: "unused binding cluster-name"
- `src/seon/cluster/agent.clj` line 1931: "unused binding handle"
- `src/seon/cluster/boot.clj` line 39: "unused binding config"

## Stale analysis check (3 of unused-namespace, by grep)

Each claimed-unused namespace exists, and its alias has zero uses in its file:

- `seon.fault` (alias `fault`): `src/seon/fault.clj` exists. `grep -c 'fault/' src/seon/cluster.clj` = 0. Real dead require, not stale.
- `datahike.gc-guard` (alias `gc-guard`): the library is used in `src/seon/blob.clj` (lines 336, 343), not in `cluster.clj`. `grep -c 'gc-guard/' src/seon/cluster.clj` = 0. Real dead require.
- `seon.render.value` (alias `render.value`): `src/seon/render/value.clj` exists. `grep -c 'render\.value/' src/my/program.clj` = 0. Real dead require.

So the unused-namespace warnings are not stale analysis. None of the 3 checked was a case of an unresolved var that actually exists. There were no unresolved-var findings to check.

## Classification of the top types

- **(a) stale analysis:** none found. Zero unresolved findings, full lint with no cache.
- **(b) real dead code or unused bindings:** unused-namespace (79), unused-binding (59), unused-private-var (19), unused-value (4), unused-import (3), unused-referred-var (3), duplicate-require (5). Verified 3 of 3 for unused-namespace.
- **(c) style or policy:** discouraged-var (742), shadowed-var (719), redundant-let (18), redundant-do (4), single-logical-operand (2), redundant-declare (1).

Rough split: 1,461 style/policy (88%), about 170 real dead code or unused bindings (10%), 6 kondo type-inference warnings, 0 stale.

## Recently changed files

Union of paths in the last 50 commits (HEAD `0691cb23c`): 107 paths. Of those, 34 hold warnings.

- Warnings in those files: **354 of 1665** (21%).
- By type: shadowed-var 193, discouraged-var 118, unused-namespace 13, unused-binding 12, redundant-let 9, redundant-do 2, unused-value 2, unused-private-var 2.

## Limits

- Counted per namespace file, not per namespace form. Files and namespaces map one to one here.
- Recently-changed count uses HEAD commits only, not the uncommitted working tree.
- Each type's class (b) or (c) is from message text and the verified sample, not a read of every finding.
- `type-mismatch` is kondo's local inference; `.clj-kondo/config.edn` says it is not a sound proof.
