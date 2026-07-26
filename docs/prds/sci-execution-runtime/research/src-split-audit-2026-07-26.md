---
type: research
status: complete
tags: [research, runtime, testing, architecture]
---

# Source and test tree split audit — 2026-07-26

## Result

R0 can be one atomic move, but it is a **source-and-test split**, not only a
source split. Current authority at `070fe7134` says:

- `src/` becomes `src-old/`, while fresh `src/` contains only
  `src/seon/cluster/`; and
- in the same commit, `test/` becomes `test-old/`, while fresh `test/`
  contains only `test/seon/cluster/`.

The pre-move tree has 175 files under `src/`, of which one is in the nucleus
(`src/seon/cluster/run.cljc`), and 160 files under `test/`, of which one is in
the nucleus (`test/seon/cluster/run_test.clj`). These counts come from
`find src -type f`, `find src/seon/cluster -type f`, `find test -type f`, and
`find test/seon/cluster -type f`; they are not inferred from namespace names.

The operational edit is wider than `deps.edn`: build fingerprints, artifact
digests, Docker inputs, Tailwind discovery, source/test indexers, changed-test
classification, release staging, the MCP and Oracle classpaths, and active
instructions all carry root literals.

In the tables below:

- **ADD** means retain the existing root and add its `-old` peer, always with
  the fresh root first.
- **REPLACE** means the referenced State A file moves physically, so its path
  becomes `src-old/...` or `test-old/...`.
- **LEAVE** means the literal is a downstream project's own root, a dependency
  name, generated output such as `out/test`, or historical evidence rather
  than a live checkout consumer.

## Evidence method and scope

The audit used tracked checkout evidence rather than remembered behavior:

```sh
rg -n --hidden --glob '!.git/**' --glob '!reference-code/**' \
  --glob '!.clj-kondo/.cache/**' \
  'src-old|src/seon|src/my|(^|[^[:alnum:]_-])src(/|[\"` ]|$)' .

rg -n --hidden --glob '!.git/**' --glob '!reference-code/**' \
  --glob '!.clj-kondo/.cache/**' \
  'test-old|(^|[^[:alnum:]_-])test(/|[\"` ]|$)' .

rg -n 'source-paths|test-paths|replace-paths|extra-paths|COPY src|COPY test|files-below root \"src\"|io/file root \"src\"|io/file root \"test\"' .

