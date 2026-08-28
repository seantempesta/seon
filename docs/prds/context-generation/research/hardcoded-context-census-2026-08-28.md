---
type: research
status: complete
tags: [research, context, render, deletion]
---

# The hardcoded-context census — every authored template at HEAD

Method: every row was produced by READING the named file at
`context-generation-drive` HEAD (`ba3a4af9b`, `git diff --stat -- src/`
empty, so working tree == HEAD for production source). Scope is
`src/` only. Rows already carried by the
[deletion register](deletion-register-2026-08-14.md) are marked
**already registered** and cross-referenced by its row number rather
than re-argued; this census adds the sites that register did not cover
— principally the OPENING-FORM sites, which are the ones ruling 43
speaks to.

**Counts.** 34 sites in five categories: **7** authored prose
templates that reach the `/ai` seam (category 1); **7** hardcoded
opening/bootstrap form sites (category 2); **14** narrated-result
renderers still emitting English sentences where a printed value
belongs (category 3, of which 11 are already registered); **4** canned
header/scaffolding sites in assembly (category 4); **2** help/doc/dir
assembly sites, both largely clean (category 5). Dispositions: **24
DELETE**, **7 FOLD-INTO-INTRO**, **3 KEEP**.

**The one structural surprise.** The live `/ai` prompt at HEAD is
assembled by `seon.render.web/history-text` (`src/seon/render/web.clj:1107-1109`)
as nothing but `"\n\n"`-joined `:seon.render.history/bytes`, each of
which is `ns=> (form)\n<printed value>`
(`src/seon/render/walk.clj:885-889`). The old prose assembler
`seon.render.walk/prose` (`src/seon/render/walk.clj:606-709`) and its
appendage `seon.effect/context-suffix` (`src/seon/effect.clj:724-816`)
have **no production caller at HEAD** — `grep -rn "walk/prose" src/`
is empty; only three tests reach them. So the ASSEMBLY layer is
already clean, and the entire remaining template problem lives one
level down: in the FORMS the opening injects and in the FACES that
narrate their results.

---

## 1. Authored prose templates reaching the `/ai` seam

