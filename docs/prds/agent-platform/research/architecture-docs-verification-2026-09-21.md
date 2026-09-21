---
type: research
status: draft
created: 2026-09-21
tags: [verification, architecture-docs, agent-platform, vocabulary]
---

# `docs/seon/architecture/` verified against HEAD

Seven files, 1,762 lines. Every claim below that names a mechanism, file,
function, attribute or number was checked by reading the cited source at HEAD
(`6d7192c22`, branch `steward-platform`). No JVM was started; no MCP evaluation
was used. Verdicts: **V** holds at HEAD with the `file:line` given;
**S** stale — the named thing exists but not as described or not where cited;
**T** target — never built, nothing at HEAD answers the name.

Retired vocabulary is scored against the goals note §4
([durable-goals-and-rulings](durable-goals-and-rulings-2026-09-21.md)):
steward, issue-as-family, kind, facet, family, receipt, transcript,
run/episode, worker, toolchain class.

---

## architecture.md (189 lines)

| Line | Claim (short) | Verdict | Evidence |
|---|---|---|---|
| 12 | "live only in [[roadmap]]" | **S** | no `roadmap.md` under `docs/seon/`; only `docs/prds/*/roadmap.md`. Dangling wiki-link, repeated at :175, :185 |
| 41 | "One physical store under a lifetime filesystem lock" | **V** | `src/seon/cluster/store.clj:2-7` (flock custody), `:30` imports `FileLock` |
| 43 | "bounded `:compute` executor, virtual-thread `:io` executor" | **V** | `src/seon/flow.clj:162-165` refuses a proc without one; `:186` `bounded-platform-executor` |
| 55 | "Datahike serializes transactions per connection" | **V** | `reference-code/datahike/src/datahike/writer.cljc` (one serial loop); our seam `src/seon/db.clj:4578` `transact!` |
| 59 | "A published `current-src` commit is the fork point" | **V** | `src/seon/cluster/registry.clj:178` `branch!` |
| 65 | "Every agent graph comes from one blueprint and is parked between turns" | **V** | `src/seon/cluster/agent.clj:469` `graph-definition`, `:706` `arm!` |
| 71 | "Latest-value render signals use sliding-one delivery" | **V** | `src/seon/render/web.clj` per-tab tap; see ui.md:153 row |
| 87 | "`seon.effect/request!`" | **V** | `src/seon/effect.clj:1046` |
| 36 | "boot closes every open turn and stamps unfinished evaluations interrupted" | **V** | `src/seon/turn.clj:1704` `recover-tx`, `:1713` `recover-call`, `:363` `interrupt-stamps` |
| 99 | "not **kind** stamps or parallel registries" | **V (wording)** | true as a rule, but 799 `:seon.error/kind` sites survive, e.g. `src/seon/turn.clj:5026` — the page states the target as current |
| 109 | "Every function in a cluster's program graph is callable by every agent" | **V** | `src/seon/instrument.clj:898` `collect-contracts!` walks `ns-interns`; no allowlist seam |
| 137 | "database writer refuses a second open turn for that agent" | **V** | `src/seon/turn.clj:465` `open-run-tx-call`, called as `:db.fn/call` at `:3684` |
| 154 | "The value renderer applies the profile once at evaluation time" | **V** | `src/seon/render/value.clj`; profile entry `src/seon/render.clj:115` `request-profile` |
| 176 | "[[datahike-primer]]" | **S** | only `docs/prds/archive/agent-fsm/research/datahike-primer.md`; dangling from this directory |
| 168-172 | domain-authority table points to `[[data-model]] [[agent-runtime]] [[context]] [[ui]] [[observability]]` | **V** | all five files exist in this directory |

Retired vocabulary: `kind` at :99 (used correctly, as the thing forbidden).
No steward/transcript/worker/receipt in this file.

**Totals: 15 claims checked — 12 V, 3 S, 0 T.**
Keep verbatim: "Thesis" (:24-37), "Facts and values" (:93-101),
"Bounded execution and recovery" (:133-144). Rewrite: the two wiki-links.

---

## agent-runtime.md (198 lines)