```

Every match was read in its owning function or paragraph. The inventory below
covers executable configuration, scripts, source that opens/scans a checkout
path, tests that exercise those mechanisms, and current instructions that tell
a maintainer which live file to edit. It deliberately does not rewrite:

- `reference-code/**`, whose `src` roots belong to dependencies;
- generated caches and outputs (`.clj-kondo/.cache/**`, `.cpcache/**`,
  `.shadow-cljs/**`, `out/test/**`);
- dated research, archived architecture, old eval transcripts, patches, and
  issue evidence whose old path is part of the recorded fact; or
- another project's own conventional roots under `acme/`,
  `src-inspect-ai/`, `src-needle/`, `src-diffusion/`, and examples.

The last category is still inspected below where a downstream program reaches
back into Seon's root tree.

## Source-root consumers: configuration, build, packaging, and runtime

| Evidence | Required atomic edit |
|---|---|
| `deps.edn:1` | **ADD** `"src-old"` to top-level `:paths`, after `"src"`. |
| `deps.edn:19` | **ADD** `"src-old"` to `:writer :replace-paths`, after `"src"`. |
| `deps.edn:98-100` | **REPLACE** the single-root lint examples with `bin/lint` examples or commands naming both source roots. |
| `bb.edn:1` | **ADD** `"src-old"` and `test-old`; use `["script" "src" "src-old" "test" "test-old"]`. |
| `shadow-cljs.edn:36,38,40` | **ADD** both old roots to the explanatory comment and `:source-paths`: `["src" "src-old" "test" "test-old"]`. The file says deps mode ignores this vector, but keeping a false declaration is still configuration drift. |
| `.lsp/config.edn:8` | **ADD** `"src-old"` and `"test-old"` to `:source-paths`. |
| `.clj-kondo/config.edn` | **LEAVE**: `rg` finds no source/test root literal in maintained config. `.clj-kondo/.cache/**` contains generated absolute paths and is not an atomic-commit input. |
| `.mcp.json`; `.codex/config.toml` | **LEAVE**: neither contains a root literal. Both reach the source through `bin/mcp-server-cljs`, so the actual edit is `script/seon/dev/mcp.clj:48` below. |
| `tests.edn:3,8` | **ADD** `"test-old"` to both Kaocha `:test-paths` vectors even though the maintained gates use the repository runners; otherwise this checked-in runner silently sees only the nucleus. |
| `.dockerignore:13-15` | **ADD** allow rules for `src-old/` and `test-old/`; update the comment to name both source and test pairs. |
| `docker/Dockerfile:118-122` | **ADD** `COPY src-old src-old` and `COPY test-old test-old` beside the existing copies. |
| `docker/seon-entrypoint:17` | **ADD** `src-old/`, `test/`, and `test-old/` to the immutable runtime-tree layout comment. |
| `build.clj:147-149` | **ADD** `"src-old"` to the POM source dirs and jar copy dirs, with `"src"` first. |
| `build.clj:204-208` | **ADD** `"src-old"` to the writer jar copy dirs and update “Seon src” to “Seon source roots”. |
| `resources/public/css/input.css:5,13-15` | **ADD** the three corresponding `../../../src-old/**/*.{clj,cljs,cljc}` globs and update the “only place” comment. `source(none)` at line 1 makes omission silent. |
| `script/seon/dev/artifact.clj:387-404` | **ADD** `"src-old"` and `"test-old"` to `common-source-input-paths`, so either quarry edit invalidates the artifact. |
| `script/seon/dev/artifact.clj:1030-1040` | **ADD** `"src-old"` to the writer input digest. |
| `script/seon/dev/artifact.clj:1261` | **ADD** `"src-old"` and `"test-old"` to `runtime-root-links`. |
| `script/seon/dev/release.clj:486-490` | **ADD** `"src-old"` and `"test-old"` to `sdk-source-paths`. |
| `script/seon/dev/release.clj:492-494` | **LEAVE**: `datahike-sdk-paths` names Datahike's own `src` and `src-secondary`, not Seon's roots. |
| `script/seon/dev/release.clj:964-970` | **ADD** `(fs/path root "src-old")` to operator staging. Preserve explicit order and reject duplicate relative targets rather than accepting last-copy-wins. |
| `script/seon/dev/mcp.clj:45-48` | **ADD** `/src-old` to the Babashka classpath; the required `seon.dev.runtime-id` moves there. Keep `/src` too for nucleus/adopted code. |
| `bin/test-cljs:258-269` | **ADD** `src-old` and `test-old` to the content fingerprint. The `SEON_EXTRA_SRC/src` and `/test` entries are the downstream project's own roots and stay unchanged. |
| `bin/lint:5-7,20,58-61` | **ADD** both old roots to `DEFAULT_PATHS` and update examples/help. Default should be `src src-old test test-old`. |
| `bin/oracle-server:11-12,27,39,47-50` | **ADD** `src-old` to the Babashka classpath and update comments. The parser/validator namespaces move to the quarry; `/src` remains for later adoption. |
| `bin/plan-state:60,66,74-76,82,85,98,103-104,108,110,122,127,130,165-177,190,210-211,359-370` | **REPLACE** exact State A file paths with `src-old/...`. |
| `bin/plan-state:146,152-153,168-169,188-189,192-198,211,316-317,332,335,338,346,374,378-379` | **REPLACE** broad `src` scans with `src-old`. These sections measure the old execution system, pod, schemas, and deletion targets; adding fresh `src` would blend the nucleus into State A and falsify the ledger. If a future row measures the nucleus, give it a separate explicit `src/` command. |

`script/seon/dev/artifact.clj:796,826` and
`test/seon/dev/artifact_test.clj:563,751` use the dependency symbol
`seon.extra/src`. That is a dependency coordinate, not Seon's root path, and
must be **left**.

## Source-root consumers: discovery and classification

| Evidence | Required atomic edit |
|---|---|
| `script/seon/dev/changed_test.clj:234-242` | **ADD** `files-below root "src-old"` to `host-corpus`; test discovery comes through the dual-root `seon.dev.test-roots` edit below. |
| `script/seon/dev/changed_test.clj:325-335` | Make `operator-path?` and `writer-path?` recognize both root families. Old DB/embed and operator tests become `src-old/seon/...` and `test-old/seon/dev/...`; fresh/adopted files may still appear under `src/` and `test/`. |
| `script/seon/dev/changed_test.clj:352,357,675` | Replace the three raw `"src/"` checks with one finite `source-path?` predicate accepting `src/` and `src-old/`. Do not use the lexical prefix `"src"` without the slash. |
| `script/seon/dev/test_roots.clj:61-64,90-114` | Introduce ordered test roots `["test" "test-old"]`; scan both in `test-files`, `operator-test-files`, and `writer-test-files`; exclude `seon/dev` beneath either root from the writer gate. Preserve the cross-root duplicate-namespace rejection at lines 73-84. |
| `script/seon/dev/program_indexer.clj:69-73` | Scan ordered roots `["src" "src-old"]`, concatenate their files, then canonical-sort. |
| `script/seon/dev/program_indexer.clj:75-96,496-511,536-541` | Preserve the project-relative resource path, so quarry rows publish as `src-old/...`; add a duplicate-namespace rejection before `into {}`. Current `namespace-closure` silently overwrites one description when a namespace exists in both trees. |
| `script/seon/dev/program_inventory.clj:11-28` | **ADD** the canonical `src-old` path to `first-party-roots`, but first replace Java `String.startsWith` with path-component-aware `java.nio.file.Path.startsWith`. As written, `/repo/src-old/x` already matches `/repo/src` accidentally. |
| `src/seon/client/indexing.clj:97-107` | After the move this file is `src-old/seon/client/indexing.clj`. Replace its duplicate raw-string prefix check with the same component-aware root predicate used by `program-inventory`; otherwise the fallback keeps the same `src`/`src-old` alias bug. |
| `script/seon/dev/test_artifact.clj:40-55,241-257` | **LEAVE**: it canonicalizes analyzer files and relativizes them against the project root, so it will correctly emit `src-old/...` and `test-old/...`. Its consumers must accept those paths. |

## Source-root consumers inside the moved tree

These files all move to `src-old/...`; their contents also open or teach an
old path and therefore need an edit in the atomic commit.

| Evidence | Required atomic edit |
|---|---|
| `src/seon/dev/docstring.clj:45,47,425,429` | **REPLACE** example old-file paths with `src-old/...`; scan both source roots at line 429 if the lint operation remains intended to cover all live code. |
| `src/seon/dev/markdown.clj:683-685` | **REPLACE** the slurped floor file with `src-old/seon/agent/ctx.cljs`. |
| `src/my/kb.cljc:136` | **REPLACE** the provenance string with `src-old/seon/db/internal.cljs:694`. |
| `src/my/AGENTS.md:1` | **REPLACE** heading with `src-old/my`. |
| `src/seon/AGENTS.md:7,27` | **REPLACE** heading and DB-owner path with `src-old/seon...`. |
| `src/seon/agent/AGENTS.md:7`; `src/seon/ai/AGENTS.md:1`; `src/seon/web/AGENTS.md:7` | **REPLACE** headings with `src-old/...`. |
| `src/seon/render/AGENTS.md:7,44` | **REPLACE** both `src/seon` paths with `src-old/seon`. |

All other nested `src/**/AGENTS.md` authorities move with their directories
but contain no literal root path. Their same-directory `CLAUDE.md` compatibility
links move with them; do not edit those links.

## Test-root verdict and consumers

### Verdict: split `test/` now

R0 at `docs/prds/sci-execution-runtime/plan/README.md:492-507` explicitly
requires the test split in the **same atomic commit**. Delaying it would make
`ls test/` falsely describe the 160-file State A suite as nucleus proof and
would violate the physical port-manifest rule.

The one fresh suite is `test/seon/cluster/run_test.clj`. Every other current
test moves to `test-old/`. The old suite remains classpath-visible and
discoverable; it is not adopted into the nucleus merely because it still runs.

### Test-root configuration and mechanics

The required `test-old` additions in `deps.edn:82,145`, `bb.edn:1`,
`shadow-cljs.edn:38,40`, `.lsp/config.edn:8`, `tests.edn:3,8`,
`.dockerignore:15`, `docker/Dockerfile:121`,
`script/seon/dev/artifact.clj:404,1261`,
`script/seon/dev/release.clj:487`, `bin/test-cljs:265`, and `bin/lint:20`
are atomic blockers. Each retains `test` first and adds `test-old` second.

`package.json:8` (`"test": "test"`) names an npm script, and every `out/test`,
Shadow build `test`, cluster named `"test"`, URL ending `.test`, or
`cljs.test` match is **not** a checkout root and stays unchanged.

### Tests that pin source/test roots

All files in this table move under `test-old/`, except the fresh nucleus test.

| Evidence | Required edit |
|---|---|
| `test/seon/dev/changed_test_test.clj:44-76,137-173,181-206,336-348` | Keep fresh `src`/`test` cases and add mirror cases for `src-old`/`test-old`; make old writer/operator examples use the old roots; assert `potential-shadow-input?`, `root-runtime-path?`, selection, and queued hook paths across both families. |
| `test/seon/dev/artifact_test.clj:119-136,761,852` | Extend digest fixtures to prove an edit beneath `src-old` invalidates writer/common input and add `src-old`/`test-old` to expected runtime roots. Do not change `seon.extra/src`. |
| `test/seon/dev/test_roots_test.clj:17-68` | Build fixtures in both `test` and `test-old`; prove operator/writer/CLJS discovery spans both and that the same namespace in both roots is rejected. Update the “Every test/**” message to name both roots. |
| `test/seon/dev/program_inventory_test.clj:15,21` | Retain the `src` first-party case, add a `src-old` case, and add a sibling-prefix negative case such as `src-older` to kill the raw-string-prefix bug. `reference-code/.../src` at line 30 remains third-party. |
| `test/seon/dev/program_artifact_test.clj:12-14,39-40,66` | **LEAVE** existing fake-project `src` fixtures: this component indexes a supplied project root and the tests prove ordinary one-root downstream behavior. Add dual-root coverage in `program_indexer_test`, not by erasing this valid case. |
| `test/seon/dev/test_artifact_test.clj:10-13,74` | Keep the current analyzer fixture and add rows under `src-old` and `test-old` to prove emitted resource paths retain the root. `out/test` is output and stays. |
| `test/seon/dev/docstring_test.clj:215` | **REPLACE** checked live file with `src-old/seon/dev/docstring.clj`. |
| `test/seon/flow/indexer_test.clj:26` | **REPLACE** the fixture root `(io/file "test" ...)` with `"test-old"`; the test and its `fixtures/` directory move together. |
| `test/seon/dev/hook_cli_test.clj:106` | **REPLACE** the simulated edited State A path with `test-old/seon/dev/runtime_id_test.cljs`. |
| `test/acme/extra_fixture.cljs:5` | **REPLACE** the comment's physical fixture root with `test-old/`; the fixture moves with the old suite. |
| `test/seon/condemned_paths_test.clj:14-131` | **REPLACE** every condemned/baseline State A path with `src-old/...`. |
| `test/seon/condemned_paths_test.clj:172` | Scan both source roots. The fresh scan prevents a condemned require from entering the nucleus; the old scan keeps the deletion gate over the quarry. |
| `test/seon/dev/release_test.clj:351-356` | Add assertions that `sdk-source-paths` includes `src-old` and `test-old`. |
| `test/seon/dev/test_roots_test.clj` and the program-indexer owning tests | Add the two structural regressions: duplicate namespaces cannot exist across fresh/old roots, and both roots are deterministically discovered. |
| `test/seon/cluster/run_test.clj` | **MOVE to fresh `test/`, content unchanged** unless the orchestrator's verification exposes a real import issue. This is the sole nucleus acceptance suite at R0. |

## Current documentation and instruction paths

Historical research and issue evidence retain the path that was true when
recorded. The following maintained, current-facing instructions should change
atomically so the first post-split reader is not sent to a nonexistent file.

| Evidence | Required edit |
|---|---|
| `AGENTS.md:485,489,544,572,665,885,895-896` | **REPLACE** old owner/source references with `src-old/...`. |
| `AGENTS.md:779` | Change the changed-test example to the real nucleus path `src/seon/cluster/run.cljc`. |
| `AGENTS.md:858` | Explain that new nucleus tests live in `test/`, while State A tests live in `test-old/` until explicitly adopted. |
| `README.md:147` | Replace “`src/seon/` is the core” with the physical rule: fresh `src/seon/` is the nucleus and `src-old/seon/` is the State A quarry. |
| `docs/conventions.md:409,781,795,803` | **REPLACE** old DB/source tree examples with `src-old`; rewrite the tree/testing rule to describe fresh/old pairs. |
| `docs/conventions.md:706-707` | **REPLACE** cited State A tests with `test-old/...`. |
| `docs/cljs-dev-loop.md:72,96` | **REPLACE** live pod edit/debug paths with `src-old/...`. Lines 108 and 110 are a dated V0 queue and may remain historical. |
| `docs/seon/pod/REPL-WORKFLOW.md:258` | **REPLACE** the live `.cljs` edit path with `src-old/seon/`. |
| `docs/seon/process-management.md:104-105` | **REPLACE** both State A process-owner paths with `src-old/...`. |
| `docs/seon/reference/driving-codex-agents.md:244,248` | **REPLACE** the live DB-directory example with `src-old/seon/db/`. |
| `docs/seon/reference/linting-setup.md:16,20,23,26,74-81,141,145,149,153,162,225` | Prefer `bin/lint` for all-root examples; where raw tools are taught, explicitly name `src src-old test test-old`. |
| `docs/prds/readme.md:66` | Change the “existing code” example to `src-old/seon/bar.clj`, or make the example neutral and explicitly distinguish fresh code from quarry code. |
| `docs/seon/architecture/agent-runtime.md:23`; `architecture.md:531`; `data-model.md:88`; `decisions/003-ref-type.md:42-43`; `library-grounding.md:16-35` | **REPLACE** citations to currently existing State A owners with `src-old/...`. Do not rewrite dependency `reference-code/**/src` paths or the `src-inspect-ai/` package name. |
| `bench/writer_throughput.clj:4` | **REPLACE** the writer-correctness citation with `test-old/seon/db`; the benchmark is not a test-root consumer otherwise. |

The maintained skill corpus has three byte-mirrored copies:
`.agents/skills/`, `.claude/skills/`, and `seon-skills/`. Apply the same path
edits to all copies; changing only one creates instruction drift.

| Canonical `.agents/skills` evidence | Required mirrored edit |
|---|---|
| `browser-automation/SKILL.md:87-89` | `src-old/seon/web/...`. |
| `clojure-testing/SKILL.md:10-11,178-181` | `test-old/...` for all cited State A tests. |
| `clojurescript/SKILL.md:14,72,186-187` | `src-old/seon/...`. |
| `data-modeling/SKILL.md:95,318-319,326-328` | `src-old/seon/...` and `src-old/my/...`. |
| `datahike/SKILL.md:130-131,173,361-365` | `src-old/...`. |
| `datahike/references/data-modeling.md:10-11`; `datahike-internals.md:38,107,144`; `querying.md:33,66` | `src-old/...`; leave dependency `reference-code/**/src`. |
| `datastar-web-ui/SKILL.md:50,122,189-195`; `references/design-principles.md:76,114-115` | `src-old/seon/...`. |
| `seon-context-config/SKILL.md:14,16-17` | `src-old/seon/...`. |
| `ui-canvas/SKILL.md:12` | `src-old/my/canvas.cljs`. |

## Atomic move recipe

### 1. Freeze and recheck

Pause all source/test editing lanes and require coherent path handoffs. Record
the exact pre-move status; do not discard unrelated edits.

```sh
git status --short
git rev-parse HEAD
find src -type f | sort
find test -type f | sort

