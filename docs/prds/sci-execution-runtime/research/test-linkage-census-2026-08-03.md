---
type: research
status: active
tags: [research, testing, program-graph, datahike]
---

# Test-linkage census

## Verdict

`functions without tests` is not yet an honest standing finding.

The companion test-reference and capability-hop fixes are necessary, but they
do not close the data model. On the immutable published `current-src` database
value measured here:

- 894 test rows exist;
- 844 carry at least one `:seon.fn/calls` edge;
- zero carry `:seon.test/subject`;
- 50 carry neither; and
- 575 public function rows exist, of which 135 have zero current transitive
  test reach.

Projecting the companion fixes reduces the public zero-reach count from 135 to
112. That 112 is still a mixed residue, not an untested count:

- clj-kondo already computes 190 non-call references from function callers;
  retaining them makes 16 more public functions reachable and reduces the
  residue to 96;
- 29 of the 112 are explicitly named from schema forms, including 16 declared
  render producers, but those schema-to-function references are stored only
  inside serialized schema forms and are not queryable as refs;
- 17 of the 112 are functions declared under `test/`, a distinction the
  analyzer knows from `:filename` but the database discards;
- five dynamic or child-process test roots need honest explicit subjects, and
  the static indexer currently has no metadata-to-`:seon.test/subject`
  producer; and
- `:seon.test/long` is computed by the runner and present in analyzer metadata,
  but is not a database fact, so “tested only by process/boot tests” cannot be
  queried today.

Recommendation: land the companion fixes, then the five discarded-fact fixes
in this report before enabling `functions without tests` in `seon.problems`.
Until then, expose the query only as a research diagnostic whose label says
“zero indexed static test reach,” never “untested.”

I read
[test-call-edge-design-2026-08-03.md](docs/prds/sci-execution-runtime/research/test-call-edge-design-2026-08-03.md)
end to end before this census. This report extends its one-graph design; it
does not propose a second analyzer, reverse edge, or test runner.

## Measured snapshot

The shared `default` cluster was alive, but it is a sovereign older fork with
794 tests and no test call edges. I therefore read the already-open
`current-src` branch connection directly inside the store-owning JVM and made
no transactions.

| Fact | Value |
|---|---:|
| Branch | `:current-src` |
| Commit ID | `6a70f8d6-8f30-5352-828f-64263f65147c` |
| Basis transaction | `536870923` |
| Test rows | 894 |
| Tests with calls | 844 |
| Tests with subjects | 0 |
| Tests with neither | 50 |
| Public functions | 575 |
| Public functions sourced under `src/` | 543 |
| Public functions sourced under `test/` | 32 |

The owner's `~906` tests and `588` public functions were approximate moving-tree
counts. The concurrent checkout analyzed to 911 tests while this report was in
progress, but it contained unrelated uncommitted AI and shell work. I did not
publish that dirty tree or combine its row counts with the immutable database
value. Current-source analysis was used only to project structured analyzer
facts for identities already present in the published database.

## Dependency ledger

| Dependency or mechanism | Selected revision | Evidence used |
|---|---|---|
| clj-kondo | `57252e07975710aa579b24f0d1b2b1e04195caa2` | The one analysis already returns caller, enclosing var, resolved target, arity presence, metadata, and filename. See [reference-code/clj-kondo](reference-code/clj-kondo), [src/seon/fn/analyzer.clj](src/seon/fn/analyzer.clj), and [src/seon/fn.clj](src/seon/fn.clj). |
| Datahike | `0e8601d7f2f6` | Immutable database value, cardinality-many refs, recursive rules, and absence queries. See [reference-code/datahike](reference-code/datahike). |
| SCI | `2db3358cba91` | Context for source-string and dynamic evaluation residue only; no SCI change is proposed. See [reference-code/sci](reference-code/sci). |
| Static program producer | current [src/seon/fn.clj](src/seon/fn.clj) | Computes call sets, exact rows, file artifacts, capability metadata, and analyzer projections. |
| Static analyzer normalization | current [src/seon/fn/analyzer.clj](src/seon/fn/analyzer.clj) | Retains `:var-usages`, `from`, `from-var`, `to`, `name`, `arity`, metadata, and filename. |
| Program-row owner | current [src/seon/program.cljc](src/seon/program.cljc) | Owns canonical function/test attributes and exact replacement. |
| Test runner | current [src/seon/test/runner.clj](src/seon/test/runner.clj) | Computes the effective `:seon.test/long` marker from var or namespace metadata. |
| Test schema | current [resources/seon/schemas/seon.test.edn](resources/seon/schemas/seon.test.edn) | Declares singular `:seon.test/subject`; no long-reason or reference attribute exists in the measured tree. |

