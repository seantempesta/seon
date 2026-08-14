---
type: issue
status: open
severity: friction
tags: [issue, render, config, class/p3, wave/context-fixes]
---

# The transcript and namespace renderers invent private token dials

## Problem

Two renderers landed in this wave carry their own budget key,
`::token-budget`, which is not a config fact, not a schema attribute,
and not `:seon.sci.admit/caps`. No producer anywhere in `src/` or
`resources/` sets either one, and the two chose opposite defaults, so
both are wrong in production:

- `src/seon/render/transcript.clj:536` — `(get unit ::token-budget 0)`.
  With no key the transcript renders NOTHING but its elision marker.
  `seon.render.transcript` is referenced by no schema declaration, no
  block, and no caller; 595 lines are reachable only from its test.
- `src/seon/render/ns.clj` — `(some-> (::token-budget unit) long (max 1))`,
  nil meaning NO bound. `seon.render.ns` IS wired as the `:seon.ns/ns`
  family lens (the PROGRAM section of `resources/seon/schema.edn`), so its entire
  "bounded, whole-form assembly" section is dead on every production
  path.

The budget is also used as a database ROW COUNT. `projection`
(`src/seon/render/transcript.clj:541-542`) computes
`candidate-limit (max recent-entry-count budget)` from an estimated
TOKEN count and passes it as `:limit` to two Datalog queries; measured,
a 100 000-token budget asks for 100 000 rows twice.

Hardcoded budget constants in the same files, none derived from a
config fact: `recent-entry-count 6`
(`src/seon/render/transcript.clj:17-20`, justified by a quarry
anecdote), `(quot preview-budget 2)` (`:418`), and in `ns.clj` a
`referenced-schema-cap` of 40 plus a `(soft-clip summary 78)` — 78 is a
CHARACTER width, which the standing rule forbids for a human-visible
size (estimated tokens through `seon.ai.tokens/estimate`, never raw
characters).

Related cost, same owner: `best-summary`
(`src/seon/render/transcript.clj:462-478`) binary-searches over
`fits?`, and `output-tokens` (`:497-501`) builds BOTH the full AI string
and the full HTML string on every probe, re-serializing every
already-accepted entry. `render-ai` and `render-html` (`:583-595`) each
call `projection` independently, so the whole search runs twice per
block. Measured on 200 messages of ~200 characters: 44 ms at budget
500, 442 ms at budget 20 000 for the AI twin alone.

## Acceptance

