# Review: notes/design-agent-view-2026-10-24.md (independent, read-only, 2026-10-24)

Verdict: **needs changes before steps 2-3; steps 0-1 (provider check, transcript reshape) are ready to prototype** once the transcript items below (A1-A4) are folded in. The transcript half is sound and mostly a reshape of what context.clj already does. The render half has one structural gap (who renders an agent's page when its process is busy or gone) and three mechanisms that are heavier than the need.

## 1. Factual errors

1. **"No render can take the whole context: each child gets a share of its parent's budget" / "breadth before depth" (note §4 l.56-57).** Wrong for hiccup. A hiccup element is a vector, and the printer fills vectors with `fill-members` (print/select.clj:557-587): whole members in order, the first that does not fit gets everything left, then one cut. Breadth-before-depth is `fill-map`/`grow-values` (select.clj:602-641), which applies only to maps. So `[:div sidebar chat tiles]` with a large sidebar (thousands of agents) gives the sidebar the whole budget, and chat and tiles become a cut. The props map (2nd member, Tailwind class strings) is printed before any child. Either the printer gets a share rule for element children (a real change to the selection, not the ~40-line hook printer-reuse.md priced) or the note must drop the claim and rely on layout order alone. Layout order alone does not meet "a single render can't blow the context" for anything but the last child.
2. **"A reply with prose and no call ... (today: an error)" (l.25).** Today it is not an error. `reply-of` (model.clj:157-170) accepts text with no calls. The turn evaluates nothing (turn.clj:205), and the next request carries a user message, "Nothing ran: the reply called no eval." (context.clj:345), and the loop goes on, which wastes a turn. Only empty content plus reasoning is an error (`:seon.model/reasoning-ate-reply`, model.clj:158). The proposed change is still right. Its "replaces" line is wrong, and it should delete context.clj:345.
3. **"Today each form gets its own result/hN": true (repl.clj:103-106).** But the note implies the new `eN` is stable. Today `n` is a per-process counter (`:n 0` at agent.clj:769), so it restarts after a resume and collides. `eN` must be the eval record id (`run/n`, repl.clj:354), not the process counter.
4. **"The model reads the view at its request's basis (main's t)" (l.33).** The context is built in the agent's process from `(agent/db)`, its branch (turn.clj:191). Main's head is not in it, and the agent holds main only as of its merge base (`:base`). Reading the view fact, and the sidebar summaries of §5, needs a read of main's head from the agent, or root pushing them. Neither exists, and the note does not name one.
5. **"Agents commit at turn end, so root can't see evals live" (l.46).** Nearly right. A commit happens whenever the agent is idle or at a turn's end (agent.clj:48-60, 145-150). The turn loop runs in the agent's process, so evals inside a turn are not visible to root. This fact matters for the alternative in §3 below.
6. Cost of the function card's "perf" (§6): `:fns` lives inside the `:seon.cluster.eval/record` data blob (repl.clj:355, seon.facts.edn:115), not as indexed facts. It also holds only the top 10 functions per eval. "Cost of var f" is therefore a scan of every eval record, O(history), which contradicts §8's "nothing costs O(history)". It is also usually empty for a given var.
7. Minor: `/call` is routed at web.clj:~158 (l.76 is `requests`). `pull-spec-attribute-dependencies` is called inside datahike (pull_api.cljc:91), though by nothing of ours. `:seon.render/as :inline`, `history-budget`, `my.agent/callers`, `seon.docs/source-of`, `my.schema/register!` and `query-attribute-dependencies` all exist as cited.
8. Render lookup is "the branch's registry, then main's (one map lookup, seon.render/declared)". `declared` (render.clj:40-46) reads the binary's `contract/properties` and walks parents. The branch registry is `my.schema/forms` = `contract/forms` merged with `program/schema-changes (agent/db)`, a db read cached per agent state (reuse.md §9). It is cheap, but it is not one map lookup, and `declared` must be changed to take a registry.

## 2. Gaps and contradictions in the note

A. Transcript (owner requirements 1-2)
- **A1. The `;;` framing is not removed.** The requirement is no `;;`/`;=>` anywhere the model sees. Three places still produce it:
  - the eval tool's description, "your thought about each in ;; comments before it" (model.clj:96), and its `thinking` argument;
  - the help page, docs/agent.md:11, 12 and 44 ("Think in `;;` comments");
  - `render/ai.clj:200`, which emits `=> ` and `\n;; result/hN`, plus `context.clj:173` (`;; Nx`) and `:366`.

  None of these is in §9's list. With prose moving to `content`, the `thinking` argument and `:lead` should go together.
- **A2. "No hidden render on a value" needs its deletion named.** `wrap-print` (repl.clj:118-125) prints "the value as its render shows it (seon.render/printed: its schema's declared AI render)". Principle 1 contradicts today's code. §9 says only "the two render faces". Name `wrap-print`'s render path, `render/printed` and `render/ai` as the things deleted.
- **A3. The result map has no error shape.** `{:result :value :ms :ticks :bytes :out :err}` uses `:err` for stderr, so a thrown exception has no key. Add `:ex` (the error map). Name the schema key: the requirement says "spec'd data". Also, today a failing form does not stop the later forms (repl.clj:114, `(recur ns end)`). "Last form's value, as load-string" means stopping at the first error. State that.
- **A4. History shrinking is under-specified.** "In large steps" has no config key and no rule. A per-message reprint at a smaller budget is not "the printer's selection" over one value; it is a new loop over messages. Give it one key (the step) and say that each step re-bills everything after the first shrunk message.

B. The dynamic section (requirement 3)
- **B1. The transcript is duplicated.** The dynamic section is "the agent's page" (§1, §4), and the page's centre column is the chat: prose, thinking and evals, which are the transcript. The requirement says never duplicate it. The section must be the page minus the chat column. The other way is to print chat units as handles and message ids only; then any unit whose data is an eval's value prints as `result/eN`, not only references (l.60 says it only for references).
- **B2. Tiles are rendered eval values,** so the same rule applies: in the model's section a tile is its handle. Otherwise the function card appears twice, as the printed map in the tool result and as card hiccup in the section.

C. Rendering (§3) against the requirements "agents render their own interface", "only render what is visible" and "scales to thousands"
- **C1. Nobody renders an agent's page once its process is gone,** and that is most agents (§4: "ended when their run ends"). The note says the agent renders its own page and that root never runs an agent's override. A finished agent's chat page then needs root to render it with main's renders, which makes two producers of one view (§9), or it has no page. The debug view "remade from the turn's basis" has the same problem: remaking a section built with branch overrides needs that branch's code loaded.
- **C2. A busy agent cannot render.** The agent is one loop (agent.clj `next-msg!`). During a 30-second eval, a toggle or scroll-in for its page waits behind it. §2 also says a toggle writes main "with no wake of the agent's process", while §3 has the agent render the toggled unit. Both cannot be true.
- **C3. It renders when nobody watches.** "After each eval it finds the dirty units, renders them and sends their hiccup to root" runs for every agent whether a tab is open or not. That is work and socket traffic per eval times thousands of agents, against §4 of AGENTS ("if nothing has changed, we do nothing") and the owner's "only render what is visible". It must run only while a view of that agent is subscribed.
- **C4. What crosses the socket is unspecified.** Hiccup with var heads is not EDN. The agent must send rendered HTML per unit, which is what `/feed` patches carry. Say so.
- **C5. The user is an agent too: not designed.** `:seon.view/by` is a "user agent ref", but no user agent entity exists. Messages today come `:from "owner"` as a string (context_test.clj:539ff), and the sidebar does not list the user. Say what the user's entity is (one `:seon.agent/*` entity with no process?) and how peers address it.

D. Hiccup rules (§3)
- **D1. Rule 1 conflicts with rule 4.** Any keyword-headed vector is an element, and unknown tags become an `m/explain`. But data children commonly are keyword-headed vectors: Malli forms in a function card's `:args` (`[:=> [:cat ...]]`), `[:enum ...]`, `[:ret v]` tuples. A function card would render its own contract as explain errors. Data must be marked, for example a child is data unless it sits in hiccup position. Simplest: data enters hiccup only as an argument of a call, `[card {:value v}]`, never as a bare child.
- **D2. The props-vs-child rule is ambiguous.** "Props when it validates against f's props schema" means Malli `:map` is open, so every map child with no conflicting keys validates as props, and `{}` is always props. Use f's own contract (every function has one, AGENTS §5): `m/parse` of its `:=>` input `[:cat props? [:* child]]` decides, with no second props schema. Or fix the position: always `[f props & children]`, props `nil` allowed, as Reagent does.
- **D3. Unit ids collide.** "id = digest(f's code identity, explicit props)" means two identical calls on one page (two `[agent-tile {:id "a3"}]`, or two equal summaries) get one DOM id, and Datastar's morph-by-id breaks. Also, putting code identity in the id means a redefinition changes the id, so the parent must re-render. That contradicts "a parent never re-renders for a child". Use digest(f's name, props, plus the place among siblings when repeated). Code identity should mark a unit dirty, not name it.

