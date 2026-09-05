---
type: research
status: active
tags: [research, render, agent]
---

# Context quality audit — what agents actually read, 2026-08-10

Read of the EXACT `:seon.render/ai` bytes the live default cluster
(pid 31570, `http://127.0.0.1:7994`) hands its agents. Every unit of root's
context was read end to end; every number below is
`seon.ai.tokens/estimate`, never a raw character count. No production
source was edited.

## Method and provenance

- Root's exact bytes came from `/ns/my.agents.root/debug`, left pane
  (`:seon.render/ai`), the last recorded `:seon.context.capture` for run
  `2ddfec05-01ba-4957-a65a-d310e85daad2`, basis `536871204`.
- The three core-namespace agents created 2026-08-10 (`seon.db`,
  `seon.fn`, `seon.render`) have NO recorded capture, so their prospective
  context was measured from the live walk the namespace page renders
  (`/ns/seon.db`) plus direct program-graph queries.
- Producer attribution was read in source; assembly is
  `seon.render.walk/prose` (`src/seon/render/walk.clj:568-671`) over
  `seon.render.walk/neighborhood` (`:280`), with per-family lenses in
  `src/seon/render/ns.clj`, `src/seon/render/transcript.clj`, and
  `src/seon/render/value.clj`.

## The measurement

Root's context: **18 units, 63,669 characters, 15,917 estimated tokens.**

| Unit | est. tokens | share |
|---|---|---|
| `d0 [:seon.cluster.agent/id "root"]` (transcript) | 5,581 | 35% |
| `d2 [:seon.ns/name my.fs]` | 2,843 | 18% |
| `d2 [:seon.ns/name my.web]` | 2,249 | 14% |
| `d2 [:seon.ns/name my.web]`…`my.background` (7 agent-facing namespaces total) | 9,552 | **60%** |
| `d1 [:seon.ns/name my.agents.root]` (own namespace) | 200 | 1% |
| cluster, config, run, message, bootstrap-note units (6 units) | 320 | 2% |
| `d2 [:seon.ns/name clojure.edn]` + `clojure.string` | 39 | 0.2% |

Cut a different way, by line kind:

| Line kind | count | est. tokens | share |
|---|---|---|---|
| `; schema <key> = <malli>` | 201 | 6,857 | **43%** |
| …of which `*-error` schema rows alone | 43 | 3,459 | **22%** |
| `; (register! …)` referenced-schema closure | 68 | 1,586 | 10% |
| `; fn <sym> — <contract> — <doc>` (the callable API) | 21 | 829 | **5%** |

**Five percent of the agent's context is the API it can call. Forty-three
percent is Malli schema source it never types.**

Elision, for contrast: `;; branches-elided=9 elided-tokens=110`. The
elision machinery saved 110 tokens while 15,917 were emitted. It is
eliding the cheap things.

---

## Ranked findings

### 1. The namespace renderer has no working token budget (blocker-shaped)

**Producer:** `src/seon/render/ns.clj:320-322`, `:447-461`.
**Cost:** ~9,552 tokens of root's 15,917 (60%); ~164,000 tokens for a core
namespace owner.

`seon.render.ns` reads its budget from a private key:

```clojure
(defn- token-budget
  [unit]
  (some-> (::token-budget unit) long (max 1)))       ; ns.clj:320-322
```

`::token-budget` here is `:seon.render.ns/token-budget`. **No caller in
`src/` or `test/` ever sets it** (`rg 'render\.ns/token-budget'` → zero
hits). The configured dial is
`:seon.config.render.agent/token-budget = 1024`, which
`seon.render/agent-render-profile` (`src/seon/render.clj:47-49`) maps to
`:seon.render.profile/token-budget`. `seon.render.value` reads that
profile correctly (`src/seon/render/value.clj:72-76`); `seon.render.ns`
does not read it at all. So `budgeted-ai` always takes the `(nil? budget)`
branch at `ns.clj:450` and renders everything, and `minimal-ai-text`
(`:435-445`) and `omission-comment` (`:329-339`) are unreachable in
production.