## Test-row census

### Top-level partition

| Link shape | Tests | Share |
|---|---:|---:|
| One or more call edges | 844 | 94.41% |
| Subject but no call edge | 0 | 0.00% |
| Neither | 50 | 5.59% |
| Total | 894 | 100.00% |

The 50-row neither set is complete below. The classifications come from the
stored exact test source plus clj-kondo's structured usages; no source-name
convention or text regex supplied a linkage fact.

### Twelve tests have computable function references

These tests contain 24 resolved first-party function references outside call
position. The companion reference fact is the correct representation.

| Test | Referenced function targets |
|---|---|
| `my.edit-test/public-entries-declare-the-single-io-handler` | `my.edit/form`, `my.edit/exact`, `my.edit/lines` |
| `my.fs-test/public-entries-declare-one-io-capability` | `my.fs/read`, `my.fs/write`, `my.fs/glob`, `my.fs/stat` |
| `my.web-test/public-entries-declare-one-io-capability` | `my.web/fetch`, `my.web/search` |
| `seon.ai-test/a-reasoning-only-length-stream-is-the-same-named-error` | `seon.ai/streamed-completion` |
| `seon.ai-test/streaming-reasoning-never-becomes-text-and-retains-terminal-evidence` | `seon.ai/streamed-completion` |
| `seon.bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit` | `seon.ai/complete` |
| `seon.cluster.boot-test/incremental-source-refresh-requires-every-unreported-file-to-match` | `seon.cluster/unreported-source-current?` |
| `seon.public-contract-test/opaque-predicate-contracts-construct-real-values` | seven predicate functions in `seon.cluster`, `seon.cluster.store`, `seon.sci.admit`, and `seon.sci.eval` |
| `seon.schema.edn-test/an-empty-resource-directory-refuses-loudly` | `seon.schema.edn/directory-resource-paths` |
| `seon.sci.eval-test/failed-evaluation-assembles-failure-presence-facts` | `seon.sci.eval/failed-evaluation` |
| `seon.sci.eval-test/success-evaluation-assembles-every-optional-projection` | `seon.sci.eval/success-evaluation` |
| `seon.test-runner-test/liveness-dump-includes-virtual-threads` | `seon.test.runner/persist-virtual-thread-dump!` |

Four are data-driven Var tables: the three `my.*` capability metadata tests
and `opaque-predicate-contracts-construct-real-values`. Five directly invoke a
private Var through var-quote, two dereference a private Var into a local, and
the bootstrap-drive row combines `ns-resolve` with `with-redefs`.

One caution is material: the bootstrap-drive test references
`seon.ai/complete` only to replace it. Its actual dynamic subject is
`seon.bootstrap-drive/run-drives!`, resolved through `ns-resolve`; treating the
reference alone as the whole coverage story would be false. That test needs an
explicit subject in addition to the reference fact.

Computable fact: retain clj-kondo usages without `arity` as the companion's
distinct shared reference relation. Do not merge them into calls: a reference
may mean invocation, metadata inspection, generation, or replacement.

### Thirty-one tests exercise first-party tooling outside the program graph

These targets resolve under `script/`, while the cluster program graph is
deliberately built from `src/` and `test/`. They test real code, but not a
`:seon.fn` target in the published cluster program.

