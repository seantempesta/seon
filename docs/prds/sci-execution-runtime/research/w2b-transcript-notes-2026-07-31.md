---
type: research
status: active
tags: [prd, research, render, context]
---

# W2b transcript projection

## Result

W2b implements the transcript as one pure, bounded agent-level render unit in
`seon.render.transcript`. It derives a time-ordered history from messages in
both directions plus eval receipts on the agent's runs. Decision 11 now
constrains both projections to strict REPL display: each form is followed by
its actual computed value, and notices are ordinary values rather than source
comments. HTML wraps those same bytes in stable per-entry elements.

This is a **pure-call proof**, not a live-context or attached-walk proof. Per
the owner's attachment ruling, W2b does not add message-family render keys and
does not displace the existing agent lens. W4 owns discovery when membership
inverts.

## Dependency ledger

- Datahike is the maintained `reference-code/datahike` revision
  `9b3be9d59cb07d9c895af280e60eb074bb57a400`. The implementation uses
  `datahike.api/q` and `datahike.api/pull-many` with concrete selectors; it
  uses neither wildcard pull nor entity views. Ordered query options are
  grounded in `reference-code/datahike/src/datahike/query.cljc` and its
  `test-order-by` coverage in
  `reference-code/datahike/test/datahike/test/query_planner_test.clj`.
- Clojure is 1.12.5 and Malli is 0.20.0 from `deps.edn`. Public render twins
  use the existing `:seon.render/unit`, `:string`, and
  `:seon.render/hiccup` contracts from `resources/seon/schema/render.edn`.
- The property uses test.check 1.1.1 from the test alias; its maintained
  source quarry is `reference-code/test.check` at
  `5ba3a25b60cf66ff531db8f44d89145abfacfa5c`.
- First-party idioms are `src/seon/render/ns.clj` for one-unit twin
  projections, `src/seon/render/block.clj` for stable `surface-id` values,
  `src/seon/ai/tokens.cljc` for all human-visible size decisions, and
  `seon.test-support/with-database` for canonical isolated fixture databases.

The relevant stored time facts are exactly
`:seon.cluster.message/at` and `:seon.cluster.eval/at`. Receipts are reached by
`:seon.cluster.run/agent` followed by `:seon.cluster.eval/run`; eval source is
looked up by run plus `:seon.cluster.run.form/ordinal`. Message membership is
the union of the two concrete reverse reads over
`:seon.cluster.message/to` and `:seon.cluster.message/from` in one `or-join`;
`count-distinct` and the query relation make a self-message one event.

An `about` ref can target any installed unique identity, so the renderer does
not maintain an identity-attribute hand list. It reads the ref's eid, derives
identity attributes from Datahike's own `db[:schema]` `:db/unique` facts, and
selects a string that resolves uniquely to the same entity under the delivery
owner's rule. If later database changes make that impossible, the projection
emits an ordinary value carrying `:seon.transcript/unresolved-about?` instead
of inventing an eid or silently dropping the relation.

## Projection and budget contract

Entries sort by stored instant, then message before eval for an exact time
tie, then stable message/eval id. The newest six entries are eligible for full
detail. Older entries render as summaries. Budget pressure tries full detail,
then the largest valid summary preview, then loudly elides one oldest prefix.
No age/detail decision is stored. Application-side acquisition is bounded by
the larger of six entries and the valid token budget: each family query is
ordered and limited, their newest results are merged, and only that bounded
candidate suffix is pulled. Two scalar `count-distinct` queries retain the
exact total needed by the loud marker without materializing the older entity
maps.

The minimum accepted output is derived by estimating the exact smallest
honest pair of projections: the HTML wrapper and, whenever facts are dropped,
the loud elision marker. A request below that floor is raised to the derived
floor rather than refusing a valid render request. The recurring property
chooses only valid budgets at or above that floor and proves that:

- AI and HTML contain the same time-ordered entry identities and detail;
- every generated message/receipt is either rendered or included in the loud
  oldest-prefix elision count;
- the visible ids are exactly the suffix of the independent ordering oracle;
- both token estimates are at most the requested valid budget; and
- every AI result is readable as zero or more Clojure forms.

The generator covers empty through 18-entry histories, tied timestamps,
inbound/outbound/self/about/declination messages, successful, failed,
interrupted, running, waiting, malformed-byte, and co-present
result/error/interruption/output receipts. Schema-valid malformed source or
result bytes are retained inside bounded ordinary values; they are never
smuggled into comments, and one bad stored string cannot corrupt the rest of
the transcript. Seed `2026073104`, 40 trials.