Consequence, measured: `my.fs` renders **2,843 tokens against a 1,024-token
declared budget** — 2.8×. This is the same absent budget recorded in
[render-token-budgets-are-private-dials-no-producer-supplies](../../../seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md);
that note's evidence measured 17,696 tokens on a `seon.flow`-owner walk on
2026-07-31. Today's measurement is an order of magnitude worse (below).

Worse, the budget is inverted where it *is* consulted. `compact-ai-text`
(`ns.clj:405-426`) emits **every** `own-schemas` row unconditionally at
line 417-419 and only caps `functions` via `included-count`. Even if the
key were supplied, the schema wall would be a fixed floor and the callable
API would be the thing squeezed out.

**Fix shape:** delete `::token-budget`; read
`:seon.render.profile/token-budget` from the request profile like
`seon.render.value` already does, and make the fit order *functions first,
own schemas second, referenced closure last* so the API survives
compaction and the schema source is what gets elided.

### 2. A core-namespace owner's context is ~5× its whole prompt budget, and those agents have never run

**Producer:** the walk fan-out, `src/seon/render/walk.clj:280-360`, with
per-namespace cost from finding 1.
**Cost:** `/ns/seon.db` renders **141 namespace family entries,
655,937 characters ≈ 163,984 estimated tokens.** The configured
`:seon.config.ai/prompt-token-budget` is **32,768**.

`seon.db`'s namespace declares 20 requires (root's `my.agents.root`
declares 2), and each neighbour renders a full compact card. The largest
single entries on that page: `my.web` 109,836 chars, `seon.db` 73,466,
`seon.schema` 43,602, `seon.ai` 39,698, `seon.config` 37,795.

The `seon.db` agent's own namespace unit renders at distance 1, which is
`full-ai-text` (`ns.clj:381-403`) — **the complete stored source of every
function**: 83 functions, 44,660 characters of source, plus 52 own schemas.
Root's own-namespace unit costs 200 tokens because root has two members;
the same code path costs a core owner ~11,000 tokens before neighbours.

`acquire-within-budget` (`src/seon/cluster/prompt.clj:148-193`) responds by
walking distance 2 → 1 → 0. At distance 0 the agent gets its transcript and
nothing else — no toolkit, no namespace, no cluster facts. Either way the
context is unusable: the agent is handed either 5× its budget or nothing.

Confirmed by facts, not inference:

```clojure
;; captures and attempts per agent, live default cluster
{:captures [["root" 8]], :attempts [["root" 8]]}
```

`seon.db`, `seon.fn`, and `seon.render` — created 2026-08-10 — have **zero
context captures and zero AI attempts**. No prompt has ever been built for
them. (Finding 3 explains the proximate cause; this finding is why they
would not have worked anyway.)

**Fix shape:** make the walk's namespace fan-out spend the profile budget
across neighbours rather than per-neighbour, and stop rendering the owner's
own namespace as complete source — the owner can `dir`/`doc` any member on
demand and is the one agent who least needs it dumped.

### 3. The bootstrap plan's deliberate teaching failures are committed as core faults and interrupt the run

**Producer:** `resources/seon/bootstrap.edn:49-70`.
**Cost:** three agents stranded; ~500 tokens of permanent transcript noise
per agent; 6 durable error facts; 6 messages to root.

The shipped bootstrap teaches by failing twice on purpose — first a `:any`
contract, then `(largest)` with no arguments:

```clojure
;; resources/seon/bootstrap.edn:49-51, :67
"(defn largest \"The row with the largest :amount.\"
   {:malli/schema [:=> [:cat [:sequential :any]] …]} …)"
"(largest)"
```

Both are classified as CORE FAULTS. The live database has one
`:user-input` error and one `:seon.instrument/contract-violated` error
bound to **every** bootstrap run:

```text
["bootstrap:seon.db"  :user-input  "seon.db/largest uses :any in an agent-authored contract. …"]
["bootstrap:seon.db"  :seon.instrument/contract-violated  "Wrong number of args (0) passed to: seon.db/largest"]
["bootstrap:seon.fn"     … same pair …]
["bootstrap:seon.render" … same pair …]
["bootstrap:root"     :user-input …]
["bootstrap:root"     :seon.sci.eval/evaluation-failed  "No such namespace: my.agents.root"]
```