The four docstring-tool tests are:

- `seon.dev.docstring-test/format-findings-test`
- `seon.dev.docstring-test/lints-its-own-source-clean-test`
- `seon.dev.docstring-test/scan-test`
- `seon.dev.docstring-test/test-and-internal-ns-skipped-test`

The 24 Markdown-tool tests are:

- `seon.dev.markdown-test/fix-blanks-around-fences-test`
- `seon.dev.markdown-test/fix-idempotent-test`
- `seon.dev.markdown-test/fix-multiple-blanks-test`
- `seon.dev.markdown-test/fix-trailing-newline-test`
- `seon.dev.markdown-test/fix-trailing-whitespace-test`
- `seon.dev.markdown-test/format-violations-test`
- `seon.dev.markdown-test/frontmatter-not-flagged-as-setext-test`
- `seon.dev.markdown-test/full-document-test`
- `seon.dev.markdown-test/parse-frontmatter-test`
- `seon.dev.markdown-test/parse-headings-test`
- `seon.dev.markdown-test/parse-links-test`
- `seon.dev.markdown-test/parse-sections-test`
- `seon.dev.markdown-test/validate-blanks-around-fences-test`
- `seon.dev.markdown-test/validate-fenced-code-style-test`
- `seon.dev.markdown-test/validate-file-nonexistent-test`
- `seon.dev.markdown-test/validate-has-frontmatter-test`
- `seon.dev.markdown-test/validate-heading-increment-test`
- `seon.dev.markdown-test/validate-list-style-test`
- `seon.dev.markdown-test/validate-no-bare-urls-test`
- `seon.dev.markdown-test/validate-no-multiple-blanks-test`
- `seon.dev.markdown-test/validate-required-fields-test`
- `seon.dev.markdown-test/validate-single-h1-test`
- `seon.dev.markdown-test/validate-trailing-whitespace-test`
- `seon.dev.markdown-test/validate-valid-tags-test`

The remaining three tooling rows are:

- `seon.dev.fresh-operator-test/every-child-jvm-command-uses-the-shared-launch-owner`
- `seon.dev.mcp-bridge-test/bridge-loads-with-only-the-tooling-classpath`
- `seon.dev.mcp-bridge-test/registrations-use-one-jvm-neutral-server-name`

The MCP load test is the only one of these 31 that starts a child process. The
fresh-operator test inspects the script source, and the registration test
inspects configuration files.

Computable fact: clj-kondo already reports the resolved targets, but no target
identity exists in the database because `script/` is outside the cluster
program. Adding subjects would be dishonest, and adding operator tooling to
the cluster program merely to quiet a coverage query would widen the program
model. These 31 rows should remain unlinked for the public cluster-function
query. If tooling coverage ever becomes a database question, it needs its own
declared program population, not a fake edge to a cluster function.

### Seven rows are honestly non-function coverage roots

| Class | Count | Test rows | Honest treatment |
|---|---:|---|---|
| Macro expansion | 1 | `my.background-test/background-macro-expands-one-direct-call` | The subject is macro expansion, and macros have no `:seon.fn` row. No function subject. |
| Namespace-surface reflection | 2 | `my.message-test/the-surface-is-exactly-two-functions`; `my.run-test/the-surface-is-exactly-two-functions` | They assert the complete `ns-publics` set rather than execute either function. No function subject. |
| Data-var catalog | 1 | `seon.bootstrap-drive-test/objective-catalog-is-the-five-ruled-fact-space-cases` | It reads the `objectives` Var, which is data rather than a function row. No function subject. |
| Source-structure invariant | 1 | `seon.render.route-test/render-code-contains-no-hand-built-route-urls` | It scans render source files for forbidden URL construction. It does not exercise one function body. No function subject. |
| Runner fixture sentinels | 2 | `seon.test-runner-failure-fixture/failing-example`; `seon.test-runner-failure-fixture/passing-example` | They are deliberately selected data for runner-result tests. Attributing either arithmetic assertion to a production function would lie. |