| # | Site | Quote | Consumed by | Replacement under the target design | Class |
|---|---|---|---|---|---|
| 1.1 | `src/seon/cluster/instruction.clj:13-30` `getting-started-text` | `"This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` …"` plus a fenced `(defn greet …)` example | `seed-rows` (59-66) → `:seon.cluster.instruction/text` fact → `instruction-ai` (68-72), the instruction family's `/ai` face; reached through the cluster's `:seon.cluster/instructions` refs | **Survives as instruction entity — but the BYTES are replaced** by the seven-sentence evergreen intro ([concrete §1](../plan/agent-context-concrete-2026-08-17.md)). The `(defn greet …)` fence, the `walk` reference (the walk no longer assembles the prompt — see the structural note above), and the `my.message/send` teaching all die: the first is scenario prose, the second is factually stale at HEAD, the third is a docstring `(doc my.message/send)` already indexes. | FOLD-INTO-INTRO |
| 1.2 | `src/seon/cluster/agent.clj:141-156` `render-situation-ai` | `"Your opening is generated from live facts. "` … `"\nEvery run ends with my.run/complete or my.run/wait; an undisposed run is unfinished work."` | The printed result of `(help)` — the FIRST thing every agent reads. Declared on `:seon.cluster.agent/agent` and on the situation value. | Help v0's four generated sections. The identity/unread/turns clauses become printed situation DATA (they are already pulled facts in `bootstrap/situation`); the two teaching sentences are evergreen rules, not facts — they belong in the one intro. The `"Injected callables: help — …"` lines (152-154) are the only part already generated (docstring first lines) and convert to §2's per-function listing. | FOLD-INTO-INTRO (teaching) + DELETE (narration) |
| 1.3 | `src/seon/bootstrap.clj:110-117` `task-message` | `"Define a durable contracted function named largest that returns the row with the greatest :example/amount…"` | Seeded as `:seon.cluster.message/content` of the bootstrap trigger message (`seed-tx`, 774-778), i.e. the agent's assignment | Scenario prose with no fact behind it. Under [concrete §4](../plan/agent-context-concrete-2026-08-17.md) the episodic goal is the root `my.plan` item (option a), authored at agent creation by whoever creates the agent — never a constant in `src/`. | DELETE |
| 1.4 | `src/seon/effect.clj:797` (inside `context-suffix`) | `";; Background work: use (my.background/await result-ref note) as the last form to wait, or retain the ref and keep working."` | `walk/prose` only — **dead on the production path at HEAD** | Delete with its caller. The teaching is `(doc my.background/await)`; the pending/result/duration lines below it (799-816) are printed data that the background-result family's face already owns. | DELETE |
| 1.5 | `src/seon/error.clj:1023-1035` `default-ai-prose` | `"Re-read the current facts before retrying or changing state."` — appended to EVERY error render with no specialist | The error family's `/ai` face (`render-ai`, 1044) — reaches an agent on every flat error | Canned advice appended regardless of the error. The evidence attributes above it are the data; the advice is either an evergreen rule (intro) or nothing. Register row **8.13** covers the surrounding prose family (already registered); this specific boilerplate sentence is not itemized there. | FOLD-INTO-INTRO |
| 1.6 | `src/seon/problems.clj:432-438` `stale-var-ai`, `448-455` `missing-model-ai` | `"Restart the JVM to remove stale loaded Var …"` / `"Add its descriptor; provider calls continue unchanged."` | The problems family's `/ai` face | Already registered (row **8.12**, `stale-var-ai`). `missing-model-ai` is the same shape and is NOT in the register — add it: attribute face over the problem entity, remediation derived or dropped. | DELETE |
| 1.7 | `src/my/run.clj:33-39` `render-namespace-ai` | `"\n\n1. complete — "` … with hardcoded fallback sentences `"Finish completed work with a reply for its requester."` | `my.run`'s namespace `/ai` face | The docstrings ARE indexed (`:seon.fn/doc`, queried at 26-32); the hand-numbered `1./2.` ordering and the two fallback sentences are the template. The generic namespace face (`render/ns.clj`) already renders exactly this from the same facts — this is a second path for one namespace. | DELETE |

---

## 2. Hardcoded opening / bootstrap forms — the ruling-43 evidence

