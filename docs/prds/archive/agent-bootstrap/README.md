---
type: prd
status: archived
tags: [prd, agent, bootstrap, context, render]
---

# Agent bootstrap: forms-only context, REPL fidelity, and the readline

## Decision

Owner-designed iteratively 2026-08-03 night (decision record:
[messaging-state-design-notes-2026-08-03.md](../../sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md),
decisions 4–11). The agent's context is rebuilt on four pillars:

1. **Forms only — no hand-built context sections.** The context is:
   `(help)` (a form rendering db-resident instruction facts) → stable
   lesson forms with their live outputs (the cacheable prefix) → volatile
   forms (messages+replied, status, plan, unreplied-check) → the agent's
   own append-only REPL session → **the readline**. The transcript
   renderer's hand-built sections (messages, status, waiting-on) are
   DELETED as each migrates to a form.
2. **Strict REPL fidelity.** Display matches a real Clojure REPL: a
   `<current-ns>=>` prompt per form, the agent's own leading `;;`
   comments echoed as INPUT after the prompt, out-lines before the bare
   result (no marker before results), comment-only entries produce
   NOTHING, errors as Clojure's standard one-liners. Comments are never
   modeled as output. The exact contract is pinned by
   [repl-display-conventions-2026-08-03.md](../../sci-execution-runtime/research/repl-display-conventions-2026-08-03.md) with a
   divergence table against `seon.render.transcript` — each divergence
   fixed at its owner.
3. **Teach fishing with actionable outputs.** Every form passes "what
   would the agent DO differently having seen this output?" Raw Datalog
   taught progressively (idiomatic `->>` composition on results; the
   most efficient shapes the Datahike source supports); capabilities and
   the `my.*` surface found BY QUERY, never enumerated; ONE helper
   (namespace overview — the only composition that earned it);
   undemonstrable lessons (register!/transact!/self-improvement) are
   db-resident prose, because forms re-run and re-running must stay
   harmless. Lucene search becomes a lesson once `seon.search` scoping
   is taught (one scoped call).
4. **The readline is real and owner-controllable.** See below.

## The readline mechanism

The readline is the LAST rendered element of every prompt — after the
cache boundary, so its churn is free. It is a declared fact, not
renderer code:

- `:seon.cluster.agent/readline` on the agent entity holds the producer:
  a fully qualified function symbol (ruling #50's producer
  representation) or an inline string. The referenced function runs in
  the one bounded invocation function like any render producer and returns the
  `:seon.render/ai` string (an `:seon.render/html` producer pairs it for
  the web page).
- The BOOTSTRAP sets the shipped default via an ordinary form the agent
  can see: query the plan (previous 1–2 completed items + what's ahead,
  through declared producers), then set the readline. Because setting it
  is a visible form, "how do I change my prompt?" is answered by the
  agent's own transcript — and the agent controls it thereafter by
  transacting the fact (self-authored PS1).
- The OWNER controls it the same way: root (or the operator) transacts
  the fact on any agent — one mechanism, provenance recorded.
- Content contract: current derived status row (decision 2's board
  projection), current work item + one advance tip, pending count, and
  the LAST-TURN DURATION line (owner-ruled: shell-prompt convention —
  "last: 3 forms · 14.2s · 1 slow (my.web/fetch 12.1s)"), derived from
  the previous run's eval receipts. Per-eval results stay pure (real
  REPLs print nothing but the value; durations are receipt facts); slow
  capability results already carry :seon.effect/duration-ms per the
  background-work ruling. Bounded by the ordinary output caps; ugly
  readline output is a defect like any other face.

## The series is produced by a function, declared by symbol

Owner-designed 2026-08-03 night: bootstrap forms are GENERATED per agent,
not stored as rows or string templates. `:seon.cluster/bootstrap` on the
cluster names the default producer function
(`seon.bootstrap/default-forms`); `:seon.cluster.agent/bootstrap` on an
agent names an override — the same declared-producer pattern as the
readline and render producers, so slice 4's override mechanism is this
one attribute. The producer is PURE: `({:agent … :db …})` → the ordered
vector of `{:thought "…" :form <data>}` entries (`:source` verbatim
string allowed where hand formatting matters, e.g. the defn lesson). The
agent's namespace, id, keywords, and web port are ordinary arguments
flowing into syntax-quoted code — the `{{…}}` mustache substitution over
`bootstrap.edn` rows IS DELETED in the same change (textual templating
of code is the parsed-representation violation). The display emits each
`:thought` as the input-side `;;` lines before its form. Consequences,
all by construction: owner iteration = editing the default defn (hot
reload, every agent next turn); agent self-modification = define a
function + transact the attribute (the exact move the readline lesson
already teaches); cache stability = pure function of stable inputs;
the producer is an ordinary indexed, doc-able, testable corpus function.
Instruction PROSE stays db facts read by `(help)`; the SERIES is code.

