---
type: research
status: active
tags: [research, sci, agent, test]
---

# Reader fences — landing, 2026-09-10

## Authority and verification boundary

Read end to end: AGENTS.md; the fenced-replies issue; live-run-2 landing;
turn PRD §§18b and 18d; reader.cljc; cluster/reply.clj; reply_test.clj;
repl_grammar_test.clj. Read the reply-reading, evaluation, and fault paths
in turn.clj, the roadmap entry and working edge, and the data-oriented-clojure,
repl, and clojure-testing skills.

Default PID 23557 was alive. MCP status returned health/Flow unknown with
`Read timed out`, the existing
[component-probe issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
JVM parsing and read-only database queries answered. No default stop,
refork, reseed, agent message, or agent evaluation was performed.

Before edits, all three exact stored replies passed live `planned-sources`
with one correct source each. The historical zero-evaluation turns are
recorded evidence; failure to split these bytes was not reproduced in the
current JVM. The first slice makes fences ordinary shared-reader whitespace
and removes the splitter's fenced-region state; it does not claim to have
identified the historical failure's cause.

## Dependency ledger

- SCI, `reference-code/sci/src/sci/core.cljc:392–419`: source-reader,
  parse-next+string, and cursor positions; first-party owner:
  `src/seon/sci/reader.cljc` read-events.
- SCI delegates its source reader to Edamame; both use tools.reader's
  read-char protocol (`reference-code/edamame/src/edamame/core.cljc:4–5`,
  `reference-code/edamame/src/edamame/impl/parser.cljc:255–263`).
  Fence skipping happens only between SCI-proven top-level forms.
- Existing fabricated-response path: `src/seon/sci/eval.clj:2241` refuses
  a reader event carrying a flat error; ordinary turn evaluation stores its
  outcome and shown text. Archaeology: `e262fdca4`.
- Canonical database/SCI fixture and ordinary proc:
  `test/seon/loop_proof_test.clj` and `test/seon/turn_continue_test.clj`.
  No alternate evaluation harness or schema roster.

## Exact live replies

Copied by read-only MCP pull from the three stored turn identities.
The machine-readable exact strings are in `test/seon/reader_fences.edn`;
the following blocks have no terminal newline in their stored string.

### 94f7a885645b

````text
I see two things happened. Let me re-examine the state:

1. My `largest-customer` defn appears to have evaluated (`#'my.agents.juniper/largest-customer`) but the auto-check reported a contract violation, and subsequent `(dir my.agents.juniper)` shows `:functions []` — so the defn did NOT persist. I need to check the error and try again with a correct schema.

2. There was a turn-completion backstop error interrupting a run.

Let me first check whether the function actually persists and inspect the schema error.

```clojure

;; I should verify whether largest-customer persisted and, if not, see the schema candidates. The defn output showed a contract violation from the "auto-check" of seon.sci.kernel/invoke — likely because my :example/order-row schema is a plain vector :map but the function received a set. Let me first try calling it to confirm presence.
(largest-customer [{:example/order "a1" :example/customer "Ada" :example/amount 60}])

```
````

### e6628e29a42b

````text
```clojure

;; Stop looping. The deftest macro worked. Now run the tests.
(my.test/run)

```
````

### 59cf96d21042

````text
I need to stop the introspection loop and act. Facts: orders are Ada 60+55=115, Bea 100, Cy 40; `largest-customer` is registered. `deftest` isn't in scope. Let me check what test macro is available.

```clojure

(dir my.test)

```
````

## First slice

The reader ignores backtick and existing tilde delimiter lines, including
language tags and indentation, only between forms. Multiline strings keep
their exact bytes; source offsets still refer to the original text.
The reply splitter retains ordinary prose handling and prompt-marker
stripping. Forms outside fences use the same grammar as forms inside.

The exact-reply proc regression supplies private `largest-customer = first`
for the one-order input and an ordinary installed order-total test. This
proves reader/evaluation behavior; it is not a domain-function regression.

Exact source sizes, in turn-id order above: 955 / 90 / 228 UTF-16 units;
959 / 90 / 228 UTF-8 bytes. Each reply produced one evaluation with no error.
The fast-run shown texts were:

```clojure
#:example{:amount 60, :customer "Ada", :order "a1"}
[#:seon.test{:error-count 0, :fail-count 0, :pass-count 1, :run-at #inst "2026-09-10T21:39:15.294-00:00", :run-basis-t 536871032, :sym "my.agents.juniper/order-total"}]
{:functions [{:doc "Run my namespace's declared tests in my live SCI context and store their results.", :in [], :out [], :sym my.test/run}], :schemas {}}
```

Fast gate: 49 tests / 546 assertions, zero failures or errors. Includes
reader, reply, REPL grammar, and the ordinary-proc loop proof. The initial
iteration found that the old fenced-region grammar forced a standalone
`Done.` symbol to prose; the general reply grammar accepts standalone
symbols once forms establish code. The prose fixture now says "That is done."

First isolated gate, `run.nbpLAV`: 50 tests / 601 assertions, zero failures,
one error in `stored-help-keeps-its-renderer-and-bare-response` before its
subject ran. `NoSuchFileException` named cached-base
`7dab2517f78f51c54871b33bd9e508847406f11f67f7e3ce9ca6b3a5c98d699e`
store key `570bd582-5784-4a45-80af-0f8398be60aa` at
`konserve.filestore/migrate-file-v1`, reached through tiered sync-on-connect.
The gate's isolated confirmation passed. This is the existing
[parallel-base acquisition issue](../../../seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md),
not evidence against the reader slice. The same path-isolated gate is rerun.

Live JVM proof after hook edits: shared-reader input
`"```clojure\n(inc 1)\n```"` returned one source `(inc 1)` and no error in
3 ms. This proves the loaded reader behavior. During ongoing publications,
adopted source `6aa31e7b-94d8-5ecf-961e-5c331bc52413` differed from current
`6aa32475-d687-573b-9838-e8c8c212db38`; that observation is not a claim of
completed development adoption.

Rerun `run.QBvbVW`: **50 tests / 613 assertions, zero failures or errors**,
112 seconds coordinator/test time. Command:

```sh
bin/test --paths src/seon/sci/reader.cljc src/seon/cluster/reply.clj test/seon/sci/reader_test.clj test/seon/cluster/reply_test.clj test/seon/loop_proof_test.clj test/seon/reader_fences.edn docs/prds/context-generation/research/reader-fences-landing-2026-09-10.md docs/seon/issues/fenced-replies-read-as-prose-and-no-forms-is-a-core-fault.md -- seon.sci.reader-test seon.cluster.reply-test seon.repl-grammar-test seon.loop-proof-test seon.turn-continue-test
```

First-slice files: `src/seon/sci/reader.cljc`, `src/seon/cluster/reply.clj`,
`test/seon/sci/reader_test.clj`, `test/seon/cluster/reply_test.clj`,
`test/seon/loop_proof_test.clj`, `test/seon/reader_fences.edn`, this note,
and the fenced-replies issue. The no-forms source and test edits are excluded
from this gate and commit. No foreign source breakage blocked this snapshot.
