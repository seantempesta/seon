---
type: research
status: active
tags: [research, sci, database, context]
---

# The agent db-query surface — grounding and three options (2026-08-01)

Owner-ruled a bug on 2026-08-01: agents evaluate through `seon.sci.eval`
and nothing db-shaped resolves. The exam form the REPL-session design
shows as the tutorial's climax
(`plan/repl-session-context-2026-08-01.md:146`) cannot run. This report
grounds why, reconciles the capability-door tension, and puts three
options to the owner. Research and proposal only; no production edits.

## Dependency ledger — exact files and lines read

First-party source (fresh tree):

- `src/seon/sci/eval.clj:147-205` — the base ctx and its `:namespaces`
  map; `:198-204` the just-landed bare `dir`/`doc` wiring (commit
  `c6db32f56`, the precedent for adding REPL surface).
- `src/seon/sci/eval.clj:656-679` —
  `install-loaded-first-party-namespaces!`, the computed membership rule
  that decides what resolves in an agent eval.
- `src/seon/sci/eval.clj:726-748` — `acquire!`; `:681-724` the
  program-derived `doc`.
- `src/seon/sci/eval.clj:892-935, 1046-1059` — `evaluate`, and admission
  performed inside the armed boundary.
- `src/seon/cluster/loop.cljc:972-995` — the `:resume` branch: one ctx
  fork per run, `acquire!`, and the walk-custody binding that already
  carries the live branch connection across every evaluation.
- `src/seon/render.clj:87-115` — `*walk-context*` (private) and
  `ambient-database-value`; `:143-198` `walk`, the existing zero-argument
  agent-facing fn that derives its own db.
- `src/seon/sci/admit.clj:1-16, 217-248` — the admission walk and its
  caps; `src/seon/config.cljc:43-48` — `result-caps`.
- `src/seon/fn.clj:173-182` — `namespace-row`, the builder that gives a
  first-party namespace its `:seon.ns/source` fact.

Quarry (State A):

- `src-old/seon/db.cljc:506-535` — `aligned-dependency-arguments`: the
  implicit-database-value insertion at the query's own parsed source
  position.
- `src-old/seon/db.cljc:537-557` — `read-attribute-dependencies`, the
  plan-derived evidence used for interest registration.
- `src-old/seon/db.cljc:595-608` — `query`, carrying
  `^{:seon.capability/effect :read}`; `:624-629` `pull`.
- `src-old/seon/CLAUDE.md` one-mechanism table — "DB access | `seon.db`
  (sole API) | touch `datahike.api` outside `src/seon/db/`".
- `src-old/my/` — `blob`, `canvas`, `kb`, `plan`, `skills`, `ui`. **There
  was no `my.db`.** `src-old/my/kb.cljc:1-12` requires `seon.db` directly;
  the agent-facing db name in State A was `seon.db`, not a `my.*` name.

Dependency source (our fork):

- `reference-code/datahike/src/datahike/api.cljc:9-19` — `q`, `entity`,
  `pull`, `datoms` are `:referentially-transparent? true` (pure over an
  immutable database value); writes are the async family.
- `reference-code/datahike/src/datahike/api/specification.cljc:437-459`
  — `q` args: `[query & args]` where a source argument is one of the
  args; `:cacheable? true`, `:cancellable? true`.
- Same file, `pull` / `entity` / `datoms` entries — `entity` "Returns
  lazy map-like structure"; `datoms` carries `:returns-lazy? true`.

Rulings and specs:

- `AGENTS.md:39` — the three shapes; db is *listed* as a capability
  family. `AGENTS.md:706` — "`seon.db` is the sole application database
  API. Outside `src-old/seon/db/`, never call `datahike.api` directly"
  (the authority is already scoped to State A by its own path).
- `plan/README.md:1637-1652` — ruling #20: agents call any function;
  `(seon.render/walk)` from an agent eval, "ambient db derived by the fn
  itself (the ambient-database-values design — no injection machinery)".
- `plan/README.md:1653-1679` — ruling #21: all functions are in the
  database; compiled Vars are the host-binding interim delivering #20.
