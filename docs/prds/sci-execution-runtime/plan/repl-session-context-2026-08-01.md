---
type: prd
status: active
tags: [prd, context, render, agent]
---

# The REPL-session context — the design to build next (2026-08-01)

The owner's synthesis after seeing the raw walk dump fail a live agent:
the context, the transcript, and the debug interface are ONE thing —
a REPL session rendered from real facts, faked nowhere, presented as
plain text to the agent and as composable HTML to the human. This
supersedes the block-labeled walk-dump presentation (rulings #13/#16
mechanics stand; their PRESENTATION is replaced by this).

## The core realizations (owner, 2026-08-01)

1. **NEVER FAKE ANYTHING.** Every form shown is a real form the agent
   ran (or could rerun) with identical results. The creation-run
   is a real run executed through the real eval path at agent creation
   — real receipts, real prompt fact, real db reads. Cost is prepaid:
   the interpreted-corpus substrate installs all schemas+functions once
   per process (489 ms / 3 MB, measured), each agent forks in ns, the
   creation-run evals are ~3 µs each + real db reads. Times are real; db
   queries are always `now`.

2. **THE TRANSCRIPT IS A VECTOR OF MAPS, and we already store it.** The run
   loop already persists ordered form entities (exact source + ordinal)
   each with a receipt (result/output/error/instant). So:
   - CONTEXT = print the per-agent ordered interleave of {forms +
     receipts + message arrivals} — a pure derived view over existing
     facts. Oldest first ⇒ stable cache prefix until aging compresses.
   - REPLAY = eval the same forms in order.
   One artifact, two verbs (print / eval). The transcript may gain more
   STRUCTURE (typed entries: banner, form, output, arrival, error) but
   the bytes derive from facts, never stored prose.

3. **THE AGENT IS THE `user`.** Clojure's default REPL namespace is
   literally `user`, so the session HONESTLY starts at `user=>`. The
   person is "the human", reachable by message like any teammate. This
   seats the model as the REPL's OPERATOR, not an assistant — the
   correct stance. First tutorial act is `(in-ns 'my.agents.<id>)` —
   teaching namespaces by doing the one op that matters, no prose.

4. **THE PROMPT IS A REAL, AGENT-OWNED FACT.** `:my/prompt` (a format
   string on the agent entity); the driver renders every prompt from
   it; `(my.repl/prompt! "%ns@t%basis=> ")` is an ordinary fn that
   transacts it. The agent watches its own prompt change next line —
   state + persistence + "this system is mine to configure" taught in
   one visible beat. Default before customization is bare `user=>`.

5. **SINGLE → INFORMED-BATCH is the rhythm to teach.** Because
   round-trip latency is high, the agent should plan the MOST forms it
   can usefully batch. Story: lone forms while ignorant, then a comment
   narrating WHY forms batch, then the batch. help states this
   explicitly. The creation run MODELS it before the agent ever speaks.

6. **THE EXAM IS THE TUTORIAL.** The creation run's seed task (root asks
   "how many schema keys does my.message use?") is answerable ONLY by
   querying the graph — so discovering the database-is-a-graph is
   forced by the work, and the agent's own history holds the exact
   query shape one screen up.

7. **help CARRIES THE ONLY PROSE**, three ideas: (a) you are the agent,
   this REPL is yours, human+agents reach you by message; (b) the
   cluster is ONE graph database — any data with a `:seon.render/ai`
   renderer prints here automatically, `:seon.render/html` renders in
   the human's browser; (c) each reply may hold MANY forms, run in
   order, round-trip is expensive, plan the batch; (d) defn+:malli/schema
   persists, else scratch; (e) `(in-ns 'my.agents.<id>)` to start.

## THE DEBUG INTERFACE = THE CONTEXT (the new frame)

The owner: "THIS IS the debug interface I wanted." One renderer, two
projections, of the SAME REPL session:

- **`:seon.render/ai`** — plain text, exactly what the model receives.
  Reads as a real REPL session file (`;` comments + forms + printed
  output). Parses: feed the whole thing to the reader, zero failures
  (property P9).
- **`:seon.render/html`** — the SAME session, but composable HTML that a
  REPL cannot do on paper: long/hairy structures COLLAPSE via the
  general bounded renderer (the ported value floor) with expand-in-place
  drill; forms get syntax structure; still reads as REPL output, nothing
  replaced. This is the two-pane debug page's content.
