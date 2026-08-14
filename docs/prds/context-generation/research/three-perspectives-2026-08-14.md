---
type: research
status: current
tags: [research, render, context, design]
---

# Three agents, one mechanism — the pull, the overrides, and both pictures

The document the owner asked for, 2026-08-14 night. For each of three
agents — root, a temp chat agent, and a maintainer of `my.note` — this
walks the SAME four moves and shows how they paint three completely
different contexts and three completely different pages with zero
per-agent machinery:

1. **the pull** — rooted at the agent's namespace (ruling 39/40:
   agents ARE namespaces; no manual membership; the schema-generated
   selector discovers everything);
2. **selection per discovered value** — the one chain: inline override
   on the value → stored face (a program fact or the schema-declared
   face) → the floor (ruling 35: a face is terminal; the floor
   composes only what nothing claimed);
3. **the AI picture** — the form+value REPL entries in derived order;
4. **the HTML picture** — the same blocks arranged by ruling 38:
   newest-changed block primary, side panels by last update, pin
   locks, all live.

Grounding: every ref named is installed today; the `my.note` faces
are real program facts (visible in the [live
capture](root-context-example-2026-08-14.md)); routes per ruling 41.

---

## 1 · Root — `my.agent.root`, the fleet's home

**The pull** at `[:seon.ns/name my.agent.root]` discovers: the ns form
`(:require my.message my.run seon.bootstrap seon.db)`; those four
namespaces through `:seon.ns/requires`; root's indexed functions
(today none); and through the namespace's agentic facts: the open run,
inbound messages (the bootstrap task, maintenance notices), the plan,
routed errors — plus, one distance further through message `/from`
refs, the OTHER AGENTS root has corresponded with. Root's fleet view
is not a feature: it is what reachability looks like from the one
namespace every agent messages.

**Selection, member by member.** The ns form and dir listings have no
declared face → floor (data rows + shape-bearing elisions). Messages
hit the schema-declared `seon.cluster.message` faces; the run hits the
run family's face (today narrating — register #3 converts it to a
data face); errors hit the error family. Nothing about root is
special-cased: root's DIFFERENT picture comes entirely from DIFFERENT
reachable data.

**The AI picture** (sketch, derived order):

```text
my.agent.root=> (dir 'my.agent.root)        ; the ns form first — define-before-use
my.agent.root=> (dir 'my.message) …         ; the four requires, names before use
my.agent.root=> (my.message/inbox db "root")
[{:my.message/id "bootstrap-task:root" …}]  ; data face, reads back
my.agent.root=> (db/pull db '[*] [:seon.cluster.run/id "f5305f54-…"])
{:seon.cluster.run/opened-at #inst "…" :seon.cluster.run/process "99029-…"} ; data, not narration
```

**The HTML picture** at `/`: root's namespace view IS the system view.
The transcript block changes most → holds primary. The side panels are
the other agents' newest-basis blocks — reachable through root's
message refs, each one a live card (ui.md's tile view, now DERIVED
rather than declared). A human watching `/` sees the fleet because
root's neighborhood IS the fleet.

**An override, and what changes.** Root defines
`(defn fleet-html [agents] …)` with a contract accepting its agent
rows and output `:seon.render/hiccup`. That definition is a program
fact in `my.agent.root` → next render, the chain's stored-face rung
selects it for that data → the fleet card grid replaces the floor's
row listing ON BOTH SEAMS' SUBSTRATE (the block's `/html` face
changes; its `/ai` face unchanged unless root also defines one). The
block just changed → it takes primary (ruling 38). No route, no
registration, no config.

---

## 2 · A temp agent — `my.agents.k3f9`, born from "new chat"