These seven plus the 31 out-of-graph tooling rows are the 38 tests left after
the 12 reference-bearing rows are projected. They need no new function edge.

### Requested mechanism cross-check

| Mechanism requested for inspection | Neither-set result |
|---|---|
| Var references outside call position | 12 tests / 24 first-party function references |
| Source-string eval through the execution path | 1 mixed row: the bootstrap-drive test supplies an agent reply string, reached through its dynamically resolved subject |
| Dynamic `requiring-resolve` or `ns-resolve` | 1: bootstrap-drive uses `ns-resolve` |
| Fixture-driven boot path | 1: the same long bootstrap-drive test |
| Generative properties over schemas | 0 property roots; the public-contract row is a data-driven generator/predicate table, not a property |
| Macro-expanded calls | 1 macro-expansion assertion; its quoted body does not establish a function call edge |
| Data-driven tables over Vars | 4 tests |
| Child-process tests | 1 in the neither set: MCP bridge load; its target is tooling outside the program graph |
| Static source/config inspection | 3: fresh-operator owner, route URL invariant, MCP registration |

## Explicit subjects required

Five existing tests need `:seon.test/subject` because their executable target
is selected dynamically and cannot be recovered as an honest call edge:

| Test | Subject |
|---|---|
| `seon.bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit` | `seon.bootstrap-drive/run-drives!` |
| `seon.cluster.store-test/the-flock-fences-across-processes` | `seon.cluster.store-child/-main` |
| `seon.cluster.store-test/an-in-process-refusal-never-drops-the-os-fence` | `seon.cluster.store-child/-main` |
| `seon.flow-test/forced-child-jvm-death-preserves-committed-facts` | `seon.flow.kill-child/-main` |
| `seon.sci.session-image-test/two-fresh-jvms-round-trip-the-owner-session` | `seon.sci.session-image-child/-main` |

This is a small and honest burden: five declarations for four dynamic targets.
Simulating them on the published graph reduces the companion-projected public
residue from 112 to 107. The static producer is missing, however:
`var-row` reads test metadata but does not project `:seon.test/subject` into a
test row. The schema and reachability rule alone are not an authoring path.

The other 49 neither-set tests should not acquire subjects merely to make the
count prettier. Eleven more get derived references; 31 target out-of-graph
tooling; and seven do not claim function-body coverage.

## Function-side census

### Current versus companion-projected result

Today's graph reaches 440 of 575 public functions and leaves 135. Projecting
test references plus the capability entry-to-handler hop reaches 463 and
leaves 112.

Test references account for all 23 newly reached public functions. The
capability hop adds zero public functions in this snapshot because the handler
entry points and their downstream helpers are private, but it does close their
private reachability and remains semantically required. Current analysis found
ten declared entry-to-handler hops.

