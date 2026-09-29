# Test deletion audit (lane test-deletion-audit, 2026-09-28)

Scope: the 25 largest test namespaces by `wc -l` (test-support excluded; it is a helper). Read-only on src/ and test/. HEAD 98d836c57 plus the shared tree diff.

## Read this first: what is proven and what is not

Two evidence levels, never mixed:

- **VERIFIED** = I read the test bodies and confirmed the class, the overlap or the defect. These lines are safe to act on.
- **CANDIDATE** = mechanical detection or a declared bound, NOT yet shown redundant. Each needs a read before deleting. The C candidates are assertion forms to rewrite onto data or delete, not whole tests.

Method: a script (scratchpad, not committed) split each namespace into top-level deftests (700+ tests), resolved every aliased and fully-qualified symbol against src/ definitions, resolved every literal `:seon...` keyword against src/ and resources/, flagged declared long tests from `:seon.test/long-ms`, and counted printed-text assertions (str/includes?, re-find, element-text, pr-str, with-out-str, :seon.render/text) inside `(is ...)` forms, excluding golden-tagged tests, render/value_test (the value printer own tests) and repl_parity_test (declared golden).

## Totals

| Category | VERIFIED lines | CANDIDATE lines |
|---|---|---|
| A retired machinery | 0 | 11 |
| B duplicates | 101 | 19 |
| C printed text / incidental | 31 | 491 |
| D long / over-bound | 0 | 652 |
| **total** | **132** | **1173** |

Test lines in the 25 namespaces: 41209. Caveat: a namespace can be counted in both columns for different tests; C candidates overlap the VERIFIED C items by a few lines.

## Ranked by estimated deletable lines (VERIFIED + CANDIDATE)

| # | Namespace | file lines | deftests | VERIFIED | CANDIDATE (C text / D long / other) | estimate |
|---|---|---|---|---|---|---|
| 1 | cluster/agent_test.clj | 2232 | 27 | 0 | 367 (18 / 349 / 0) | 367 |
| 2 | turn_test.clj | 2443 | 42 | 0 | 143 (34 / 109 / 0) | 143 |
| 3 | fn_test.clj | 3141 | 77 | 9 | 101 (25 / 57 / 19) | 110 |
| 4 | render/web_debug_test.clj | 1205 | 20 | 0 | 93 (93 / 0 / 0) | 93 |
| 5 | db_test.clj | 3046 | 102 | 38 | 53 (17 / 36 / 0) | 91 |
| 6 | cluster/turn_test.clj | 3339 | 48 | 0 | 87 (76 / 0 / 11) | 87 |
| 7 | sci/eval_test.clj | 2571 | 71 | 0 | 81 (51 / 30 / 0) | 81 |
| 8 | render/web_test.clj | 2431 | 62 | 57 | 24 (24 / 0 / 0) | 81 |
| 9 | render/transcript_test.clj | 1497 | 21 | 0 | 78 (7 / 71 / 0) | 78 |
| 10 | ai_test.clj | 1188 | 47 | 28 | 6 (6 / 0 / 0) | 34 |
| 11 | instrument_test.clj | 1793 | 57 | 0 | 32 (32 / 0 / 0) | 32 |
| 12 | loop_proof_test.clj | 920 | 3 | 0 | 32 (32 / 0 / 0) | 32 |
| 13 | schema_test.clj | 1997 | 52 | 0 | 19 (19 / 0 / 0) | 19 |
| 14 | cluster/message_test.clj | 889 | 20 | 0 | 12 (12 / 0 / 0) | 12 |
| 15 | render_simplification_test.clj | 961 | 19 | 0 | 11 (11 / 0 / 0) | 11 |
| 16 | error_test.clj | 1702 | 59 | 0 | 9 (9 / 0 / 0) | 9 |
| 17 | sci/reader_test.clj | 825 | 33 | 0 | 6 (6 / 0 / 0) | 6 |
| 18 | call_preparation_test.clj | 1223 | 30 | 0 | 5 (5 / 0 / 0) | 5 |
| 19 | turn_loop_test.clj | 1163 | 15 | 0 | 5 (5 / 0 / 0) | 5 |
| 20 | contracts_compile_test.clj | 991 | 5 | 0 | 5 (5 / 0 / 0) | 5 |
| 21 | config_test.clj | 879 | 32 | 0 | 3 (3 / 0 / 0) | 3 |
| 22 | program_test.clj | 1435 | 38 | 0 | 1 (1 / 0 / 0) | 1 |
| 23 | render/value_test.clj | 1310 | 51 | 0 | 0 (0 / 0 / 0) | 0 |
| 24 | effect_test.clj | 1030 | 18 | 0 | 0 (0 / 0 / 0) | 0 |
| 25 | repl_parity_test.clj | 998 | 0 | 0 | 0 (0 / 0 / 0) | 0 |

repl_parity_test.clj (998 lines) defines its tests with the `defparity` macro, so it shows 0 deftests; it is a declared golden and is not counted. contracts_compile_test.clj is mostly helper code (5 deftests, 59 lines).

## A. Retired machinery