Both renderers read the one `:seon.sci.admit/caps` the block floor
already carries, or a declared `:seon.config.*` fact — no private
`::token-budget` survives, and no numeric budget literal remains that a
config fact could own. `candidate-limit` derives its row count from a
per-entry token cost, not from the budget scalar. Cost is accumulated
incrementally rather than by re-serializing the accepted prefix, and
`projection` is computed once with both twins taken from it. One
recurring measurement pins the render cost so a regression is visible.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`

The context-MVP seam rerun in
`docs/prds/sci-execution-runtime/research/mvp-seams-notes-2026-07-31.md`
measured the production consequence after distance normalization removed raw
namespace-member traversal: the `seon.flow` owner d2 walk still reached 25
compact namespace cards and rendered 17,696 estimated tokens (71,302 UTF-8
bytes). No raw alias/import/function entity datoms remained. The remaining
size is therefore this absent namespace-card budget on the real walk path,
not the repaired distance seam.

## Recurrence and exact root cause — live default cluster, 2026-08-10

The key mismatch is now pinned precisely. `seon.render.ns` reads
`::token-budget`, i.e. `:seon.render.ns/token-budget`, at
`src/seon/render/ns.clj:320-322`. `rg 'render\.ns/token-budget' src/ test/`
returns **zero hits** — no producer, no test. The configured dial
`:seon.config.render.agent/token-budget` is **1024** on the live cluster, and
`seon.render/agent-render-profile` (`src/seon/render.clj:47-49`) already maps
it to `:seon.render.profile/token-budget`. `seon.render.value` reads that
profile correctly (`src/seon/render/value.clj:72-76`); `seon.render.ns` never
reads it. So `budgeted-ai` (`ns.clj:447-461`) always takes the `(nil? budget)`
branch, and `minimal-ai-text` (`:435-445`) plus `omission-comment`
(`:329-339`) are unreachable in production.

The budget is also INVERTED where it would apply: `compact-ai-text`
(`ns.clj:405-426`) emits every `own-schemas` row unconditionally at
`:417-419` and caps only `functions` via `included-count`. Even with the key
supplied, the Malli schema wall would be a fixed floor and the callable API
would be what gets squeezed out.

Measured consequences on the live default cluster (pid 31570):

- Root's exact context: 63,669 characters / **15,917 estimated tokens**, of
  which the seven toolkit namespace units are **9,552 (60%)** — `my.fs`
  alone renders **2,843 tokens against the declared 1,024-token budget**,
  2.8×. Only **829 tokens (5%)** are the `; fn` API lines.
- A core-namespace owner is far worse than the 17,696 tokens recorded above
  for `seon.flow` on 2026-07-31: `/ns/seon.db` renders **141 namespace family
  entries, 655,937 characters ≈ 163,984 estimated tokens** against a
  `:seon.config.ai/prompt-token-budget` of **32,768** — 5× the whole prompt
  budget. `acquire-within-budget` (`src/seon/cluster/prompt.clj:148-193`)
  can only respond by collapsing distance 2 → 1 → 0, where the agent gets its
  transcript and nothing else.

Full measurement and the API-first fit ordering this needs:
[context quality audit 2026-08-10](../../prds/sci-execution-runtime/research/context-quality-audit-2026-08-10.md),
findings 1 and 2. This is named there as the single change with the largest
effect on what agents read.

## Implementation boundary — 2026-08-10

The namespace owner now reads
\`:seon.render.profile/token-budget\` from \`:seon.render/profile\`, and its
compact AI entry order is functions, own schemas, then referenced schemas.
The focused namespace suite passed 5 tests / 60 assertions. After hot reload,
a direct census of every indexed \`my.*\` namespace rendered at or below 1,002
estimated tokens against the 1,024-token profile, with every public function
line retained.

The live context path exposed one protected-owner dependency. The
\`seon.render/walk\` call to \`seon.render.walk/neighborhood\`
(\`src/seon/render.clj\`, in the request assembled around lines 640-660) omits
both \`:seon.render/profile\` and \`:seon.cluster.agent/id\`. Consequently
\`request-profile\` cannot derive the effective profile before invoking the
namespace producer. A proc-owned \`seon.render/acquire-context!\` probe after the
hot reload still returned 229,442 characters / 57,360 estimated tokens on the
current default database, while the last recorded root capture remains 63,669
characters / 15,917 estimated tokens.

The exact remaining edit is to add
\`:seon.cluster.agent/id agent-id\` to that \`neighborhood\` request. The existing
\`request-profile\` function then derives the cluster and agent effective
profile and supplies the already-declared token budget; no second profile or
budget mechanism is needed. \`src/seon/render.clj\` was owned by the concurrent
\`ns-page-perf\` lane, so this lane did not edit it. The live root before/after
and prospective \`seon.db\` walk measurements remain acceptance evidence for
that integration.

## Census cross-reference — 2026-08-14

The outward-bounding census
([context-clipping-census-2026-08-14](../../prds/context-generation/research/context-clipping-census-2026-08-14.md))
records this note as the missing-FACT half of the §2.4 law, beside a
missing-HONESTY half. `seon.render.ns`'s budgeted assembly is a MODEL member of
the compliant pattern in shape — `:337-341,470-478,587-609` emit a real
`:seon.print/elided` node with `:seon.print/omitted` and
`:seon.render.data/total`, and honest prose at `:364` — yet twelve lines away
the same file's private `soft-clip` (`:234-240`, called at `:393`/`:409` with a
hard `78`) cuts docstrings with an invented `[clipped]` token, no count, no
requery identity, and first rewrites any real `…` to `...`, destroying the
elision vocabulary on the way through. Fixing the budget dial without deleting
`soft-clip` leaves the namespace page still clipping agent context outside the
owner.

## Clipping rip-out update — 2026-08-14

The illegal twin is gone: `38f18880b` deleted `soft-clip`, the invented
`[clipped]` token, the ellipsis rewriter, and both hard-78 calls while landing
the coordinated block-coverage work. `ab693ea4d` then made the text bounder
private to `seon.print` and added the program-graph lock requiring exactly the
render-fit and storage-admission call paths, with zero-subject failure.

This issue remains open. Those changes remove dishonest producer-side cuts;
they do not supply the still-missing render token-budget facts described by
this note.

## Behavioral ablation — 2026-08-14

The exact Attempt 5 prompt carried a 32,159-character toolkit span. The paid
ablation retained the complete medium demonstration, task-turn suffix, and the
task-relevant `my.message`, `my.plan`, agent-namespace, and `seon.db` blocks:
10,125 toolkit characters, or 31.48%. The whole prompt fell from 34,955 to
12,921 characters and from 11,476 billed input tokens to 4,188 on the retained
repeat.

With no tail medium reminder, both trimmed calls emitted reader-accepted
Clojure forms where the full prompt and identical-byte demo control emitted
none. This is behavioral evidence that namespace bulk affects medium execution,
not only spend. It is not evidence that trimming alone is sufficient: the
first trimmed reply was a runaway survey, and the repeat emitted 16 exploratory
forms without completing the task. The ranked fix remains to enforce the
existing namespace budget and API-first fit here, while the reply-medium owner
states the prose-only edge at the task tail.
