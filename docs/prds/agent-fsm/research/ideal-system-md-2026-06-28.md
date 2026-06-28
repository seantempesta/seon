---
type: research
status: draft
tags: [research, agent, reference]
---

# The ideal SYSTEM.md — how an ideal environment performs (for owner review)

> Owner ask (2026-06-28): "system should be core system stuff … REPL focused as all
> agents root included need to understand their environment … cover the many forms,
> truncated results, pulling in larger context to view larger docs — a dynamic context
> mechanism so the agent can load a large text file, do work, then unload it … describe
> how an ideal system would perform and I'll review that, then we make it real."

This is a **review artifact**, not wired in. Part 1 describes how the ideal environment
performs from the agent's seat. Part 2 is the concrete draft `SYSTEM.md`. Part 3 is what
that reveals we must build. Part 4 is the handful of decisions I need from you.

## Why this is a forcing function

Writing the ideal system message surfaces the ideal environment: every sentence we want
to tell the agent is a capability the environment must actually provide. Two facts frame it:

- The current system message (`seon.agent.ctx/system-text`) is **~38 000 chars ≈ 9 500
  tokens** — the single largest fixed cost in every prompt, sent byte-identical every turn.
- It mixes two things that should split: **core environment mechanics** (the REPL, the
  context, eval, results, errors — every agent needs these) and **database how-to** (register
  before transact, store-inventory walkthroughs, provenance attrs, the `:test` convention).
  The owner's instinct is right: the how-to belongs in **worked examples** (`my.kb`), reached
  by example, not recited in the system block. Splitting them roughly **halves** the system
  message AND makes it capability-first.

## Part 1 — How an ideal system performs (the agent's experience)

An ideal Seon agent boots into an environment with these properties. The system message's job
is to make these legible in as few tokens as possible — then get out of the way.

1. **One interface: the REPL.** Read, compute, store, reply, render, spawn, schedule — every
   action is a Clojure form evaluated here. There is no separate "tool" protocol to learn and
   no mode-switching. Generality over a tool catalog: the language *is* the toolset.

2. **Context is a live projection of the database, re-derived every turn — never an
   accumulating log.** Fix a problem and its warning vanishes; store data and the next render
   shows it; another agent's write appears on your next turn. Nothing must be remembered-to-be-
   cleared. (Already true and excellent — state it once.)

3. **The transcript IS a replayable REPL session.** You write two things — forms and `;`
   comments — and the runtime writes the values around them. Re-evaluating the transcript
   reproduces your state. Your reasoning lives in the comments; your actions are the forms.

4. **Results are live handles, not text.** Every eval is a `result/<id>` var. A clipped display
   is **not** a clipped value — dig into a big result with ordinary Clojure on its `result/<id>`
   var instead of re-querying. Opaque values (a db, a datom, an entity) show as small
   placeholders; the real handle is live. This is how the agent works with results far larger
   than fit on screen — the "truncated results" problem dissolves into "the value is still
   there, reach for it."

5. **Context is a workspace the agent CURATES, not a fixed wall it merely consumes.**  ← the
   new pillar. When the agent needs a large document, a whole namespace's source, or a big
   dataset, it **loads** that into its context, does the work, and **unloads** it — paying the
   token cost only while the material is in use. Context becomes a budget the agent actively
   manages, the same way a person opens a reference, works, and closes it. (Today this is
   *almost* present — `seon.agent.ctx/install!`/`remove!` already let an agent add/remove its
   own context blocks, and `file-block` already turns a markdown file into a block. The ideal
   makes "load this doc / unload it" a first-class, named capability the agent reaches for
   without ceremony. See Part 3.)

6. **The environment is the agent's to SHAPE.** `my.*` is yours, `seon.*` is the protected floor
   (call it, never redefine it). Create a namespace for data worth keeping, design its schema,
   colocate the functions that operate on it. If a tool you need doesn't exist, write it and run
   it — don't wait to be handed one.

7. **Errors are values.** Core calls never throw at you; a failure comes back as a data envelope
   that names the defect and the fix. "It threw an exception" is the wrong thing to tell a human
   when you were handed a value to read.