E. Records (§7)
- **E1. Handles and memory.** Every value stays reachable through `[:results :hN]` and an interned var in `result` (repl.clj:104-105) for the process's life, and is unbound after a restart. The note leans on handles for history shrinking and cuts, but says nothing about their cost (O(evals × value size) per agent on a 16 GB target) or what `result/e12` answers after a resume. Decide on one of these: keep live and say "unbound after restart" as an error fact when read; or rebind lazily from the record, which holds only printed text, so the value is gone anyway.

## 3. Overbuilt (a simpler way, same result)
1. **Attribute-level unit dependencies** (§3 "Units", about 130 lines, a new index and a recorder hook into `d/q`/`pull`) are the biggest new piece.
   - The note does not say how a provider's reads are recorded. Doing it needs a hook inside datahike's `q`/`pull`, or `with-redefs`, which is a §5 smell. `d/entity`, git and docs reads fall back to "any commit" anyway.
   - Most renders read through those fallbacks (challenge.md §1). So start with "any commit of the branch the view reads", plus input equality and output-bytes suppression, which exist (proc.clj:140-179). Add attribute dependencies only when a measured pass shows the re-walk costs.
2. **`{:seon.render/from some-fn}` providers** add a second way for data to reach a render. Rule 4 already lets a render call functions in its body, and caller-overridable data is plain Clojure: `(or (:x props) (query db))`. Constant defaults are Malli's `:default` with `mt/default-value-transformer` (`:default/fn` exists, malli transform.cljc:488): the library's word. Drop the provider key.
3. **`seon.view/show 'u-…` and a unit registry for the model.** A unit's cut form can be the call itself: `(card {:var 'seon.turn/observe})` is a plain REPL form the model can evaluate. No new function, no id lookup, no hash for the model to copy.
4. **The per-eval-content serialized-message cache** (§8 "model request") is a cache: allowed only for a unit measured as expensive (AGENTS §5). Measure first, and say so.
5. **Agent-side rendering for the browser** (see C1-C3). The simplest design meeting the same needs:
   - root renders every browser view from records, with main's renders: one producer, works for dead agents, never wakes an agent;
   - the agent commits after each eval, so root sees evals live (or ships the eval record on its existing `send!` events);
   - the agent renders only its own model section, where its overrides apply at once;
   - its override of the browser view takes effect when its branch merges, which is the accretion loop itself.

   It costs the owner's "agent rewrites its own interface live". Put that to the owner as a priced choice (option A: overrides live at merge, about 130 lines fewer; option B: the agent renders its page while live and watched, root renders when it is gone, which means two producers). Do not leave it implicit.

## 4. Hard in practice, glossed
- **Printing view hiccup for a model.** Tailwind class strings, `data-*` attributes and ids are most of the tokens and none of the meaning. The printer needs a print option that drops `:class`/`:style`/`data-*` for the model, and says so in the cut. That is a print opt, not a second render. Var heads print as `#'ns/f`, which is readable.
- **The view on main against the agent's basis** (fact 4). The read of main's head from the agent must be named, and the turn's basis must record main's t (the note says it will, but not how it is read).
- **Sidebar for N agents in the agent's section.** "Includes the sidebar's summaries (ids, status)" is O(N) per request and, by fact 1, eats the budget first. Use peers named in its task or messages, plus a form, `(my.agent/agents)`, that reads the rest.
- **References from thinking.** Parsing thinking for references feeds the model's musings back as cards. Each change of the visible strings changes the section's shape, so cards appear and vanish. Pinned references (recorded) are steadier; challenge.md §1 says the same.
- **Tests reaching a var.** `reach-graph` is SCC-condensed and built for digests (reuse.md §10). Reversing it per card is O(graph) unless kept per program digest, which would be a content-keyed cache and must be measured.
- **Step 0's provider check runs with thinking off** (today `:seon.config.ai/thinking :disabled`). The note claims reasoning is replayed, so test with thinking on. DeepSeek's thinking mode with a trailing user message after every tool result is untested. Its template may treat each one as a new user turn, which changes what reasoning it keeps, and the cache with it.
- **The model loses the dynamic section it saw.** When a reply was written against section N, which the next request drops, the history shows an assistant reasoning about content that is no longer there. This is acceptable, but tell the model in the system prompt that the section is replaced each request.

