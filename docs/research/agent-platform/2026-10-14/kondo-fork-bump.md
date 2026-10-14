# clj-kondo fork bump: hooks keep their own SCI context

Lane kondo-fork (root decision, option a of lane writeback-fix, cd130166a).

## Fork commit

- Repository: `reference-code/clj-kondo` (remote `origin` = `git@github.com:seantempesta/clj-kondo.git`, branch `seon`).
- Commit: `eb245d8260736ca463cda85219b6fc3ae73699c3`, pushed to `origin/seon` (57252e07 -> eb245d82).
- Change: `clj-kondo.impl.hooks` holds the hooks' context in a private volatile (`hooks-ctx`) instead of `sci.ctx-store`'s root; `reset-ctx!` fills it; both `eval-string*` call sites use it; `hook-fn` wraps each resolved hook function so it runs with `sci.ctx-store/*ctx*` bound to that context (needed by `resolve`, `requiring-resolve`, `if-kondo` inside hooks). The first patch alone (writeback-fix-kondo-hooks-ctx.patch) made 3 `hooks-test` cases red (issue-2636-requiring-resolve, macro-from-source-resolve-test, macro-from-source-if-kondo-test: "No context found in sci.ctx-store/*ctx*"), so the fork commit adds the binding.
- Fork tests: `clojure -M:test -n clj-kondo.hooks-test` 42 tests / 120 assertions, 0 failures; `-n clj-kondo.main-test` 165 / 1133, 0 failures.

## Seon change (root, inside a restart batch)

The dependency is a `:local/root`, so `deps.edn` does not change:

    clj-kondo/clj-kondo
    {:local/root "reference-code/clj-kondo"

Bump the gitlink: `reference-code/clj-kondo` from `57252e07` to `eb245d82` (the submodule checkout is already on `seon` at `eb245d82`, uncommitted in Seon). Update the clj-kondo row of `docs/seon/architecture/reference-code.md` ("gitlink and `:local/root` `57252e07`" ... "plus 2 commits") to `eb245d82`, 3 commits, and add: hooks run in their own SCI context.

## Tests root names

- the red proof: deftest `an-agent-writes-back-a-file-whose-analysis-runs-a-hook` in `test/my/program_test.clj` (task `my.program/write-branch-panics-with-a-null-pointer-on-a-branch-that-changed-an-existing-file`; patch writeback-fix-hook-red-proof.patch, red run f936d07e1e61)
- `test/seon/fn_test.clj` and `test/seon/fn/` (tests reaching `seon.fn/lint-entities` and the analyzer's `run!` with hooks)
- `test/seon/cluster/source_test.clj`, `source_database_test.clj`, `source_nochange_test.clj` (write-back)