- `plan/README.md:1052-1058` — ruling 22a: "RENDERS CALL `seon.db`,
  NEVER `datahike.api` directly — so we can intercept and do whatever we
  want", and the mined pattern to keep: **dual positional arities where
  the conn/db argument may be omitted and the latest db auto-inserts**.
- `plan/README.md:839-861` — the facade must prove `q`, `pull`,
  `pull-many`, `entity`, `datoms`; dual use (one-off vs registering pass).
- `plan/seondb-facade-contract-spec.md:15-84` — the authored, sealed
  contract; `:786-787` of `plan/unsettled.md` places it as item 3 in the
  ordered queue, awaiting owner review.
- `plan/repl-session-context-2026-08-01.md:146, 158-160, 197-201` — the
  exam beat, "what must become real", and the prototype's finding of
  this exact gap.
- `tmp/repl_context_prototype.clj:42-57, 208-215` — the working door
  probe and its `authored-targets` list naming db access as unwired.

Live probes: MCP `eval_clj`, cluster `default` (pid 35130, prepl 63855),
session `dbsurface`, basis `:max-tx 536870933`.

## Findings

### 1. How the door builds context, and why db is absent

`base-ctx` (`eval.clj:147-205`) installs a deliberately tiny set:
interrupt-aware `clojure.core`/`clojure.string`, the two `seon.schema`
lifecycle fns, all of `clojure.test`, `my.run`'s two dispositions,
`my.message`'s two values, plus `Throwable`/`Error`, and (since
`c6db32f56`) bare `dir` and `doc` refer'd through `clojure.core`.

Everything else an agent can call arrives through
`install-loaded-first-party-namespaces!` (`eval.clj:656-679`), whose
membership rule is **computed, not listed**: a namespace resolves iff it
has a `:seon.ns/source` fact asserted by a `:core`-admission transaction
**and** is loaded in this JVM. Its Vars are bound with `ns-interns`, i.e.
the real compiled Vars, so re-evaluating a `defn` changes agent-visible
behavior without reacquisition.

Live, on `default`:

| probe | result |
|---|---|
| namespaces with `:seon.ns/name` | 189 |
| of those, with `:seon.ns/source` | 132, **all** `:core` admission |
| `(d/pull db '[*] [:seon.ns/name 'datahike.api])` | `{:db/id 2160, :seon.ns/name datahike.api}` — a bare ref stub from some namespace's `:seon.ns/requires`, **no source** |
| `(sci/resolve ctx 'seon.render/walk)` | truthy |
| `(sci/resolve ctx 'datahike.api/q)` | **false** |
| `(seon.db/q …)` through the door | `Unable to resolve symbol: seon.db/q` |
| `(datahike.api/q …)` through the door | `Unable to resolve symbol: datahike.api/q` |
| `(seon.cluster.agent/owner-of 'my.agents.root)` | `Wrong number of args (1)` — i.e. **resolved** |

So the bug has one precise cause and it is not a door defect: the door's
rule is right, `datahike.api` is third-party and correctly outside the
corpus, and **`seon.db` does not exist in `src/`**. Ruling #20 is
genuinely live — first-party fns resolve — and db is the one hole
because nobody has written the first-party namespace that owns it.

Corollary worth stating plainly: **adding `src/seon/db.cljc` makes
`seon.db/q` resolve in agent evals with zero changes to `seon.sci.eval`.**
`seon.fn.clj:173-182` gives it a `:seon.ns/source` row at indexing, the
`:core` admission comes with core publication, and `install-loaded-…`
picks it up by the existing computed rule.

### 2. Where a db value comes from inside an evaluation

It is already there. `loop.cljc:976-985` wraps the compiled evaluate in

```clojure
(render/call-with-walk-context
 {:seon.store/branch-connection connection
  :seon.cluster.agent/id agent-id
  :seon.sci.admit/caps caps}
 #(compiled-evaluate request))
```

with the in-source comment: "The public walk dereferences this exact
live branch connection; no database value is injected into the
interpreter context." `render.clj:112-115` reads it:

```clojure
(defn- ambient-database-value []
  (or (:seon.db/db *walk-context*)
      (some-> (:seon.store/branch-connection *walk-context*) deref)))
```

`seon.render/walk` (`render.clj:143-198`) is therefore an existing,
working, zero-db-argument agent-facing function that derives `now` from
ambient custody — exactly the shape ruling #20 endorses. A `seon.db/q`
of option (a) is the *same* mechanism applied to the *same* custody.

The three sub-options weighed:

- **(a) zero-db-arg fn over ambient custody.** Dereference happens once
  per call, so every call is `now` — satisfies ruling #24 even when an
  earlier form in the same run transacted. Matches ruling 22a's mined
  dual-arity rule and `src-old/seon/db.cljc:506-535`, which inserts the
  implicit database value at the query's own parsed source position
  rather than assuming argument 1.
- **(b) a `db` var refreshed per form.** Breaks ruling #24 *within* a
  form: `(do (transact …) (q … db))` reads the pre-transaction basis. It
  also hands the agent a Datahike `DB` object; live-probed, admitting one
  yields an elided `{… :seon.sci.admit/type "datahike.db.DB", …}` map, so
  it cannot survive leaving the boundary and reads as noise in a REPL
  transcript. It is a var whose whole purpose is to be passed to a
  function that could have fetched it.
- **(c) expose `datahike.api` directly.** Requires a *second* membership
  rule beside the computed corpus one — a hand list of blessed
  third-party namespaces, which the no-hand-lists rule and the R34
  precedent reject. See finding 4 for why the raw surface is worse than
  merely inelegant.

The one genuine design call inside (a): `*walk-context*` is
`^:private` and named for render. Either `seon.render` exports
`ambient-database-value` (one line; keeps a single custody owner and a
single binder) or the custody moves to a neutral owner that both
`seon.render` and `seon.db` read. Recommend the former first — moving it
is a rename wave across four binders (`loop.cljc:981`,
`prompt.cljc:59`, `render/web.clj:1004`, plus tests) for no behavior.

### 3. What the old system did

State A's answer was `seon.db`, and it was already the pattern the owner
later re-ruled in 22a:

- One sole API namespace (`src-old/seon/CLAUDE.md` one-mechanism table),
  never `datahike.api` at call sites.
- `query` (`src-old/seon/db.cljc:595-608`) tagged
  `^{:seon.capability/effect :read}` — note State A classified a read as
  a capability *effect kind*, not as a gated crossing; the gate was the
  wire, which we deleted.
- Implicit database source (`:506-535`): if no argument is a database
  value, the latest is inserted at the query's parsed source position.
- `read-attribute-dependencies` (`:537-557`): plan-derived attribute
  sets, fail-open to `:all` — the interest-registration evidence.
- **No `my.db` and no `my.*` query fn ever existed.** `src-old/my/`
  contains `blob`, `canvas`, `kb`, `plan`, `skills`, `ui`; `my.kb`
  (`src-old/my/kb.cljc:1-12`) simply requires `seon.db`. The agent-facing
  name for querying was always `seon.db`.

The State A lesson worth *not* porting is the wire: `query` was `^:async`
in CLJS and routed a request through a UDS transport. Under O1
co-location a read is a pointer into an immutable value already in this
JVM's heap. The facade should be plain synchronous CLJ-side.

### 4. Datahike specifics, and what admission does to results

`d/q` needs a database value among its arguments; `q`, `pull`, `entity`,
`datoms` are all `:referentially-transparent? true`
(`api.cljc:9-19`) — pure over that value, so nothing about a read is
inherently unsafe for interpreted code.

The hazard is laziness and ref-following, and it is measurable. Live, with
production caps (`max-depth 12, max-collection 64, max-string 4096,
max-nodes 4096`), admitting each shape:

| value | `capped?` | admitted result |
|---|---|---|
| `(d/pull db '[*] 4308)` (root agent) | false | `{:db/id 4308, :seon.cluster.agent/cluster #:db{:id 4306}, :seon.cluster.agent/id "root", :seon.cluster.agent/namespace #:db{:id 4307}}` |
| `(d/entity db 4308)` | **true** | `[[:seon.cluster.agent/cluster [[:seon.cluster/config [[:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY"] [:seon.config.eval/time-limit-ms 30000] …` |
| `(d/datoms db :aevt :seon.ns/name)`, 2 taken | false | `[[741 :seon.ns/name my.message 536870918 true] …]` |
| `(d/datoms db :eavt)` (full scan) | true | capped |
| `(d/q '[:find ?n :where [?e :seon.ns/name ?n]] db)` — 189 rows | **true** | 64 of 189 |
| `(d/q '[:find (count ?e) . …] db)` | false | `189` |
| the `DB` value itself | true | elided map with `:seon.sci.admit/type "datahike.db.DB"` |

Three conclusions:

1. **A raw `entity` in an agent eval is actively harmful**, not merely
   untidy. It has no `:db/id`, is a vector of pairs rather than a map,
   its content depends on realization state, and admission *follows its
   refs*, dragging the entire cluster-config entity into the agent's
   context. `pull '[*]` on the same entity is a clean four-key map. This
   independently confirms the facade spec's ruling
   (`seondb-facade-contract-spec.md:22`) that `entity` must be an eager
   bounded `pull '[*]` projection, never a lazy Entity object.
2. **Caps are a silent truncator at 64 collection elements.** A
   189-row query returns 64 rows with `capped? true` — the flag is
   present on the evaluation, but a REPL-session transcript that prints
   only the value teaches the agent a false count. Any db surface must
   make cappedness legible in the printed session (an elision line, as
   the walk already does), and aggregate queries (`(count ?e)`) should be
   taught as the idiom — which the exam beat already does.
3. Aggregates and pulls sail through untouched; the exam form's answer
   (`7` for `my.message` schema keys) is a scalar. The exam is safe today
   the moment the symbol resolves.

### 5. Naming

The vocabulary rule says use the dependency's vocabulary at the boundary.
Datahike's fn is `q`, so the surface is `seon.db/q` with datahike's own
argument order (`[query & args]`, source argument at its parsed position
— which is precisely what `src-old/seon/db.cljc:506-535` implemented).

`seon.db` is the name every authority already uses: `AGENTS.md:706`,
ruling 22a (`README.md:1053`), the sealed contract spec, the REPL-session
design's S6 beat, and State A's own toolkit. `AGENTS.md:706` is not stale
in its *name*, only in its *path scope* (`src-old/seon/db/`); when the
fresh facade lands, that sentence's parenthetical becomes `src/seon/db`
and the authority is reconciled rather than repealed.

A new `my.*` name is not free: the `:my/*` key set is under open owner
veto (`repl-session-context-2026-08-01.md:131-134`), and ruling #20
already frames `my.*` as "the CURATED, documented surface — a entry point,
never a wall". A `my.db/q` delegating to a `seon.db/q` that must exist
anyway is a second name for one mechanism.

## The tension, stated crisply

`AGENTS.md:39` lists **db** among the capability families that must go
"through the one guarded door (fs, web, llm, db)". Ruling #20
(`README.md:1637-1652`, six days later) says agents call any function and
that `(seon.render/walk)` derives its ambient db itself, "no injection
machinery". Both are current. Do they conflict?

**No — they partition on write versus read, and the standing goal's own
sentence says so.** Its three shapes are (i) pure code returning values,
(ii) genuine capability *requests*, (iii) durable **facts the driver
commits**. A transaction is shape (iii): durable, provenance-stamped,
serialized by Datahike's writer, and something recovery must reason
about. That is the crossing the door exists for.

A read of the agent's own cluster at `now` is shape (i). It is a pure
function of an immutable value; there is nothing to authorize (the agent
already receives that same data rendered into its context every turn —
the walk *is* a bulk read), nothing to serialize, nothing recovery needs
(reads re-derive; a lost read is free by the transport law), and no
external service on the far side. Forcing it through the effect request handler
would mean routing a pure function call through request identity and
receipt machinery to read bytes already in this JVM's heap — the exact
wire-shaped mistake `README.md:1825` records as State A's defect.

