---
type: prd
status: draft
tags: [prd, agent]
---

# Context v3 — code-first (2026-06-10)

User direction (verbatim intent, 2026-06-10 evening): hide internals and
plumbing by default; show FULL CODE for all agent-relevant namespaces,
including the full datahike API (querying is core); **remove all the hacky
context that isn't really helping in favor of clear code with comments and
docs and normal ways of expressing ideas via Clojure code.**

## The rule (replaces tiers, sections-as-prose, and six filters)

A namespace is either **relevant → full source rendered in context** or
**internal → not rendered** (still indexed; one taught query away). One
config set, one full-index query, one classifier, one renderer. The
teaching itself becomes CODE: docstrings on the real fns, not handcrafted
prompt prose.

**The namespace map (user-confirmed 2026-06-10):** root `seon.*` = the
language layer only (`seon.db` `seon.schema` `seon.repl`); the agent's
TOOLBELT = `seon.agent.*` (`seon.agent` itself = reply!/message!, SHOWN;
`seon.agent.todo`/`.fs`/`.search` = tools, SHOWN; `seon.agent.internal` =
loop machinery, HIDDEN by the standard `*.internal` rule); agent code =
`my.*`; knowledge = `my.kb.*` sub-namespaces with real schemas. The
sentence agents learn: "your code is `my.*`, your knowledge is `my.kb.*`,
your tools are `seon.agent.*`, the language is `seon.db`/`seon.schema`/
`seon.repl`."

## What renders (the relevant set)

| Source | chars | Notes |
|---|---:|---|
| `seon.db` (post-split API) | ~12k est | transact!/query/pull/entity/with-agent + envelope schemas |
| `seon.schema` (post-split API) | ~8k est | register! + the rules, as docstrings |
| datahike API surface | ~8–33k | from var metadata / `api.specification` — unit measures + trims |
| `seon.agent.fs` (rename of seon.fs) | 18.1k | nodejs integration exemplar |
| `seon.agent.search` (rename) + test | 26.6k | npm wrapper + the model test ns |
| `seon.agent.todo` (rename) + test | 18.5k | store/retrieve arc + resume |
| `seon.agent` (post-split, future) | — | the agent's surface: reply!/message!; renders once the loop machinery splits to the HIDDEN `seon.agent.internal` (150k today — unshowable until split) |
| `seon.repl` | 5.3k | |
| `my.kb` (new) | ~3k target | substrate-scaffolded knowledge base ns: general guidelines as ns-doc/docstrings + shared provenance shapes (`:my.kb/*`). SUPERSEDES the earlier `seon.recipes` idea (user, 2026-06-10: NO recipes ns — real namespaces doing real work; remaining teaching → docstrings of the public faces) |

Dynamic derived blocks stay (they are data, not prose): domain attrs,
stored finding claims, open todos, warnings, transcript, prompt line.
Prose that survives: SOUL/system identity only.

## Units

- **V3-A — `seon.db` API split** (precedes everything): public surface
  stays in `seon.db`; DIS/wire/conn plumbing moves to `seon.db.internal`,
  which the public ns requires. **Convention (user, 2026-06-10): complex
  namespaces keep a clear public face + a `*.internal` sub-namespace for
  plumbing; `*.internal` is never rendered to agents — the ns name IS the
  filter.** Atomic refactor, suite + replica probes + live boot as oracles.
  `seon.schema` split follows once the S-21 lane frees the file.
- **V3-B — `my.kb` scaffold** (CORRECTED 2026-06-10 — supersedes the
  earlier `seon.recipes` AND `seon.kb` ideas; user decision, PRD §10
  "Tonight's USER DECISIONS", commit 04556da): kb = SCHEMA'D DATA NOT
  TEXT. A substrate-scaffolded `my.kb` base ns carrying (a) general
  guidelines as ns-doc/docstrings — agents create `my.kb.<domain>`
  sub-namespaces with REAL schemas per knowledge kind; explicitly NOT a
  general memory-markdown structure; large-text storage allowed when the
  user wants it, never the default — and (b) the SHARED provenance
  shapes registered once (`:my.kb/source-path` `:my.kb/source-line`
  `:my.kb/verified-at` `:my.kb/confidence`). NO `store!`/`consult` fns,
  NO RAG. Consult = catalog + datalog (salience surface already built).
  Remaining capabilities teaching moves into docstrings of the public
  faces — NO recipes namespace.
- **V3-C — context-model unification, HOMED IN `seon.ctx` (user,
  2026-06-10: consolidate the context-generation namespaces under one
  roof; `seon.ctx` over `seon.context` — the `:seon.ctx/*` keywords
  already name exactly this)**: ONE full-index query → ONE classifier
  (relevant-set config + agent-authored detection derived at render
  time, no stamping) → dumb renderers, all moving OUT of agent.cljs
  into `seon.ctx` (+ `seon.ctx.internal` if it grows plumbing).
  Deletes: internal-attr-ns? regex (S-21's provenance query landed
  fe2b026 — the FIRST of the six filters converted, validating the
  provenance mechanism), substrate-ns-name? heuristic, per-section
  queries/gating, count lines, signature blocks, entity-kind inference.

