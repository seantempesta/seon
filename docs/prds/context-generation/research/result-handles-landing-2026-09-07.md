---
type: research
status: complete
date: 2026-09-07
tags: [research, repl, sci, render, context, testing]
---

# Result handles are the evaluation's own identity — 2026-09-07

Landing note for the `result-handles` lane, closing defect **B2** of
[the REPL and record audit](audit-repl-and-record-2026-09-07.md) and the
handle half of PRD §4
([the agent record and the REPL response](../plan/agent-record-and-repl-response-prd-2026-09-07.md)).

## 1. The defect, live, before the change

`http://127.0.0.1:7766/feed/juniper?debug=true&output=:seon.render/ai&prompt=true`
on the dev cluster `juniper-context`, one load, 14 `#:seon.repl{…}` responses.
Handle census of that byte stream:

| handle | occurrences |
|---|---|
| `result/e0` | 9 |
| `result/e1` | 3 |
| `result/e2` | 3 |
| `result/e3` | 3 |

`result/e0` named four DIFFERENT values in one agent's context, verbatim:

```
#:seon.repl{:value "Agent     juniper\nNamespace my.agents.juniper\nCluster   juniper-context", :result result/e0}
#:seon.repl{:value "Cluster juniper-context.\nConfiguration juniper-context; 1 shared instruction and 9 toolkit namespaces.", :result result/e0}
#:seon.repl{:value "Plan for juniper\nObjective: Improve Juniper context inspection…", :result result/e0}
#:seon.repl{:value "Plan step 1 Render this plan clearly [juniper/render-plan] — open…", :result result/e0}
```

Ordinals restart at 0 in every run, so the name an agent reads in its context
resolves — in the next turn's fork — to whichever run happened to be bound.
A silently wrong value is the worst available failure.

## 2. The change

**One derivation, in one place.** `seon.sci.admit` now owns both halves of the
question, because it already owns the print node's grammar and nothing above
it may re-decide the answer:

- `seon.sci.admit/result-handle` — `[:=> [:cat :int] :qualified-symbol]`,
  `(symbol "result" (str "e" entity-id))`. The evaluation entity's own id, so
  two runs of one agent can never mint one name for two values.
- `seon.sci.admit/restorable-node` — `[:=> [:cat [:maybe :string]] [:maybe :map]]`,
  moved out of `seon.sci.eval` (it was `opaque-result-faces` +
  `restorable-result-node`, both private) and made one public contracted
  function. Ruling 59c's refusal set is unchanged.

**The binding follows the identity.** `seon.sci.eval/bind-result!` takes the
handle symbol, not an ordinal, and interns `(name handle)` in the `result`
namespace. `bind-stored-results!` binds every stored evaluation of the calling
AGENT whose node is restorable — not one run's ordinals — because the agent's
context renders every run's evaluations; `fork-for-turn` hands it the agent id.