## The narrative rule — the owner's one rule for the form series

Owner-ruled 2026-08-03 night: **the bootstrap must read as one
logically self-explaining story.** Each form is preceded by input-side
`;;` thinking that explains HOW the agent knew to run THIS form given
the PREVIOUS form's actual output — a complete causal chain that
educates: `(help)` opens (rendering the db-resident system prompt —
that prose is a named design deliverable of slice 2), then each form
builds on the last: query for functions → the output names one →
`(doc …)` it to learn usage → call it → combine results → discover the
capability surface → on and on. No form appears without the thought
that motivated it; no thought references output the reader has not
seen. This is input-side narration (comments before forms — the
display contract's comments-never-as-output rule is untouched), and it
doubles as the worked demonstration of the thinking-then-forms style
agents should imitate.

## Dependency ledger

| Dependency | State | Evidence |
|---|---|---|
| Curriculum + recipes + helper verdict | DONE (revised under the actionable filter) | [bootstrap-curriculum-2026-08-03.md](../../sci-execution-runtime/research/bootstrap-curriculum-2026-08-03.md) |
| REPL display contract | DONE (slice 1) | [repl-display-conventions-2026-08-03.md](../../sci-execution-runtime/research/repl-display-conventions-2026-08-03.md); focused behavioral gates cover namespace prompts, input comments, output-before-result, bare values, standard errors, and zero-event comment forms |
| Comments-as-output purge | IN FLIGHT | `comment-output-sweep` lane; ruling = notes decision 11 |
| Message-context form + replied derivation | RULED | notes decisions 4, 5, 7 (X is a literal in the agent's own form) |
| Status/board derivation | RULED | notes decision 2; the readline renders the agent's own row |
| Plan/work facts | RULED, unbuilt | work-identity research decisions 3/6 (owner adoption pending on the full set); readline degrades gracefully while absent (status + messages only) |
| `(help)` from db instruction facts | RULED (2026-07-31 blocks ruling + tonight) | instruction facts exist; `(help)` renders them; config manifest seeds/updates |
| Prefix caching | MEASURED | 67% hit, 58,642-byte stable prefix; forms-only + append-only session should raise it |
| Bootstrap plan storage | EXISTS | per-form rows, `:seon.cluster/bootstrap-plan` ref; per-agent override via a declared agent attribute (naming-convention-free), mechanism finalized in slice 4 |

## Implementation order

1. **Display contract first — DONE (slice 1).** The divergence-table fixes landed in
   `seon.render.transcript`/`seon.cluster.reply` display (prompt-per-form,
   bare results, comment handling, error one-liners). Everything else
   renders through this. Receipt and session shapes declare both render
   producers; discovery is the output-declaration query rather than a
   walk attachment. The message/status sections remain until their
   bootstrap-form replacements land in slice 2.
2. **The form series rewrite** — replace `resources/seon/bootstrap.edn`
   per the revised curriculum: fix the `{:closed true}` defect (live in
   every agent's context today), the capability/`my.*` query lessons, the
   contract-read-then-call arc, the message-context form, the
   unreplied-check, ordered stable→volatile with the cache-boundary
   marker. Prose to db instruction facts; `(help)` reads facts.
3. **The readline** — the declared attribute, both producers, the
   bootstrap default-setting form, prompt assembly renders it last.
4. **Per-agent override** — the declared agent attribute selecting a
   custom starting-form series; root can set it on any agent.
5. **Live drives** — DeepSeek agents on a fresh cluster with dedicated
   observers: minimal-prompting tasks through the new tools; measure
   orientation, reply norm, readline usage, cache hit rate; every ugly
   output filed. Iterate forms from evidence.

## Falsifiers / graduation

- A fresh agent's first turn renders: `(help)` + lessons + volatile forms
  plus empty session + default readline — byte-stable prefix across two
  turns except declared-volatile forms (measured hit rate recorded).
- The rendered session is accepted verbatim by a real Clojure reader of
  transcripts: prompts, bare results, no comment appears as output.
- An agent edits its message-window literal and its next context shows
  the wider window (self-modified context proven).
- An agent (and separately root) transacts the readline fact and the
  next prompt's tail changes accordingly; provenance shows who.
- The unanswered-asker advisory appears only when true and disappears on
  reply (derivation, not state).
- A live drive: user message → agent orients, uses a capability, replies
  to the user, completes with an honest note — scored by
  message-reaching-user, not receipt archaeology.

## What not to build

- no hand-built context sections, section headers, or renderer-owned
  message/status views (the session structure itself is the one
  non-form survivor);
- no `;; =>`-style output annotations anywhere, ever;
- no per-tool prose walls; undemonstrable lessons are db instruction
  facts, editable by transaction;
- no naming-convention override discovery — per-agent forms and readline
  are declared attributes;
- no second prompt-assembly path — the readline is a declared producer
  rendered by the one walk, last.
