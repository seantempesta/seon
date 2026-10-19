# Doc view: what it saves and what each kind's render lacks (experiment, 2026-10-14)

Probe on default (JVM mode, `(seon.cluster.boot/connection "default")`), read-only: `seon.context/current-state` and
`seon.context/model-context` on the branch database of agent `attempt/341f9a9016cf` (`:attempt-341f9a9016cf`), held
task `seon.ref/define-a-pathless-reference-section-is-a-missing-file-error` (Run 25 defect chain, Define step). No
source edited, nothing transacted, nothing committed. Tokens are `seon.ai.tokens/estimate`, the one sizer: characters
/ 3.2, floored (`shipped-chars-per-token`). The earlier "9.5k tokens" in TASK-CHAIN 3a divided by 4. Single samples,
warm. (inferred) marks items read from source, not measured.

## 1. The block today

| What | chars | tokens |
|---|---|---|
| Raw `current-state` value (`pr-str`, before any render) | 37,400 | 11,687 (11,973 on the branch db) |
| of which must-reads (16 entries) | | 9,826 |
| of which task 718, chain 354, proofs 693, step 32, turns 27, rest | | ~2,130 together |
| The prompt the model is sent (`model-context` `:seon.ai/prompt`, after the AI renders and the value printer) | 19,242 | 6,013 |
| of which the must-reads section | | 4,147 |
| The system message (`(help)` shown, `:seon.ai/system`) | 6,621 | 2,069 |

Findings that change the premise of 3a:

1. The raw block is 11.7k tokens, not 9.5k; but the model never sees it. The value printer's budget holds the prompt
   to about 19k characters (~6k tokens) and spreads it across the shape: the shown prompt has 91 elisions
   (`"…+47"` markers). Function and deftest sources are cut mid-form (`seon.ref/section` shows 20 of its ~75 lines;
   `my.task/refine!` shows 3 lines plus `…54`). So today a must-read is neither whole nor a summary: it is an
   arbitrary prefix of the source.
2. Must-reads cost 4,147 of the 6,013 shown tokens (69%). The model's whole task context before them is ~1,865 tokens.
3. Assembling `model-context` took 382 ms and 898 MB allocated (one eval request, 1,647 reads, 115k calls); the
   block itself 43 ms. Allocation is high for a read; not investigated here (belongs to the sentinel).

## 2. Per kind: today and the doc view

Distance semantics found (`seon.render/render-ai`, `seon.context/must-read-ai`): `must-read-ai` renders every
must-read at `(inc 0)` = distance 1. For functions, `seon.render.ns/function-ai` has exactly two tiers: distance 0 is
`{:seon.fn/sym, :seon.fn/doc}` (10 tokens); distance 1 and above (917 tokens for `seon.ref/section`, identical at 2)
is the whole source lines plus `callers`/`tests-reaching` operations. There is no middle tier. Deftests
(`seon.render.test/render-ai`), schema keys (`seon.render.schema`, listed at 0, usage report at 1), namespaces
(`seon.render.ns/render-ai`) and tasks (`task-ai`) have the same two-tier shape. So the doc view cannot be "distance 1":
distance 1 is today's full view. Either the doc view is a new tier (distance 1 becomes the doc view and the source tier
moves to 2, which is what 3a's table needs) or it is a render function of its own; the first is consistent with
"each kind's declared AI render at brief depth".

Tokens, `seon.ref/section` shape and sample per kind (rendered value via `pr-str`; "shown" in parentheses where measured
through the printer):