| # | Site | Quote | Consumed by | Replacement | Class |
|---|---|---|---|---|---|
| 2.1 | `src/my/run.clj:41-83` `walkthrough` | five authored entries, e.g. `:seon.repl/comment "; My namespace is empty — this function will be its first resident."` with `:seon.repl/form '(defn largest [rows] (or (last (sort-by :example/amount rows)) {}))`, then `'(largest :not-a-row-sequence)`, a full `'(clojure.test/deftest largest-usage …)`, and `'(my.run/complete (str "Built largest: …"))` | `usage-form` (85-114), declared as `:seon.render/form` on `:my.run/namespace-unit` and `:my.run/usage-unit` (`resources/seon/schemas/my.run.edn:14,21`) → `bootstrap/direct-candidates` (202-247) → `ordered-episode` → the agent's opening episode | **The largest authored artifact in the system's context path.** Under ruling 44 the `/form` face does not exist; the opening's forms are CONSTRUCTED from contracts + call preparation. `my.run`'s teaching becomes: the ns docstring + `(doc my.run/complete)` + the intro's "End every run with `(my.run/complete "…")` or `(my.run/wait)`". Its anti-rot guard (the `:seon.test/usage` query at 100-113) proves the walkthrough is checked, not that it is generated. | DELETE + FOLD-INTO-INTRO |
| 2.2 | `src/seon/cluster/agent.clj:112-120` `situation-form` | `:seon.repl/comment "; A new run just opened. Why am I awake — do I have messages?"` · `:seon.repl/form '(help)` | Declared `:seon.render/form` on the agent family (`resources/seon/schemas/seon.cluster.agent.edn:9,97`); it is the ROOT candidate of every opening episode | The root read is the one constructible-by-contract call (`help` takes only ambient inputs); the authored inner monologue comment is pure narration and dies under ruling 45's comment ban. | DELETE (comment) + KEEP-as-constructed (the call itself, once constructed rather than quoted) |
| 2.3 | `src/seon/bootstrap.clj:196-200` `executable-namespace-entry` | `(assoc entry :seon.repl/form (list 'dir namespace-name))` | Rewrites every namespace-subject candidate's form in the opening | A literal form built in code. Under ruling 44 a namespace edge's reader is selected by contract, not hardcoded to `dir`. | DELETE |
| 2.4 | `src/seon/bootstrap.clj:342-365` `usage-demonstration-candidates` | `:seon.repl/comment "; First real use — the indexed call-edge demonstration."` · `:seon.repl/form (list 'clojure.test/test-var (list 'var (symbol test-symbol)))` | Opening candidates for a subject with a green indexed usage test | The test symbol is a fact; the CALL SHAPE and the comment are authored. Ruling 45 bans the comment; the call becomes a constructed reader. | DELETE |
| 2.5 | `src/seon/bootstrap.clj:678-741` `supervision-tx` | form SOURCE STRINGS built by concatenation: a whole 11-clause Datalog query (`read-expression`, 695-708), `(str "(my.message/send " (pr-str agent-id) " \"What are you doing?\")")` (716-717), and `(run/complete \"Read " agent-id "'s recent history and asked what it is doing.\")` | Transacted as `:seon.cluster.run.form/source` rows on root's supervision run — root then EXECUTES them | The worst instance of the class: authored Clojure as strings, including an authored English message another agent receives. Under the target design root's supervision reads are the same contract-selected calls as any other agent's, and the "What are you doing?" message is root's own authored output, not a system constant. | DELETE |
| 2.6 | `src/seon/bootstrap.clj:19-21, 87-94` `help`/`dir`/`doc` macros | `(list 'seon.bootstrap/situation)`, `(list 'clojure.repl/dir namespace-name)`, `(list 'clojure.repl/doc documented-symbol)` | The injected callables in every agent ctx | Ordinary macro expansion, not injected context. These are the definitions the opening REFERS to. | KEEP |
| 2.7 | `src/seon/bootstrap.clj:754-770` `seed-tx` namespace row | literal `:seon.ns/requires [my.run my.message seon.bootstrap]` and three literal `:seon.ns/refers` for `help`/`dir`/`doc` | Every new agent's namespace | Transaction data, not rendered context — but it is a hand-maintained roster of what a fresh agent gets. Flagged, not counted as a context template. | KEEP (flagged) |

---

## 3. Narrated results — English where a printed value belongs