**An unpersisted evaluation has no handle.** `seon.cluster.loop/evaluate-sources`
resolves the frozen evaluation entity id against the connection's current
value after each form (the turn's one intent commit already transacted it),
binds the live value under that handle, and assoc's `:seon.repl/handle` onto
the evaluation. The debug page's in-memory preview froze nothing, so it
resolves nothing, binds nothing, and shows no `:result` key — ruling 59c.

**The grammar carries a fact, not a flag.** `:seon.repl/handle`
(`:qualified-symbol`) enters `:seon.repl/emission`; `:seon.repl/result` is
emitted exactly when it is present, as `(str handle)`.
`:seon.repl/result-handle?` — the boolean with no production writer that the
audit filed as C2 — is deleted with its one test use.
`seon.repl/entity-emission` and `seon.render.transcript/emission` both derive
the handle from the same two facts: the entity id, and `restorable-node`.

## 3. Gates

`bin/test seon.repl-test seon.sci.eval-test seon.cluster.evaluate-sources-test
seon.cluster.run-test seon.render.transcript-test seon.cluster.agent-test
seon.cluster.turn-test` — **197 tests / 1256 assertions / 32 failures, 2
errors**, 17 red tests. Two of the seventeen were this lane's and are fixed;
the other fifteen are inherited, each already recorded:

| namespace | red | attribution |
|---|---|---|
| `seon.repl-test` | 0 | — |
| `seon.cluster.evaluate-sources-test` | 0 | — |
| `seon.cluster.run-test` | 1 (`settlement-mints-rows-for-unindexed-call-targets`) | inherited ([repl-grammar greens §1.1](repl-grammar-greens-2026-09-07.md)) |
| `seon.sci.eval-test` | 2 (`runtime-function-rows-carry-parsed-contract-facts`, `static-and-runtime-contracted-definitions-publish-identical-facts`) | inherited (greens §5) |
| `seon.cluster.turn-test` | 3 (`a-run-prompts-from-its-opening-database-value`, `a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end`, `turn-intent-is-the-complete-crash-falsifier`) | inherited (greens §5) |
| `seon.render.transcript-test` | 8, the disabled-rendering-limits and message-sentence families | inherited (greens §1.2) |
| `seon.cluster.agent-test` | 1 (`install-gate-failure-settles-commits-and-cancels-the-turn-backstop`) | inherited ([namespace-steward landing §5](namespace-steward-landing-2026-09-07.md), reproduced there with that lane's files reverted) |

The two that were this lane's, both now green:

- `seon.render.transcript-test/selected-evaluations-project-only-their-stored-source-and-result`
  — the `populated-history` fixture stored `"42"` as an evaluation's
  `result-edn`. That is raw EDN the writer never produces; the handle is
  derived from the node's face, so the fixture was asserting a shape that
  cannot have one. The fixture now stores the admitted node.
  Re-run: `bin/test seon.render.transcript-test seon.cluster.agent-test` →
  the transcript reds are exactly the eight inherited.
- `seon.cluster.agent-test/system-source-submission-uses-the-ordinary-durable-run`
  — a stale expectation from the reply-span repair (the comment is now its own
  fact, not glued into `/source`), which this lane's source edit made visible.
  It asserts both facts now. Re-run: `bin/test seon.cluster.agent-test` →
  **22 tests / 204 assertions / 0 failures, 1 error**, that one error being the
  inherited backstop timeout.

Direct proof of the emitter, run against a fresh JVM on this tree:

```
result/e8143
my.probe=> (+ 1 1)
#:seon.repl{:value 2, :result result/e8143}      ; :db/id 8143, restorable node
user=> (+ 1 1)
#:seon.repl{:value 2}                            ; no :db/id → no handle
user=> (atom 1)
#:seon.repl{:value #object[]}                    ; opaque node → no handle
```

The class regression is
`seon.sci.eval-test/a-later-turn-reaches-the-values-its-earlier-forms-produced`:
one agent, two runs, both at ordinal 0, plus an opaque node. It asserts the two
handles differ, that both are qualified symbols in the `result` namespace, and
that a third fork resolves each to its own value while the opaque one stays
unbound.

## 4. The live page after convergence

`bin/seon --root tmp/juniper-context-live init --dev juniper-context` reported
`development schema declarations`, `program reconciliation`, `loaded
definitions`, `SCI acquisition` and `JVM instrumentation` — then refused its
final commit-id fact with
`:seon.boot/refused` / `"Source changed during development adoption; the next
edit must converge it."`, twice in a row. That refusal is by design and it
fires on the DIGEST, not on the adoption: everything above it succeeded, so the
running JVM serves this code, and only the recorded commit id is withheld.
Other lanes were editing the shared tree during both ~40 s adoptions. Reported,
not worked around.

The page proves it. Same feed, same load shape as §1
(`/feed/juniper?debug=true&output=:seon.render/ai&prompt=true`):

| handle | occurrences | evaluation |
|---|---|---|
| `result/e33866` | 14 | the persisted `(seon.bootstrap/whoami)` map |
| `result/e33884` | 14 | `(dir my.message)` |
| `result/e33897` | 14 | `(dir my.run)`, run one |
| `result/e33910` | 14 | `(dir my.run)`, run two |

Four distinct evaluations, four distinct handles, no repetition — against nine
occurrences of one `result/e0` before. The exact bytes:

```
#:seon.repl{:value [my.message/decline my.message/inbox my.message/read my.message/send], :result result/e33884, :out "decline\ninbox\nread\nsend\n"}
#:seon.repl{:value [my.run/complete my.run/render-namespace-ai my.run/usage-form my.run/wait
  my.run/walkthrough], :result result/e33897, :out "complete\nrender-namespace-ai\nusage-form\nwait\nwalkthrough\n"}
#:seon.repl{:value [my.run/complete my.run/render-namespace-ai my.run/usage-form my.run/wait
  my.run/walkthrough], :result result/e33910, :out "complete\nrender-namespace-ai\nusage-form\nwait\nwalkthrough\n"}
```

`e33897` and `e33910` are the sharpest case: the same source and the same value
in two different runs, and they are two names now.

And the unpersisted previews carry NO handle, exactly as ruling 59c says. The
four responses that were all `result/e0` in §1 now read:

```
#:seon.repl{:value "Agent     juniper\nNamespace my.agents.juniper\nCluster   juniper-context"}
#:seon.repl{:value "Cluster juniper-context.\nConfiguration juniper-context; 1 shared instruction and 9 toolkit namespaces."}
#:seon.repl{:value "Plan for juniper\nObjective: Improve Juniper context inspection…"}
#:seon.repl{:value "Plan step 1 Render this plan clearly [juniper/render-plan] — open…"}
```

These are the debug page's in-memory previews. They froze no evaluation entity,
so they resolve no identity, bind nothing, and name nothing.

## 5. Out of scope, seen while here

- `#:seon.repl{:value #object[]}` — an admitted `:seon.print/object` node
  renders as an empty `#object[]`, naming neither its class nor anything else.
  Ugly output by the standing order; not this lane's seam. Filed as
  [an issue](../../../seon/issues/object-print-node-renders-as-empty-object.md).
- AGENTS.md has no `result/` vocabulary row to update (`rg "result/" AGENTS.md`
  returns nothing); the PRD's §4 examples still spell `result/e0`/`e1`/`e2` in
  its illustration, which its own §4 prose already corrects to
  `result/e<entity-id>`. Not an owned path here.
