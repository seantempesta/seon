---
date: 2026-09-16
lane: dir-elision-floor
surface: render-value / print
---

# A cut that showed nothing: the AI projection's missing floor

Issues:
[dir-of-a-namespace-returns-an-elision-with-nothing-shown](../../../seon/issues/dir-of-a-namespace-returns-an-elision-with-nothing-shown.md)
and
[a-value-larger-than-the-budget-is-elided-to-nothing](../../../seon/issues/a-value-larger-than-the-budget-is-elided-to-nothing.md)
— one class, two sightings.

## Reproduced first — `default` pid 45917, SCI evaluation mode, read-only

```clojure
(dir seon.turn)
;; shown text, verbatim, 718 ms, 208 bytes:
;; {:seon.print/bound-by :seon.render.profile/token-budget,
;;  :seon.print/elision-unit :characters, :seon.print/omitted 41040,
;;  :seon.render.data/next-offset 0, :seon.render.data/path [],
;;  :seon.render.data/total 41040}
```

The second sighting reproduced identically:

```clojure
[(apply str (repeat 3000 "ab"))]
;; [{:seon.print/bound-by :seon.render.profile/token-budget,
;;   :seon.print/elision-unit :characters, :seon.print/omitted 6000,
;;   :seon.render.data/next-offset 0, :seon.render.data/path [0],
;;   :seon.render.data/total 6000}]
```

A plain 300-member vector was NOT affected (32 members shown, elision with
`next-offset 32`), so the defect is not "collections are cut badly" — it is
every cut that lands on a STRING or a projected producer's text.

## Cause — three seams, one disease

1. **`seon.print/fit-text` omitted its whole subject.** It minted
   `(elision-node … path 0 original original :characters nil)`: offset zero,
   omitted equal to total, no prefix. The characters that fit were computed
   and thrown away.
2. **`seon.print/fit`'s search had no floor.** The loop drove `child-limit`,
   then `depth-limit`, then `string-limit` to ZERO — a zero string limit is
   "show no characters", a zero child limit "show no members". Absence read
   as a bound.
3. **`render-elision-ai` dropped `::prefix` and `::requery-refusal`** from
   the AI text and reported CHARACTER counts, which AGENTS.md §2.4 rules are
   storage projections, never the agent-facing size.

Why `dir` in particular: `:seon.repl/directory` declares the AI pair
`seon.repl/render-directory-ai` (`resources/seon/schemas/seon.repl.edn:2`),
which `pr-str`s the whole directory with `*print-length* nil`. The floor
therefore meets ONE 41,040-character projected node, not 150 members. The
selection is correct — measured live, `matching-shapes-in` answers `[]` for a
fabricated `{:schemas … :functions …}`, so the pair is chosen on the real
directory's shape and nothing else hijacks it.

## Fix — `src/seon/print.cljc` only, the one clipping spot

- `fit-text` keeps `string-limit` characters as the cut's declared
  `::prefix`, sets `next-offset` to how many were shown, and counts only the
  remainder.
- `enrich-node`'s `::truncated-string` branch does the same with the text
  admission already kept.
- `fit`'s search floors STRUCTURALLY at one child, one level, one character.
  Below the floor the candidate may exceed the budget; that is the honest
  answer and its cut names the bound. The string step now HALVES rather than
  jumping to zero, so the retained prefix is as large as the budget allows.
- `render-elision-ai` surfaces `::prefix` and `::requery-refusal`, and adds
  `:seon.ai.tokens/estimate` — the omitted remainder's estimated token size,
  the figure AGENTS.md §2.4 rules an agent budgets by.

## Live proof — `default` pid 95853 (post-refork), SCI evaluation mode

