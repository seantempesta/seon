# file-reader: Seon's own files read by Clojure's readers (2026-10-07)

Assignment: agent-loop spec §5 P0, "switch our JVM-side file reading from edamame to Clojure's reader". Evidence:
`docs/research/agent-platform/2026-10-07/contract-check/contract-check-notes.md`; task
`seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing`.

Loop step served: the edit hook's contract check (`seon.contracts-compile-test/check`, run by `bin/seon-hook` on every
file edit) and task ingestion (`seon.task/tasks-tx`, publication). Owner: Clojure's own `LispReader`/`EdnReader`,
already in the JVM. Deleted: edamame from both namespaces.

## Inventory of direct edamame uses (rg edamame src test)

| Site | Reads | Decision |
|---|---|---|
| `seon.task/file-declarations` | task EDN files (`resources/seon/tasks/*.edn`) | SWITCHED to `clojure.edn/read` |
| `seon.contracts-compile-test/read-source` | our `.clj`/`.cljc` sources (hook and corpus census) | SWITCHED to `clojure.core/read` (LispReader) |
| `seon.sci.forgive` | agent text, structural trial reads | kept: agent code, SCI's reader family |
| `seon.sci.reader` | agent text (edamame parser-data in reader errors) | kept: agent code |
| `seon.sci.eval/namespace-source` | one stored `ns` form of an agent namespace (`:seon.ns/source`), uses edamame end positions | kept: agent namespace text, one small form; file held by lane s5a-reply |
| `test/seon/repl_parity_test.clj`, `test/seon/sci/reader_test.clj` | comment / SCI reader error data | not a reader of our files |

## Reader choice, measured (throwaway JVM, 475 src/test files, 10.08 M chars, one parse each, warm)

| Reader | ms | allocated |
|---|---|---|
| edamame (`:all`, `:auto-resolve-ns`) | 417 | 1,030 MB |
| `clojure.tools.reader` indexing reader 1.6.0 | 383 | 1,229 MB |
| `clojure.core/read` (LispReader) on a `LineNumberingPushbackReader` | 187 | 313 MB |

tools.reader (transitive via core.async) allocates more than edamame, so LispReader it is. LispReader gives `:line`
and `:column` on lists only (no end positions, nothing on symbols), resolves `::k`/`::alias/k` through
`*reader-resolver*` (`reference-code/clojure` 3bc2b3e9, `LispReader.java:430-445` keywords, `:687-700` namespaced
maps, `:1043-1065` syntax-quote), and refuses `#=` under `*read-eval*` false.

## Changes, labelled

- `read-source`: LispReader, `:read-cond :allow`, features `#{:clj}`, `*read-eval*` false, `*data-readers*` `{}` and
  `*default-data-reader-fn*` `tagged-literal`, a `LispReader$Resolver` per top-level form whose `currentNS` is the
  text's `ns` name (`user` before it) and whose aliases are the `ns` form's `:as`/`:as-alias` requires
  (`require-aliases`, moved up and extended to `:as-alias`). The `ns` form carries `:end-line`/`:end-column` from the
  reader's position after it. EQUIVALENT on the corpus: every one of 475 files reads the same number of forms with
  no read error, identical `ns` end positions, and forms equal except the three classes below
  (`scratchpad/file-reader/equiv.clj`).
  - BEHAVIOR CHANGE (harmless): `#inst`/`#uuid` read as `Date`/`UUID` (Clojure's built-in `default-data-readers`
    cannot be unbound) where edamame gave tagged literals. A contract's stored form goes through `pr-str` +
    `edn/read-string` either way, which yields the same value.
  - BEHAVIOR CHANGE: syntax-quote expands as Clojure expands it (`seq`/`concat`/`apply vector`) instead of edamame's
    (`sequence`/`vec`). Consistent between baseline and edit; no throw site or reference lives inside either expansion.
  - BEHAVIOR CHANGE, regression added: `#()` parameters read as `p1__N#` (fresh per read) where edamame gave `%1`.
    `same-definition?` now compares reader-generated symbols (`#()` parameters and `x#` gensyms) by the name they
    were generated from (`generated-symbol?`). This also fixes a pre-existing defect: any defn holding a syntax-quote
    gensym read as changed on every edit under edamame. Row `with-generated` in
    `a-new-or-changed-function-needs-a-complete-contract`.
  - BEHAVIOR CHANGE: a symbol reference site in `form-sites` (an uncontained-throw finding's `:row`/`:col` when the
    first escaping entry is a call of a throwing helper) reports the position of the list holding the symbol, since
    LispReader puts no position on symbols. Throw sites (lists) are unchanged.
  - Location keys renamed in the file: edamame `:row`/`:col`/`:end-row`/`:end-col` → `:line`/`:column`/
    `:end-line`/`:end-column`. Finding output keys (`:row`/`:col`, clj-kondo shape) unchanged.