**Finding: none proven.** The symbol check (every `alias/name` and `seon.x/name` use in the 700+ deftests resolved against src definitions) found zero calls to a deleted function. The four raw hits were prose or data, not calls: `config/default` (a docstring-style reference, config.clj has `default-manifest-path`, `default-decisions`), `seon.fn/new-bare-entity` (a quoted symbol used as fixture data, fn_test.clj:1010), `walk/generic-history-entries` (a comment, transcript_test.clj:1103), `seon.db/transact-applied!` (a comment, cluster/turn_test.clj:2367). Keyword-only misses (about 150 distinct keys) are unreliable: src writes many keys as `::alias/x`, so a literal search misses them; I did not treat them as retired.

CANDIDATES (a retired attribute family asserted absent; guards against resurrection, behavior is live streaming):
- test/seon/cluster/turn_test.clj:2870-2880, inside `streaming-writes-zero-datoms-test` (2 asserts, ~11 lines): asserts the whole `seon.ai.stream` attribute family is gone from the registry. The zero-datoms behavior around it is live; only this assertion tests deleted machinery. Src still has `:seon.ai/partial` and `:seon.turn.loop/stream-channel`, so the streaming tests themselves are not retired.
- test/seon/render/web_test.clj:1630-1655, the `testing` block in `reconnect-is-repaint-wire-test` (same assertion, ~20 lines). Already counted under B because the whole test is a duplicate.
- Out of my 25 but seen: test/seon/ai_stream_fold_test.clj:5 names the same removed `seon.ai.stream` half; worth an audit pass of its own.

## B. Duplicates

VERIFIED (read both bodies):
- **db/q stays under 5 ms** (wall-clock class): db_test.clj:238-271 `carried-queries-stay-within-the-five-millisecond-budget` (34 lines, also prints a map) duplicates db_test.clj:307-319 `handed-family-query-stays-below-five-milliseconds` (13 lines). Keep 307, delete 238: **34 lines**.
- **Reconnect repaints at the current basis**: web_test.clj:1370-1388 `reconnect-is-repaint` (19) is the same behavior as web_test.clj:1599-1655 `reconnect-is-repaint-wire-test` (57); the wire test adds only a dropped in-flight partial, which web_test.clj:1729 `reconnect-mid-stream-is-a-fact-only-repaint` (34) already covers, plus the deleted-family absence assertion (see A). Keep 1370 and 1729, delete 1599: **57 lines**.
- **Truncated stream keeps what arrived**: ai_test.clj:741-766 (26) already asserts `text-received`, the closed/connection-reset cause chain and the truncation schema; ai_test.clj:767-776 `a-stream-that-ends-before-any-text-is-named-truncation-not-bad-json` (10) repeats the chain and zero text for the empty body. Keep 741, delete 767: **10 lines**.

CANDIDATE (names and first lines only, UNVERIFIED):
- fn_test.clj:2300 `every-indexed-file-carries-the-root-the-indexer-walked` (19), 2319 `a-declaration-answers-its-source-root-through-its-file` (14) and 2333 `process-owner-is-a-queryable-program-namespace` (25) all assert the file-to-relative-root join; keep one (2319 states the consumer): **19 lines**.
- sci/eval_test.clj:1330 `a-previously-defined-function-uses-the-current-evaluation-limit`, 1345 `a-base-created-function-uses-the-invoking-threads-arm`, 1308 `an-interpreted-infinite-loop-dies-at-the-limit`, 2075 `an-inherited-arm-keeps-the-governing-deadline`: one class (the time limit stops interpreted loops). Not counted; needs a read to name the survivor.
- loop_proof_test.clj:303 `virtual-loop-end-to-end` is one 619-line deftest with 19 `testing` blocks (opening once, elisions requery, changed reads answer wakes, boot closes durable intent, completion derived from the report...). It is not one behavior per test. Many blocks likely duplicate turn_loop_test and cluster/turn_test; splitting then deleting the duplicates is the largest single B opportunity, but I have not proven any block redundant. Not counted.
- Other large single tests to split, not counted: render/web_debug_test.clj:46 (324 lines, 59 asserts), render/transcript_test.clj:1097 (267, golden), cluster/agent_test.clj:1711 `restamp-recovery-test` (170), cluster/turn_test.clj:753 (142), :2567 (135), :2857 (136).
- Checked and NOT duplicates: cluster/turn_test.clj:1975 and :2038 versus ai_test.clj:628-812 (leaf shape vs persisted attempt facts); error_test.clj:698 and :707 (signature ignores message vs separates schemas); turn_test.clj:928 and :948 (different callers of the same poison fixture).

## C. Printed text, incidental ordering, counts, timing

VERIFIED:
- ai_test.clj:794-811 `a-truncated-stream-never-reports-output-that-never-arrived` (**18 lines**): the test computes `:seon.ai/output-observed?` itself with `(pos? ...)` and then asserts that value is false; it never calls a production derivation. It tests nothing.
- fn_test.clj:193-201 `program-population-store-uses-measured-index-fanout` (**9 lines**): asserts two exact tuning constants (128, 32) that its own comment says were changed once already; not a behavior.
- db_test.clj:1082-1100 `the-stable-walk-costs-less-than-encoding-the-read-result`: the `(< walk encoding)` median-of-5 wall-clock assertion (about **4 lines**, incl. the median helper) is incidental timing; the stability assertions beside it stay.
- db_test.clj:238 and :307 also print a map with `println` (a test report printing a value outside the value printer).