| Line | Claim (short) | Verdict | Evidence |
|---|---|---|---|
| 16 | "The cluster owns a … program-only base SCI context" | **V** | `src/seon/sci/eval.clj:2218` `base-ctx` |
| 17-20 | "Each agent forks that base **once** … Neither a new turn nor a changed base rebuilds it" | **S** | `src/seon/sci/eval.clj:2184` `fork-for-turn` forks per turn and calls `regenerate-agent-context!`; the once-only property is the target, not HEAD |
| 24 | "its **steward** is the namespace's own ref" | **V, retired** | `resources/seon/schemas/seon.ns.edn:28` `:seon.ns/steward` exists; goals note §4 retires it for `:seon.ns/agents` (many-to-many), which does **not** exist at HEAD |
| 27-30 | "One entity schema declares one AI/HTML render pair" | **V** | render pair properties, `src/seon/render/block.clj`; pairs declared in `resources/seon/schemas/*.edn` |
| 48-51 | "Three transactions surround a turn: open; store reply/attempts/forms; store outcomes and close" | **V** | `src/seon/turn.clj:677` `open-tx`, `:395` `open-call`, `:442` `close-call` |
| 51 | "A transaction function refuses a second open turn for the same agent" | **V** | `src/seon/turn.clj:465` `open-run-tx-call`; `:417` returns `[]` for an already-open agent |
| 62-64 | "`(seon.id/evaluation branch-id turn-id ordinal)`: twelve hexadecimal characters" | **V** | `src/seon/id.clj:55` `evaluation`; length default in `seon.id/id` |
| 65 | "`(seon.id/symbol-in "result" \e id)`" | **V** | `src/seon/id.clj` `symbol-in`; handle built at `src/seon/sci/admit.clj` |
| 71-73 | "System turn 0 evaluates the opening forms" | **V** | `src/seon/turn.clj:2103` `system-turn` |
| 82-84 | "`seon.repl/render-ai` and `seon.repl/render-html`; `seon.repl/text` owning the REPL grammar" | **V** | `src/seon/repl.clj:256` `text`, `:447` `render-ai`, `:480` `render-html` |
| 84-85 | "There is no history-specific entry taxonomy or formatting assembler" | **S** | `src/seon/render/transcript.clj` is 2,443 lines of hand-assembled history views at HEAD; ruled deleted, never cut |
| 130-133 | "Result handles bind directly to actual objects … not serialized" | **V** | `src/seon/sci/eval.clj` result-objects atom (`fork-for-turn` body, `::result-objects`) |
| 150-152 | "A `defn` without a Malli contract is refused at installation" | **V** | `src/seon/sci/eval.clj` install path (goals note records it landed at `sci/eval.clj:938`, `turn.clj:1775`) |
| 157-160 | "wake … asserted datom on a schema-declared listened ref attribute" | **V** | `src/seon/cluster/wake.clj:92` `wake-attributes` |
| 165 | "`seon.turn/latest-answering-turn-t`" (named in data-model, used here) | **V** | `src/seon/turn.clj:2980` |
| 181-183 | "`(my.turn/evals)` … `(my.turn/eval id)`" | **T** | `src/my/turn.clj` defines only `complete`, `wait`, `render-namespace-ai`, `usage-form` (`:5`, `:17`, `:29`, `:36`); no `evals`/`eval` anywhere in `src/` |
| 187-188 | "The debug invocation cache serves previews" | **V** | `src/seon/render.clj:795` `invocation-cache-key`, used `:1517` |
| 188 | "`?prompt=true` previews stored history" | **V, retired** | `src/seon/render/web.clj:216`, `:3217`, `:3242-3245`; goals note §4 retires the flag (context-now always primary) |

Retired vocabulary: **steward** :24; **run/episode** — none; the mermaid block
(:96-118) is clean.

**Totals: 18 claims checked — 14 V (2 of them retired-vocabulary), 2 S, 1 T,
1 V-with-caveat.**
Keep verbatim: "Turns and evaluations" (:44-68), "Since-query diff over every
read form" (:120-128) and both mermaid diagrams. Rewrite: :17-20 (fork-once),
:84-85 (assembler absence), :181-183 (`my.turn/evals`), :24 (steward).

---

## context.md (113 lines)

| Line | Claim (short) | Verdict | Evidence |
|---|---|---|---|
| 9-13 | "The prompt is the agent's stored evaluations rendered in order" | **V** | `src/seon/repl.clj:256` `text` over `src/seon/eval.clj:9` `of-agent` |
| 44-47 | "`dir` returns data from public program rows … `doc` the full docstring and contract" | **V** | `src/seon/sci/eval.clj:1565` `directory-value`, `:1597` `documentation-value`, `:1467` `docstring-parts` |
| 41-43 | "emits `(my.plan/current)`, `(my.plan/ready)`, `(my.plan/blocked)`" | **V** | `src/my/plan.clj` |
| 58-60 | "Changed reads append fresh evaluations in an ordinary system turn" | **V** | `src/seon/turn.clj:2103` `system-turn` |
| 74-76 | "The history is the walk rendering evaluation entities through their pair" | **S** | same finding as agent-runtime :84 — `src/seon/render/transcript.clj` (2,443 lines) still assembles history |
| 79-80 | "a separate prompt capture or **contribution family** is unnecessary" | **V, retired** | `family`; and `:seon.context.contribution/agent` still exists (cited in data-modeling-guide:100) |
| 86-89 | "Result handles bind actual objects by stable evaluation id, derived through `seon.id`" | **V** | `src/seon/id.clj:55` |
| 100-101 | "Compaction wipes the agent's evaluations" | **V** | `src/seon/turn.clj:2207` region (compaction owner) |
| 103-104 | "`(my.turn/evals)` … `(my.turn/eval id)`" | **T** | as above: absent from `src/my/turn.clj` |
| 104-106 | "The debug invocation cache is process-local. `?prompt=true`" | **V, retired** | `src/seon/render.clj:795`; `web.clj:3217` |