## 5. Owner requirements, met or not
| Requirement | Status |
|---|---|
| REPL semantics, one printer, one handle per call, spec'd result with handle and timing | Mostly met. Gaps A2 (wrap-print render), A3 (error shape, schema name), fact 3 (stable eN). |
| Function-calling only, no `;;`/`;=>`, prose as content | Structure met. A1: the tool description, help page and render/ai still emit `;;`. |
| Dynamic section: what the user sees, handles not reprints, references, no transcript duplication | **Not met** (B1, B2: the chat column and tiles duplicate the transcript). |
| Explicit state, show what the user sees, render only visible | State met (view fact). Visible-only broken for agent-side renders (C3). "What the user sees" = page in layout order, not viewport: say so to the owner. |
| Views: HTML keywords, Tailwind, `[f props? & children]`, data by schema, explicit data, spec defaults overridable, budget not fixed sizes, no single render blows context | Partly. D1, D2 ambiguities. Overridable defaults via a new provider key, where Malli `:default` would do. The budget claim is false for vectors (fact 1). |
| Default render per schema, agent overrides, agent rewrites own interface | Declared, but C1/C2 make it unworkable as written. Needs the owner's choice (§3.5). |
| Claude-app page, sidebar with summaries (root more), chat, tiles newest-first, offscreen not rendered, input prose/form, user is an agent, peers' ids, messaging | Layout met. "User is an agent" not designed (C5). Sidebar scale (fact 1, §4). |
| Function card: source, callers, perf, failing tests | Source and callers fine. Perf is O(history) and mostly empty (fact 6). Tests-reaching is new and its cost is unpriced. |
| Lightweight, scales, little code, reuse | About 520 new lines. §3 items 1, 2, 3 and 5 cut roughly 200 of them. The agent-side render path is the scaling risk (C3). |