The cluster dial is `core faults panic` (root's own config unit says so).
The fault committer then messages root, verbatim from root's context:

> Core fault `:seon.instrument/contract-violated` reached 3 occurrences in
> process 31570-1786191855600 (notification limit 3). Latest: Wrong number
> of args (0) passed to: seon.fn/largest **It interrupted run
> bootstrap:seon.fn.** Further occurrences remain in seon.problems but will
> not message you.

So the onboarding sequence designed to teach an agent its contract rules is
the thing that interrupts its first run, poisons the cluster's problem
counts (`:seon.problems/errored-receipts 9`, `:failed-runs 1`), and pages
root about it. Root pays for it forever: its transcript retains both
deliberate failures (lines 75-76 and 89-91 of the captured context) in
every future turn.

The two failures are also inconsistent: root's wrong-arity call reports
`No such namespace: my.agents.root` while the other three report
`Wrong number of args (0)` — live recurrence of
[a-wrong-arity-call-reports-a-missing-namespace](../../../seon/issues/a-wrong-arity-call-reports-a-missing-namespace.md).

**Fix shape:** a bootstrap form declared as an expected refusal is not a
core fault. Either mark the teaching forms so their refusals settle as
ordinary values, or teach the rules by stating them (the `(help)` text
already does) and drop the two failing forms entirely.

### 4. Namespace units are error-schema boilerplate agents never construct

**Producer:** `src/seon/render/ns.clj:351-354` (`compact-schema-line`),
`:363-379` (`referenced-schema-ai-section`).
**Cost:** 3,459 tokens (22% of root's whole context) in 43 lines.

Every capability namespace declares one `X` marker schema and one
`X-error` map schema per failure mode. Both render. The `-error` rows
carry the render-producer wiring verbatim; the string
`:seon.render/ai seon.error/render-ai` appears **40 times** in root's
context. One representative line, unedited:

```text
; schema :my.fs/not-found-error = [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must identify the absent filesystem path"} [:my.fs/not-found :my.fs/not-found] [:seon.error/message :seon.error/message]]
```

The agent never constructs a `:my.fs/not-found-error`; it *receives* one,
already rendered by the producer named in that very line. It also never
needs `:seon.render/html`. The one fact worth a token — "a not-found error
identifies the absent path" — is already the `:error/message`.

The referenced-schema closure repeats across units with no
deduplication: `(register! :seon.error/value …)` appears 6×,
`:seon.error/kind` 6×, `:seon.blob/digest` 4×, and `; referenced schemas`
opens 7 separate blocks. `prose` deduplicates whole units by logical key
(`walk.clj:551-566`) but nothing deduplicates *lines* across units.

**Fix shape:** render an error schema by its `:error/message` sentence, not
its Malli form; drop render-producer and internal properties from the
agent projection; hoist the referenced-schema closure to one walk-level
section rendered once.

### 5. Rendered results are framed as source comments, including a literal `;; =>`

**Producer:** `src/seon/render/walk.clj:606-635`,
`:568` (`prose` docstring, "Each unit gets one compact comment"),
`src/seon/render/ns.clj:329-345`, `:405-426`.

The comment grammar reserves `;`/`;;` for source and forbids `;; =>`
annotations and decorative comment framing in output. The live assembly
violates it at every level. Verbatim, root's first two lines and last four:

```text
;; (seon.render/walk {:root [:seon.cluster.agent/id "root"], :depth 2}) => root=[:seon.cluster.agent/id "root"] depth=2
;; Some branches are elided · inspect with (seon.render/walk {:root [:seon.cluster.agent/id "root"], :depth 3})
…
;; Volatile context metadata
;; branches-elided=9 elided-tokens=110
;; unit=25752 branch=[:seon.render.walk/neighbours 0]
;; REPL state namespace=my.agents.root basis=536871204 time=#inst "2026-08-10T19:38:48.066-00:00"
```

The header is exactly the banned shape: a form, an `=>`, and a result, all
inside a comment. A real REPL would display the form and then its actual
computed value. The error path is the same:

```text
;; (seon.render/walk) => error
No calling agent is bound to this evaluation.
```

Every unit body is preceded by `;; d<n> · <lookup>`, and namespace units
prefix every member line with `; `. Live recurrence of
[render-walk-frames-values-as-comments](../../../seon/issues/render-walk-frames-values-as-comments.md)
and
[namespace-renderer-encodes-results-as-comments](../../../seon/issues/namespace-renderer-encodes-results-as-comments.md).

### 6. Foreign namespaces render as "no definitions yet" — a false statement

**Producer:** `src/seon/render/ns.clj:341-345` (`empty-comment`), reached
from `compact-ai-text` at `:424-425`.
**Cost:** 39 tokens, but the harm is not tokens.

```text
;; d2 · [:seon.ns/name clojure.string]
(ns clojure.string)

;; no definitions yet.
```

`clojure.string` has definitions; root calls `str/includes?` and
`str/starts-with?` later in the very same context. What is absent is
first-party `:seon.fn` facts for a namespace we do not index. The renderer
turns "I hold no facts about this" into "this namespace is empty", which is
exactly the docstring-that-lies failure mode: an agent reading it would
conclude `clojure.string` is unusable.

**Fix shape:** distinguish *no members* from *no indexed members*. A
namespace with no first-party facts should say so, or not be rendered as a
unit at all.

### 7. Error values in the transcript are print-tree walls with dead-end elisions

**Producer:** the `:seon.error/data-edn` projection reaching the transcript
via `src/seon/render/transcript.clj`; structural owner is
[contract-violation-serializes-print-tree-inside-error-data](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md).
**Cost:** ~1,350 tokens for one maintenance error, rendered twice.

Root ran `(seon.db/entity 25787)` and then `(seon.db/pull '[*] 25787)`.
Both returned the same ~2,700-character wall of re-encoded print faces:

```text
{:seon.error/capped? false, :seon.error/data-edn "#:seon.print{:face :seon.print/throwable, :value #:seon.print{:face :seon.print/map, :entries [[#:seon.print{:face :seon.print/keyword, :value :via} #:seon.print{:face :seon.print/vector, :items [#:seon.print{:face :seon.print/map, :entries [[#:seon.print{:face :seon.print/keyword, :value :type} …
```

It terminates in an elision that is a dead end:

> … "… 7811 more characters of 9859; **requery refused: the value has no
> durable blob or entity identity** at path [1 1] offset 2048 with
> :seon.render.profile/agent"

The elision names the omitted count, the total, the path, the offset, and
the profile — and then refuses the one thing that would make it
actionable. The agent is told 7,811 characters exist and that it may not
have them. It then paid the same 2,700 characters a second time for the
`pull` form.

Meanwhile the underlying message is one sentence long: a `.ksv…new` file
path. Live recurrence of the size class recorded in
[a-six-word-eval-error-renders-as-two-thousand-characters](../../../seon/issues/a-six-word-eval-error-renders-as-two-thousand-characters.md).

### 8. Repeated operator maintenance messages carry 64-hex signatures and an internal map

**Producer:** the message renderer's `about` projection reaching root's
transcript.
**Cost:** ~500 tokens across 7 occurrences.

Seven near-identical messages sit in root's transcript, three of them the
same reap/census failure on successive days:

```text
From outside this cluster to root: The reaper cannot read every external claim. (:seon.operator/reap-incomplete). Inspect error maintenance-error/maintenance-receipt/["root/maintenance/reap-dead-roots" #inst "2026-08-09T02:15:00.000-00:00"]; nothing was retried. Signature: 46621b16f93a8dc7a82b1a85df26071e953e926fae76c1ecc1d4b14c15579429.
#:seon.transcript{:unresolved-about? true, ...}
```

Two problems. The 64-character signature is an internal dedup key with no
agent use — it costs ~16 tokens each time and identifies nothing the agent
can query more cheaply than the `:seon.error/id` already printed beside
it. And `#:seon.transcript{:unresolved-about? true, ...}` is an internal
renderer flag emitted as agent-visible output, trailing an `...` that
resolves to nothing: seven appearances, meaningless to the reader.

### 9. A never-run agent's context cannot be inspected at all

**Producer:** `src/seon/render/web.clj`, debug AI pane.

`/ns/seon.db/debug` returns 2,111 bytes:

```html
<section class="seon-debug-body seon-debug-body-ai" id="debug-ai-seon.db">
  <pre>No recorded context capture exists for this agent.</pre>
</section>
```

The debug page shows only the last recorded `:seon.context.capture`. There
is no way to see what an agent WILL receive before its first turn — which
is precisely when onboarding context needs auditing, and precisely the case
that is broken (findings 2 and 3). The HTML twin on `/ns/seon.db` does
render the live walk, so the fix is available: give the AI pane the same
live projection when no capture exists.

### 10. `; (register! :seon.schema/value :any)` teaches what the system panics on

**Producer:** `src/seon/render/ns.clj:363-379`, referenced-schema closure.

Root's context contains, three times:

```text
; (register! :seon.schema/value :any)
```

…and, 90 lines earlier, the panic root received for doing exactly that:

> `my.agents.root/largest uses :any in an agent-authored contract. Replace
> the undefined slot with a named predicate schema…`

The closure renders internal schemas by their raw Malli form regardless of
whether the form is something an agent is permitted to write. `:any` is a
legitimate third-party-boundary declaration in `seon.schema`; showing it to
an agent as a registration example directly contradicts the rule the same
context enforces.

---

## Recurrences confirmed live (existing issues, evidence appended)

| Issue | Live evidence in root's 2026-08-10 context |
|---|---|
| [render-token-budgets-are-private-dials-no-producer-supplies](../../../seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md) | 60% of context unbudgeted; 164k tokens for a core owner |
| [render-walk-frames-values-as-comments](../../../seon/issues/render-walk-frames-values-as-comments.md) | literal `;; (seon.render/walk …) => root=…` header |
| [namespace-renderer-encodes-results-as-comments](../../../seon/issues/namespace-renderer-encodes-results-as-comments.md) | every `; schema`/`; fn`/`;; no definitions yet` line |
| [a-wrong-arity-call-reports-a-missing-namespace](../../../seon/issues/a-wrong-arity-call-reports-a-missing-namespace.md) | `(largest)` → `No such namespace: my.agents.root` |
| [agent-repl-cannot-require-clojure-pprint](../../../seon/issues/agent-repl-cannot-require-clojure-pprint.md) | root's turn died on `(require '[clojure.pprint …])` |
| [a-six-word-eval-error-renders-as-two-thousand-characters](../../../seon/issues/a-six-word-eval-error-renders-as-two-thousand-characters.md) | one-sentence maintenance error → 2×2,700 characters |
| [bootstrap-teaches-bare-map-keys](../../../seon/issues/bootstrap-teaches-bare-map-keys.md) | `{:label "a" :amount 3}` still live in every transcript |

## The one fix with the largest effect

**Give `seon.render.ns` the profile budget it already has a producer for,
and spend it API-first.** One key change at `src/seon/render/ns.clj:320-322`
(read `:seon.render.profile/token-budget` instead of the private
`::token-budget`), plus reordering `compact-ai-text` (`:405-426`) so
functions are admitted before own schemas and the referenced closure.

At the configured 1,024-token namespace budget that turns root's 9,552
tokens of toolkit into ~7,000 tokens saved — 44% of the whole context —
while keeping all 21 `; fn` lines, which are the only part an agent acts
on. It is also the single precondition for finding 2: without it, no
core-namespace agent can ever be given a fitting prompt.

## What is genuinely in good shape

Calibration, not just alarm:

- The `(help)` text (root's d0 unit, ~340 tokens) is excellent — dense,
  honest, no comment framing, and every claim it makes is true of the
  system. It is the model the rest of the context should follow.
- `; fn <sym> — <contract> — <doc>` is the right compact shape: symbol,
  contract, one-line docstring, nothing else. It costs 829 tokens for the
  entire agent-facing API of seven namespaces.
- The cluster, config, and run units are genuinely concise (195, 127, and
  625 characters) and say exactly what an agent needs.
- Unit deduplication by logical key (`walk.clj:551-566`) works: no entity
  is rendered twice in root's 18 units.
- `seon.render.value` reads the render profile correctly — the budget
  plumbing exists and is right in one of the two places it is needed.
- The `:seon.error/message` sentences on the capability schemas are
  well-written and actionable; the defect is that the Malli form around
  them is what gets rendered.