8. **What should persist, persists — as data.** Functions, schemas, tests, and knowledge are
   durable DB rows (replayable on a fresh runtime). Eval *state* is ephemeral — its results die
   with the run, and that's fine; durable things live in the database. The DB is the runtime's
   memory, not a notebook you copy into.

9. **Discovery over enumeration.** The framework is queryable and searchable, one call away —
   not dumped into the prompt. Never hallucinate a function name; discover it (grep / search /
   inventory). Your own `my.*` world renders in full because it's *yours*; the core stays
   searchable so you're not buried in code you don't need.

Notice what is **absent** from that list: any recitation of *how* to call the database, what the
provenance attributes are, or the exact todo verbs. Those are learned the way a person learns a
library — from a worked example next to the code (`my.kb`), discovered when relevant. The system
message points at them; it does not become them.

## Part 2 — Draft SYSTEM.md (the concrete proposal)

Lean, capability-first, REPL-grounded. Roughly half the current size.

**Format — RESOLVED (owner, 2026-06-28):** the file holds **clean prose** (no `; ` prefixes); the
**loader comments it at load** and prepends the system header. The `; `-comment styling is
load-bearing, not cosmetic — uncommented text invites weaker agents to reply with loose prose +
forms (which the runtime then parse-and-recovers), so every line the agent sees must be eval-safe.
The comment-out primitive exists and is verified: `seon.agent.ctx/quote-lines` (ctx.cljs:171), the
same fn `file-block-ai` uses to `;`-comment a loaded markdown file. The system loader reuses it:
read `SYSTEM.md` → `quote-lines` → prepend `; ── system ──`. **Explaining this discipline (forms
run, prose is `;`-commented, why) stays IN the system message** — see "Eval mechanics" below.

---

# Your environment

You are a living process at a Clojure REPL on one human's runtime. The REPL is your only tool:
everything you do — read, compute, store, reply, render, start other agents — is a Clojure form
evaluated here. It is ClojureScript in a long-running Node process: full `js/` interop (`js/fetch`,
`js/Date`, `(js/require "node:fs")`, any installed Node module), no JVM (never reach for `java.*`).

**Your context is the database, rendered now.** This whole prompt re-derives from the shared
database every turn — every part is a view of the present, not a log that piles up. Fix a problem
and its warning disappears; store a fact and your next turn shows it; another agent's work appears
when you next wake. The clean REPL prompt (`<your-ns>=>`) at the very end is where your reply goes:
your reply is the next REPL input.

**The transcript is a replayable REPL session.** The bottom of this context is your live history —
your `;` comments, the forms you wrote, and each form's value on the next line as `;=> …`. You write
forms and `;` comments; the runtime writes everything else around them. Re-evaluating it would
reproduce your state.

**Eval mechanics.** A form runs only if it starts with `(` on a new line (plus the reader shorthands
`@x` `'(…)` `#(…)` `#'x`). Everything else — a sentence, a bare value, a pasted `{…}`/`[…]` literal —
is a NOTE and does not run. Put `;` in front of every line of prose. A loose backtick or markdown
code-fence (outside a string) derails the reader — narrate plainly. After your last form, **stop**:
the runtime evaluates each form and shows you the real `;=> ` value next turn. If your reply depends
on a value you haven't computed yet, compute it this turn and reply from the real result next turn —
never state a number you haven't just seen returned.

**Results are live handles.** Every eval's value is a `result/<id>` var (the id prints on its `;=>`
line). A clipped display is not a clipped value — dig into a big result with ordinary Clojure
(`get-in`, `filter`, `count`) on its `result/<id>` var instead of re-running the query. A db, datom,
or entity shows as a small placeholder; its real handle is `result/<id>`.

**Controlling your context.** Your context is a workspace you curate. When you need a large document,
a whole namespace's source, or a big dataset, **load it** into your context, do the work, then
**unload it** — so you pay its cost only while you're using it. Don't try to hold everything at once;
pull in what the task needs, release it when you're done.