The one thing that *looks* like a crossing is the render pass's evidence
capture (interest registration). The facade spec already rules this
correctly (`seondb-facade-contract-spec.md:32-46`): capture is a property
of the calling **pass**, bound dynamically by the render proc's
derivation, never a per-call agent choice. Agent code cannot opt in or
out, so it is not an agent-facing capability at all.

**Proposed reconciliation to record in the same beat as whichever option
is chosen:** reads are ambient context; writes are the bounded evaluation. The
`AGENTS.md:39` "db" family means transacting.

## Three options

### Option 1 (RECOMMENDED, simplest constraint) — land `src/seon/db.cljc` as the read facade, ambient-latest dual arities, `q` + `pull` first

Write the already-authored contract (`plan/seondb-facade-contract-spec.md`)
as a first-party namespace, starting with the two fns the exam and the
render family actually need. `entity`, `pull-many`, and `datoms` follow
as later slices of the same spec (the spec explicitly permits slicing;
the learning is already done).

- **Guarantee.** `(seon.db/q '[:find (count ?k) . :where …])` resolves in
  every agent eval with **zero changes to `seon.sci.eval`**, because the
  door's membership rule is already computed and already correct. Every
  read is `now` (deref once per call, ruling #24). One interception point
  exists forever after (ruling 22a), so the interest/evidence seam lands
  later without touching a single call site. `entity`'s ref-following
  blow-up is unrepresentable because the facade's `entity` is an eager
  bounded pull.
- **Cost / risk.** One new namespace; the fns are thin. One real design
  call: `seon.render/*walk-context*` is private, so either `seon.render`
  exports `ambient-database-value` (recommended, one line, one custody
  owner) or the custody moves to a neutral owner (four binders + tests).
  Risk is low and the failure mode is loud — an unbound custody returns a
  flat `:seon.error` value the same way `walk` returns its error text
  today (`render.clj:108-110, 171-177`).
- **Operational trade-off.** A new first-party namespace reaches an agent
  eval only where the cluster's branch carries its `:core`-provenanced
  `:seon.ns/source` row and the JVM has loaded it — so `bin/seon init`
  plus a cluster bounce, not a hot-reload. Normal weather, but it means
  "the exam runs" is a reset-boundary proof, not a REPL-only one.
- **Capability given up.** Time travel and index access in the first
  slice (`as-of`, `history`, `datoms`), and writes — deliberately: writes
  stay on the store owner per the spec's out-of-scope section.

### Option 2 — expose `datahike.api` in the ctx plus a per-form database value

Add a second membership rule admitting selected third-party namespaces
into the ctx, and bind the current database value (as a var, or by
letting agents call an ambient `(seon.db/db)`), so agents write literal
Datahike.

- **Guarantee.** Maximum transparency: the agent writes exactly what any
  Datahike user writes, and the model's pretraining transfers directly.
  `as-of`/`history`/`datoms` are available on day one.
- **Cost / risk.** Requires a hand-maintained allowlist of blessed
  third-party namespaces — the exact shape the no-hand-lists rule and the
  R34 precedent forbid; the corpus rule (#21) says third-party internals
  are outside the corpus **by definition**. It permanently forfeits ruling
  22a's interception point, and with it the interest/evidence seam the
  whole render pipeline depends on. And it ships the live-falsified
  hazards: a `db` var frozen at form start violates ruling #24 mid-form,
  and `(d/entity db …)` silently drags the cluster config into agent
  context (measured above).
- **Operational trade-off.** Nothing to publish or refork — it is a door
  change, so it lands with a JVM restart. That is genuinely faster today.
- **Capability given up.** Interception, evidence capture, dedupe, and
  every future policy (result shaping, cost accounting, narrow wakes).
  Once agents write `datahike.api/q` in durable corpus functions, taking
  it back is a corpus migration, not a refactor.

### Option 3 — a `my.*` toolkit query fn (`my.db/q` or similar)

Put the agent-facing read on the curated toolkit surface alongside
`my.message` and `my.run`, delegating to whatever internal implementation
exists.

- **Guarantee.** Consistency with the toolkit family: it appears in
  `(dir my.db)`, its docstring renders through the program-derived `doc`
  (`eval.clj:681-724`), and the agent's entry point is one namespace
  family.
- **Cost / risk.** It is a second name for one mechanism. The internal
  `seon.db` still has to exist for renders (ruling 22a) and for
  first-party call sites, so `my.db` is pure alias surface — and ruling
  #20 explicitly frames `my.*` as "a front door, never a wall", not as a
  required wrapper. The `:my/*` key set is under open owner veto, so the
  name is not free. It also contradicts three current authorities that
  say `seon.db/q` by name (`AGENTS.md:706`, `README.md:1053`, the S6 beat
  at `repl-session-context-2026-08-01.md:146`) and State A's own
  precedent, where `my.kb` required `seon.db` rather than wrapping it.
- **Operational trade-off.** Same publication path as option 1, plus a
  second namespace to keep in sync.
- **Capability given up.** Nothing functionally; it spends a second
  mechanism to buy discoverability that `(doc seon.db/q)` already gives.

**Recommendation: Option 1.** It is the smallest change (one namespace,
no door edit), it is the option the owner has already ruled twice (22a's
dual-arity auto-inserting facade; #20's ambient-db-derived-by-the-fn),
its contract is authored and awaiting only review, and the two failure
modes measured live — lazy `entity` ref-following and silent 64-element
capping — are both closed by construction inside a facade and both wide
open in option 2.

## Acceptance evidence (for whichever option wins)

1. **The exam form runs through the real door.** Using the prototype's
   recipe (`tmp/repl_context_prototype.clj:42-57`) against a live cluster:
   `(seon.db/q '[:find (count ?k) . :where [_ :seon.schema/key ?k]
   [(namespace ?k) ?ns] [(= ?ns "my.message")]])` returns the same
   scalar the JVM-side query returns (`7` on `default` at the time of the
   prototype), with `:seon.cluster.eval/error` **absent** and
   `:seon.sci.admit/capped?` false. Record the evaluation map, not a
   summary.
2. **Ruling #24 within a form.** In one run's fold: form 1 transacts a
   fact, form 2 queries for it and finds it. This is the falsifier that
   kills option (b)'s frozen var and proves "always `now`".
3. **`entity` is eager and bounded** (whichever slice lands it): assert
   `(seon.db/entity eid)` ≡ `(d/pull db '[*] eid)` byte-for-byte, and
   assert that no admitted result contains the cluster-config attributes
   the raw Entity dragged in above. Pin the measured blow-up as the
   regression's negative case.
4. **Cappedness is legible.** A query returning more than
   `max-collection` rows prints an elision line in the `:seon.render/ai`
   projection, so the agent is never taught a false count. Assert on the
   189-row query above.
5. **Recovery / crash story.** A read is not a fact and leaves no
   receipt of its own; the *form* and its *receipt* are already committed
   by the run loop (`loop.cljc:996-1011`). So the crash walk is unchanged
   by this feature, and the proof is a negative one: kill the JVM during
   a query-bearing run and show that `recover-tx` settles the receipt as
   `:interrupted` with no db-read-specific residue and nothing to
   re-execute. Reads re-derive; a lost read costs nothing. Falsify the
   converse too — assert that no new durable attribute is introduced by
   the read path (a datom diff across a query-only run is empty apart
   from the form/receipt rows).
6. **Custody is required and fails loud.** Calling the facade outside an
   agent evaluation (no bound custody, no explicit db argument) returns a
   flat `:seon.error` value, never `nil` and never a stale db — the same
   contract `walk` already honors (`render.clj:171-177`).
7. **The authority is reconciled in the same commit.** `AGENTS.md:706`
   updated from `src-old/seon/db/` to the fresh owner, and the
   reads-are-ambient / writes-are-the-door partition recorded next to the
   `AGENTS.md:39` capability-family sentence so the tension does not
   resurface.