**The pull** discovers almost nothing — and that is the design
working: the shipped ns form (say `(:require my.message my.run)`),
two dir listings, no indexed members, one inbound message (the
human's first line), one open run, an empty plan.

**Selection**: the message → the message face; the run → the run
face; everything else → floor. The temp agent's context fits on one
screen BY DERIVATION.

**The AI picture**:

```text
my.agents.k3f9=> (dir 'my.agents.k3f9)
(ns my.agents.k3f9 (:require my.message my.run))
my.agents.k3f9=> (my.message/inbox db "k3f9")
[{:my.message/id "…" :my.message/content "can you graph my sleep data?" …}]
```

**The HTML picture** at `/ns/my.agents.k3f9`: transcript primary
(it's the only thing changing), side panel empty. A chat window,
derived.

**The override that makes it an app.** The agent answers by defining
`(defn sleep-chart [readings] …)` → `:seon.render/hiccup`, and
evaluating a form whose result it wants shown. Two override paths,
both ordinary:

- **inline** (rung 1): the agent returns the value with
  `:seon.render/html 'my.agents.k3f9/sleep-chart` ON the value — data
  wins, that block renders through the chart;
- **stored** (rung 2): the function's contract fits the reading rows,
  so ANY such rows in the neighborhood select it automatically.

Either way the chart block is newest-changed → primary; the transcript
slides to the side panel; the human sees a chart where a chat was, and
can pin it. The agent's context shows the SAME block as its
form + printed value, so "my human is seeing this" is structurally
true. This is the whole "define a function and it's there" story with
zero present-to-user machinery.

---

## 3 · A maintainer — an agent rooted at `my.note` itself

Under ruling 40 the assignment is not "an agent linked to my.note" —
the namespace IS the agent now; its context is `my.note`'s world.

**The pull** at `[:seon.ns/name my.note]` discovers: the ns form and
requires; ALL of `my.note`'s functions with contracts and docs
(reverse `:seon.fn/ns`) — including its OWN SIX DECLARED FACES
(`render-note-ai/form/html`, `render-notes-ai/form/html`, real program
facts today); its schema rows (`:my.note/id` identity, the error
classes); its tests (reverse `:seon.test/ns`, per-function
`:seon.test/subject`); its DEPENDENTS through reverse `:seon.fn/calls`;
incoming change-request messages; its run and plan.

**Selection** — here the override story inverts: the namespace being
maintained CARRIES its own faces, so actual note rows in the
neighborhood render through `render-note-ai`/`render-note-html`
(stored faces, rung 2/3), while the maintainer-work material (tests,
dependents, change requests) rides the floor and the shared families.
The maintainer sees its subject matter through the subject's own eyes
— the exact faces its users see — which is what makes "UGLY OUTPUT IS
A DEFECT" actionable: this agent IS the owner of those faces, and its
own context confronts it with them.

**The AI picture** (sketch):

```text
my.note=> (dir 'my.note)                      ; its own public surface, contracts, docs
my.note=> (seon.fn/tests-reaching db "my.note/add!") ; reachable test facts
my.note=> (my.message/inbox db "my.note")
[{:my.message/content "add! should accept a tag set — request from k3f9" …}]
my.note=> (my.note/notes db "my.note")
Note packing-list: "…"                        ; ITS OWN render-notes-ai face
```

**The HTML picture** at `/ns/my.note`: transcript primary; side
panels are `my.note`'s own HTML faces rendering live data —
`render-notes-html` over current notes, the test-status block, the
plan. When the maintainer edits `render-note-html` and re-evaluates,
that block is newest-changed → takes primary: the maintainer
LITERALLY WATCHES its own face change as it works, and every other
page showing notes morphs on the next render. The feedback loop for
face curation (rulings 34-35) is the page itself.

---

## What the three prove together

- ONE mechanism (namespace-root pull → chain → two seams) with ZERO
  per-agent configuration produces a fleet console, a chat app, and a
  maintainer's workbench — the differences are entirely which
  namespace the pull is rooted at and which faces are reachable.
- Overrides compose at exactly three grains, all data: inline key on
  a value (this block, now), a defined contract-fitting function (all
  matching data, this namespace), a schema-declared face (all matching
  data, everywhere). Painting a different picture = writing ordinary
  Clojure into your own namespace.
- Ruling 38's recency-promotes rule is what turns face definition
  into presentation: define → block changes → primary. No other
  mechanism exists or is needed.