### Mechanical method for the reorg (user, 2026-06-10 — standard for all moves)

Never rebuild from scratch; git is the safety net. Two recipes:

1. **Namespace move/rename** (proven on seon.agent.todo, ~10 min incl.
   fallout): `mv` the files → one `perl -pi 's/old\.ns/new.ns/g'` sweep
   over the explicit affected-file list (catches ns forms, keywords,
   requires, strings, error messages in one pass; regex LITERALS with
   escaped dots need a second targeted sweep) → full suite → fix the
   2-3 semantic fallouts the suite names (sort orders, assertions) →
   one atomic commit.
2. **Face/internal split**: `cp x.cljs x/internal.cljs` → DELETE the
   public face's fns from internal and the plumbing from the face (two
   subtractive passes over intact, working code — never re-authoring)
   → face requires internal → re-point external callers of moved
   private vars → suite → commit. Future splits (schema.cljc,
   agent.cljs) use this copy-then-delete route.
- **V3-D — datahike API block**: render the query API from var metadata
  (docstrings live on the vars; code-as-data, no dep-file reads). Budget
  guard; trim to the querying surface (q, pull, entity, datoms, history…).
- **V3-E — SHOW DON'T TELL: sections → demonstrated evals + instruction
  entities (user, 2026-06-10 late — reshapes the old "delete the prose"
  unit):** after the static corpus, turn-0's transcript OPENS with a
  small fixed list (4–6) of substrate-authored evals — real forms,
  REALLY EXECUTED at render time against the live store (never
  templated results) — demonstrating: `(seon.agent.todo/list-open {})`
  (replaces the todos section), a `:seon.schema` catalog query
  (replaces the passive domain-attrs wall), and a query over
  **`my.kb.instruction`** — instructions are DATA in the FIRST WORKED
  `my.kb` DOMAIN (real schema: `:my.kb.instruction/text`,
  `applies-when`, `priority`, + the shared `:my.kb/*` provenance
  attrs), seeded by the substrate, runtime-editable by user AND agents
  via transact (resurrects the orphaned sticky-preamble mechanism as
  data). Pulling your instructions IS the my.kb consult demo — the
  scaffold ships with a living example domain, not comments describing
  one (user correction 2026-06-10: NOT a seon.instruction ns —
  instructions are my.kb content). The agent wakes mid-session in a REPL where the
  orientation queries already ran — imitation over obedience. Dynamic
  demos live AFTER the byte-stable prefix (transcript zone). Convert
  ONE section per unit, gym-scorecard each (does S-32 consult-rate move
  when consult is demonstrated instead of told?). The agreement
  property test (all surfaces classify identically) still lands here.

Budget: turn-0 ≈ 105–130k chars ≈ 27–33k tokens, byte-stable prefix.
User: token cost is acceptable; correctness of the lesson > size.

## Unit specs (2026-06-10, launch-ready — anchors verified against the live tree + store)

Anchoring rule: `src/seon/db.cljs`, `src/seon/db/internal.cljs`,
`src/seon/warn.cljs`, `src/seon/schema.cljc`, `src/seon/client.cljs`,
`test/seon/agent_context_test.cljs`, `test/seon/gym/driver{,_test}.cljs`,
`test/seon/gym/scenarios/s21-log-workout-existing-schema.edn`, and
`test/seon/warn_test.cljs` carry UNCOMMITTED in-flight edits (V3-A split,
S-21 fixes, todo wiring). Anchors below are HEAD (`git show HEAD:<file>`)
unless marked `[tree]`; line numbers in clean files (`agent.cljs`,
`render.cljs`, `eval.cljs`, `todo.cljs`, `bin/seon`, `wire.cljs`) are
current working tree (= HEAD for those files, except `todo.cljs` which is
new/uncommitted).