| # | Site | Quote | Consumed by | Replacement | Class | Register |
|---|---|---|---|---|---|---|
| 3.1 | `src/seon/cluster/run.clj:1869-1966` `render-ai` | nine-arm `cond`: `"It was interrupted at form "` … `"It completed."` … `"It is open."` | the run family's `/ai` face, in every agent's neighborhood | derived disposition + evidence DATA face | DELETE | **8.10** |
| 3.2 | `src/seon/cluster/message.clj:437-471` `render-ai` | `"Agent X said to Y: …"` shape | message family `/ai` | attribute face | DELETE | **8.11** |
| 3.3 | `src/seon/effect.clj:60-88` `render-ai` | `"Effect … · run … returned in N ms."` | effect receipt `/ai` | inline attribute face | DELETE | **8.6** |
| 3.4 | `src/seon/config.clj:52-66` `render-ai` | `"Configuration X · manifest Y.\nModel Z (thinking …)"` | config `/ai` | inline attribute face | DELETE | **8.2** |
| 3.5 | `src/seon/cluster.clj:152-168` `render-ai` | `"Cluster N.\nConfiguration … ; K shared instructions and M toolkit namespaces."` — with hand-written English pluralization | cluster `/ai` | inline attribute face | DELETE | **8.4** |
| 3.6 | `src/seon/db.clj:1926-1945` `render-transaction-ai` | `"Committed transaction T at commit C with N datoms."` | transaction report `/ai` | data face | DELETE | **8.8** |
| 3.7 | `src/seon/error.clj:530-671` `notice-ai-prose` / `ai-prose` | the four-question steering paragraph | error notices, four consumers | evidence-derived data faces | DELETE | **8.13** |
| 3.8 | `src/seon/render/value.clj:541-547` `render-ai-data` | `" ; elided — this value is larger than the configured window"` | the floor's `/ai` | the one elision value | DELETE | **5.6** |
| 3.9 | `src/seon/render/walk.clj:585-590` | `"Renderer unavailable."` / `[:div {:class "seon-render-unavailable"} "renderer unavailable"]` | every failed member render in the walk | error card family | DELETE | **6.2** |
| 3.10 | `src/seon/render/web.clj:271-272, 383` and siblings | the same `renderer unavailable` placeholders | the page | error card family | DELETE | **7.8** |
| 3.11 | `src/seon/test/accretion.clj:325-330` | `"N more in the complete gate-report blob."` | install-refusal `/ai` | elision value | DELETE | **9.5** |
| 3.12 | `src/seon/render/agent.clj:16-33` `agent-ai` | `" is running now."` / `" is idle."` | the agent family default `/ai` — reached whenever an agent is a neighbour | presence IS the state; print the `/run` ref or nothing. Not in the register. | DELETE | — |
| 3.13 | `src/my/note.clj:60-67` `render-notes-ai` | `"Current notes (N):\n"` + `"- "` bullets + `"No current notes."` | `my.note` collection `/ai` | printed collection + elision value; empty renders nothing, never a sentence asserting emptiness | DELETE | — |
| 3.14 | `src/seon/oversight.clj:228-250` `ai-story` | `"No agent graphs are armed."` · `"X: parked"` · `", second run this episode"` (English ordinals) | the oversight face | printed data rows | DELETE | — |

`src/my/plan.clj:881-885` (`"Plan item " …`), `src/seon/cluster/agent.clj:158-165`
(`"Agent X · namespace Y · cluster Z · bootstrap run R."`), and
`src/seon/print.cljc:283-302` (`render-elision-ai`) are labeled-data faces —
a prefix word plus printed facts. They are the mild end of the same class and
convert to attribute faces without a separate argument. `src/seon/context.clj:108-116`
`capture-ai` returning `nil` is CORRECT and is the model the others should follow.

---

## 4. Canned headers, labels, and scaffolding in assembly

