# no-print-hash — notes (lane no-print-hash, 2026-10-07)

## Step 4 — print-site inventory totals (tmp/no-print-hash/print-sites.tsv)

Scan: src/, script/, test/seon/test_support.clj; patterns pr-str, prn, println, print-str, printf, pr, print, with-out-str, format, subs, clip/bound/truncate/elide names, *print-* vars, id/id, id/digest, id/printed, canonical-data-string, stack traces (getStackTrace, Throwable->map), logs (log/*, *err*, System/err), ex-info with a str message, str over collections, spit. A lexer marks matches inside docstrings/comments as prose. 1,068 rows; root's raw inventory (504 parsed rows) is covered (7 raw rows moved lines with this lane's runner edit or used the bound-text name the raw regex lacked).

Every row is classified individually: rule passes for prose, fixture writes, hex/number formatting, string parsing (subs without a clip marker), random ids and the printer's own cuts; every other row (720) by reading it. Owner rule applied: grows-with-input yes in Seon's own errors, logs, hashes, stored evidence or pages is a violation; legitimate is the value printer itself, storage serialization of data that must be stored, and string work that prints no value. Limits: str over a collection is found only where the str call names vec/set/keys/vals/into/select-keys/seq/mapv/sort on the same line; interpolation through intermediate locals is not traced.

| verdict | class | sites |
|---|---|---|
| legitimate | cli-io | 14 |
| legitimate | code-gen | 24 |
| legitimate | fixture-io | 17 |
| legitimate | fresh-id | 21 |
| legitimate | hex | 3 |
| legitimate | name-match | 115 |
| legitimate | prose | 114 |
| legitimate | scalar-format | 34 |
| legitimate | scalar-message | 147 |
| legitimate | small-identity | 32 |
| legitimate | storage | 45 |
| legitimate | string-parse | 158 |
| legitimate | value-printer | 69 |
| violation | ad-hoc-clip | 2 |
| violation | cli-print | 4 |
| violation | error-text | 56 |
| violation | hash-by-print | 33 |
| violation | log | 30 |
| violation | printed-copy | 39 |
| violation | second-clipper | 36 |
| violation | sort-by-print | 28 |
| violation | stack-trace | 15 |
| violation | ui-print | 32 |

Violations 275 of 1068; legitimate 793.

Violations per file:

| file | violations | all rows |
|---|---|---|
| src/seon/error.clj | 33 | 58 |
| src/seon/render/web.clj | 24 | 58 |
| src/seon/fn.clj | 20 | 37 |
| src/seon/db.clj | 14 | 53 |
| src/seon/test/runner.clj | 14 | 21 |
| src/seon/error/refusal.clj | 13 | 23 |
| src/seon/cluster.clj | 11 | 15 |
| src/seon/schema.clj | 11 | 32 |
| src/seon/turn.clj | 10 | 46 |
| script/seon/operator.clj | 8 | 66 |
| src/seon/program.cljc | 8 | 18 |
| src/seon/sci/eval.clj | 6 | 27 |
| src/seon/cluster/boot.clj | 5 | 5 |
| src/seon/config.clj | 5 | 6 |
| src/seon/edit.clj | 5 | 18 |
| src/seon/health.clj | 5 | 7 |
| src/seon/repl.clj | 5 | 18 |
| src/seon/schema/admission.clj | 4 | 6 |
| src/seon/schema/internal.cljc | 4 | 7 |
| src/seon/test.clj | 4 | 19 |
| test/seon/test_support.clj | 4 | 8 |
| script/seon/dev/dependency_digest.clj | 3 | 8 |
| src/seon/batch.clj | 3 | 5 |
| src/seon/cluster/store.clj | 3 | 8 |
| src/seon/fault.clj | 3 | 5 |
| src/seon/fs.clj | 3 | 14 |
| src/seon/instrument.clj | 3 | 11 |
| src/seon/render.clj | 3 | 8 |
| script/seon/dev/harness_costs.clj | 2 | 3 |
| src/seon/cluster/source.clj | 2 | 7 |
| src/seon/mcp.clj | 2 | 6 |
| src/seon/problems.clj | 2 | 3 |
| src/seon/render/schema.clj | 2 | 3 |
| src/seon/render/transcript.clj | 2 | 43 |
| src/seon/schema/datahike.clj | 2 | 8 |
| src/seon/sci/kernel.clj | 2 | 4 |
| src/seon/test/accretion.clj | 2 | 2 |
| script/seon/operator_outcome_test.clj | 1 | 1 |
| src/my/background.clj | 1 | 1 |
| src/my/program.clj | 1 | 1 |
| src/seon/background.clj | 1 | 1 |
| src/seon/cluster/agent.clj | 1 | 6 |
| src/seon/cluster/message.clj | 1 | 12 |
| src/seon/cluster/prompt.clj | 1 | 5 |
| src/seon/cluster/status.clj | 1 | 1 |
| src/seon/cluster/wake.clj | 1 | 1 |
| src/seon/effect.clj | 1 | 7 |
| src/seon/env.clj | 1 | 3 |
| src/seon/fn/analyzer.clj | 1 | 8 |
| src/seon/reconcile.cljc | 1 | 1 |
| src/seon/render/test.clj | 1 | 1 |
| src/seon/render/value.clj | 1 | 24 |
| src/seon/render/var.clj | 1 | 1 |
| src/seon/render/walk.clj | 1 | 24 |
| src/seon/schema/edn.clj | 1 | 4 |
| src/seon/sci/admit.clj | 1 | 16 |
| src/seon/sci/arm.clj | 1 | 2 |
| src/seon/task.clj | 1 | 6 |
| src/seon/test/cache.clj | 1 | 5 |
| src/seon/turn/batch.clj | 1 | 6 |

Tasks (one per violation class): seon.id identities-hash-structure-not-printed-text (hash-by-print, updated); seon.error error-messages-print-growing-values (error-text), error-admission-is-a-second-clipper (second-clipper); seon.error.refusal errors-capture-whole-stack-traces (stack-trace); seon.fault log-lines-print-values-outside-the-printer (log); seon.print.value printed-copies-stored-or-sent-instead-of-values (printed-copy), ad-hoc-text-clips-outside-the-printer (ad-hoc-clip); seon.render.web pages-print-values-instead-of-the-printer-html-face (ui-print); seon.program values-printed-to-order-or-compare (sort-by-print); seon.operator operator-prints-whole-results (cli-print).

## Step 1 — measurements (default, JVM mode, armed, HEAD 860fcc15f)

Profile cells (cumulative since boot, `seon.profile/observation` over `(seon.profile/cells)`):

| fn | calls | total ms | mean us | max ms |
|---|---|---|---|---|
| seon.id/print-canonical! | 1,971,891 | 26,396 | 13 | 67 |
| seon.id/id | 917,027 | 19,633 | 21 | 23 |
| seon.id/digest | 810,063 | 19,315 | 23 | 23 |
| seon.id/printed | 1,054,864 | 9,055 | 8 | 68 |
| seon.test.runner/reach-memberships | 21 | 12,608 | 600,414 | 5,644 |
| seon.test.runner/reach-entries | 53 | 8,520 | 160,763 | 2,035 |
| seon.test.runner/reach-digests | 32 | 8,094 | | |
| seon.test.runner/reach-entry | 7,458 | 5,620 | | |
| seon.test.runner/closure-digest | 2,406 | 1,767 | | |
| seon.test.runner/reach-graph | 53 | 1,928 | | |

seon.id/* calls are many and small: mean 21 us, max 23 ms (~1 MB printed at most).
Largest identity input found: the whole schema projection's forms (`seon.cluster`
world key, `id/digest 64 [forms dependencies row-shapes]`): 3,389 forms,
641,443 chars printed, 6.6 ms; per-form median 82 chars, max 10,020.
No caller prints anything near 600 MB today: the "600 MB" was the old reach
digest (every test's closure printed through `seon.id/digest`), already replaced
on 09-28 by `closure-digest`; the id.clj docstring still cites it.

Whole suite on default (2,365 tests; graph 9,165 name nodes, 104,761 edges,
6,834 schema nodes), reach caches (::reach-entries, ::reach-digest) evicted first:

| call | ms | allocated |
|---|---|---|
| reach-digests, cold | 2,974 | 3.37 GB |
| reach-digests, warm (cached) | 42 | 65 MB |
| reach-memberships, cold | 7,758 | 13.67 GB |
| reach-graph alone (node cache warm) | 119 | 194 MB |
| reach-digests one test | 18 | 27 MB |

memberships answer 5,224,126 names in total (tests x closure): the output itself.
Its only multi-test caller (`seon.test` effect-reach) asks one question: which
requested tests reach `seon.effect/request*`.

Root cause: both paths are O(tests x closure): every test's closure is
materialized (`members` over BitSets), fingerprinted, and hashed line by line.

## Step 2 — design

- Reach digest = Merkle over the condensation. Tarjan SCCs on the name graph and
  on the schema-key graph (iterative, int arrays), emitted sinks first. Each
  component's digest = SHA-256 over its members' `name TAB leaf` lines (sorted),
  then its successor components' digests (distinct, sorted), then (name graph)
  the schema components its members' keywords name (distinct, sorted).
  A test's digest = SHA-256 of `test-symbol NL component-digest`.
  Cost O(V + E) hashes for the whole requested graph; O(1) per test.
- Equivalence: a component digest is a function of every leaf, edge and keyword
  in its closure, so a test's digest changes iff something in its closure
  changes. Edges and keywords of a digested node are the analysis of the
  source its leaf hashes, and a schema node's keys come from the form its leaf
  hashes, so an edge-only difference with equal leaves cannot occur for a
  digested declaration (names without a node have no edges).
- Undigested: a per-component flag propagated along successors; the failure
  path (rare) walks the flagged tests' closures to name the declarations.
- Memberships: walked per requested test from the node map (output-sized);
  the effect-reach caller switches to `runner/tests-reaching`, one reverse walk
  from the target, O(V + E).
- Retired: per-test ::reach-digest cache kind, closure fingerprints
  (`reach-bits`), BitSet closures (`reach-closures`, `reach-postorder`),
  `closure-digest`, `reach-entry`. The ::reach-entries kind becomes
  ::reach-index (its value shape changes).
- Stored reach digests change encoding: every recorded green reuse key misses
  once and those tests rerun once. No identity entity changes; no reset needed.
- seon.id/id: NOT changed. Measurement shows it is only called on small values
  (max 23 ms, ~641 KB largest input found). A structural digest would change
  every stored identity and needs the owner's go (task
  identities-hash-structure-not-printed-text stays deferred). Docstring's
  stale 600 MB claim corrected.