Live-store evidence captured 2026-06-10 (read-only CLJS REPL against the
pod's `@seon.client/!agent-conn`, cluster store `data/clusters/default`):

```clojure
;; tx-meta attrs on tx entities ([?tx :db/txInstant _][?tx ?a _]):
[[:seon.db/request-id 16] [:db/txInstant 22]
 [:seon.db/origin 6]      [:seon.db/agent-id 10]]
;; all :seon.db/origin values: [[:substrate-seed 6]]
;; agent-stamped txs: [["DVp-2606101224" 5] ["huj-2606101309" 5]]
;; (the 6 substrate-seed txs are AMONG the 10 agent-stamped ones — the
;;  boot seed runs inside the booting agent's with-agent scope)
;; :seon.ns/name rows: 64, ALL substrate (incl. seon.todo) — ZERO
;;   seon.agent.<id> home-ns rows; :seon.eval/ns rows: ZERO
;; d/datoms on an attr absent from the installed datahike schema THROWS:
;;   "Bad entity attribute :never.installed/attr at (resolve-datom …),
;;    not defined in current schema"   (datalog `q` is safe — returns 0)
;; :seon.schema rows carrying :seon.schema/id-attr (18) include EIGHT
;;   request/response envelopes — the derive-entity-id-attr over-match:
;;   :seon.todo/write-response :seon.todo/reopen-request
;;   :seon.todo/complete-request :seon.agent/render-namespace-request
;;   :seon.handler/input :seon.inspect/request
;;   :seon.render/assemble-request :seon.effect/wake-request

```

### Unit 1 — home-ns rename `seon.agent.<id>` → `my.agent.<id>`

Goal: agent HOME namespaces mint as `my.agent.<id>` (convention only —
NO write-block machinery; user decision c60e334). `seon.*` stays
substrate-only by convention.

Durable-store compat — VERIFIED, decision: **pure rename, no
migration**. The home ns is recomputed, never stored
(`src/seon/agent.cljs:434-438` comment: "deterministic function of the
agent's id — no need to store it on the entity"), and the live store
holds ZERO `seon.agent.*` `:seon.ns` rows and ZERO `:seon.eval/ns`
datoms (evidence above) — the two existing agent entities (`DVp-…`,
`huj-…`) never wrote home-ns corpus. Nothing to migrate; if stale
old-prefix rows ever appear they classify as ordinary agent corpus.

Every site (grep `seon\.agent\.` minus the unrelated JVM
`:seon.agent.run/*` schema attrs in `src/seon/runtime.clj` — do NOT
touch those):

- `src/seon/agent.cljs:440-444` — `home-ns`, THE mint:
  `(symbol (str "seon.agent." agent-id))` + docstring example at 442.
- `src/seon/agent.cljs:466-474` — `per-agent-shape?` second literal
  mint: `(str "seon.agent." agent-id)`.
- `src/seon/agent.cljs:482` — `ai-render-input` third literal mint:
  `(keyword (str "seon.agent." agent-id) "ctx")`.
- `src/seon/agent.cljs:2572-2582` — `substrate-ns-name?`: the
  `(not (str/starts-with? ns-str "seon.agent."))` exclusion clause
  becomes DEAD (my.agent.* never matches `seon.`) — delete the clause,
  keep `(str/starts-with? ns-str "seon.")`; update docstring 2574-2578.
- `src/seon/ai/deepseek.cljs:247` — system-prompt text
  "Your home-ns (seon.agent.<your-id>) is scratch" — FUNCTIONAL prompt
  text, must change.
- `pod-host/wasm-tauri/mcp-server-seon/src/server.rs:56,92,95` — the
  Rust MCP server's default eval ns:
  `format!("seon.agent.{}", p.agent_id)` at :95 — FUNCTIONAL; needs
  `my.agent.{}` + cargo rebuild of mcp-server-seon.
- Doc-comment-only sweeps (same patch, zero behavior):
  `agent.cljs:7,2574,2939`, `eval.cljs:531,1198`,
  `client.cljs:580,712,897` `[tree — todo hunks elsewhere in file]`.
- Tests asserting the old prefix (fixture nses move to `my.agent.*` so
  they keep modeling AGENT corpus vs the renamed classifier):
  `test/seon/agent_context_test.cljs:65,105,115-123,196,326,645-647`
  (`:seon.agent.ctxtest`) `[tree — file carries in-flight S-21 edits;
  coordinate or land after]`;
  `test/seon/resume_replay_test.cljs:7,53-78,114-116,156,164`
  (`:seon.agent.t1`);
  `test/seon/repl_parity_test.cljs:24,30,37-39,45` (`'seon.agent.x`).

NOT prefix-coupled (verified, no edits): replay scoping
(`client.cljs:888-907` `substrate-ns-set` is var-meta-derived, name-
free); gym driver (`test/seon/gym/driver.cljs:735` calls
`agent/home-ns`); `client.cljs:1376` (calls `agent/home-ns`);
inspector + agent_view (zero `seon.agent.` literals — display derives
from data); prompt line (`agent.cljs:2769-2789` renders `current-ns`,
which falls back to `(home-ns id)` at `agent.cljs:1386-1401`).

Files (7): `src/seon/agent.cljs`, `src/seon/ai/deepseek.cljs`,
`pod-host/wasm-tauri/mcp-server-seon/src/server.rs`,
`test/seon/agent_context_test.cljs`, `test/seon/resume_replay_test.cljs`,
`test/seon/repl_parity_test.cljs`, plus the comment-only sweep in
`src/seon/eval.cljs` + `src/seon/client.cljs` (fold into the same
commit; trivial).

Live proofs: (1) boot a fresh agent, inspector prompt line ends
`my.agent.<id>=>`; (2) MCP `eval` with `agent_id` set and no `ns`
lands in `my.agent.<id>` (after cargo rebuild); (3) agent defines a fn,
`:seon.fn/ns` row names `my.agent.<id>`; (4) pod restart → replay
n-ok>0 and substrate rows still skipped. Gym oracle: S-01 stub smoke
(free tier) green.

### Unit 2 — todo wiring + exemplar swap + visible-entities hardening

**STATUS: SHIPPED 2026-06-10 (uncommitted).** Roots = `#{"seon.search"
"seon.todo"}` (one def, both sites); `seon.todo/open-todos-section`
wired at priority 45 (`:open-todos`, between warnings and transcript);
`renderable-kinds` gated on `:seon.schema/render-fn` + installed-schema
filter before the `d/datoms` scan in `renderable-entities` (belt +
suspenders; `catalog-kind-count` verified safe, unchanged). Tests
updated: `agent_context_test` (section order, exemplar set, catalog
cross-ref) + `index_substrate_test` (fs back to stub, todo full).
Live proofs on the fresh pod boot (NLc-2606101409): todo `:seon.ns/source`
= 8731 chars full text, todo-test 10266, fs stub; turn-0 = 63,027 chars
(≤65k); add!→section renders→complete!→section gone; synthesized
uninstalled id-attr+render-fn row — `d/datoms` throws, `visible-entities`
survives; inspector pages 200. bin/test-cljs 330/1277/0 exit 0. NOT done
here: `todo-resume.edn` gym scenario (gym tree fenced to the S-21
verifier lane at ship time — encode it when the lane frees).

Goal: commit the parked `seon.todo` exemplar, swap it into the exemplar
roots (fs→todo), surface open todos as a context section, and stop the
renderer crashing on registered-but-uninstalled id-attrs.

Commit plan (explicit-path): `src/seon/todo.cljs` (new, 213 lines,
suite-green, reviewed) + `test/seon/todo_test.cljs` (new) + the TWO
client.cljs hunks already in tree `[tree]`: the `[seon.todo]` require
(~line 121) and the `:seon.todo/*` boot-install attrs (~lines 344-355 —
"installed at boot so a RESUMING agent can list-open before any todo tx
has lazily installed the attrs").

Exemplar swap: `src/seon/agent.cljs:1827`
`(def exemplar-roots #{"seon.fs" "seon.search"})` →
`#{"seon.search" "seon.todo"}`. ONE def verified to drive BOTH sites:
the boot indexer (`client.cljs:979` `ns-row` → `agent/exemplar-ns?`,
full file text into `:seon.ns/source`; test sibling resolves via
`ns-file-path`/`read-src-file` probing the `test` root,
`client.cljs:909-921,948-958`) and the renderer
(`agent.cljs:1888` `exemplars-section` filter) — both go through
`exemplar-ns?` (`agent.cljs:1838-1852`), no drift possible. Update the
`exemplar-roots` docstring rationale (1819-1826) — `seon.todo` is the
store/retrieve + resume arc; `seon.fs` returns at full depth in V3-D/E.
Takes effect on pod restart (boot indexer re-reads files; rows upsert).

Visible-entities hardening (live-proven crash): `render.cljs:337-342`
(`renderable-entities` phase 1) calls `(d/datoms db :aevt id-attr)` for
EVERY `:seon.schema` row carrying `:seon.schema/id-attr` — and
`d/datoms` THROWS "Bad entity attribute … not defined in current
schema" for any id-attr not yet installed on the conn (evidence above).
`renderable-kinds` (`render.cljs:207-241`) reads kinds from
`:seon.schema` rows with NO render-fn gate, and the over-match (eight
request/response schemas carry id-attr — evidence above) widens the
exposure. Fix BOTH cheaply here: (a) gate `renderable-kinds`' base
query on `[?s :seon.schema/render-fn ?ai]` — rows without a renderer
are already dropped post-pull at `render.cljs:377` `:when ai-sym`, so
this only moves the existing gate before the crash point, matching
`schema-catalog-section`'s gate (`agent.cljs:2168-2176`); (b) belt +
suspenders: skip id-attrs absent from `(:schema db)` (FilteredDB-safe
read, same guard as `agent.cljs:2027-2039 db-schema`) before the
`d/datoms` scan. `catalog-kind-count` (`agent.cljs:1981-1986`) uses
datalog `q` — VERIFIED SAFE (returns 0, doesn't throw); no change. The
registry-side root cause (derive-entity-id-attr over-match) is unit 4's.

Open-todos context section: `todo.cljs:197-213` `open-todos-block`
already exists (`[db owner]` `:catn`, returns `""` when none — derived,
self-healing). Add a thin section fn `seon.todo/open-todos-section`
(map-in `{:seon.db/db … :seon.agent/id …}` → string, resolving owner
ref `[:seon.agent/id id]`) and ONE entry in `substrate-default-ctx`
(`agent.cljs:2954-2972`): `{:seon.ctx/name :open-todos
:seon.ctx/priority 45 :seon.ctx/fn 'seon.todo/open-todos-section}` —
between `:warnings` (40) and `:transcript` (50): dynamic, derived,
vanishes when empty, correctly outside the static cache prefix.

Gym todo-resume scenario sketch (new
`test/seon/gym/scenarios/todo-resume.edn`, status `:todo` until the
S-06 restart machinery lands — the driver can't restart a pod
mid-scenario yet): turn 1 user asks "note this for later: <item>";
fixture-free; restart pod on the SAME store; turn 2 fresh wake "where
were we?". Predicates: `:first-eval-matches` pattern
`seon\.todo/list-open|:seon\.todo` (axis `:consults-findings`-style
resume check) + `:datalog` `[:find ?t :where [?t :seon.todo/status
:open]]` non-empty after turn 1 + judge grades the turn-2 reply names
the open item. Pattern model: `s32-consult-before-research.edn`.

Files (≤7): `src/seon/client.cljs` `[tree hunks]`, `src/seon/todo.cljs`,
`test/seon/todo_test.cljs`, `src/seon/agent.cljs`,
`src/seon/render.cljs`, `test/seon/gym/scenarios/todo-resume.edn`.

Live proofs: (1) fresh-boot agent's ctx-preview shows the exemplars
section containing `<exemplar ns="seon.todo">` with REAL file text (not
the stub) and NO `seon.fs` exemplar; (2) `(seon.todo/add! …)` then pod
restart then `(seon.todo/list-open {})` returns the item; (3) open-todo
present → `:open-todos` section renders; complete it → section gone
next render; (4) inspector renders on a store where `:seon.todo/*` was
never transacted (the hardening proof — today that THROWS). Gym oracle:
S-21 re-run green (catalog unaffected) + the todo-resume scenario
encoded (`:todo` status).

### Unit 3 — V3-B `my.kb` scaffold (corrected design)

Goal: substrate-scaffolded `my.kb` base namespace; kill the generic
`:kb.finding/*` taught shape. NO store!/consult fns, NO RAG, NO
text-claim default.

New file `src/my/kb.cljs` (`ns my.kb` — keyword ns = real code ns,
CLAUDE.md rule). Contents, in full:

- ns-doc (the general guidelines every user gets, verbatim spirit of
  04556da): knowledge is SCHEMA'D DATA — create `my.kb.<domain>`
  sub-namespaces with REAL schemas per knowledge kind
  (`my.kb.codebase.fn/*`, `my.kb.paper/*`, …) — same skill as user-data
  modeling; do NOT build a general memory-markdown structure; storing
  large text is allowed when the user wants it, never the default;
  reference the shared `:my.kb/*` provenance attrs from your domain
  schemas instead of re-inventing source-path/line/confidence per
  domain; consult = schema-catalog + datalog, FIRST, before research.
- four `register!` calls (the shared provenance shapes, registered
  once): `(schema/register! ::source-path :string)`
  `(schema/register! ::source-line :int)`
  `(schema/register! ::verified-at :inst)`
  `(schema/register! ::confidence [:enum :verified :inferred])`.
- NOTHING else. No fns. (`seon.kb` as a namespace is DEAD.)

Seeding + the agent-provenance render rule — DECISION: **the name rule
dominates; `my.*` renders regardless of tx provenance.** `my.kb` is
compiled substrate (require it in `client.cljs`'s boot require block,
~line 118 next to `[seon.todo]` `[tree]`), so the boot indexer seeds
its `:seon.ns`/`:seon.fn`/`:seon.schema` rows inside the
`:substrate-seed` tx like every substrate ns, and `substrate-ns-set`
correctly marks it replay-skipped. Justification: provenance
(agent-stamped vs `:substrate-seed`, evidence above) exists to
disambiguate `seon.*` (substrate plumbing vs agent-squatted); `my.*`
is BY DEFINITION the human's world and always-relevant — one name rule,
no special case, and the scaffold teaches by being visible to every
agent. Unit 4's classifier encodes exactly this. Also boot-install the
four `:my.kb/*` attrs in `client.cljs`'s install list (same rationale
as `:seon.todo/*` — domain schemas reference them before any kb tx
lands).

Capabilities prose it replaces (`src/seon/agent.cljs`, the
`capabilities-section` body, fn at 1600):

- `agent.cljs:1713-1731` — recipe "step 0" consult query hardcoding
  `:kb.finding/question|claim|source-path|line`. Rewrite: consult the
  schema-catalog's `my.kb.*`/`my.*` attrs and datalog those exact
  keywords (no hardcoded shape — the catalog IS the index).
- `agent.cljs:1743-1766` — "### Storing what you learn — the canonical
  finding shape" block: five `:kb.finding/*` register! lines + the
  worked transact. Rewrite: design a `my.kb.<domain>` schema for the
  knowledge kind at hand, referencing `:my.kb/source-path`
  `:my.kb/source-line` `:my.kb/verified-at` `:my.kb/confidence`; one
  worked example (e.g. `my.kb.codebase.fn/*` with required name +
  claim-like fact + the provenance refs). Keep STORE-PROACTIVELY and
  the reuse-the-catalog imperative.
- `agent.cljs:2083-2113` `finding-claims-block` (salience, #26):
  matches any domain attr NAMED `claim` — docstring at 2087 names
  `:kb.finding/claim` as the taught shape. Update the docstring; the
  name-based match stays for now (my.kb domains that store claim-like
  facts keep salience); generalizing salience to all `my.kb.*` string
  attrs is V3-E follow-up, not this unit.

Teaching migration from `:kb.finding/*`: the store was wiped 16:24 —
live evidence confirms ZERO `:kb.finding` schema/data rows. No data
migration; this is purely a teaching + gym change.

Gym predicate updates (S-12 + S-32 — "consults relevant
my.kb.*/my.* attrs first"):

- `test/seon/gym/scenarios/s32-consult-before-research.edn` —
  `:seon.gym.scenario/schema-registrations` (five `:kb.finding/*`
  entries) → a `my.kb.codebase/*` domain shape referencing `:my.kb/*`
  provenance attrs (fixtures rewritten to match); predicate
  `:first-eval-consults-stored-findings` pattern `"kb\\.finding"` →
  `"my\\.kb\\.|my\\."`; doc string updated.
- `test/seon/gym/scenarios/consults-findings-run8.edn` (S-12) — same
  registration/fixture/pattern migration.
- `test/seon/gym/driver.cljs` `[tree — in-flight S-21 edits; land
  after that lane commits]` — fixture install path is
  registration-driven, expect no code change; verify only.

Files (≤7): `src/my/kb.cljs` (new), `src/seon/client.cljs` `[tree]`,
`src/seon/agent.cljs`, the two scenario edns, plus (optional) a small
`test/my/kb_test.cljs` asserting the four registrations + ns-doc
presence.

Live proofs: (1) fresh boot → `:seon.ns/name :my.kb` row exists with
full source (once unit 4 renders my.* in full; until then it appears in
the catalogs); (2) schema-catalog shows the four `:my.kb/*` attrs;
(3) capabilities section contains zero `kb.finding` occurrences;
(4) an agent registering `my.kb.<domain>/*` attrs sees them in
domain-attrs next render (warn.cljs provenance path, in-flight S-21
fix). Gym oracle: paid S-12 + S-32 re-run GREEN under the new
predicates (the pass bar: first eval queries `my.kb.*`/`my.*` attrs,
at-most-one repo search on S-32).

### Unit 4 — V3-C one-query classifier

Goal: ONE full-index query → ONE classifier → dumb renderers. Delete
the six scattered name filters.

Classifier fn (new, lives in `seon.agent` next to its only consumers —
no new ns): `(context-model {:seon.db/db db :seon.agent/id id})` →
`{::relevant-nses [<ns-str> …]      ;; full source renders
  ::internal-nses [<ns-str> …]      ;; indexed, never rendered
  ::agent-nses    #{<ns-str> …}     ;; agent-authored (any agent)
  ::agent-attrs   #{<kw> …}}        ;; agent-registered attrs`
fed by ONE pass over the full index (`:seon.ns` rows + `:seon.fn` →
ns join + `:seon.schema/key` rows + tx provenance) — every section fn
(exemplars/catalogs/namespace-context/warnings/domain-attrs) takes the
model, none re-queries or re-classifies.

Classification rules, in precedence order:

1. `*.internal` ns name → internal, ALWAYS (the V3-A convention; the
   ns name IS the filter).
2. `my.*` → relevant, ALWAYS (unit 3 decision: name rule dominates,
   provenance not consulted for my.*).
3. ns/attr whose corpus rows landed in an AGENT tx → agent-authored →
   relevant. Provenance predicate, VERIFIED against the live store
   (evidence above): a tx is agent-scoped iff
   `[?tx :seon.db/agent-id ?aid]` AND NOT
   `[?tx :seon.db/origin :substrate-seed]` — the boot seed runs inside
   the booting agent's `with-agent` scope so seed txs carry BOTH attrs
   (6 of the 10 agent-stamped txs are seed txs); unstamped txs are
   substrate. Same clause family as
   `agent_view.cljs:20-43 substrate-or-mine?` and
   `render.cljs:346-360` tx-meta memo — the classifier becomes the
   single owner of this predicate.
4. `seon.*` (and bare substrate roots) → relevant iff in the
   `relevant-roots` config (the post-split public faces table above:
   seon.db, seon.schema, seon.todo, seon.search, seon.fs, seon.repl)
   AND NOT in the temporary exclusion set; else internal.

Temporary exclusion set (transition only): a def listing the
NOT-YET-SPLIT substrate plumbing nses whose public face still carries
plumbing (today: `seon.agent`, `seon.client`, `seon.eval`,
`seon.render`, `seon.warn`, `seon.schema` — pending the V3-A-style
splits; `seon.db` leaves the set when the in-flight split commits).
DEATH CONDITION (encode in the docstring + a test): an entry is STALE
the moment a `<entry>.internal` sibling ns exists in the index — the
agreement test fails on stale entries; the def is DELETED when empty.

Legacy filter call-sites to delete/replace:

- `warn.cljs` HEAD:294-298 `internal-attr-ns?` (used HEAD:321) — the
  blanket `(db|seon)(\..*)?` regex, the S-21 production bug. `[tree]`
  already replaces it with provenance-based `agent-registered-attrs`
  (in-flight S-21 lane). V3-C absorbs that fn INTO the classifier
  (`::agent-attrs` leg); `domain-attrs` takes the model.
- `agent.cljs:2572-2582` `substrate-ns-name?` (used 2652) — DELETE;
  the functions-catalog depth choice reads the model's
  `::agent-nses`/internal verdicts.
- `agent.cljs:1838-1852` `exemplar-ns?` (used 1888 + `client.cljs:979`)
  — replaced by `::relevant-nses` (full-source rendering rule); the
  boot indexer persists full `:seon.ns/source` for every RELEVANT
  substrate ns instead of the exemplar set. `exemplar-roots`
  (`agent.cljs:1812-1827`) dies with it.
- `client.cljs:888-907` `substrate-ns-set` — KEEP (it is the replay
  discriminator, var-meta-derived, a different question:
  "is this row compiled code?"). Relationship contract: classifier
  verdict `agent-authored` ⇒ ns ∉ `substrate-ns-set`, asserted by the
  agreement test.
- `schema.cljc` HEAD:198-213 `derive-entity-id-attr` over-match (stamps
  `:seon.entity/id-attr` on ANY `:map` containing an identity-attr
  entry — eight request/response envelopes in the live store, evidence
  above). Fix at the CONSUMER, not the registry: unit 2 already gates
  `renderable-kinds` on `:seon.schema/render-fn`; this unit narrows the
  stamp itself — derive only when the map is an ENTITY shape. Precise
  rule: the id-attr entry is REQUIRED (catches `::write-response`,
  whose id is `{:optional true}`) AND the schema key is not suffixed
  `-request`/`-response` (catches `::complete-request`, whose id is
  required; the suffix is the conventions-mandated naming for API
  envelopes, docs/conventions.md). Name-based, but the name is itself
  a hard convention — flag the coupling in the docstring. ("Is this
  key consumed as an envelope in some `:=>` fn schema" would be the
  structural rule but is not cheaply decidable at register! time.)
- `agent.cljs:2140-2200` schema-catalog gating — already fixed at HEAD
  (wrapper renders when ANY block has content, 2158-2164); the section
  just consumes the model now.

Agreement property test (new `test/seon/context_model_test.cljs`):
for every ns in the live index plus generated ns-name strings —
(a) all surfaces classify identically (functions-catalog depth,
exemplar/full-source choice, replay skip, domain-attrs) derive from
the ONE model; (b) `*.internal` never appears in any rendered section;
(c) `substrate-ns-set` members are never `::agent-nses`;
(d) exclusion-set entries have no `.internal` sibling (death
condition); (e) `my.*` always relevant.

Files (7): `src/seon/agent.cljs`, `src/seon/warn.cljs` `[tree]`,
`src/seon/client.cljs` `[tree]`, `src/seon/render.cljs`,
`src/seon/schema.cljc` `[tree]`, `test/seon/context_model_test.cljs`
(new), plus deletions inside those files. PRECONDITION: the V3-A db
split and S-21 lanes must COMMIT first (three of these files carry
their in-flight edits).

Live proofs: (1) fresh agent ctx-preview: no `*.internal` ns anywhere,
`my.kb` + relevant substrate at full source, the eight envelope schemas
absent from renderable kinds; (2) agent defines a fn in `my.agent.<id>`
→ next render shows it (provenance leg); (3) the same query count per
render drops (one full-index query — measure via konserve read count or
eval timing, was the A1 wedge vector). Gym oracle: full trio
S-01/S-12/S-21/S-32 re-run + the agreement test green.

### Unit 5 — `bin/seon start/restart all`

**STATUS: SHIPPED 2026-06-10 (uncommitted).** `start|stop|restart all`
with `wait_ready` socket/HTTP/build gates and auto-prep fingerprint
landed in `bin/seon`; ping bounded retry (5 × 2s + 500ms backoff, same
fail-loud error) in `src/seon/store/wire.cljs` with unit tests at
`test/seon/store/wire_test.cljs` (stubs wire-node/rpc; multi-arity
stub required — direct `arity$3` call sites). Live proofs: no-op
`start all` 0.2s clean; `restart all` 32.6s ordered+gated, pod clean
first-try ping; bin/test-cljs 330/1277 green. deps.edn:168 stale
:writer comment fixed; protocol doc updated. NOT proven live (deliberate):
`cluster reset` (destructive — wipes the live store; it now shares
`wait_ready`, which the restart proved) and the actual-prep run
(detection proven both ways; prep commands not exercised end-to-end).

Goal: one command brings the whole stack up dependency-ordered with
real ready gates; sha bumps stop blowing boot timeouts.

Current structure (read `bin/seon`): process registry =
`process_command` case at :39-64 (pod / cljs-watch / jvm /
wire-server); `process_ready_hint` :66-74 is INFORMATIONAL ONLY;
`all_processes` :76 exists but `cmd_start` :233-238 takes exactly one
name; `cluster_reset` :321-375 already implements an ordered
wire-server→pod bring-up but gates on a LOG GREP
(`grep '\[writer\] ready'` :361).

Changes:

- `start all` / `restart all` / `stop all`: extend `cmd_start`/
  `cmd_stop`/`cmd_restart` to accept `all`. Start order (dependency
  graph): `cljs-watch` → `wire-server` → `pod` (watch produces
  `out/client/main.js` which the pod execs; the pod's boot ping is
  fail-loud against the wire socket — `wire.cljs:118-136`). `jvm` is
  NOT in `all` (independent lane, start explicitly). Stop order:
  reverse (`pod` → `wire-server` → `cljs-watch`).
- Socket-level ready gates between stages (promote the hints to
  bounded waits, factored as `wait_ready <name>`):
  - `wire-server`: `tmp/seon-cluster-default-req.sock` exists AND
    accepts a connection (`nc -z -U` on macOS; fall back to the
    existing log grep if nc lacks `-U`), plus
    `tmp/seon-writer-repl-port` written. Bound ~180s (matches
    `cluster_reset`'s wait, which should be refactored onto the same
    helper).
  - `pod`: `tmp/seon-port` exists (hint at :68) and answers one HTTP
    request. Bound ~120s.
  - `cljs-watch`: `out/client/main.js` exists and is newer than the
    watcher's `started-at`, or `'Build completed'` in
    `logs/cljs-watch.log` (no socket exists for shadow watch). Bound
    ~300s cold.
- Auto-prep on datahike sha change: deps.edn pins the datahike fork by
  git sha in `:writer` (deps.edn:153-155,
  `seantempesta/datahike@1ae3569…`) and the pod's `:cljs` alias runs
  the same sha (deps.edn:142-148). First resolve after a sha bump
  downloads + builds the git dep inside the ready window. Gate: write
  the sha-bearing deps.edn lines' hash to
  `tmp/proc/<name>/deps-fingerprint` on successful start; on `start`,
  if the hash changed, run `clojure -P -M:writer` (wire-server) /
  `clojure -P -M:cljs` (cljs-watch) SYNCHRONOUSLY first, logging to the
  process log, then spawn. SMELL found while anchoring (report, fix in
  passing): deps.edn:168 comment claims ":writer stays pinned to mvn
  0.8.1671" but `:writer` carries the git sha since 156a53e — stale
  comment.
- Pod bounded ping retry (~10s): `src/seon/store/wire.cljs:118-136`
  `ping!` does ONE rpc (3s timeout) then the fail-loud throw at :129.
  Change to a bounded retry loop — e.g. up to 5 attempts × 2s timeout
  (~10s total), keeping the SAME final fail-loud error (message
  unchanged plus "after N attempts/~10s"). This closes the
  `start all` race where the pod execs before the writer's socket
  accepts, WITHOUT weakening boots-only-against-cluster-store.

Files (3): `bin/seon`, `src/seon/store/wire.cljs`,
`docs/seon/process-management.md` (protocol doc).

Live proofs (no gym — process-level): (1) from all-stopped,
`bin/seon start all` → green stack, `tmp/seon-port` written, one agent
boots; (2) `bin/seon restart all` from running; (3) start pod with
wire-server deliberately started 5s later → pod survives via ping
retry; (4) touch the deps-fingerprint → next start runs `clojure -P`
visibly in the log; (5) `bin/seon cluster reset` still works (shares
`wait_ready`).