```

### 2. Move both trees and return only the nucleus

```sh
git mv src src-old
mkdir -p src/seon
git mv src-old/seon/cluster src/seon/cluster

git mv test test-old
mkdir -p test/seon
git mv test-old/seon/cluster test/seon/cluster

```

The post-move structural falsifier is:

```sh
find src -type f | sort
find test -type f | sort
test "$(find src -type f | wc -l | tr -d ' ')" = 1
test "$(find test -type f | wc -l | tr -d ' ')" = 1
test -f src/seon/cluster/run.cljc
test -f test/seon/cluster/run_test.clj

```

### 3. Apply all atomic configuration and classifier edits

Apply the configuration/build/runtime, discovery/classification, test, and
current-instruction edits catalogued above. Fresh roots precede old roots
where order is explicit, but duplicate namespaces/relative targets are errors;
classpath order is not an adoption mechanism.

Before any build, prove the effective classpaths and root discovery:

```sh
clojure -Spath | tr ':' '\n' | rg '(^|/)(src|src-old)$'
clojure -Spath -M:writer | tr ':' '\n' | rg '(^|/)(src|src-old)$'
clojure -Spath -M:writer-test | tr ':' '\n' | rg '(^|/)(test|test-old)$'
clojure -M:writer-test -e \
  '(require (quote seon.dev.test-roots))
   (prn (seon.dev.test-roots/writer-test-namespaces
         (System/getProperty "user.dir")))'