CANDIDATE (mechanical: assertion forms whose subject is rendered or printed text). These are the lines to rewrite against hiccup or the render data, or drop when the data assertion already exists:
| Namespace | text-assertion forms | lines |
|---|---|---|
| render/web_debug_test.clj | 67 | 93 |
| cluster/turn_test.clj | 30 | 76 |
| sci/eval_test.clj | 24 | 51 |
| turn_test.clj | 18 | 34 |
| instrument_test.clj | 19 | 32 |
| loop_proof_test.clj | 19 | 32 |
| fn_test.clj | 10 | 25 |
| render/web_test.clj | 12 | 24 |
| schema_test.clj | 7 | 19 |
| cluster/agent_test.clj | 6 | 18 |
| db_test.clj | 10 | 17 |
| cluster/message_test.clj | 6 | 12 |
| render_simplification_test.clj | 7 | 11 |
| error_test.clj | 6 | 9 |
| render/transcript_test.clj | 7 | 7 |
| ai_test.clj | 3 | 6 |
| sci/reader_test.clj | 3 | 6 |
| call_preparation_test.clj | 3 | 5 |
| turn_loop_test.clj | 2 | 5 |
| contracts_compile_test.clj | 3 | 5 |
| config_test.clj | 2 | 3 |
| program_test.clj | 1 | 1 |

Highest yield by reading the assertions: render/web_debug_test.clj (lines 92-93, 154-157, 192-201, 990-1010, 1090-1178 are `str/includes?` on `element-text` of the page; the law asks for the hiccup data), cluster/turn_test.clj, sci/eval_test.clj (2244 `both-entrances-classify-one-failure-identically` has 6 text asserts).
Exact-count assertions (`(is (= N (count ...`, `(is (< N`) are not counted above; the mechanical count is about 240 forms across the 25 files, one to two lines each. Not verified as incidental.

## D. Long or over-bound tests

Only tests with a declared `:seon.test/long` in these 25 namespaces; none is proven covered by a smaller test, so all are CANDIDATES. The CLAUDE.md law: over 10 s needs owner authorization and an issue.
| Namespace:line | test | lines | bound / evidence |
|---|---|---|---|
| fn_test.clj:1522 | fresh-population-flattens-with-its-supplied-declarations | 57 | 180000 ms bound |
| turn_test.clj:1139 | settlement-keeps-unresolved-call-and-require-names-as-values | 109 | 60000 ms bound; measured 20.54 s per its own note |
| cluster/agent_test.clj:897 | disarm-interrupts-an-in-flight-turn-and-records-it-interrupted | 80 | 12000 ms |
| cluster/agent_test.clj:977 | disarm-has-a-declared-loud-turn-completion-backstop | 97 | 12000 ms; sibling turn-start-has-the-same-declared-loud-completion-backstop (1232, 37 lines, not long) may cover it, UNVERIFIED |
| cluster/agent_test.clj:1074 | release-joins-every-owned-graph-before-it-closes-the-branch | 55 | 15000 ms |
| cluster/agent_test.clj:1129 | concurrent-arm-and-release-never-deadlock | 48 | 15000 ms |
| cluster/agent_test.clj:2165 | every-run-reaches-one-terminal-and-the-next-run-works | 69 | 20000 ms |
| sci/eval_test.clj:2459 | a-caller-named-before-its-callee-is-acquired-with-its-namespace | 30 | 8000 ms bound, own note says acquisition measures 1-1.7 s |
| db_test.clj:2634 | system-turn-renders-a-changed-read-with-value-diff | 36 | 10000 ms bound; measured 7849 ms 2026-09-22 |
| render/transcript_test.clj:900 | every-generated-history-is-ordered-and-total | 71 | 66.666 s measured, 40 generated histories, no bound declared |

Notes: fn_test.clj:1522 declares 180000 ms (the largest bound seen). transcript_test.clj:900 measured 66.666 s for 40 generated histories; its ordering and totality claims overlap the small tests at transcript_test.clj:527 (`same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order`) and :578 (`supersession-chains-vanish-from-the-history`), but I did not compare properties. Lowering the generated count is the cheaper first step than deletion.

## Other findings (outside the four categories)

- Law violations by text search in the 25 files (comments included, not separated): `with-redefs` about 105 occurrences (cluster/turn_test 50, fn_test 10, db_test 6, agent_test 6, turn_loop_test 6, sci/eval_test 5, render_simplification_test 5, config_test 4); `alter-var-root` 14 (sci/eval_test 6, instrument_test 4, agent_test 2, repl_parity_test 2). CLAUDE.md forbids both in tests. Each is a rewrite-or-delete candidate that the counts above do not include. Example: turn_test.clj:964 redefines `db/q`.