| Namespace | Current zero reach | Projected zero reach |
|---|---:|---:|
| `seon.schema` | 23 | 23 |
| `seon.cluster.run` | 15 | 13 |
| `seon.schema.datahike` | 8 | 8 |
| `seon.ai` | 6 | 6 |
| `my.fs` | 4 | 0 |
| `seon.cluster.source-test` | 4 | 4 |
| `seon.effect` | 4 | 4 |
| `seon.flow` | 4 | 4 |
| `seon.render.web` | 4 | 3 |
| `my.edit` | 3 | 0 |
| `seon.cluster` | 3 | 2 |
| `seon.eval.drive` | 3 | 3 |
| `seon.print` | 3 | 3 |
| `my.shell` | 2 | 2 |
| `my.web` | 2 | 0 |
| `seon.artifact` | 2 | 2 |
| `seon.bootstrap` | 2 | 2 |
| `seon.cluster.agent` | 2 | 0 |
| `seon.cluster.instruction` | 2 | 2 |
| `seon.cluster.message` | 2 | 2 |
| `seon.context` | 2 | 2 |
| `seon.render-simplification.fixture-ambiguous` | 2 | 2 |
| `seon.render-simplification.fixture-b` | 2 | 2 |
| `seon.render.agent` | 2 | 2 |
| `seon.render.hiccup` | 2 | 2 |
| `seon.background-blob-test` | 1 | 0 |
| `seon.blob` | 1 | 1 |
| `seon.bootstrap-drive` | 1 | 1 |
| `seon.cluster.agent-test` | 1 | 0 |
| `seon.cluster.store` | 1 | 0 |
| `seon.cluster.store-child` | 1 | 1 |
| `seon.cluster.store-transact-test` | 1 | 0 |
| `seon.cluster.turn-test` | 1 | 1 |
| `seon.effect-test` | 1 | 0 |
| `seon.flow.kill-child` | 1 | 1 |
| `seon.oversight` | 1 | 1 |
| `seon.problems-test` | 1 | 1 |
| `seon.reconcile` | 1 | 1 |
| `seon.render` | 1 | 0 |
| `seon.render-fixture` | 1 | 1 |
| `seon.render-simplification.fixture-a` | 1 | 1 |
| `seon.repl-parity-test` | 1 | 1 |
| `seon.schema.admission` | 1 | 1 |
| `seon.schema.edn` | 1 | 1 |
| `seon.schema.edn-test-fixture` | 1 | 1 |
| `seon.schema.form` | 1 | 1 |
| `seon.schema.internal` | 1 | 1 |
| `seon.sci.admit` | 1 | 0 |
| `seon.sci.eval` | 1 | 0 |
| `seon.sci.kernel` | 1 | 1 |
| `seon.sci.session-image-child` | 1 | 1 |
| `seon.test.runner` | 1 | 1 |

### Missing function-caller references

The companion scope addresses references whose caller is a test. The same
analysis contains 190 non-call references from 150 function callers to
first-party function targets. Sixteen public functions move from zero reach to
reachable when those references are traversed:

- `seon.bootstrap/seed-tx`
- `seon.cluster.run/claim-call`
- `seon.cluster.run/close-call`
- `seon.cluster.run/open-call`
- `seon.cluster.run/plan-call`
- `seon.cluster.run/receipt-start-call`
- `seon.cluster.run/recover-call`
- `seon.cluster.run/release-call`
- `seon.cluster/ensure-entity-call`
- `seon.effect/interrupt-call`
- `seon.effect/interruption-stamps`
- `seon.effect/open-call`
- `seon.effect/settle-call`
- `seon.reconcile/reconcile-call`
- `seon.render.web/render-step`
- `seon.sci.kernel/program-namespace`

Most are transaction functions, step functions, callbacks, or other functions
passed as values. The analyzer computed caller and target and discarded only
the missing `arity`. This is the same fact class as test references and should
use the same distinct reference relation on function rows. It must remain
distinct from `:seon.fn/calls`: a reference is potential reach, not proof of a
call position.

After this fact is retained, the public residue is 96: 79 functions from
`src/` and 17 helpers from `test/`.

### Schema-declared dynamic residue

Twenty-nine of the 112 companion-projected residual functions are named
directly by qualified symbols in published schema forms. Sixteen are declared
render producers:

- `seon.ai/attempt-ai`, `seon.ai/attempt-html`
- `seon.cluster.instruction/instruction-ai`,
  `seon.cluster.instruction/instruction-html`
- `seon.cluster.message/render-ai`, `seon.cluster.message/render-html`
- `seon.cluster.run/render-ai`, `seon.cluster.run/render-html`
- `seon.cluster.run/render-form-ai`, `seon.cluster.run/render-form-html`
- `seon.cluster.run/render-receipt-ai`,
  `seon.cluster.run/render-receipt-html`
- `seon.context/capture-ai`, `seon.context/capture-html`
- `seon.render.agent/agent-ai`, `seon.render.agent/agent-html`

The other 13 include schema predicates and printer or web predicates. The
schema registry already parses these symbols as properties or predicate
references before the canonical form is stored. Keeping only the serialized
`:seon.schema/form` makes the relationship invisible to Datalog.