```

The namespace listing must include `seon.cluster.run-test` and retained
State A writer tests, with no duplicate-namespace exception.

### 4. Run the narrow nucleus gate

```sh
bin/test-writer seon.cluster.run-test

```

### 5. Prove live boot at the reset boundary

Use the operator so it owns all children:

```sh
bin/seon down
bin/seon up
bin/seon status

```

The proof is readiness from current code with no missing namespace, source,
test, artifact, Tailwind-input, or runtime-root error. If an operator-owned
child survives an interrupted proof, use `bin/seon down`; do not kill it
directly.

### 6. Prove changed-test selection on a nucleus path

The move itself is a real change to the nucleus path, so explicitly exercise
the hook with that path:

```sh
bin/seon test changed --path src/seon/cluster/run.cljc

```

Inspect `tmp/test-changed/latest.report.edn`. It must select and pass
`seon.cluster.run-test`; `:no-affected-tests`, an unknown-host-resource
widening, or selection of only `test-old` unrelated suites fails R0.

### 7. Commit atomically

The orchestrator owns the cross-cutting commit. Stage/name the exact move and
edit paths only; never use `git add -A`. Immediately after commit, rerun:

```sh
git status --short
bin/test-writer seon.cluster.run-test
bin/seon test changed --path src/seon/cluster/run.cljc