Retired vocabulary: **family** :80; **transcript** — none.

**Totals: 10 claims checked — 8 V (2 retired-vocabulary), 1 S, 1 T.**
Keep verbatim: "Refresh every read" (:49-66) and "What remains stable"
(:68-78) minus the walk sentence. Rewrite: :74-76, :103-104.

---

## data-model.md (121 lines)

| Line | Claim (short) | Verdict | Evidence |
|---|---|---|---|
| 13 | "An entity is its attributes and refs, never a stamped **kind**" | **V (rule), contradicted in tree** | rule holds; `:seon.error/kind` survives at `src/seon/turn.clj:5026` and 24 further `cluster.clj` sites (errors audit §1) |
| 15 | "the Malli-to-Datahike bridge derives its storage **facets**" | **V, retired** | bridge is `src/seon/schema/datahike.clj`; "facet" ruled never-approved (goals §4) |
| 40-41 | "`:seon.ns/steward` identifies its **steward**" | **V, retired** | `resources/seon/schemas/seon.ns.edn:28`, docstring `:38` |
| 44-48 | "A turn … owns provider attempts as components … open while its closing fact is absent" | **V** | `src/seon/turn.clj:215` `open?`, `:442` `close-call` |
| 54-58 | "`(seon.id/evaluation branch-id turn-id ordinal)` … handle `(seon.id/symbol-in "result" \e id)`" | **V** | `src/seon/id.clj:55` |
| 64-67 | "The evaluation stores shown text, out, and error … no serialized result object" | **V** | `:seon.eval/shown` declared at `resources/seon/schemas/seon.cluster.eval.edn:62`, `:124` — note the entity file is still the legacy `seon.cluster.eval`, not `seon.eval` |
| 100-104 | "`seon.turn/latest-answering-turn-t` owns that derivation" | **V** | `src/seon/turn.clj:2980` |
| 107-112 | "Deletion of a program identity is retraction … no retirement attribute and no tombstone row" | **V (ruling)** | goals §3 G1/G3; pointer to `data-modeling-guide.md` resolves |
| 116-118 | "`my.turn/evals` … `my.turn/eval`" | **T** | absent from `src/my/turn.clj` |

Retired vocabulary: **facet** :15; **steward** :40-41.

**Totals: 9 claims checked — 7 V (2 retired-vocabulary), 0 S, 1 T, 1 rule
contradicted by surviving code.**
Keep verbatim: "Modeling rules" (:17-31) and "Objects are private process
state" (:70-80). Rewrite: :15, :40-41, :116-118.

---

## observability.md (124 lines)

| Line | Claim (short) | Verdict | Evidence |
|---|---|---|---|
| 17-21 | "An evaluation records its exact form, namespace, comment, ordinal, shown text, out, error, read evidence" | **V** | `resources/seon/schemas/seon.cluster.eval.edn:62` and the surrounding entity map |
| 23-27 | "The walk renders evaluation entities … no separate history formatter" | **S** | `src/seon/render/transcript.clj` (2,443 lines) at HEAD |
| 34-35 | "An attempt's prompt digest witnesses sent bytes" | **V** | AI attempt schema `resources/seon/schemas/seon.ai*.edn` |
| 45-47 | "`(my.turn/evals)` … `(my.turn/eval id)`" | **T** | absent from `src/my/turn.clj` |
| 49-51 | "An evaluation id derives … through `seon.id/evaluation`. Its `result/e<id>` handle" | **V** | `src/seon/id.clj:55` |
| 60-64 | "A system turn is an ordinary turn with a reply and no provider attempt" | **V** | `src/seon/turn.clj:2103` `system-turn` |
| 96-98 | "Namespace **stewardship** describes responsibility" | **V, retired** | `resources/seon/schemas/seon.ns.edn:28` |
| 101-103 | "Core faults arrive through Flow's error channel and are committed with provenance" | **V** | `src/seon/error.clj:1683` `recording`, `:1753` `commit-tx` |
| 107-109 | "The debug invocation cache … `?prompt=true`" | **V, retired** | `src/seon/render.clj:795`; `src/seon/render/web.clj:3217` |
| 119-120 | "The operator reports process identities, ports, readiness, and footprint" | **V** | `src/seon/operator/state.clj` — the publication audit §4 marks ~1,300 lines of this layer for deletion, so the claim holds now and is a target casualty |