```clojure
(dir seon.turn)
;; shown text, 494 ms, 1,893 bytes (was 208):
;; {:seon.print/bound-by :seon.render.profile/token-budget,
;;  :seon.print/elision-unit :tokens,
;;  :seon.print/omitted 12386,
;;  :seon.print/prefix "#:seon.repl{:columns [:sym :arglists :doc :in :out :supplied], :rows [[seon.turn/append-generated-call ([db request]) \"Append exactly one system-authored form to an open generated turn.\" [:cat :seon.db/database-value :seon.turn/generated-form-request] :seon.store/transaction-data nil] … [seon.turn/disp",
;;  :seon.print/requery-refusal "the value has no result handle",
;;  :seon.render.data/next-offset 511,
;;  :seon.render.data/path [],
;;  :seon.render.data/total 12898}
```

Eight complete function rows — symbol, arglists, docstring, `:in`, `:out`,
`:supplied` — where the agent previously received a count and nothing else.
The string sighting shows the same shape: a 1,638-character prefix,
`:elision-unit :tokens`, `omitted 1363` of `total 1875`, `next-offset 511`.

The `requery-refusal` is honest, not a residual: the MCP SCI path creates no
evaluation, so the value has no `result/e…` handle. An agent's own evaluation
carries one, and the regressions assert the `seon.print/value-at` form
appears when a handle is supplied.

## Verification boundary

- Reproduction, cause and the live after-bytes are on `default`, read-only
  SCI evaluations.
- The first probe round redefined `seon.print` Vars through the MCP with
  `::`-aliased keywords. **The MCP `jvm` reader resolves `::` in `user`, not
  in the `namespace` argument**, so those definitions installed
  `:user/…` keys and briefly broke rendering in the shared JVM. Restored with
  `(require 'seon.print :reload)` within the same minute, and the orchestrator
  reforked `default` afterwards. Do not send `::` in an MCP form.