This does not prove that a test exercised every dynamically selected producer.
It does prove that “genuinely untested” and “unreachable through declared
schema dispatch” are different categories. Publish schema-to-function refs so
the query can name the residue honestly; do not infer producer names from a
naming convention or parse schema strings in the query path.

### Process/boot-only coverage

Using current analyzer metadata, exactly two public functions have corrected
test reach exclusively from long tests:

- `seon.cluster/read-advertisement` from `src/`; and
- `seon.background-blob-test/binary-capability` from `test/`.

This classification cannot be reproduced from the database because no
`:seon.test/long` fact is installed. The runner already computes the effective
reason from test-var then namespace metadata. The static analyzer also retains
both metadata maps. Discarding the effective reason is the defect.

### Source-role residue

The 575 public functions comprise 543 `src/` functions and 32 `test/`
functions. Current zero reach is 114 `src/` plus 21 `test/`; after the
companion projection it is 95 plus 17.

The analyzer groups rows by exact filename and the manifest carries
`:seon.fn.file/path`, but no row-to-file or row-to-source-root relationship is
published. Consequently Datalog cannot distinguish a product function from a
test helper without the banned `-test` naming convention. Publish the
already-computed file ownership and declared root role.

### What “genuinely untested” means today

It is not a number the current database can answer. The narrowest measured
upper bounds are:

| Model | Public functions with zero reach |
|---|---:|
| Current calls plus current subjects | 135 |
| Companion test references plus capability hop | 112 |
| Companion model plus five honest dynamic subjects | 107 |
| Companion model plus function-caller references | 96 |

The 96 still contains 29 schema-declared dynamic functions, 17 test helpers,
process entry functions with no declared subject, and functions that may truly
have no test. Calling all 96 “genuinely untested” would repeat the defect this
census was commissioned to remove.

## Missing facts beyond the companion fixes

Ordered by what the producer already computes:

1. **Function-caller references.** Publish the same distinct reference
   relation on function rows, not only test rows. Measured: 190 pairs from 150
   callers; 16 public zero-reach functions become reachable.
2. **Static explicit subjects.** Project a qualified
   `:seon.test/subject` declaration from test metadata and add the five
   declarations listed above. The schema/query exists; the static authoring
   path does not.
3. **Effective long-test reason.** Publish the runner's effective nonblank
   reason on the test row. It is source metadata, not a derived pass/fail fact.
4. **Program-row file ownership and root role.** Publish the analyzer's exact
   filename relationship and whether the admitted root is `src` or `test`.
   Never derive this from namespace spelling.
5. **Schema-to-function references.** Publish the registry's parsed qualified
   producer and predicate symbols as refs to function rows. Keep render
   selection dynamic; this fact names the declared dispatch boundary without
   claiming every test exercised every producer.

No new reverse edge is needed. Every consumer derives reverse reachability
from these forward facts.

## Target Datalog

The following is the exact zero-linkage query once the companion's distinct
shared reference relation is installed as `:seon.fn/references`. It preserves
calls, references, explicit subjects, and the declared capability
entry-to-handler hop as separate forward facts.

```clojure
(def corrected-test-reach-rules
  '[[(function-edge ?from ?to)
     [?from :seon.fn/calls ?to]]

    [(function-edge ?from ?to)
     [?from :seon.fn/references ?to]]

    [(function-edge ?from ?to)
     [?from :seon.effect/capability ?handler-symbol]
     [(str ?handler-symbol) ?handler-string]
     [?to :seon.fn/sym ?handler-string]]

    [(function-reaches ?from ?target)
     (function-edge ?from ?target)]

    [(function-reaches ?from ?target)
     (function-edge ?from ?next)
     (function-reaches ?next ?target)]

    [(test-root ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.fn/calls ?target]]

    [(test-root ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.fn/references ?target]]

    [(test-root ?test ?target)
     [?test :seon.test/sym]
     [?test :seon.test/subject ?target]]

    [(test-reaches ?test ?target)
     (test-root ?test ?target)]

    [(test-reaches ?test ?target)
     (test-root ?test ?entry)
     (function-reaches ?entry ?target)]])

[:find ?function-symbol ?namespace-name
 :in $ %
 :where
 [?function :seon.fn/sym ?function-symbol]
 [?function :seon.fn/private? false]
 [?function :seon.fn/ns ?namespace]
 [?namespace :seon.ns/name ?namespace-name]
 (not-join [?function]
   (test-reaches ?test ?function))]
```

