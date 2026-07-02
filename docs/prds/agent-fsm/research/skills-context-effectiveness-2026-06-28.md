---
type: research
status: active
tags: [research, agent]
---

# Skills + config-driven context — does it land in a real agent's prompt?

Read-only context-observer pass on the LIVE default pod (7890), 2026-06-28.
Grounding: the two trigger drives — `050a215b` (ui-live-tiles drive: "agent
SHOWS a view; message-verb undiscoverable") and `89201467` (acme A/B:
"repl-skill guidance is null"). Both predate the config-driven loadout +
skills going live on the DEFAULT pod (`525bd0f0`, `aba1b5dd`). This pass
re-checks on the default pod, rendering the ACTUAL prompt bytes an agent
sees via `seon.agent.ctx/render-context`.

Method: `render-context {:seon.agent/id "root"}` against a freshly re-seeded
world (a cluster reset landed mid-pass — fortunate: gave a clean root with
no accumulated history to contaminate the "what does a fresh agent see"
question). Quotes below are verbatim prompt bytes.

## TL;DR

- **The skills MECHANISM works end-to-end on the default pod.** The
  `:skills-catalog` block (priority 12) and the default-loaded `:skill/repl`
  body (priority 16) both render in a real agent's prompt. The catalog
  correctly excludes `browser-automation` + `clojure-testing` per
  `config/system.edn`. The repl body is the real SKILL.md content,
  `;`-commented, with the derived token footer. No mechanism bug.
- **The "message-verb undiscoverable" finding is HALF-fixed on the default
  pod — and the remaining half is a RENDER gap, not missing content.** The
  home-ns now aliases `[seon.agent.message :as message]` at runtime (every
  agent, via `seon.eval/setup-agent-ns!`), so `(message/user …)` WORKS — root
  used it successfully this boot. BUT the rendered home-ns block STRIPS the
  alias, showing bare `(:require … seon.agent.message …)`. The agent can call
  the verb but cannot SEE that `message` is its alias. This is
  context-composition (Core/`my` lane), not UI's skill/toolkit text.
- **Skill CONTENT value remains unproven/null for capable models.** repl is
  ~860 always-on tokens on EVERY agent; two A/B drives (gemini, acme-DeepSeek)
  found no measurable read-error reduction. The mechanism is solid; the
  marginal value of default-loading the repl BODY is not.

## Q1 — Is the repl skill in a real agent's rendered prompt? YES.

`render-context` for root yields these blocks (name, priority, renders-ai):

```
[:shared-instructions 10] [:skills-catalog 12] [:skill/repl 16]
[:namespaces 20] [:live-tile 35] [:warnings 40] [:open-todos 45]
[:relevant-source 48] [:inventory 97] [:transcript 100]
```

`:skill/repl` renders (bracketed as `┌─ repl ─`, ~919 tok). Head + footer
quoted verbatim:

```
┌─ repl ─
; # REPL — how your forms are read, repaired, and run
;
; Every reply you write is split into top-level forms by a **real reader**
; (rewrite-clj), not string-splitting. …
…
; ── repl skill · ~863 tok
;    done? (my.skills/unload :repl) ──
;;; └─ end repl ─
```

Real SKILL.md body, `;`-commented (eval-safe), derived token footer present.
Default-loaded via `config/system.edn` `:seon.config/default-load [:repl]`.
**No mechanism bug** — config default-load → `:skill/repl` block →
`my.skills/skill-block` → fresh file read all fire correctly.

## Q2 — Is the catalog present + does it reflect config curation? YES.

`(my.skills/list)` → `[:clojurescript :data-oriented-clojure :datahike :repl
:ui-live-tiles]`. `browser-automation` + `clojure-testing` are correctly
absent (the `:seon.config/skills :exclude` list). The catalog renders with a
clear how-to header:

```
; SKILLS — loadable knowledge. Each costs nothing here until you load
; its body. Load one with (my.skills/load :name); its full body then
; rides your context showing its token cost — (my.skills/unload :name)
; to drop what you're done with. ● loaded · ○ available.
; - :clojurescript  ○ — ClojureScript semantics for the Seon CLJS pod. Use when …
; - :repl  ● loaded — How the Seon REPL reads, repairs, and evaluates …
```

`● loaded` for repl (derived from the agent's own `:skill/*` blocks — correct).
An agent would know to `(my.skills/load :name)`. **Catalog: PASS.** (Note:
`ui-live-tiles` is the frontmatter `name` of the `datastar-web-ui/` skill dir
— name ≠ dirname, no silent drop.)

## Q3 — Can an agent discover how to message its human?

This is the load-bearing finding. On a fresh root, `message/user` appears 3×;
ALL 3 are non-instructional artifacts:

| occurrence | block | nature |
|---|---|---|
| `(message/user "hi")` | repl | incidental list-vs-prose example |
| `16:42:08 root λ (message/user "Hi …")` | live-tile | root's OWN boot eval, echoed in the activity log |
| `(message/user …` + `;=> {…ok? true…}` | transcript | root's own past eval |

None is a "to message your human, call `(message/user …)`" instruction. For a
truly fresh CHILD (empty transcript, empty activity), only the buried repl
example remains.

**Why it nonetheless WORKS at runtime:** `seon.eval/setup-agent-ns!`
(`src/seon/eval.cljs:1232-1239`) evals, for EVERY agent's home ns:

```clojure
(ns my.agent.root
  (:require [seon.agent.message :as message]
            [seon.agent :as agent]
            [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
            [seon.schema :as schema]
            [seon.db :as db]
            [seon.agent.todo :as todo]))
```

So `(message/user …)` / `(db/transact! …)` / `(todo/add! …)` / `(wait)` /
`(complete)` all resolve without the agent doing anything. The ui-live-tiles
drive's "~100 evals to find `(require '[seon.agent.message :as message])`" was
on acme's OLD pre-config bundle; on the DEFAULT pod the alias is pre-wired.
**The runtime half of that finding is fixed.**

**The remaining half is a RENDER gap.** The home-ns block does NOT show the
real require form — it reconstructs a stub from `:seon.ns/requires`, which is
a `[:vector :keyword]` of BARE namespace names (no `:as`/`:refer`). Verbatim
rendered bytes:

```
┌─ namespace my.agent.root ─
(ns my.agent.root
  (:require seon.agent seon.agent.lifecycle seon.agent.message seon.agent.todo seon.db seon.schema))