- Adoption of this edit was REFUSED on the pre-refork `default`
  (`:seon.issue/agent` incompatible schema change, a foreign lane's). The
  post-refork JVM carries the edit; the live bytes above are from it.
- **No in-process regression run: the shared fixture base REFUSES to
  construct in this JVM.** Followed the base construction rule exactly — a
  daemon-thread `future`, no bound, reached through `seon.test`'s own loader
  because `test/` is not on `default`'s classpath — and it answered
  `#:seon.error{:kind :seon.test-support/database-base-unavailable, :message
  "Canonical fixture base construction failed: Schema declaration resolution
  requires the projection handed to the operation."}`. Nothing was rebuilt
  (fixture-base poison rule); recorded in
  [in-process-test-runs-poison-the-shared-fixture-base](../../../seon/issues/in-process-test-runs-poison-the-shared-fixture-base.md).
  The five test files need the cold gate; requested in
  `tmp/orchestrator/gate-requests/dir-elision.txt`.
- clj-kondo: 0 errors on all changed files; warnings are pre-existing.

## Residual, filed not fixed

`seon.repl/render-directory-ai` is an AI render function that applies NO
profile — it is `pr-str` of the whole directory. AGENTS.md §2.4 says the AI
render functions apply the profile's limits; this one cannot, because its
contract returns a bare string. The floor can only cut its output as TEXT, so
`dir` pages by character offset rather than by member. Removing or bounding
that pair is a separate slice: `:seon.repl/directory`'s pair is asserted by
`seon.data-shapes-test`, `seon.render.value-test` and
`seon.render.web-debug-test`.


## Correction after batch 54 — a coordinate is not a display size

The first cut of this slice rewrote `:seon.print/omitted`,
`:seon.render.data/total` and `:seon.render.data/next-offset` into estimated
tokens and relabelled the unit `:tokens`. Batch 54 falsified that, and the
evidence is worth keeping:

| red | what it proved |
|---|---|
| `seon.render.value-test/explicit-structural-results-retain-attributes-through-real-evaluation` (×4) | `total` IS `(count original)` and `omitted` IS `total - next-offset`, derived from the value itself and then used to execute `(:seon.print/requery-form cut)` |
| `seon.render.value-test/generated-values-have-deterministic-readable-executable-elisions` | the same invariant, generatively, over arbitrary values |
| `an-oversized-string-shows-its-prefix-not-only-a-count` (this lane's own) | `1875 ≠ 1363 + 511` — three independently floored estimates stop adding up |

So these fields are the reader's COORDINATES into the value, not display
sizes. Restating one as a floored estimate names a position that does not
exist, and the cut's own numbers contradict each other on the page. The
declared fields now keep the value's own units, and the token figure rides
alongside as `:seon.ai.tokens/estimate`, present only where it is derivable
(a character cut) and absent on a member cut rather than invented.

### The second correction — breadth and text degrade together

Restoring the coordinates left one red standing and produced another, and the
pair of them name the real shape of the search:

- keeping the original order (children, then depth, then strings) let ONE
  string be allotted the entire budget, so the search paid for its length by
  deleting a whole map entry. `background-poll-keeps-identity-while-payloads-grow`
  lost `:seon.effect/result-edn` outright — the reader could not even see
  which attribute had been cut. Measured at the seam: entries retained were
  `[:seon.effect/duration-ms :seon.effect/id]` and a `:children` elision.
- inverting it (strings first) clipped a 36-character one-liner to nothing to
  pay for 116 siblings: `fit-preserves-breadth-and-long-strings` went red on
  "a one-line string remains readable before structural breadth", measured
  `includes-text false, item-count 3`.

Either order destroys one dimension to save the other. The search now HALVES
BOTH at once and leaves depth last, because a cut level takes a whole subtree
with it. Measured after: every entry retained, the 8,000-character string
clipped to 1,638 with `total 8000 = 1638 + 6362`, whole text 647 tokens
against a 1,024-token budget.

### In-process runs, `default` pid 95853, reloaded `seon.print`

| run | result |
|---|---|
| `seon.print-test/fit-preserves-breadth-and-long-strings` | 7 pass, 0 fail, 0 error |
| `seon.print-test/fit-bounds-a-terminal-projection-and-names-what-it-omitted` | 11 pass, 0 fail, 0 error |
| `seon.print-test/refitting-a-truncated-collection-preserves-its-honest-elision` | 5 pass, 0 fail, 0 error |
| `seon.render.value-test/an-oversized-string-shows-its-prefix-not-only-a-count` | 11 pass, 0 fail, 0 error |
| `seon.render.value-test/an-agent-facing-cut-reports-its-size-in-estimated-tokens` | 7 pass, 0 fail, 0 error |
| `seon.render.value-test/dir-of-a-large-namespace-shows-members-and-how-to-continue` | 6 pass, 0 fail, 0 error |
| `seon.render.value-test/background-poll-keeps-identity-while-payloads-grow` | 17 pass, 0 fail, 0 error |
| `seon.render.value-test/documentation-body-is-whole-or-one-executable-elision` | 8 pass, 0 fail, 0 error |
| `seon.render.value-test/explicit-structural-results-retain-attributes-through-real-evaluation` | 56 pass, 0 fail, 0 error |
| `seon.render.value-test/generated-values-have-deterministic-readable-executable-elisions` | 2 pass, 0 fail, 0 error |

Every batch-54 red attributed to this slice is green in process. ONE
CAVEAT, stated rather than smoothed over: in the back-to-back sweep
`an-oversized-string-shows-its-prefix-not-only-a-count` recorded 11 pass, 0
fail and 1 ERROR once; re-run alone immediately afterwards it was 11/0/0. The
error did not reproduce and its text was not captured, so it is unexplained,
not resolved.

### Final live bytes — `default` pid 95853, SCI evaluation mode

```clojure
(dir seon.turn)
;; {:seon.ai.tokens/estimate 12386,
;;  :seon.print/bound-by :seon.render.profile/token-budget,
;;  :seon.print/elision-unit :characters,
;;  :seon.print/omitted 39636,
;;  :seon.print/prefix "#:seon.repl{:columns [:sym :arglists :doc :in :out :supplied], :rows [[seon.turn/append-generated-call …",
;;  :seon.print/requery-refusal "the value has no result handle",
;;  :seon.render.data/next-offset 1638,
;;  :seon.render.data/path [],
;;  :seon.render.data/total 41274}
```

1638 + 39636 = 41274: the coordinates add up, and the token cost rides
alongside them.

### Batch 55 — the render-coverage reds are NOT this slice

`seon.render-coverage-test/effect-receipts-render-state-from-attribute-presence`
run in process against the SAME fixture, three times in one JVM:

| `seon.print` loaded | result |
|---|---|
| HEAD (this slice) | 12 pass, 20 fail, 3 error |
| `bb33b93fa~1` (pre-slice), `load-file`d | 12 pass, 20 fail, 3 error |
| HEAD again | 12 pass, 20 fail, 3 error |

Identical, so the floor is not the cause. The refusal is minted by
`seon.render.value/node-id` (`src/seon/render/value.clj:82`), which requires a
caller-supplied root address — `:seon.render.call/id`, `:seon.render.value/root`,
`:db/id` or `:seon.render.block/name`. The fixture's `render-request`
(`test/seon/render_coverage_test.clj:37`) supplies none of them, so the
fixture only ever passed while SELECTION found a declared producer for an
effect receipt and never reached the floor. The candidate is `3f07beb88`
("Rename agent attributes to seon.agent"), the one recent commit touching
both `src/seon/render/value.clj` and that test. The peer's `15a15e9c1` is NOT
a candidate: it touches `src/seon/turn.clj` only — it does not modify
`src/seon/render.clj` at all, contrary to the dispatch note.

Stopping there, as instructed. Worth the owner's attention, though: the
coordinator's reading is right that an agent id is presentation context
rather than a precondition, but `node-id` does not refuse on the agent id —
it refuses on the ROOT ADDRESS, and `:seon.agent/id nil` in the refusal's
evidence is a symptom shown beside it, not the cause.

Adoption is STILL refused on `default` — now by `:seon.issue/title` changing
`:db/index` from true to nil, another lane's in-flight resources edit, a
different attribute from the pre-refork refusal. The runs above are against
`(require 'seon.print :reload)`, which drops that namespace's contract
wrappers; the cold gate remains the proof.

### Reds attributed to other lanes at this HEAD

`seon.cluster.agent-arming-test/starting-an-issue-leaves-one-unanswered-wake-its-first-reply-answers`
(event backstop), `seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`
(625 ms against a 300 ms bound), `seon.html-views-test/fault-pairs-preserve-ai`
(a golden fault string), and
`seon.render.web-test/declared-units-are-components-in-schema-order`
(`:db/id` and `:db/txInstant` now reaching the declared list). None touch the
print floor; none were changed here.


## Batch 57 — the last red was a second sighting of the open-map shadow

`seon.print-test` and `seon.render.value-test` are GREEN COLD on `b7e0bda66`.
The one red left,
`seon.render.web-test/declared-units-are-components-in-schema-order`, was
`declared-entity-units` returning the six agent units PLUS `:db/id` and
`:db/txInstant`.

No pull selector was widened: the fixture's pull is `'[*]` and has been since
the test was written. Measured in process on `default`, the contributor is
ONE shape:

```clojure
;; contributors carrying :db/id into the agent entity's unit list
[[":seon.render.transcript/pulled-transaction" [:db/id :db/txInstant]]]
;; and the registry declares neither attribute in its own right
{:db-id-declared? false :db-tx-declared? false}
```

`:seon.render.transcript/pulled-transaction` is `[:map [:db/id :int]
[:db/txInstant {:optional true} :inst]]`
(`resources/seon/schemas/seon.render.transcript.edn:16`). Maps are OPEN, so
EVERY entity a `'[*]` pull returns satisfies it, and its entries were folded
into every page's unit list. This is the same class the `sci-pull` lane fixed
at the AI-pair seam — a small open map shadowing a whole entity family —
reaching a different consumer.

The expectation is right and the mechanism was wrong: `:db/id` is the
database's address for an entity, not one of its units, and other surfaces
already forbid showing it
(`seon.render-coverage-test` asserts `(not (str/includes? ai ":db/id"))`).

Fixed at the root in `seon.render.web/declared-unit?`: an attribute is a unit
only when the REGISTRY DECLARES IT — `(projection-form projection
(forward-attribute attribute))` is present. That is a derivation over data
the function already holds, not a reserved-name list or a `:db/` prefix
test. Measured after:

```clojure
(declared-entity-units projection database entity)
;; => [:seon.agent/id :seon.agent/plan :seon.agent/namespace
;;     :seon.agent/settings :seon.agent/runtime :seon.message/inbound-content]
(declared-entity-units projection database "ordinary value") ;; => []
```


## Batch 70 — the reds are not frozen elision expectations

The dispatch read these as 45 assertions frozen on the retired elision text.
They are not, and the two namespaces it named are not elision failures at all:

- `seon.cluster.agent-identity-test/identity-map-and-omitted-arguments-use-the-same-function`
  (5) fails on `(= "identity-root" nil)` for `:my.agent/id`, a missing
  `:my.agent/namespace`, `:my.agent/steward` and `:my.agent/turns-left`. An
  agent identity/dials change, no elision in the assertions.
- `seon.context-selection-test/selection-references-terminal-evaluations-in-writer-decided-order`
  (15) fails on writer refusals — `:datahike/write-rejected
  {:kind :seon.context/selection-refused, :cause "Context selection refused:
  no-such-agent."}` — and on `:seon.context/no-such-agent` arriving where
  `:seon.context/no-such-run` was expected. The same agent-identity family.

**No test outside this lane's four files asserts elision text at all.**
`rg` over `test/` for `:seon.print/elision-unit`, a `:characters` literal or
`:seon.ai.tokens/estimate` finds only `value_test`, `print_test`, `web_test`
and `transcript_test` — all already updated here and all green cold in batch
57.

The real elision-shaped reds are in `seon.concurrency-independence-test`
(199), and they are CONTENT assertions, not presentation ones:
`(is (str/includes? rendered (::payload incoming)))`. A/B in one JVM, same
input (2,075 characters, ~640-token budget):

| `seon.print` | result |
|---|---|
| `bb33b93fa~1` (pre-slice) | `omitted 2075`, `next-offset 0`, **no prefix** |
| HEAD | `omitted 1051`, `next-offset 1024`, 1,024-character prefix |

A whole-omission elision contains no payload, so those assertions could not
have passed before this slice either. The floor strictly increased what they
can see. **Nothing was changed for batch 70**: relaxing them would hide a real
defect, and no assertion among them executes a requery form against the
coordinates, so no mechanism bug in the floor is implicated.

### The mechanism bug that IS there, filed not fixed

`seon.render.transcript/render-ai` carries `seon.render/request-profile` — the
VALUE render profile — and renders the whole history through
`seon.render.value/render-ai` (`src/seon/render/transcript.clj:1893`,
`src/seon/render/transcript.clj:425`). The walk emits the history as one
string, so an over-budget history is cut at a CHARACTER OFFSET: mid-form,
mid-message, `:seon.render.data/path []`, nothing naming which turns were
lost. It should be cut BY EVALUATION, oldest first, and judged against the
prompt's own `:seon.config.ai/prompt-token-budget`
(`src/seon/cluster/prompt.clj:232`) rather than the bound for one result.
Filed as
[the-agents-history-is-cut-as-one-string-by-the-value-budget](../../../seon/issues/the-agents-history-is-cut-as-one-string-by-the-value-budget.md).
