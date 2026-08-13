---
type: research
status: active
tags: [research, bootstrap, context, repl]
---

# Explained-set result growth probe — 2026-08-13

## Verdict

**PARTIALLY.** Executed admitted results do participate in the next readiness
computation. They grow the invocation's reconstructed `frontier` with Clojure
symbols and schema-declared entity identities. A candidate whose subject is
introduced only by a prior result can therefore become the next emitted entry.
Readiness is not limited to parsed form symbols or the initial pull.

The boundary is namespaced keywords. Arbitrary namespaced keywords in an
admitted result do not grow `frontier` or `explained`, and namespaced keywords
in a later form are not readiness dependencies at all. The current form scan
collects qualified and namespace **symbols** only. Thus a later form containing
`:seon.cluster.message/content` can be ready, but not because that key was
introduced or explained: the generator never asks.

This satisfies the literal result-growth requirement for symbols and entity
identities, including a message lookup ref such as
`[:seon.cluster.message/id "result-only-message"]`. It does not establish a
stronger invariant that every namespaced keyword appearing in a generated form
was first present in an executed result or explanation.

No production source or regression was changed. The requested regression was
permitted only for a `HOLDS` verdict; the existing test does not pin this
boundary tightly enough to turn the verdict into `HOLDS`.

## Authorities and observed revision

I read the following named authorities end to end before probing:

- [docs/prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md](../plan/self-generating-context-prd-2026-08-11.md), especially ruling 29;
- [docs/prds/sci-execution-runtime/plan/evolving-session-implementation-2026-08-12.md](../plan/evolving-session-implementation-2026-08-12.md), especially “One generator, one entry at a time”;
- [src/seon/bootstrap.clj](../../../../src/seon/bootstrap.clj), all 483 lines; and
- [test/seon/bootstrap_test.clj](../../../../test/seon/bootstrap_test.clj), all 222 lines.

The live probe ran against repository HEAD
`0ac0a57ec59873e6f86d85fa78122c2ee89a9335`; the observed bootstrap blob was
`bdc597aef6c1bf9b39201089f3049e96498eb01f` and the observed render-walk blob
was `a53ae182432e0c03c489535478d6c68df92b9157`. The isolated operator root was
`tmp/explained-probe/operator`, cluster `results-probe`, on OpenJDK 26.0.1.

## Current implementation trace

There is no landed invocation-local generation-state value yet. Each
`next-entry` call reconstructs the state from durable receipts:

