# Lane fix-analysis-key-omits-analyzer (2026-09-24)

Stored program rows are derived from two inputs: the file's bytes and the
analyzer that was loaded when the file was published. Publication keys the rows
only by the bytes (`:seon.fn.file/digest`). The honest fix needs one fact on
the publication seal, and that fact lives in two files this lane does not own.
So this lane stopped before editing and reports three priced options. No
production code changed.

## Still broken

- On default all 11 rows from lane-fix-env-macro-callers still hold
  `:seon.fn/host-bound? false`, including `seon.cluster/serve!`. They have not
  converged.
- **The obvious fix is refuted.** The obvious fix is to classify the
  publication as `:all` when an analyzer file changes, the same way `.clj-kondo`
  changes do (`source/classify-paths`, `source.clj:203`). It would reanalyze
  every file with the old analyzer. In `refresh-source!`, `full-source-refresh!`
  (analysis, `cluster.clj:2956`) runs before `development-source-refresh!` or
  `save-gate!` (reload, `cluster.clj:2959-2966`). So when `fn.clj` changes, its
  own publication is analyzed by the code that was loaded before the change. The
  new analyzer is in effect only from the next publication on, and nothing
  records that the stored rows came from the old one.

## Answers (REPL on default, pid 99265)

1. **What each row carries, and what selects reanalysis.** Each row carries
   `:seon.program/analyzed-source-digest`. It is the SHA-256 of the file's text
   (`fn.clj:114-119`, `:544`, `:578`). It does not work as a key:
   - Incremental population drops rows that differ only in that digest
     (`fn.clj:2952`). An unchanged declaration therefore keeps its old digest.
   - The schema describes the digest as the fact that analysis was done, not as
     a key.

   Reanalysis is selected in `full-source-refresh!`: a file is reanalyzed when
   its stored `:seon.fn.file/digest` differs from its observed byte digest
   (`cluster.clj:2019-2022`). All files are reanalyzed only for `.clj-kondo`
   changes (`source.clj:203`).
2. **What the derived facts read.** The rows depend on:
   - the source bytes;
   - the namespace context;
   - the clj-kondo analysis. Its version is fixed by `deps.edn`, and a change
     there already refuses with a restart;
   - the loaded analyzer code;
   - the `:macro` metadata of loaded Vars (`host-macro?`).

   The analyzer is the first-party reach of `seon.fn/analyze-rows` through the
   stored `:seon.fn/calls` edges: 338 declarations in 12 namespaces (`seon.fn`
   69, `seon.schema` 86, `seon.db` 80, `seon.fn.analyzer` 23, …). Walking that
   reach takes 2 ms, and the query plus walk about 20 ms.
   - Armed profiling cells are not a complete source for the loaded identity:
     176 of the 338 have no cell digest, because they are uncontracted.
   - Every cell that does exist equals default's row `:seon.program/definition-digest`.
   - So the loaded identity is the stored definition digests at the adopted
     commit. The adoption record already names that commit: rows, then load,
     then arm, then record (`cluster.clj:2641`).
3. **Smallest honest fix.** See option A below.

## Options (priced; A recommended)

- **A (recommended). Record the analyzer identity on the seal.**
  - Define `:seon.source/analyzer-digest` as the `id/digest` of the sorted map
    `{sym → :seon.program/definition-digest}` over the reach of `analyze-rows`,
    read from the loaded (adopted) commit's database.
  - `full-source-refresh!` compares it with the published seal's value. If they
    differ, it classifies the publication as `:all`. This check runs before the
    unchanged-inputs shortcut at `cluster.clj:2044`, so it also fires when no
    file changed.
  - `publish!` writes the value beside `:seon.source/test-input-digest`.
  - Guarantee: a changed analyzer reanalyzes every file exactly once, on the
    first publication after its reload. An unchanged analyzer reanalyzes
    nothing and adds about 20 ms per publication. The cost is that the change
    crosses owners: `resources/seon/schemas/seon.source.edn` (+2 lines),
    `src/seon/cluster/source.clj` `publish!` (+3 lines), `cluster.clj` (+~25),
    and a regression (+~30). About 60 lines in total.
- **B. Fold the analyzer digest into `:seon.source/digest`.**
  - This needs no new attribute and stays inside `cluster.clj`. The seal
    digest is used as the program identity by tests, the web page and
    artifacts.
  - To detect a change, the prior seal must be recomputed from the stored
    inputs. A mismatch in the input inventory would then look like an analyzer
    change and trigger a full reanalysis on every publication.
  - The cost is that the seal no longer means "digest of inputs", and the
    comparison is fragile.
- **C. Republish everything after an adoption that reloads an analyzer
  namespace** (inside `development-source-refresh!`).
  - It stays inside `cluster.clj`, but it is not durable. A JVM restart between
    reload and republication loses the obligation, and the rows are stale again
    with no fact saying so. This is the kind of silent staleness the laws
    forbid.

## Residual gaps (under any option)

- `host-macro?` reads loaded Var `:macro` metadata. A first-party macro that
  changes whether it is a macro does not reanalyze its callers in other files.
  That is a dependent-file key gap, and it is separate from the analyzer key.

## Evidence and limits

- Probes were read-only JVM evaluations in session `fix-analysis-key`. No
  branches were created and no writes were made.
- I did **not** measure a full reanalysis publication. It would rewrite the
  shared `current-src` under the other lanes' hook publications. Lane
  fix-env-macro-callers measured the whole-program analysis alone at 5.7 s.
  The profile shows `seon.cluster/refresh-source!` at up to 132 s (not
  attributed here).
- There is no before/after timing for unchanged publications, because no code
  changed.
- Net lines: 0 source and 0 test; this note is the only change. RESET NEEDED: no.