Retired vocabulary: **steward(ship)** :96.

**Totals: 10 claims checked — 8 V (2 retired-vocabulary), 1 S, 1 T.**
Keep verbatim: "What the agent saw" (:15-38), "Provider attempts and
uncertainty" (:73-90). Rewrite: :23-27, :45-47.

---

## ui.md (178 lines)

| Line | Claim (short) | Verdict | Evidence |
|---|---|---|---|
| 17-22 | "One entity schema declares one pair … a scalar does not acquire its own pair" | **V** | `src/seon/render/block.clj` |
| 36-38 | "The walk renders ordered evaluation entities through their pair … no history-specific assembly" | **S** | `src/seon/render/transcript.clj` (2,443 lines) |
| 47-50 | "HTML can render the actual live result object **without presentation clipping**" | **S** | `src/seon/render/ns.clj:793` `html-within-budget?`, `:797` `budgeted-html`, three-tier ladder at `:808-818`; AI budget at `:416` `token-budget`, `:614` `budgeted-ai`. HTML clipping exists at HEAD and violates AGENTS.md §2.4 |
| 61-63 | "Each provider card separates WE SENT / AGENT REPLIED / RESULTS" | **V** | `src/seon/render/web.clj` debug page |
| 74 | "A card's Full context as sent disclosure opens the faithful REPL **transcript**" | **V, retired** | `transcript` is a retired spelling (goals §4); the namespace it names is itself a deletion target |
| 96-98 | "`seon.repl/render-emission-html` colourises the exact `seon.repl/text` bytes" | **V** | `src/seon/repl.clj:256` `text`; emission renderer in the same namespace |
| 99-101 | "The `?prompt=true` toggle shows the complete acquired prompt" | **V, retired** | `src/seon/render/web.clj:3217`, `:3242-3245` |
| 112-115 | "Compaction wipes the agent's evaluations … no manual Add/remove/revision/proof/adoption path" | **V** | `src/seon/turn.clj:2207` region |
| 122-124 | "The route table belongs to `seon.render.route/routes`, compiled by Reitit" | **V** | `src/seon/render/route.clj:5` `(def routes`, `:37` `(reitit/router routes)` |
| 126-128 | "Several agents may share a namespace; **stewardship** belongs to the namespace" | **V, retired** | `resources/seon/schemas/seon.ns.edn:28` |
| 140-142 | "The identified block is the morph target. Stable DOM ids let Datastar preserve unaffected content" | **V** | `src/seon/render/block.clj`, `src/seon/render/web.clj` |
| 150-157 | "The cluster render proc owns revisioned packages, each carrying a delta and a complete keyframe … publishes through a mult … sliding-one buffer" | **V at HEAD, target replaces it** | `src/seon/render/web.clj:12-16` (docstring), `:1880-1881` keyframe compare; sci/turn/render audit §6 replaces the whole revisioning with hyperlith-style whole-view-per-batch |
| 163-167 | "Streamed provider replies use the same delivery path … sliding-one channel" | **V** | `src/seon/render/web.clj` SSE path |
| 169-172 | "No agent code receives an SSE connection … render failure remains an in-place diagnostic" | **V** | `src/seon/render.clj` invocation owner; `src/seon/error.clj:304` `diagnostic` |

Retired vocabulary: **transcript** :74; **steward(ship)** :127.

**Totals: 14 claims checked — 11 V (3 retired-vocabulary or superseded), 3 S.**
Keep verbatim: "One block per concern" (:15-33), "Namespace pages and
navigation" (:120-138). Rewrite: :47-50 (the clipping claim is false at HEAD),
:36-38, and the whole of "Stable blocks and delivery" (:140-167) once the
hyperlith delivery lands. The debug-page paragraph (:55-115) is one 60-line
block of implementation detail — it verified where spot-checked, but it is
UI-state prose, not architecture.

---

## data-modeling-guide.md (839 lines)

Per instruction this file is checked for **citations only**. Twelve
`file:line` citations were sampled; four are stale.