- `same-definition?` first tries `=` and canonicalizes only when that fails (regex or generated symbols): one
  postwalk per side, as before, only for the forms that need it.
- `namespace-contexts` analyses (clj-kondo) only texts whose declared contracts name a symbol: a contract with no
  symbol is unchanged by `seon.fn/qualify-schema-symbols` whatever the context. EQUIVALENT (findings identical: the
  corpus check returns zero findings before and after; the per-text context is read only by that qualification).
- `seon.task/edn-forms`: `clojure.edn/read` with an `:eof` sentinel over a `LineNumberingPushbackReader`, so every read
  failure arrives as `clojure.lang.EdnReader$ReaderException` (`EdnReader.java:173-181`), which the existing
  `:unreadable-file` refusal now catches instead of `ExceptionInfo`. EQUIVALENT for task files (EDN, `#:ns{}` maps,
  symbols, sets, strings); `#=` and `[{` still refuse `:unreadable-file`
  (`seon.task-ingestion-test/malformed-task-files-refuse-by-name-without-writing`).

## Numbers (same throwaway JVM per pair, getThreadAllocatedBytes on the calling thread, warm medians)

| Probe | before | after |
|---|---|---|
| whole-corpus `check` (475 files, 2,936 contracts) | 1,290-1,748 ms, 1,709-1,733 MB | 395 ms, 631 MB |
| of which read-texts | ~1,040 MB (contract-check notes) | 229 ms, 330 MB |
| of which clj-kondo `namespace-contexts` | 491 ms, 463 MB | 128 ms, 105 MB |
| one task file ingestion read (`seon.task.edn`, `file-declarations`) | 0.94-2.2 ms, 304 KB | 0.28 ms, 112 KB |
| one changed-file hook check, `src/seon/task.clj` | 24-26 ms, 27.8 MB | 23 ms, 18.1 MB |
| one changed-file hook check, `src/seon/sci/eval.clj` | 61-69 ms, 137 MB | 37-40 ms, 34.7 MB |

Linear: the read is about 33 bytes allocated per source character, most of it the retained forms and their
position maps. Armed calls: none (throwaway JVM, test namespace not armed).

## Design check

- The design held against the code: `read-source` was already the single reader for every caller in the file
  (`read-texts`, baselines, `transaction-functions`, `checkout-defines?`), so one function changed.
- Awkward: LispReader has no end positions and no symbol positions. End position comes from the reader after the
  `ns` form (the only end position used); symbol positions come from the enclosing list (documented change above).
- Awkward: `#()` gensyms made unchanged functions compare as changed; canonicalization by generated-from name is a
  string test (`str/includes?`/`ends-with?`), no regex.
- The first canonicalization (number by first appearance with `tree-seq` + `distinct` + `postwalk`) allocated
  more than edamame's whole hook check gain (task.clj hook 27.8 → 39.9 MB); replaced by `=` first plus one postwalk.
- Not done: tools.reader is not added; it allocates more than edamame.

## Verification

- `seon.contracts-compile-test`: 7 tests, 42 assertions, 0 failures, 3.9 s (throwaway JVM), including the
  whole-corpus `every-declared-contract-compiles-against-the-packaged-projection` and the new reader table
  `source-reads-as-the-clojure-reader-reads-it`.
- clj-kondo `--cache false` on both files: 0 errors, 17 warnings, the same 17 as HEAD.
- Commit check: see the commit (`bin/lane commit` with `--test seon.contracts-compile-test seon.task-ingestion-test`).
- Default runs a committed archive: `seon.task` changes reach it at root's next `start --head`. No reset needed.