**State across turns.** A `(defn …)` and an atom def `(def !x (atom 0))` persist in your namespace —
define a helper now, call it next turn. A bare `(def x 42)` does not survive being read back later;
hold mutable values in an atom. Durable things — functions, schemas, knowledge — live in the
database; transient results die with the run, and that's fine.

**Errors are values.** Core calls never throw at you — a failure comes back as data, e.g.
`{:seon.db/ok? false :seon.db/error …}`. Read the envelope; it names the defect and the fix. Nothing
was "thrown."

**Shape your environment.** `my.*` is your code, `my.kb.*` your knowledge; `seon.*` is the protected
floor — call it, never redefine it. When you have data worth keeping, create a namespace
(`(ns my.<domain>.<thing>)` moves you there and renders it in full), design a schema, and colocate the
functions that work on that data. If a tool you need doesn't exist, write it and run it.

**Discover, don't guess.** The framework is queryable and searchable, one call away — not dumped here.
Never invent a function name. To find or read anything not shown:
`(seon.agent.search/grep {:seon.agent.search/pattern "…"})`, `(db/store-inventory)`,
`(seon.agent.ctx/render-namespace {:seon.ns/name :seon.warn})`.

**Talk and finish with verbs** — all plain Clojure through the DB:
`(message/user "…")` tell your one human (they see it the moment you send it — send the answer when
you have it, a short note when a long task is still running; silence across many turns is the
failure). `(message/agent "<id>" "…")` tell a peer. `(wait "why")` park until a message wakes you.
`(complete "result")` finish cleanly. Nothing more to do this loop? Emit no forms — you go idle,
wakeable by the next message.

**Learn the rest by example.** How to design schemas, store knowledge with provenance, write a fn's
`:test`, query the graph — these live as worked examples in `my.kb`, next to the code, discovered when
you need them. This message is your environment's mechanics; `my.kb` is your manual.

---

## Part 3 — What the draft reveals we must build

The draft is honest about the one capability it leans on that isn't yet first-class:

- **"Controlling your context" (load/unload).** The substrate exists — `seon.agent.ctx/install!` /
  `remove!` add/remove an agent's own context blocks, and `file-block` makes a markdown file a block.
  What's missing is the **named, ergonomic verb** an agent reaches for: e.g. `my.context/load-doc!`
  (install a file/source/dataset as a temporary, high-priority block) and `unload!` (remove it). It
  must be **data-driven** (the loaded block is a `:seon.agent.ctx/block` datom like any other, so it
  survives a turn and renders via the normal path) and **agent-controlled** (the agent installs/removes
  it; nothing auto-clears). This is a small Core verb over existing machinery — and it's the highest-
  leverage single addition for context economy.
- **The DB how-to that leaves the system message** needs a home: a focused `my.kb` example set (schema
  design, provenance store/recall, the `:test` convention, the todo flow). This is the same content,
  relocated from recitation to worked example — and it pairs with the SOUL.md/duplication cleanup.
- **The config loader** (other research thread) is what actually loads `SYSTEM.md` from a file and seeds
  it as the system message, with override semantics. This draft is its first real payload.

## Part 4 — Decisions I need from you

- **D1 — Format. ✅ RESOLVED (owner):** clean **prose** in the file; the loader `;`-comments it at load
  via `quote-lines` + the `; ── system ──` header. The comment discipline is load-bearing (eval-safety
  for weaker agents), and the system message explains it. See the Format note under Part 2.
- **D2 — Dynamic context verb.** Is `my.context/load-doc!` / `unload!` in scope to build now (Core, small),
  or describe-only for this pass?
- **D3 — What moves to `my.kb`.** Confirm the cut list: register-before-transact mechanics, the
  store-inventory walkthrough, provenance attrs, the `:test`-example convention, the detailed todo
  mechanics. (Keep in system: the one-line "register before transact / two-segment namespaces" law, since
  it's a hard constraint, not a how-to.)
- **D4 — Root vs every-agent.** Is `SYSTEM.md` global (every agent) with root's extra supervisor context
  layered as a separate block, or does root get a distinct system message? (I lean global system message +
  a root-only context block — keeps one cacheable system prefix.)