## 6. Changes, in priority order
1. **Decide who renders the browser view** (C1-C3; §3.5). Recommended: root renders from records with main's renders; the agent commits per eval; the agent's overrides apply to its own model section now and to its browser page at merge. Otherwise B: the agent renders only while live and watched, and root renders when it is gone, with the two producers named.
2. **Define the dynamic section as the page without the chat column,** every eval-valued unit printed as its handle (B1, B2), with a print option dropping `class`/`style`/`data-*` for the model.
3. **Fix the budget rule for element children** (fact 1): a share per child in the printer's sequential fill for hiccup, or the claim dropped. Price it.
4. **Transcript items:** remove `;;` framing (A1); delete wrap-print's render path (A2); the result-map schema with `:ex` and stop-at-first-error (A3); stable `eN` = record id (fact 3); one history-shrink key (A4); fix "today: an error" and delete context.clj:345 (fact 2).
5. **Hiccup rules:** data only as call arguments (D1); props decided by f's own contract or fixed position (D2); unit id without code identity, unique among siblings (D3).
6. **Drop `:seon.render/from`** for body calls plus Malli `:default`. Drop `seon.view/show` for the call itself as the cut form. Defer attribute dependencies until measured (§3.1-3.3).
7. **Name the read of main's head** for view and sidebar (fact 4). Model the user as an agent entity (C5). Define handle lifetime after restart (E1).
8. **Function card perf:** index `:fns` as facts, or show only the producing eval's cost (fact 6). Price tests-reaching.
9. **Step 0** with thinking on, and the trailing-message reasoning behaviour checked.