| Kind (sample) | d0 | d1 today | `seon.ref/brief` | Doc view in 3a | Present today | Missing |
|---|---|---|---|---|---|---|
| Function (`seon.ref/section`) | 10 | 917 (957): whole source | 70: name, doc sentence, `source` form | doc summary, arglists, contract with named schemas, each schema's description | doc summary (d0, and inside the source); contract only if the source lacks `:malli/schema`; arglists only when no source (library Vars) | the contract's named schemas and their descriptions; a no-source tier. Spec, arglists, doc are all pullable (`:seon.fn/doc`, `:seon.fn/arglists`, `:seon.fn/spec` on the function entity) |
| Library Var (`malli.core/validate`) | 31 (doc line) | 104 (105): doc, arglists, callers/tests ops, `clojure.repl/source` | 48: doc line, `(doc …)` | same | doc line, arglists, read form | nothing for the doc view; d1 already is the doc view (it adds two callers/tests operations that mean nothing for a library Var, inferred) |
| Deftest (`seon.ref-test/a-reference-with-no-path-is-a-missing-file`) | 23 | 218 (226): whole source, `:reach-unknown`, host | 57: name and source form, no doc | docstring summary, functions it reaches, latest status | name, state (once run), source, `reach-unknown` | a docstring: deftests carry none (`:seon.test/doc` is not an attribute), so "docstring summary" has no producer; the reached functions are in `:seon.ref/references` (5 of 5 sampled name `seon.ref/section`), status is in the render at d0 |
| Schema key (`:seon.ref/path`, `:seon.ref/section-error`) | 13-36 (36 with description) | 124-168: form plus usage report (referenced-by, contracts) | 29-34: name, source form | its form and description | form, `:description` property for d0 of some | the description is read by `seon.render.schema/description`, which is private (`var is not public`); `:seon.ref/section-error` has none; of the schemas named by `refine!`'s contract 2 of 5 have no description at all (`:seon.task/refused-error`, `:seon.task/conflict-error`) |
| Namespace (`seon.ref`) | 7 | 604 (655): name, requires, a table of every public function with arglists and full doc sentence | 76: name, doc line, `(dir ns)` | not in the 3a table | name, doc, directory table | a table row's "summary" is the first sentence, which is often a whole paragraph; for `seon.ref` ~25 tokens per function (inferred from 604 over 24 rows, not counted) |
| Document section (`docs/…/vocabulary.md#git-words`) | 837 (its render has no d0/d1 difference) | 837: heading, anchor, path, a read form, and `:seon.ref/references` | no `brief` for paths | heading, headings below it, size in tokens | heading, anchor, path, read form, the inner references | `:seon.ref/tokens` (887) and `:seon.ref/below` are in `seon.ref/section`'s answer but `render-doc-ai` does not carry them; the inner references dominate the 837 tokens (6 briefs, one a 150-token docstring) |
| Task (`seon.ref/a-pathless-…`) | 54 | 591 (1,367 shown): sym, name, the whole plan, references | 30: name and `"## Problem"` (first line of the plan) | name, plan's first line, proofs with status | the whole plan (d1), references | the first line of a plan is always a heading, so "the plan's first line" is `## Problem`; use the first line of the first section's body; proofs and their status are not in the task render (they come from `seon.context/proofs`) |

Where "brief" and the doc view overlap: `seon.ref/brief` is the right shape for the reference's own name line (name,
one doc sentence, read form) and is already what every inner reference uses (`inner`, `cite-once`). It lacks arglists,
contract and schema descriptions, and its `doc` is not always one line: `seon.merge/merge!`'s brief carries a 150-token
sentence because `docstring`'s `:summary` is the first sentence, and Seon docstrings use semicolon-chained sentences
(observed in `docs/seon/architecture/vocabulary.md#git-words`).

## 3. The block with every must-read as a doc view

Scratch fold (REPL only, not installed): `doc-view` per entry kind as described in the table (function: summary,
arglists, `:seon.fn/spec` and a map of each named schema to its `:description`, else its form when under 90 characters,
else `:no-description`, plus the `source` form; schema: form, description, `source` form; deftest: sym, status, the
functions it names, `source` form; inner `:seon.ref/references` kept as today), then the same printer path.

| Measure | Today | Doc view | Change |
|---|---|---|---|
| Block, raw tokens | 11,973 | 7,087 | -41% |
| Must-reads, raw tokens | 9,826 | 4,939 | -50% |
| Must-reads without inner references (the doc view alone) | | 2,874 | -71% |
| Must-reads, inner references cited by name only | | 3,152 | -68% |
| Block, raw tokens, no inner references | | 5,022 | -58% |
| Block as the model sees it (shown tokens) | 6,013 | 5,982 | -1% |
| Elisions in the shown prompt | 91 | 30 | -67% |

Per kind (raw tokens, doc view with inner references; no-inner in parentheses):

| Kind | Entries | Today | Doc view |
|---|---|---|---|
| Function | 8 | 7,709 | 4,144 (2,079) |
| Schema | 3 | 283 | 280 |
| Deftest | 5 | 1,834 | 515 |

Reading the numbers:

- The shown size does not move because the value printer fills the same ~6k budget either way. What the doc view
  buys is not fewer tokens in the prompt but all of the information in them: 61 fewer elisions, no function cut
  mid-form, and room for more references inside the same budget. The S3d proof should therefore assert raw tokens
  and the elision count, not shown tokens (a shown-token assertion would pass with no change).
- Functions are the cost: 8 entries, 78% of must-read tokens. `my.task/refine!` alone is 2,184 raw (1,244 as a doc
  view, 13 inner references, 5 schema descriptions of which the one for `:my.task/refine-request` is 60 tokens).
  `seon.profile/measure` 1,804 to 616, `seon.batch/cost-diff` 1,045 to 609.
- Inner references are now half the doc view (2,065 of 4,939 raw tokens). 3a says "the schemas it names one level
  below, cited once"; today's `inner` adds every program function a body calls (13 for `refine!`, 6 for `section`).
  That is the cost to cut next: for a doc view, cite the contract's schemas, not the callees.
- Schemas are already close to their doc view (283 to 280): nothing to save, only the missing descriptions.
- Deftests drop 72% (1,834 to 515) once source is gone; this is where the doc view saves the most per entry
  (236 to 89, 801 to 115).
- One name appears twice (`seon.ref/section-error` as function and `:seon.ref/section-error` as schema key); counting
  is by entry.