The `str` expression is the existing boundary translation between
`:seon.effect/capability`'s qualified symbol and `:seon.fn/sym`'s string
identity. If the companion implementation materializes the handler as a ref,
the third `function-edge` rule should use that ref directly and delete the
translation; the query must follow the landed fact, not maintain both.

### Named residue queries

Once `:seon.test/long` is indexed, long-only linkage is derivable rather than
stored:

```clojure
(def long-test-rules
  (conj corrected-test-reach-rules
        '[(short-test ?test)
          [?test :seon.test/sym]
          (not [?test :seon.test/long])]))

[:find ?function-symbol
 :in $ %
 :where
 [?function :seon.fn/sym ?function-symbol]
 [?function :seon.fn/private? false]
 (test-reaches ?some-test ?function)
 (not-join [?function]
   (short-test ?short-test)
   (test-reaches ?short-test ?function))]
```

Once row-to-file/root ownership is published, the product-function query adds
the direct root join; it must not inspect namespace text:

```clojure
[?function :seon.fn/source-file ?file]
[?file :seon.fn.file/root :src]
```

Once parsed schema refs are published, schema-declared dynamic residue is a
separate query result, not a silent exclusion:

```clojure
[:find [?function-symbol ...]
 :where
 [?schema :seon.schema/functions ?function]
 [?function :seon.fn/sym ?function-symbol]]
```

The last two attribute names state the required facts; their final schema
names must be settled in the implementation slice after registry-first schema
discovery. There is no existing declaration to reuse in the measured schema
registry.

## `seon.problems` recommendation

Do not enable the finding now.

Enable it after:

1. the companion test-reference and capability-hop changes publish and refork
   a proof cluster;
2. function-caller references use the same distinct relation;
3. the five dynamic subjects are statically producible and declared;
4. long-test reasons and row-to-source ownership are queryable; and
5. schema-declared function refs can be reported as their own residue.

At that point `seon.problems` should report at least three counts, never one
collapsed alarm:

- public `src/` functions with zero corrected test reach;
- public functions reached only by long tests; and
- zero-reach functions that are nevertheless declared dynamic schema targets.

Test helpers and out-of-program tooling remain separately queryable context.
Only the first count is a candidate “functions without tests” problem, and it
should render the exact function symbols and why each named residue rule did
not apply.

## Tool and render feedback

- `bin/seon status` printed the default cluster as alive and, in the same
  response, said the recorded JVM's prepl was unreachable. MCP runtime status
  immediately observed the same cluster and prepl. That contradictory status
  face is noisy and should be reported by its owner.
- A read-only query in SCI failed with a flat `NoClassDefFoundError` from
  the live stale SCI context. JVM mode against the store-owning process worked
  and kept the census read-only. The error was visible rather than swallowed,
  which was useful.
- `get_value` reported `shown: 8` while rendering seven concrete collection
  entries plus an elision marker. For a census this makes the numeric paging
  contract misleading: offsets by eight silently skip the elided eighth
  value. Returning `pr-str` under the text cap exposed all 50 symbols and was
  the workaround. The page should either render eight real entries or report
  seven shown.
- The database's exact stored test sources were retrievable, but large source
  strings were truncated inside each page even when the parent value was
  already blob-backed. Source-file reads were therefore still required for
  classification. The truncation was shape-preserving and loudly marked, but
  the drill could not request an individual collection element and then its
  full `:source` without first knowing the skipped index.