## Attachment seam for W4

W4 should add **one separate transcript block on the agent**, discovered once
from the agent's inverted membership. It should call these exact twins with
the same map:

```clojure
(seon.render.transcript/render-ai
 {:seon.db/db db
  :seon.cluster.agent/id agent-id
  :seon.render.transcript/token-budget token-budget})

(seon.render.transcript/render-html
 {:seon.db/db db
  :seon.cluster.agent/id agent-id
  :seon.render.transcript/token-budget token-budget})
```

If W4 needs to expose the valid floor before allocation, its mechanical call
is:

```clojure
(seon.render.transcript/minimum-token-budget
 {:seon.db/db db
  :seon.cluster.agent/id agent-id})
```

Do not attach either twin to `message.edn`: that would render the entire
transcript once per message. Do not replace the agent lens: the lens and the
transcript are distinct blocks. The later walk/cache call identity is the
render function plus this explicit agent map; there is no message entity
argument.

## Pure-call proof

The proof populated the canonical fixture database with two agents, five
messages, three runs, three forms, and three eval receipts. It then called the
twins directly at two budgets and measured the returned values with
`seon.ai.tokens/estimate`.

| Request | Budget | Derived minimum | AI tokens | HTML tokens | Visible / elided |
|---|---:|---:|---:|---:|---:|
| full | 100,000 | 46 | 452 | 964 | 8 / 0 |
| tight | 226 | 46 | 71 | 167 | 1 / 7 |

The full projection restored every item in the S1 fidelity checklist, with the
same transcript bytes present inside the HTML entry `<code>` elements:

```clojure
(my.message/send "transcript-agent" "Repair the owning namespace." "problem-transcript")
(my.message/send "transcript-peer" "Check the repaired namespace.")
(my.message/decline "transcript-peer" "problem-transcript" "The namespace is not mine.")

(my.run/wait "waiting for the peer review")
{:my.run/disposition :wait :my.run/note "waiting for the peer review"}
```

The failed/interrupted eval retained its form, flat result value, receipt
error identity, and effect warning without treating those legal co-present
facts as mutually exclusive:

```clojure
(missing.function/call)
{:seon.error/kind :seon.sci.eval/refused}
{:seon.cluster.eval/error "No such namespace: missing.function"
 :seon.error/kind :seon.sci.eval/refused
 :seon.problems/id "problem-eval-error"}
{:seon.cluster.eval/interrupted-at #inst "2026-07-31T12:13:24.501-00:00"
 :seon.cluster.eval/notice "Its effect may have happened; nothing was retried."}
```

The first two entries were age-derived summaries while the newest six were
full. At the tight budget both projections emitted the same loud marker:

```clojure
{:seon.transcript/elided 7
 :seon.transcript/notice "7 older transcript entries elided by the token budget."}
```

The HTML marker was verbatim
`<p class="seon-transcript-elision" data-transcript-elided="7">7 older
transcript entries elided by the token budget.</p>`, followed by the newest
entry at stable id `surface-seon.transcript.message_2f_self-4`.

This document's original comment-framed proof established ordering, bounds,
and twin parity, but decision 11 supersedes that display shape. Current
`entry-header` and the elision arm still emit `;;` text
(`src/seon/render/transcript.clj:326-330,503-505`); those sites are an
implementation defect, not evidence that comments remain an output type.

## Recurring evidence

`bin/test seon.render.transcript-test` passed 5 tests and 41 assertions with
zero failures and zero errors. The examples cover the populated fidelity
fixture, a tight budget, schema-valid malformed bytes, arbitrary installed
identity attributes, and a 100-message bounded-acquisition check. The seeded
generative property covers ordering, totality, twin parity, reader validity,
and both token bounds over the widened domain above.

The first independent adversarial audit falsified the initial green version
on malformed stored bytes, co-present error facts, a `:seon.test/sym` about
ref, and unbounded entity pulls. Those four counterexamples became the
regressions described above; the initial 3-test/30-assertion gate and its old
token measurements are deliberately not claimed as final evidence.
The follow-up audit independently cleared all four fixes and verified that no
variable-attribute query remains; the transcript's database dependency set is
finite rather than `:all`.
