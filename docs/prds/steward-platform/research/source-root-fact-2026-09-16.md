---
type: research
status: active
tags: [research, schema, program-graph, blocked]
---

# Source root as a fact — 2026-09-16 (BLOCKED at a protected file)

## Verdict first

**No code change landed.** The assignment's design — declare the walked
source root on the `seon.fn.file` entity where the indexer mints it — cannot be
written without adding one attribute to `seon.program/shapes` in
`src/seon/program.cljc`, which this assignment protects (reach-closure is
verifying a tuple retraction there) with an explicit instruction to stop and
report. The blocker is verified live, not inferred; the exact edits are below.

Read end to end: AGENTS.md, `tmp/orchestrator/wave2/repl-rule.txt` (including
the BASE CONSTRUCTION and LIVE-PROOF VALIDATION rules), the issue note
[the program graph cannot say which source root a declaration came from](../../../seon/issues/the-program-graph-cannot-say-which-source-root-a-declaration-came-from.md),
and [program-provenance-2026-09-16.md](program-provenance-2026-09-16.md).

## Why the protected file is required

`seon.fn/artifact` (`src/seon/fn.clj:966`) mints the file row and then passes
every row through `seon.program/canonical-row`, which keeps ONLY the attributes
declared in `seon.program/shapes` for that identity family. The
`:seon.fn.file/path` shape owns exactly
`[:seon.fn.file/path :seon.fn.file/digest :seon.schema.admission/source]`
(`src/seon/program.cljc:41`). An attribute outside that vector is silently
dropped at mint time AND never retracted on re-index, because
`exact-replacement-tx` drives replacement from the same owned-attribute list.

Live proof, default JVM (pid 45917), MCP `jvm` mode:

```clojure
(seon.program/canonical-row
 {:seon.fn.file/path "/x/y.clj"
  :seon.fn.file/digest "aaaa…(64)"
  :seon.fn.file/root "src"
  :seon.schema.admission/source :core})
;; => {:seon.fn.file/digest "aaaa…", :seon.fn.file/path "/x/y.clj",
;;     :seon.schema.admission/source :core}
```

`:seon.fn.file/root` is gone. Writing the fact anywhere else would be a second
write path for one entity (AGENTS.md §2.5) and would not be replaced on
re-index — and a regression over it would read absence as health, the project's
named recurring failure class.

Checked for an existing carrier before proposing a new attribute, as the task
asked: `seon.source` stores no per-file entity — `:seon.source/file-digests` is
a `[:map-of :string :seon.source/digest]` VALUE, not rows, and
`:seon.source/roots` is a manifest-level vector.
`:seon.schema.admission/source` is the `:core`/`:agent` admission enum, not a
source root. `:seon.fn.manifest/roots` exists but is per-manifest and stores
canonicalized ABSOLUTE root paths, not a per-file fact. So no existing fact
answers the question.

## The minimal change, ready to land once program.cljc is free

1. `src/seon/program.cljc:45` — add `:seon.fn.file/root` to the
   `:seon.fn.file/path` shape's `:seon.program/owned-attributes`. One vector
   element; pure accretion (an absent value is already removed by
   `canonical-row`, so existing rows without a root remain valid).
2. `resources/seon/schemas/seon.fn.file.edn` — declare
   `:seon.fn.file/root [:string {:min 1}]` and add it as an OPTIONAL key to
   `:seon.fn.file/file` and `:seon.fn.file/artifact`. Optional is required, not
   a hedge: see the out-of-root row measured below.
3. `src/seon/fn.clj` — carry the root through the walk instead of recovering it
   from the path. `source-files` (`:80`) already iterates roots; it should
   return `[root file]` pairs so `build-manifest` (`:1556`) hands `artifact`
   the exact root string it walked (`"src"` / `"test"`, or the literal root a
   caller supplied — `fn_test.clj` passes temp-directory paths). `artifact`
   assocs `:seon.fn.file/root` onto the file row. For the single-file seam,
   `build-artifact` (`:1454`) receives only a path, so it resolves the root by
   canonical DIRECTORY CONTAINMENT against `(rooted-file root)` for each
   declared root — real ancestry of real directories, not a name rule on the
   path string — and omits the key when the file is under no declared root.

Then `[?f :seon.fn/file ?file] [?file :seon.fn.file/root "test"]` answers
"test-only?", and `seon.issue.detect/public-without-doc`
(`src/seon/issue/detect.clj:100`) gains an optional root scope while keeping
its current unscoped behaviour by default (the issue note is explicit that the
honest over-report is deliberate until the fact exists).

## A measured finding that changes the acceptance regression

Default (basis **536871448**) holds **335** `seon.fn.file` entities:

| Path prefix | File entities |
|---|---|
| `src/` | 106 |
| `test/` | 228 |
| neither | 1 |

The one outlier is
`/Users/sean/src/seon/docs/prds/steward-platform/research/reach-closure-live-proof-2026-09-16.clj`
— a lane's probe script, minted by the edit hook's changed-path
`build-artifact` seam, which admits any existing `.clj`/`.cljc` file regardless
of the declared roots. (The prefix split above is a SIZING measurement of
today's population, not the proposed mechanism; the mechanism carries the
walked root and never parses a path.)

Consequence for the assignment's regression (1): "every `seon.fn.file` entity
carries a root" holds for the canonical FIXTURE population (walked from `src`
and `test` only) but is false on `default`, and making it true would require
either refusing out-of-root files at the edit hook or fabricating a root. So
the attribute must be optional, absence must mean "under no declared source
root", and every consumer must join positively on the root value — never
`(not [?file :seon.fn.file/root "test"])`, which would read absence as "src".

`seon.fn/source-roots` is `["src" "test"]` (`src/seon/fn.clj:23`); there is no
`script/` handling in `seon.fn` — `script` appears only in
`seon.cluster/source-roots` (`src/seon/cluster.clj:1482`), which widens the
SOURCE SNAPSHOT roots, not the program-graph walk. The assignment's parenthetical
"plus whatever script/ handling exists" therefore resolves to: none in the
indexer.

## Verification boundary

- Live evidence above is default's JVM through MCP `jvm` mode with
  `(seon.operator/connection "default")`: the `canonical-row` drop and the
  335/106/228/1 census. Default was not stopped, reforked, or reset.
- No production file, schema, test, or detector was edited. No commit, no
  publication, no adoption attempt, no test JVM, no `seon.test/run`, no
  fixture-base construction. Nothing to gate.
- The issue remains open and unfixed.