- **A MARKDOWN projection for the human** (owner review aid): the same
  session as fenced code blocks + print-table tables — easy to parse by
  eye. May be the same bytes as ai (REPL text is already markdown-ish)
  or a thin superset.

OPEN DESIGN QUESTION the owner posed (the next-phase crux): **how does a
REPL print, and can we print composable HTML the same way?** A REPL
prints via `print-method`/`pr` over the returned value. The design
target: a print path whose atoms emit text for ai and HTML nodes for
html from ONE traversal (the general renderer already is that traversal)
— so "REPL output" and "composable HTML" are one mechanism at two
serialization leaves. "Closer to the metal" — mirror how Clojure's
printer actually works, don't invent a parallel one.

## The dynamic transcript (owner, 2026-08-01 evening)

The transcript re-renders VALUES at varying detail per render: the PDF
the agent read at turn 1 matters less by turn 10, so its entry prints
small with age while the recent tail prints full. The mechanics are the
pieces already landing, composed:

- the transcript is a DERIVED print of facts — nothing stored, so
  re-rendering old entries smaller is just the printer choosing
  per-entry presentation options (`:seon.render.value/options`, the
  declared-and-now-wired layer) from an age/relevance policy;
- ruling #25's blob tier is what makes aging HONEST: the full generous
  projection survives (result-blob + result-size), so detail is a
  render-time choice, never destroyed at commit (the old caps destroyed
  it — that is why aging could not work before);
- **re-query restores detail**: every receipt already has an identity
  (`:seon.cluster.eval/id`); the agent re-queries any prior result to
  bring it back at full output — the capped/aged print names the
  identity it would query. THE TUTORIAL MUST DEMONSTRATE THIS BEAT
  (read something big → see it age → re-query one part). Needs the
  small agent-facing read (blob-backed full projection by receipt
  identity) — the `result/<id>` idea reborn on the JVM.
- **one config dial pins detail constant → the transcript is STATIC**:
  same output at every age, fully deterministic, and the prompt-cache
  prefix never changes. Dynamic mode trades cache-prefix stability for
  context economy.

Open decision (owner): aging granularity. Continuous re-aging breaks
the prompt-cache prefix every turn; STEPPED aging (entries re-render
only at discrete boundaries, e.g. leaving the recent-tail window)
keeps the prefix stable between steps; STATIC pins everything. The
policy that derives per-entry options (age, size, relevance, budget)
is the same seam the context-budget layer already owns.

## Columns / tables

Canonical REPL answer = `clojure.pprint/print-table` — emits pipe+dash
tables that ARE essentially markdown tables, and are deep in model
training data. So we don't choose REPL-native vs markdown for tabular
data; print-table is both. Overhead measured/estimated ~+5–10% tokens
over raw pprint, tabular sections only (~30–60 tokens on a 1,300-token
context). Alignment padding mostly folds into single tokens. Approved
cost.

## A BETTER AGENT SCHEMA (owner: "start there")

Design the agent's PROJECTION around the reader (storage stays
normalized; these are CONTRACTED DERIVED EDGES from the agent, not
stored-derived). All connections point OUTWARD from the agent with
names that explain themselves:

```clojure
{:my/name      "tally"
 :my/guide     "…"          ; the instruction text, one string
 :my/tools     [fn-syms…]   ; the toolkit fns (from the cluster toolkit)
 :my/inbox     [msg…]       ; messages TO me, newest last  (reverse ref)
 :my/sent      [msg…]       ; messages FROM me
 :my/task      {…}          ; current run, ABSENT when idle
 :my/history   [entry…]     ; prior turns, oldest elided
 :my/namespace shop.cart    ; my code — (source-ns) to see it
 :my/teammates ["qa" …]}    ; other agents, human words
```

A "schemas legend" line names each schema shape appearing below, printed
concisely at the top so the data is pre-explained. Naming still open for
owner veto: the key set (inbox/sent/task/history/guide/tools/teammates),
whether an entry fn is named at all (owner wants LESS magic — prefer
teaching via real forms `(dir …)`/`(doc …)`/`(seon.db/q …)` over a
`(my/self)` convenience), and legend-in-banner vs first-key.

## The story beats (the creation run), with teaching intent

S1 minimal banner — credibility by restraint.
S2 `(help)` — the ONLY prose: world-model + batching economics +
   persistence rule + the `in-ns` start.
S3 `(in-ns 'my.agents.<id>)` — namespaces by doing.
S4 `(my.repl/prompt! …)` — ownership + visible persistence (prompt
   changes next line).
