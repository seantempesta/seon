---
type: landing
status: in progress; tests owed to root
lane: delete-duplicates-opus
created: 2026-09-25
---

# Lane delete-duplicates (2026-09-25)

Seon already owns analysis (`seon.fn.analyzer`), test selection and runs (`seon.test/select`, `run`),
schema resource checks (`seon.schema.edn`) and the namespace renderer (`seon.render.ns`). This lane
deletes duplicate paths beside those owners, one slice at a time, converting every caller in the same
commit. Sources: [duplicate-paths audit §11](../../../research/agent-platform/duplicate-paths-audit-2026-09-25.md)
and [script/dev roots audit](../../../research/agent-platform/script-dev-roots-audit-2026-09-25.md).

## Broken / not done

- Tests were not run (LOAD RULE). Root runs the commands at the end of this note.
- Hook fixtures in `test/seon/dev/edit_feedback_test.clj` still write `:changed-tests {:enabled false}`
  into hook configuration; no reader of that key exists. Left in place (harmless residue, not this slice's
  machinery).

## Slice 1: the legacy changed-test/cache-helper family (script audit rank 1; §11 R9 inside it)

- Survivor: `seon.fn.analyzer/analyze-dependencies!` (dependency clj-kondo cache in the loaded JVM),
  `seon.test/select` / `run` via `bin/test-check` (test selection and runs), `seon.cluster.process`
  (subprocess ownership, whose tests stay).
- Deleted files: `script/seon/dev/changed_test.clj` 380, `script/seon/dev/test_roots.clj` 118,
  `script/seon/dev/clj_kondo.clj` 96, `script/seon/dev/state.clj` 69 = **663**.
- Callers converted:
  - `bin/seon-hook` required `seon.dev.clj-kondo` only for the string `clj-kondo-output-config`; the
    constant moves beside the hook's existing `clj-kondo-definition-output-config`.
  - `test/seon/dev/dependency_cache_test.clj` wrote its process-reference fixture with
    `seon.dev.state/write-edn!`; it now writes the same bytes (`pr-str` + newline) the operator's
    `record-cache-reference!` writes.
  - Comment/docstring references in `script/seon/operator.clj` (nuke path list) and
    `script/seon/dev/dependency_digest.clj`.
- Tests of deleted machinery deleted: `test/seon/dev/clj_kondo_test.clj` (40, whole file);
  `changed-paths-are-handed-to-the-one-gate-selector` and `concurrent-tooling-transition-refuses-busy`
  in `test/seon/dev/changed_test_test.clj`; `changed-test-analysis-keeps-the-valid-namespace-graph` in
  `test/seon/dev/edit_feedback_test.clj`. The remaining subprocess-ownership tests of
  `seon.cluster.process` stay; their file moves to `test/seon/cluster/subprocess_test.clj` so the
  namespace no longer names a deleted namespace.

- Also deleted: `seon.dev.dependency-digest/dependency-set-digest` (17 lines), whose only caller was
  `clj_kondo.clj`; `configuration-digest` keeps the declared-set inputs.
- Stale docs corrected in the same commit: `docs/seon/reference/linting-setup.md` (named
  `run-changed!` as current); issue `source-publication-cache-contention-hides-dependency-analysis-failure`
  resolved by deletion (its producer is gone).
- Equivalence evidence (Babashka, repository `bb.edn`, before deletion): `state/write-edn!` and the
  replacement `spit (str (pr-str record) "\n")` wrote byte-identical files and read back `=` records for a
  process-reference record; the hook's moved constant is the same string,
  `"{:output {:format :edn} :analysis {:var-usages true}}"`. After the edit, `load-file "bin/seon-hook"`
  resolves `user/clj-kondo-output-config` to that string, `seon.operator` loads under BB, and
  `configuration-digest` returns a 64-character digest.
- Lint: clj-kondo 0 errors on every touched file (warnings pre-existing).
- Sizes (numstat): script/bin +18/−698 (net −680); tests +4/−133 (net −129); docs +13/−5.

## Slice 2: the completed schema-split migration CLI (script audit rank 3)

- Survivor: `seon.schema.edn/validate-resource-placement!` and the schema resource loader.
- Deleted: `script/seon/schema_split.clj` **141** (whole file). Callers: none executable at HEAD
  (`rg` over src, script, test, bin, hidden config; only its own `-main`).

## Slice 3: namespace unreachable omission helpers (§11 R8)

- Survivor: the floor printer, the one clipper (owner 2026-09-25). `seon.render.ns` renderers already
  include every item: `ai-text` passes `(count items)` and `render-html` passes
  `(count (::functions data))`, so `omitted` is always 0.
- Deleted: `omission-value` 11, `omission-text` 10, the `included-count` parameter/`subvec`/`omitted`
  bindings and the three `(pos? omitted)` branches in `compact-ai-text`, `full-html-view`,
  `compact-html-view`, `html-view`.