1. [src/seon/bootstrap.clj:242](../../../../src/seon/bootstrap.clj#L242) queries
   settled generated forms and their `:seon.cluster.eval/result-edn` values.
2. [src/seon/bootstrap.clj:275](../../../../src/seon/bootstrap.clj#L275) maps
   each stored source back to its pulled candidate and decodes the real admitted
   result into `:seon.sci.admit/print-node`.
3. [src/seon/bootstrap.clj:287](../../../../src/seon/bootstrap.clj#L287) passes
   those settled print nodes to `seon.render.walk/ordered-episode`.
4. [src/seon/render/walk.clj:753](../../../../src/seon/render/walk.clj#L753)
   indexes the settled print nodes by candidate key. Once a selected candidate
   has a receipt, lines 783–790 add `seon.print/references` from its admitted
   result to `frontier`, while adding the settled candidate's subject to
   `explained`.
5. [src/seon/print.cljc:612](../../../../src/seon/print.cljc#L612) defines
   result references as every symbol plus every schema-declared entity identity
   structurally present in the print node. It does not collect arbitrary
   keywords.
6. [src/seon/render/walk.clj:714](../../../../src/seon/render/walk.clj#L714)
   collects only qualified or namespace symbols from a candidate form.
   [src/seon/render/walk.clj:765](../../../../src/seon/render/walk.clj#L765)
   requires the candidate subject to be in `frontier` and every collected form
   symbol to be in `explained` or to match the candidate's own subject.

The mechanism is therefore result-driven, but in two stages: a result
introduces a subject into `frontier`; settlement of that subject's explanation
candidate adds the subject to `explained`. Result symbols are not copied
directly into `explained`.

## Live behavioral probe

The reusable probe is
[tmp/explained-probe/probe.clj](../../../../../tmp/explained-probe/probe.clj).
It invokes the current pure readiness owner inside the isolated cluster's
`io-prepl`, using real `seon.sci.admit/admit-value` print nodes.

The synthetic pull contains four dependency-relevant candidates:

- root `(help)`;
- a message-identity candidate whose subject is
  `[:seon.cluster.message/id "result-only-message"]`;
- a symbol explanation candidate whose subject is `result.only/name`; and
- a later consumer `(result.only/name :seon.cluster.message/content)`.

Neither the message identity nor `result.only/name` appears in an earlier form
or initial frontier. The executed root result is the only source of the
message identity. The executed message-candidate result is the only source of
the symbol. An additional keyword-subject candidate tests whether the result's
`:seon.cluster.message/content` map key enters the frontier.

The complete returned `io-prepl` value was:

```clojure
#:probe{:root-result-references
        #{[:seon.cluster.message/id "result-only-message"]},
        :root-result-next-key
        [[:seon.cluster.message/id "result-only-message"] 0],
        :message-result-next-key [result.only/name 0],
        :consumer-form-symbols #{result.only/name},
        :after-symbol-next-key
        [[:seon.cluster.message/id "result-only-message"] 1],
        :keyword-candidate-emitted? false}
```

This establishes all three edges independently:

- the admitted root result exposes the message identity and unlocks the
  message candidate;
- the admitted message result exposes the new symbol and unlocks that symbol's
  explanation candidate; and
- after that explanation settles, the consumer becomes next even though its
  namespaced keyword was neither collected from the result nor included in
  `form-symbols`.

The first probe attempt used vector and keyword values directly as
`:seon.repl/form`; instrumentation correctly refused them because the declared
form contract accepts a qualified symbol or Clojure list form. The retained
probe uses list forms and returned normally in 114 ms.

## What the existing regression proves

[test/seon/bootstrap_test.clj:79](../../../../test/seon/bootstrap_test.clj#L79)
settles the real `(help)` result, calls `next-entry`, and proves that one new,
byte-deterministic entry appears. It is useful integration coverage for receipt
consumption, but its assertions at lines 142–149 only require “a map,” a source
different from `(help)`, and repeatability.

It does not construct a candidate whose subject is absent from the initial
pull/frontier and introduced only by the executed result. It also does not
assert the keyword boundary. A change that stopped harvesting result identities
could remain green if the real situation/pull happened to make some other
candidate ready.

## Smallest follow-up shape

If ruling 29 remains literal—unexplained **symbols**, with entity identities
governing candidate subjects—the implementation already has the required
result-growth mechanism. The smallest follow-up is one focused regression
using an admitted result-only identity and symbol, structurally equivalent to
this probe, after the concurrent Phase 1 owner settles the invocation-local
state API.

If the intended invariant also includes namespaced keywords, the gap is
precise and cross-cutting: form dependency extraction and admitted-result
reference extraction must share one structural reference vocabulary that
includes qualified keywords, and keyword explanation candidates must be
derivable inside pull membership. Changing only one side would either block
valid forms forever or continue admitting unexplained keys. That is a semantic
expansion beyond ruling 29's current word “symbols”; it should be ruled before
a production edit rather than inferred inside the generator.

## Reproduction

From the repository root:

```bash
mkdir -p tmp/explained-probe/operator
bin/seon --root tmp/explained-probe/operator init
bin/seon --root tmp/explained-probe/operator start results-probe
nc -w 5 127.0.0.1 54930 <<< \
  '(load-file "tmp/explained-probe/probe.clj")'
```

The recorded port is specific to this run; derive the current port from the
isolated root's `bin/seon status` output before repeating.
