# Lane transcript-redefs (2026-09-23)

`test/seon/render/transcript_test.clj` no longer redefines any of default's
Vars (0 `with-redefs`/`with-redefs-fn`/`alter-var-root`; was 5 sites).
Test lines 1436 -> 1386 (+60 / -110). src unchanged.

## Per site

1. `durable-history-entries-never-invent-executions` redefined private
   `history`, `db/q` and `render/render-call` to feed synthetic candidates.
   Now a fixture: one message, a run with no evaluation, a run with a submitted
   form (no terminal fact) and a settled one (`:seon.eval/shown "3"`), read by
   `transcript/history-entries` over `(unit connection)`. Same three assertions.
2. `stored-evaluations-are-terminal-transcript-values` redefined
   `render/render-call` (throw) and `repl/response` (call counter). Counting
   calls is machinery (README §6 q1); the rendered-text assertions that prove
   shown text is terminal stay unchanged. `seon.repl` require dropped.
3. reasoning disclosure: `blob/get` redefined to return the text. Now the
   reasoning is stored with `blob/put!` and its real digest referenced.
4. `the-history-query-bounds-what-the-transcript-pulls`: `db/pull-many`
   size spy replaced by the declared option — the unit's
   `:seon.config.eval.result/max-nodes` set to 10 admits exactly 10 of 100
   entries; the default caps still render all 100 with no elision.
5. one-grammar test: `sci.eval/evaluate` call counter around
   `turn/evaluate-sources` (machinery; `(count outcomes)` = 6 stays).

## Other test files (one rg, matching lines, not edits)

`rg -c "with-redefs|alter-var-root|with-redefs-fn" test/`: 103 files,
370 matching lines (upper bound: prose mentions count). Largest:
- test/seon/cluster/turn_test.clj:72
- test/seon/render/web_test.clj:19
- test/seon/cluster/agent_test.clj:18
- test/seon/fn_test.clj:16
- test/seon/sci/eval_test.clj:13
- test/seon/ai_test.clj:11
- test/seon/turn_loop_test.clj:10
- test/seon/instrument_test.clj:10
- test/seon/dev/mcp_bridge_test.clj:9
- test/seon/render_simplification_test.clj:8
- test/seon/db_test.clj:8
- test/seon/schema_test.clj:7

## Proof and limits

- clj-kondo on the file: 0 errors.
- `bin/seon init --dev default --changed test/seon/render/transcript_test.clj`:
  loaded; 15,241 ms wall. Over ten seconds: defect. Its profile is dominated
  by concurrent work (`seon.cluster/refresh-source!` x6, 153 s inclusive;
  `full-source-refresh!` x2), not by this one test file.
- `bin/test-check default --policy named --ns seon.render.transcript-test`:
  CHECK UNAVAILABLE twice (76.3 s, 57.7 s). `seon.test/resolve-test` refused
  its own output: install-row returned "Cannot interpret
  seon.cluster.reload-measure/-main: Host-bound declaration … must change
  through the loaded source files", a map outside resolve-test's declared
  union. Cause class: docs/seon/issues/development-adoption-leaves-the-loaded-program-at-the-boot-commit.md
  (context's loaded program pinned at the boot commit). Second defect: the
  `resolve-test` output union lacks the SCI program-refusal error.
- Direct `clojure.test/test-vars` via eval_clj refuses by design
  (`with-database` needs seon.test/run custody).
- So the rewritten tests are LOADED but NOT RUN. Behaviour unverified.
