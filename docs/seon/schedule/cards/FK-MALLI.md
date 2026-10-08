---
title: "FK-MALLI: One Malli fork commit (instrument loop options, public primitive-fn and edit distance)"
type: reference
status: ready
created: 2026-10-08
---

# FK-MALLI: One Malli fork commit

Ledger row FK-MALLI (group E). Owner answers AU-Q2 option 2 and AU-Q5 option 2. Consumers: UL-12 (arming loop),
UL-15 ("did you mean"). Model: Sonnet. Host-bound (a dependency of compiled code): throwaway check for the bump.

## Where the fork lives (verified at Seon `1fcdbf47a`)

- `deps.edn:15`: `metosin/malli {:local/root "reference-code/malli"}`, a **local root** submodule.
- `reference-code/malli` is at `43c6dca8` on branch `seon-ref-scope`, remote `fork`
  (`https://github.com/seantempesta/malli.git`). Commit on `seon-ref-scope`, push to `fork seon-ref-scope`, then bump
  the gitlink in Seon.

## Changes in the fork (all in `src/malli/instrument.clj` and `src/malli/error.cljc`)

1. **Options reach the compile.** `malli.instrument/-strument!` (`instrument.clj:18-41`) builds `dgen` from
   `(select-keys options [:scope :report :gen])` and calls `(m/-instrument dgen f)` with no options
   (`instrument.clj:35`), so a `:registry` never reaches `(schema options)` in `m/-instrument`
   (`core.cljc:3181-3187`). Change: `(m/-instrument dgen f (select-keys options [:registry]))`. Upstream behavior
   without `:registry` is unchanged.
2. **A loop that continues past a failing Var and returns the failures.** ADD
   `malli.instrument/-strument-each!` `[options]`: same arguments as `-strument!`'s 1-arity; for each selected Var it
   runs the same body inside `try`; returns
   `{:instrumented [var …] :failed [{:var var :exception throwable} …]}`. `-strument!` is reimplemented as
   `(-> (-strument-each! options) …)` only if that keeps its return value (a seq of Vars, nils for skipped) and its
   first-failure throw; otherwise leave `-strument!` untouched (upstream compatibility over sharing).
3. **Public primitive-fn predicate.** `-primitive-fn?` (`instrument.clj:15-16`) becomes public `primitive-fn?`
   (keep `-primitive-fn?` as a private alias the file's own calls use, or rename its two uses). Seon's copy is
   `seon.instrument/primitive-fn?` (`src/seon/instrument.clj:65-81`).
4. **Public edit distance.** `malli.error/-levenshtein` (`error.cljc:256-259`) becomes public `levenshtein`
   `[s1 s2] -> int` (strings or sequences, as today); `-similar-key` and `-most-similar-to` call it. Seon's copy is
   `seon.error/edit-distance` (`src/seon/error.clj:1835`).

## Fork tests (`reference-code/malli/test/malli/instrument_test.clj`, `test/malli/error_test.cljc`)

- `-strument!` with `{:registry (mr/composite-registry m/default-registry {::id :int})}` and a Var whose schema is
  `[:=> [:cat ::id] ::id]` instruments, and a call with a string throws `::m/invalid-input`; without the registry it
  fails to compile (proves the option reaches the compile).
- `-strument-each!` over two Vars, the first with an uncompilable schema: returns one `:instrumented` and one
  `:failed` with the Var and the exception.
- `primitive-fn?` true for `(fn ^long [^long x] x)`, false for `inc`'s wrapper-free `fn` and a keyword.
- `levenshtein` laws: `0` for equal strings, `3` for `"kitten"`/`"sitting"`, symmetric.
- Run Malli's own suite: `clojure -M:test -m kaocha.runner` or the fork's documented test command (read
  `reference-code/malli/README.md`/`deps.edn` for the alias); every existing test green.

## Seon side (the bump commit)

- Files: the gitlink `reference-code/malli`. Nothing in Seon calls the new functions in this commit (UL-12, UL-15 do).
- Proof that the bump changes nothing for Seon: `seon.instrument-test`, `seon.contracts-compile-test`,
  `seon.error-test` green.
- `--test seon.instrument-test --test seon.contracts-compile-test --test seon.error-test`
- Commit: `bin/lane commit <lane> -m "FK-MALLI: malli fork passes the registry to instrumentation, continues past a failing Var, publishes primitive-fn? and levenshtein" --test seon.instrument-test --test seon.contracts-compile-test --test seon.error-test reference-code/malli`
- Root restarts default once for the batch of fork bumps (CLAUDE.md "root batches one restart for many landings").

## Cost

None at runtime until UL-12 calls it; UL-12 then deletes about 100 lines (the copied predicate and the loop's
duplicate parts), UL-15 about 15.

## Deleted

Nothing in this row.

## Dependencies

None. UL-12 (after UL-3a, UL-3b4) and UL-15 (after PB-07a, UL-5) wait on this row.