; (your workspace — nothing defined here yet; define schemas + fns and they appear here)
```

Source: `seon.agent.ctx.namespaces/cur-ns-workspace-stub`
(`src/seon/agent/ctx/namespaces.cljs:209-211`) joins `(map name reqs)` — alias
metadata is gone because `:seon.ns/requires` (`src/seon/agent/ctx.cljs:101`)
only stores bare keywords. So the agent SEES `seon.agent.message` with no
alias and must GUESS that `message` is the handle. Capable models guess the
last-segment convention; weaker ones burn evals (the drive's failure).

**Secondarily:** `seon.agent.message` is curated OUT of the full-source
namespaces. `full-source-whitelist` (`src/seon/agent/ctx/namespaces.cljs:126`)
is `#{:seon.agent.todo}` — todo gets a full docstring+verb block; message gets
nothing showing the `user` verb's signature. The docstring at lines 114-117
even names "message" among the dropped nses.

**Lane verdict (answers the observer's Q3):** the gap is
CONTEXT-COMPOSITION (which blocks render / what the home-ns stub shows) —
Core/`my` lane — NOT UI's skill/toolkit text.

## Q4 — Net: does the skills/config context HELP, or inert tokens?

Skeptical read:

- **Mechanism: genuinely helps / is correct.** Catalog renders, exclude
  works, load/unload installs a real block with a real cost footer, `loaded?`
  is derived. This is solid, self-healing plumbing.
- **Default-loaded repl CONTENT: unproven, plausibly inert for capable
  models.** ~860 always-on tokens on every agent every turn. Two A/B drives
  (`89201467` acme-DeepSeek; the earlier gemini null) found NO read-error
  reduction — the parser-repair guidance targets noisy/weak generation;
  DeepSeek/Gemini write clean Clojure. The ui-live-tiles drive agent never
  loaded a non-default skill at all (got enough from always-on blocks). So the
  catalog+load SYSTEM is proven; the marginal value of the repl BODY being
  default-ON is not. Candidate: drop repl from `:default-load` (keep it
  catalog-loadable), reclaiming ~860 tok/turn/agent — judgment call for the
  owner, flagged not actioned.

## Findings routing

### (b) Mechanism bugs / gaps for Core (`my` lane), with file:line

1. **HIGHEST LEVERAGE — home-ns stub strips aliases.** The rendered workspace
   ns form should show the REAL require with aliases/refers (the canonical
   form lives in `seon.eval/setup-agent-ns!` `src/seon/eval.cljs:1234-1239`),
   not bare names. Fix at `src/seon/agent/ctx/namespaces.cljs:209-211`
   (`cur-ns-workspace-stub`) — render `[seon.agent.message :as message]`,
   `[seon.db :as db]`, `[seon.agent.todo :as todo]`,
   `[seon.agent.lifecycle :refer [wait complete pause resume terminate]]`,
   etc. Requires either storing the alias-bearing form (extend
   `:seon.ns/requires` beyond `[:vector :keyword]` at
   `src/seon/agent/ctx.cljs:101`) or rendering from the single canonical
   home-ns form. ONE change surfaces `message/user`, `db/transact!`,
   `todo/add!`, `schema/register!`, `wait`/`complete` — every verb alias — as
   copy-pasteable, self-documenting, with zero new prose.

2. **`seon.agent.message` curated out of full-source namespaces.**
   `full-source-whitelist` `src/seon/agent/ctx/namespaces.cljs:126` =
   `#{:seon.agent.todo}`. Adding `:seon.agent.message` renders its docstring +
   `user`/`agent` verb signatures the same way todo's render — making the
   message API discoverable by example (the todo block is explicitly "THE
   EXEMPLAR"). Lower priority than #1 if #1 lands (the alias render alone may
   suffice), but it is the principled twin: the agent messages its human as
   often as it plans.

### (c) Content gaps to route to UI (skill/toolkit text)

- None blocking from this pass. The repl + ui-live-tiles skill BODIES are
  UI/toolkit's lane to refine (the ui-live-tiles drive already filed the
  safelist/status-dot hoist). The message-verb gap is NOT content — do not
  route it to UI.

### (d) Single highest-leverage fix

**Render the home-ns require form WITH its real aliases/refers**
(`src/seon/agent/ctx/namespaces.cljs:209-211`). It is the cheapest change with
the widest payoff: it closes the message-verb discoverability gap (the biggest
eval-burn in the trigger drive) AND surfaces every other verb alias at once,
turning the always-on workspace stub from a misleading bare-names list into a
copy-pasteable verb map. The aliases already work at runtime; only the render
hides them.