| # | Site | Quote | Consumed by | Replacement | Class |
|---|---|---|---|---|---|
| 4.1 | `src/seon/render/walk.clj:670-673` | `(str ";; (seon.render/walk " (pr-str options) ")" " => root=" … " depth=" …)` — the assembly header | `prose` only — **dead at HEAD** | delete with `prose` | DELETE |
| 4.2 | `src/seon/render/walk.clj:658, 661, 689-694, 700` | `";; d0 · [...]"` provenance comments, `";; Some branches are elided · inspect with (seon.render/walk …)"` guidance, `";; branches-elided=N elided-tokens=K"`, and the literal divider `";; Volatile context metadata"` | `prose` only — dead at HEAD | delete with `prose`; the elision fact is the one elision value | DELETE |
| 4.3 | `src/seon/render/walk.clj:596-597` `marker` suppressed for HTML only | the distance-cap unit added for `/ai` and not `/html` | live | one elision, both faces — the absence-as-health class | DELETE (register **6.3**) |
| 4.4 | `src/seon/render/web.clj:1099-1109` `history-segments`/`history-text` + `src/seon/render/walk.clj:885-889` | `(str (or namespace-name 'user) "=> " (pr-str form) "\n" printed-value)` and the `"\n\n"` join | **the live prompt bytes** | This is the only scaffolding on the live `/ai` path and it is the REPL's own convention over derived facts (the ns name is pulled, the form and value are receipted). Keep — but note the `'user` fallback is an invented default where a typed refusal belongs. | KEEP (flag the fallback) |

Register rows **9.9** (`acquire-within-budget`'s distance-decrement loop) and
**9.10** (the prompt-tail reminder string) both named `src/seon/cluster/prompt.clj`.
At HEAD **9.10 is GONE** — `prompt.clj` appends no reminder text; it produces
only `{text, contributions, db}` from the acquired walk. **9.9 survives** at
`prompt.clj:182-232`, unchanged in kind: whole branches vanish at each
`(dec distance)` with no elision value emitted.

---

## 5. `help` / `doc` / `dir` — generated versus authored

| # | Site | What it assembles from | Verdict |
|---|---|---|---|
| 5.1 | `src/seon/sci/eval.clj:1104-1153` `program-doc-var` / `program-dir-var` | Indexed program-graph rows only: `:seon.fn/sym`, `:seon.fn/doc`, `:seon.fn/arglists`, `:seon.fn/private?`, and derived arity contract lines (query at 1095-1102). Both return the queried data as the value and print the same facts. | **Generated — clean.** The one authored byte is the `"-------------------------"` divider at 1116, `clojure.repl`'s own convention. KEEP. |
| 5.2 | `src/seon/bootstrap.clj:23-84` `situation` | Pure Datalog over live facts: namespace ref, unread-message count (a `not-join`, correctly counting absence of a claiming run), turns remaining, protocol namespaces, open run, trigger. Stores nothing. | **Generated — clean.** KEEP. The template is not here; it is in the FACE that renders this value (row 1.2). |

The split is exactly where the target design wants it: the situation VALUE
is derived and the situation NARRATION is authored. Deleting row 1.2's prose
and printing the situation value is a small, self-contained change.

---

## Ruling-43 verification

**Ruling 43's claim, "NO hardcoded bootstrap forms exist anywhere," is
REFUTED at HEAD.** Seven sites author forms in code that reach an agent's
opening episode; three of them are unambiguous:

1. **`src/my/run.clj:41-83`** — `walkthrough` returns five `{:seon.repl/comment
   :seon.repl/form}` entries whose forms are quoted literals (`'(defn largest
   …)`, `'(largest :not-a-row-sequence)`, `'(clojure.test/deftest largest-usage
   …)`, `'(my.run/complete (str "Built largest: …"))`) and whose comments are
   authored English. `usage-form` (85-114) prepends `'(dir 'my.run)` and is
   registered as the `:seon.render/form` face for `my.run` in
   `resources/seon/schemas/my.run.edn:14,21`, so `seon.bootstrap/direct-candidates`
   (202-247) admits it as opening candidates and `walk/ordered-episode`
   (`src/seon/render/walk.clj:762-815`) orders it into the episode.
2. **`src/seon/cluster/agent.clj:112-120`** — `situation-form` returns the
   literal `'(help)` with an authored inner-monologue comment, registered on
   the agent family in `resources/seon/schemas/seon.cluster.agent.edn:9,97`.
   It is the ROOT candidate of every opening (`bootstrap/root-candidate`,
   521-535), so every agent's first form is a quoted constant.
3. **`src/seon/bootstrap.clj:678-741`** — `supervision-tx` builds executable
   Clojure as concatenated STRINGS, including an 11-clause Datalog query and
   `(my.message/send <id> "What are you doing?")`, and transacts them as
   `:seon.cluster.run.form/source` rows that root then executes.

Two further sites hardcode the form shape from inside otherwise
fact-driven code: `bootstrap.clj:199` (`(list 'dir namespace-name)`) and
`bootstrap.clj:350-354` (`(clojure.test/test-var (var …))` with an authored
comment). `bootstrap.clj:110-117`'s `task-message` is authored assignment
prose seeded as the trigger message.

What ruling 43 IS right about, and worth recording precisely: the
prompt-ASSEMBLY layer has no authored scaffolding left. The live `/ai` bytes
are `history-text` over `:seon.render.history/bytes` — form, newline,
printed value, joined by blank lines — and the older prose assembler
(`walk/prose`) with its `;;` headers, guidance sentence, and volatile-metadata
divider is reachable only from tests. The remaining hardcoding moved down a
level, from how the context is joined to what the opening executes.