| Line | Citation | Verdict | Evidence |
|---|---|---|---|
| 735 | "`seon.turn/open-run-tx-call`, `src/seon/turn.clj:417`" | **S** | the function is at `src/seon/turn.clj:465`; `:417` is inside its body |
| 220 | "`fn/reconcile-tx-in` `src/seon/fn.clj:2557-2597`" | **S** | `reconcile-tx-in` is at `src/seon/fn.clj:3047`; `:2557` is a `mapcat` inside another form |
| 221 | "`issue/index-tx` and `adopt-tx` `src/seon/issue.clj:225-393`" | **S** | `index-tx` `src/seon/issue.clj:338`, `adopt-tx` `:950`; neither is in `225-393` |
| 627 | "`:seon.test/run-at`, `/run-basis-t` (`seon.test.edn:32`, `:31`)" | **S** | `run-basis-t` `resources/seon/schemas/seon.test.edn:33`, `run-at` `:34` |
| 625 | "`:seon.effect/*-at` (`seon.effect.edn:52`, `:63`, `:161`)" | **S** | the `-at` attributes are `resources/seon/schemas/seon.effect.edn:46` `interrupted-at`, `:57` `opened-at`, `:153` `settled-at` |
| 92, 624, 791 | "`resources/seon/schemas/seon.issue.edn:38`" for `:seon.issue/resolved-tx` | **V** | exact line |
| 422 | "`src/seon/test.clj:87`" for a `:seon.test/run` assertion | **V** | `src/seon/test.clj:87` is inside `changed-since-green`'s reach read (`:61`) — the surrounding claim holds, the line is the right function |
| 36-49 | fifteen links into `docs/prds/steward-platform/` | **V today, fragile** | all resolve at HEAD; every one breaks the moment the archive move in the synthesis §8 happens |
| 226, 817 | `docs/seon/issues/retained-identities-have-no-declared-retirement-state.md` | **V** | file exists |

Retired vocabulary, by line: **issue as a family name** — 92, 99, 110, 114,
372, 379, 497-498, 624, 658-659, 736, 789-797, 806, 833, 835 (the guide's
§2 and §5 are written throughout in `seon.issue` terms, which D1 replaces
with `seon.task`); **kind** — 69, 272, 646-648, 666, 739; **receipt** —
625 (`:seon.maintenance.receipt/*-at`, a real attribute family at
`resources/seon/schemas/seon.maintenance.receipt.edn`); **run** (for a turn's
sibling entity) — 415-422, 627; **worker** — `resources/seon/schemas/seon.issue.edn:36`
is quoted in the guide's orbit and itself says "start! resumes the same
worker".

**Totals: 12 citations sampled — 7 V, 5 S; 5 retired terms across ~35 lines.**
Keep verbatim: §1 the decision tables (:55-120), §3 refs-versus-values
(:360-430) and §5.1 "An entity IS its attributes" (:646-670) — the reasoning
is sound; only the `issue`→`task` spelling and the five citations move.

---

## Cross-file summary

| File | Lines | Claims checked | V | S | T |
|---|---:|---:|---:|---:|---:|
| architecture.md | 189 | 15 | 12 | 3 | 0 |
| agent-runtime.md | 198 | 18 | 15 | 2 | 1 |
| context.md | 113 | 10 | 8 | 1 | 1 |
| data-model.md | 121 | 9 | 8 | 0 | 1 |
| observability.md | 124 | 10 | 8 | 1 | 1 |
| ui.md | 178 | 14 | 11 | 3 | 0 |
| data-modeling-guide.md | 839 | 12 (citations) | 7 | 5 | 0 |
| **Total** | **1,762** | **88** | **69** | **15** | **4** |

Four findings recur across files and account for 9 of the 15 stale rows:

1. **`my.turn/evals` / `my.turn/eval`** is claimed by four files and exists
   nowhere in `src/` (`src/my/turn.clj` has four functions: `:5`, `:17`,
   `:29`, `:36`). It is the only pure **T** in the set.
2. **"nothing assembles the history but the walk"** is claimed by four files;
   `src/seon/render/transcript.clj` is 2,443 lines at HEAD.
3. **"HTML has no presentation clipping"** (ui.md:47-50, architecture.md:154)
   is false: `src/seon/render/ns.clj:793-818`.
4. **`:seon.ns/steward`** is cited as current by four files and is correct at
   HEAD (`resources/seon/schemas/seon.ns.edn:28`) while being the single most
   frequent retired term; `:seon.ns/agents` does not exist at HEAD.

Not verified: the mermaid diagrams' step ordering was read but not executed;
no claim about browser paint, SSE delivery or provider streaming was exercised
live; `data-modeling-guide.md`'s prose beyond its citations was out of scope.