```

## Silent behavior and ordering risks

### Classpath order can masquerade as adoption

`deps.edn:1,19`, `bb.edn:1`, and `shadow-cljs.edn:40` establish path order.
Fresh roots should appear before old roots for deterministic lookup, but a
namespace present in both is a defect. R0 defines adoption as `git mv`, not
shadowing an old file with a new copy.

### Program indexing currently overwrites duplicate namespaces

`script/seon/dev/program_indexer.clj:69-73` canonical-sorts files, then
`namespace-closure` at lines 92-96 builds `by-namespace` with `into {}`.
Whichever duplicate description appears last silently wins. Later,
lines 500-511 and 536-541 derive rows and the source artifact from those
descriptions. Add a duplicate check before map construction; do not make root
order the verdict.

### Release staging can overwrite by relative path

`script/seon/dev/release.clj:964-970` copies every source root into one
`operator-source` directory using only the path relative to that root. Adding
`src-old` permits two inputs to target the same output. Detect duplicate
relative targets and fail loudly before copying.

### Changed-test classification is prefix-bound

`script/seon/dev/changed_test.clj:325-357,675` recognizes literal `src/` and
`test/seon/dev/`. Meanwhile `test_artifact.clj:40-55,241-257` faithfully emits
the actual relative analyzer path, which becomes `src-old/...` or
`test-old/...`. Without the classifier edits, a real quarry edit becomes
unknown, misses the writer/operator boundary, or fails to seed Shadow.

### Raw string prefix checks alias `src` and `src-old`

`script/seon/dev/program_inventory.clj:24-28` and
`src/seon/client/indexing.clj:103-107` call Java `String.startsWith`. Thus
`/repo/src-old/example.clj` already passes the `/repo/src` test. Adding the old
root without fixing this creates apparently green coverage whose root
classification is false. Use normalized `Path.startsWith`.

### Test discovery must preserve a cross-root uniqueness fence

`script/seon/dev/test_roots.clj:73-84` already rejects duplicate test
namespaces, but lines 90-114 currently scan only `test`. Generalizing the
single discovery owner to both roots preserves the fence. Independent scans
later concatenated by runners would lose it.

### Tailwind is opt-in, not implicit

`resources/public/css/input.css:1,5,13-15` disables automatic source discovery
and enumerates only `src`. Omitting `src-old` does not throw; it silently drops
classes used only by the old web UI.

### Artifact freshness must include both quarries

`script/seon/dev/artifact.clj:387-404,1030-1040`,
`bin/test-cljs:258-269`, and the Docker copy list define different freshness
surfaces. Missing an old root can reuse a stale writer/client artifact even
though the classpath reads changed old code.

### State A metrics must not blend with the nucleus

`bin/plan-state` is full of exact and broad State A probes. After R0, scanning
only `src` reports an almost empty system; scanning both roots blends the new
nucleus with the deletion quarry. Its current rows must point to `src-old`;
new nucleus measures require separately named rows.

### Package-prefix classification is stale evidence, not an R0 path rule

`docs/seon/issues/package-placement-is-a-namespace-prefix-hand-list.md:29-45`
records a former `src/seon/program/plan.cljc` rule. That source file no longer
exists (`test -e` is false), and `rg` finds no live
`str/starts-with? target "seon.packages..."` implementation. The remaining
`src/seon/packages.cljc:105-119` check recognizes the shape of a JavaScript
wrapper namespace; it does not classify a filesystem root. R0 therefore only
changes this file's physical path to `src-old/seon/packages.cljc`; it does not
invent a package-placement edit from stale issue evidence.

## `acme/`, `src-inspect-ai/`, and `src-needle/`

The move commands do not target these trees. Their tracked inventories are
unchanged: `git ls-files` reports 17 files under `acme/`, 69 under
`src-inspect-ai/`, and 48 under `src-needle/`.

### `acme/`: untouched and behaviorally independent

- `acme/deps.edn:1-7` declares ACME's own `src` and `test`; **LEAVE**.
- `acme/src/acme/context.cljs:13,40` and
  `acme/gym/diffusion_gym.bb:8,13` say “zero `src/seon` edits”; these are
  downstream boundary prose, not path discovery. Leave the tree untouched.
- The gym reaches Seon through `bin/oracle-server`; the required compatibility
  change is in that root-owned script, not in ACME.

### `src-inspect-ai/`: physically untouched, with an explicit admission risk

The tree must remain byte-untouched for R0, but three consumers do reach into
Seon's roots:

- `src-inspect-ai/evaluation-sources.lock.json:45-65` admits `src` but not
  `src-old` or `test-old`;
- `src-inspect-ai/tests/test_source_admission.py:126-134` pins that set; and
- `src-inspect-ai/tests/test_canary_guard.py:19-21` scans `src` and `test` but
  not their old peers.

Therefore “untouched” is **not** equivalent to “coverage preserved”: after R0,
Inspect source admission and canary scanning omit the classpath-loaded quarry.
R0 must record this as a downstream follow-up or explicitly waive those
surfaces; changing them would violate the requested untouched-tree boundary.
Documentary citations such as `src-inspect-ai/src/seon_inspect/scorecard.py:71`
also become stale but do not affect admission.

### `src-needle/`: physically untouched, with an explicit sample risk

The package's own `src/seon_needle` and `test` roots remain untouched. Two
scripts reach back into old Seon source:

- `src-needle/src/seon_needle/token_efficiency.py:50-57,77-78` samples fixed
  files below root `src/seon`; after R0 it silently skips those missing files.
- `src-needle/scripts/lora_curate.py:18,61` reads `src/my/plan.cljs`, which
  becomes `src-old/my/plan.cljs`.

R0 leaves the downstream tree unchanged as ordered, so those measurements and
curation are knowingly stale until a downstream follow-up. This is safer than
claiming that a physically untouched package preserves behavior it no longer
observes.

## Exit criteria

R0 is complete only when all of the following are simultaneously true:

- `find src -type f` and `find test -type f` show only the nucleus pair;
- old source and tests remain on every relevant classpath, artifact digest,
  Docker/runtime tree, and maintained discovery surface;
- no duplicate source or test namespace can be hidden by root order;
- `bin/test-writer seon.cluster.run-test` passes;
- `bin/seon up` reaches readiness from the split tree;
- the changed-test hook selects `seon.cluster.run-test` for a nucleus edit;
- active instructions point to the physical post-move paths; and
- the three downstream trees remain untouched, with the two explicitly
  recorded follow-up risks above rather than false coverage claims.