S5 narrate-then-batch — `(dir my.message)` + `(my.message/inbox)` in one
   round-trip; THE batching lesson performed.
S6 informed drill — `(doc my.message/send)` + the real `seon.db/q`
   count, batched; database-is-a-graph forced by the task.
S7 trailing prompt = the ask.

(Owner-agent turn-3 mockup: same frame, history includes a real arity
`Execution error` + its correction — error recovery taught in the
agent's own past, the single most trained-on repair signal.)

## What must become real (all small, all no-magic)

- `help`, `my.repl/prompt!` + `:my/prompt` fact + driver prompt rendering
- `my.message/inbox`, `source-ns` (or `(source …)` over a ns)
- `dir`/`doc` inside agent evals (sci ships them — mostly wiring)
- `seon.db/q` reachable in agent evals (the any-function ruling #20;
  under the interpreted-corpus substrate it is just available)
- CREATION RUN executed at creation through the real eval path
- the transcript PRINTER over existing form/receipt/message facts
  (typed entries; oldest-first; arrivals interleaved by instant)
- refusals rendered as REPL `Execution error:` output
- P9: the whole ai context reads clean through the reader
- the html projection = same session, collapsible via the general
  renderer; the markdown projection for human review

## Prototype

`tmp/repl_context_prototype.clj` (v2) constructs the session view from
the LIVE default cluster's real facts (default up at 7994) and writes
ai + markdown files, proving the printer against real data before the
production renderers land. v2 runs every runnable form through the
REAL door (`seon.sci.eval` fork → acquire! → evaluate) and captures
the true outputs; only the unbuilt fns' outputs are authored, each
named in `authored-targets` in the file. Artifacts:
`tmp/repl-context-bootstrap.{ai.txt,md}` (570 tokens) and
`tmp/repl-context-root-real.{ai.txt,md}` (192 tokens — root's actual
stored turn, zero authored bytes).

### Prototype v2 findings (2026-08-01, all REPL-verified live)

- **P9 REDEFINED (owner, 2026-08-01): forms re-run, output is output.**
  The original "whole context reads clean through the reader" forced
  prose outputs to print as `;` comment lines and error faces to hide in
  comments — no real REPL does that, and the goal is a session
  indistinguishable from a real Clojure REPL. P9 now: every FORM after a
  prompt echo must read and re-run identically (the replay property);
  printed output prints exactly as a REPL prints it — prose, error
  reports, `#object[…]` faces and all. The earlier comment-prefixing
  rules are DELETED.
- **The door today**: `(in-ns …)` works (admitted as opaque map);
  `clojure.repl/dir` WORKS and prints exactly the installed public fns
  (`decline`, `send` — `send-value` is `:seon.fn/private? true`), but
  bare `dir`/`doc` are unresolved (refer wiring pending); `doc` prints
  NOTHING for corpus fns (must be wired to `:seon.fn/doc`/`arglists`
  facts, not var metadata); `seon.db/q` and `datahike.api/q` are both
  unresolved — the any-function db surface for agent evals is a real
  gap, the exam cannot run through the door yet (the count, 7, is
  computed JVM-side by the same query).
- **`my.message/send` value-shape works end to end**: returns
  `#:my.message{:to "root", :content "hi"}` through admission.
- **One reader event per evaluation** is enforced
  (`::reader-event-count` refusal), so a batch is several form rows
  echoed at several prompts — the printer renders per-form prompts,
  which is also what pasting a batch into a real REPL looks like.
- **Wrong arity prints garbage**: `(my.message/send)` surfaces as
  `Execution error: :malli.core/invalid-schema` — the single most
  trained-on repair signal (arity error) is unteachable in this form.
  Issue filed: `docs/seon/issues/arity-mistake-prints-invalid-schema.md`.
  The owner-agent turn-3 error-recovery mockup blocks on this fix (its
  history beat needs real, legible arity bytes).
- **Refusal face**: `Execution error: …` printed bare, exactly as
  clojure.main reports it (the comment-wrapped variant is deleted with
  the P9 redefinition).
- **Arrivals** render as `;; ← <from> (HH:MM)` + the pr-str'd content
  line (reader-clean); a message with no from-agent renders as
  `the human`.
- **Prompt truth**: the historical prompt is derived from the receipt's
  own `:seon.cluster.eval/ns` (root's real turn prints
  `my.agents.root=> `), never from the current prompt fact.