## 4. The help text

`(help)` shown as the model sees it (`:seon.ai/system`): 6,621 characters, 2,069 tokens, rendered by
`seon.bootstrap/render-help-ai`; the raw value is 2,066 tokens, so rendering adds almost nothing. Eight groups:
Who you are 127, How to speak 76, Your task 464, How work proceeds 515, Write what you learn 185, When you cannot 119,
Each reply is data 109, Data 318 tokens (lines only, 1,913 together). Ten qualified names are cited in its lines
(`seon.ref/names`). With the move to `resources/seon/help.md` and doc views for each cited reference, the help would
grow by about 10 doc views at ~60-250 tokens (inferred from the function rows above), about 0.6-2.5k tokens, to a
total of 2.7-4.6k; 3a promises this is "measured with the block's", and it is the system message, sent on every
request, so it must be counted at every pass, unlike the block, which a task's references fix.

## 5. Concrete edits

TASK-CHAIN section 3a:

1. Replace the sentence "the raw block measured about 38k characters, 31k of them must-reads (an estimate of about
   9.5k tokens from characters; the rendered AI context was not measured)" with the measured facts: the raw block
   is 37.4k characters, 11.7k tokens (`seon.ai.tokens/estimate`, 3.2 characters per token); the prompt the model sees
   is 19.2k characters, 6.0k tokens, because the value printer's budget cuts function and deftest sources mid-form
   (91 elisions); must-reads are 4.1k of those 6.0k.
2. State the tier change: render functions have two tiers today (name and doc line at distance 0, whole source at
   1). The doc view is the new distance 1; the source tier moves to distance 2 (callers/tests operations too).
   `must-read-ai` renders at `(inc distance)` and keeps working.
3. Fix the table cells that name data that does not exist or is not carried: Deftest "docstring summary" (deftests
   have none: say "its name, status and the functions it names"); Task "the plan's first line" (say "the first line
   of the plan's first section body", `## Problem` is a heading); Document section "its size in tokens" (the answer
   carries `:seon.ref/tokens` and `:seon.ref/below`, `render-doc-ai` drops them: carry them); Schema "one-line
   description" (`seon.render.schema/description` is private: make it public, and give the schemas without a
   `:description` one: `:seon.task/refused-error`, `:seon.task/conflict-error`, `:seon.ref/section-error`).
4. Say which inner references a doc view carries: the schemas its contract names, cited once; not the callees
   `inner` adds today (13 for `my.task/refine!`; they are half of the doc view's tokens). A callee is one `callers`
   read away.
5. Say a one-line summary is the first line of the docstring up to its first sentence cut at 160 characters, or
   `docstring` returns the first sentence of the first paragraph only; today a semicolon-chained sentence
   (`seon.merge/merge!`) gives a 150-token "line".
6. Add the measured expectation to the closing paragraph: on this task's block the doc view takes the raw block from
   11,973 to 7,087 tokens (-41%), to about 5,000 with schemas only as inner references (-58%); the shown prompt stays
   near 6k because the printer's budget is fixed, so the gain is completeness (91 to 30 elisions), not size.

Slice S3d:

- Red proof `seon.context-test/a-referenced-function-shows-its-doc-view`: keep "docstring, contract and schema
  descriptions, no source, and the read form" and add: for this task, `(seon.ai.tokens/estimate (pr-str block))`
  falls by at least 35% (measured 41%), and the shown prompt holds no `…+` elision inside a function or deftest entry
  (measured: 91 elisions before, 30 after, the rest in inner reference lists). Do not assert shown tokens: they do not
  move (6,013 to 5,982).
- Scope of the slice, in order of size: (a) a doc-view tier in `function-ai`, `render.test/render-ai`, `render.schema`
  and `render-doc-ai` (carry `:seon.ref/tokens`/`:below`); (b) make `seon.render.schema/description` public and
  fill the missing descriptions; (c) `inner` stops adding callees to a doc view; (d) `seon.ref/brief` keeps its role
  as the inner-reference line and gains a one-line summary rule (item 5). Savings by kind: deftests -72%, functions
  -46% (-73% with schemas as the only inner references), schemas none.
- Add `seon.profile/measure` on `model-context` for the same agent: 382 ms and 898 MB allocated today; a doc view must
  not raise either (inferred: it should lower both, fewer source reads).
- Help (S1d): count the system message at every pass (2,069 tokens today) and add its growth when its cited
  references show doc views (inferred 0.6-2.5k tokens) to the S1d proof.

Reproduction: the forms are the ones in this note's section 3 headings; the probe used `seon.context/model-context`
with the request `{:seon.db/connection conn :seon.sci.admit/caps … :seon.sci.eval/ctx … :seon.sci.eval/time-limit-ms …}`
as `seon.turn` builds it, `seon.sci.eval/shown-result` for the shown text, and `seon.render/render-ai` with
`:seon.render/distance 0`, `1`, `2` on each resolved entry of `seon.ref/references`.
