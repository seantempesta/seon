---
type: prd
status: draft
tags: [prd, agent]
---

# Agent toolkit catalog — the agent-owned `my.*` tools over a protected `seon.*` floor

The agent's whole working surface, designed as a SMALL set of namespaces that,
shown in full, ARE its context — replacing a 70k-char source dump with a few
high-signal, threadable verbs. The owner's framing decision: the agent's tools
live in **`my.*` namespaces, fully agent-owned and editable** (`my.files`,
`my.todos`, `my.search`, `my.shell`, `my.test`, `my.kb`, plus `my.code`,
`my.schedule`, `my.recall`, `my.tile`). Each is a THIN wrapper over a protected
`seon.*` substrate — the real syscalls, db engine, compiler, and wire stay
`seon.*` and are `:core-seed`-guarded (un-clobberable). Build-your-environment
extends to the tools themselves: the agent can tweak `my.files`, and if it breaks
a wrapper, the protected floor still stands and `forget!` / the bitemporal store
recover it. This is a DESIGN deliverable — it specifies interfaces, the shared
shapes, and the two-tier model; it changes no source.

## TL;DR

- Seon's agent does not edit files; it DEFINES functions, evals them, redefines
  (= upserts), composes, and tests in a live REPL, with code and knowledge
  persisting as datoms. The toolkit must serve THAT loop — every verb is a REPL
  one-liner whose value is data the agent reads back and threads onward.
- **Two tiers.** A PROTECTED FLOOR (`seon.*`, `:core-seed`-guarded,
  un-clobberable): the db engine (`seon.db`, aliased `db`), the compiler
  (`seon.eval`), the loop's control verbs (`seon.agent.message`,
  `seon.agent.lifecycle`), and the `*.internal` syscall namespaces + the wire.
  An AGENT-OWNED TOOLKIT (`my.*`, editable, thin wrappers): `my.files`,
  `my.search`, `my.shell`, `my.todos`, `my.test`, `my.kb`, `my.code`,
  `my.schedule`, `my.recall` (+ `my.tile`, + a deferred `my.blob`). Each `my.*`
  tool delegates the dangerous work to a named `seon.*` floor.
- **Isolation: SHARED-COLLECTIVE** (recommended). The `my.*` tools are ONE
  cluster-wide seeded definition the user's agents collectively evolve. In a
  personal single-user cluster a shared edit is a feature, not a leak; it keeps
  the short catchy name (`my.files`, not `my.agent.<id>.files`), is leaner (one
  indexed+rendered copy, not N), and the `seon.*` floor + `forget!`/undo bound the
  only real risk. Mechanism: seed the toolkit under a NON-core origin
  (`:toolkit-seed`) so the override/`forget!` guard — which keys on `:core-seed`
  — leaves it editable, while the floor stays `:core-seed`.
- **Most of the floor + several tools already EXIST** and are well-shaped (the
  current `seon.db`, `seon.agent.todo`/`fs`/`search`/`message`/`lifecycle`,
  `seon.test.runner`, `seon.agent.schedule`, `seon.embed`). The work is: (1) the
  `my.*` rename + thin-wrapper split, (2) FORMALIZE four shared shapes so verbs
  THREAD without reshaping, (3) one new floor + tool (`shell`/`my.shell`), (4)
  the new `forget!` primitive, (5) the lean facades (`my.test`, `my.tile`,
  `my.recall`).
- The single biggest composability defect today: a `grep` hit's path key is
  `:seon.agent.search/path` but `read-file` wants `:seon.agent.fs/path`, so the
  core "search → read" move pays a manual rekey tax. The fix is a shared
  `:seon.path/*` shape both wrappers reference.
- **NEW primitive — `forget!`** (in `my.code`): the agent can define + redefine
  (= upsert) but cannot REMOVE. `(forget! 'my.foo/bar)` retracts whichever entity
  owns that sym and drops the live binding, core-guarded by the SAME `:core-seed`
  check that blocks core overrides. UNDO is free from the bitemporal store.
- **Candidate verdicts:** `my.schedule`/`remind` — **IN** (cron engine + ticker
  exist; only the verb is missing). `my.recall` (semantic KNN) — **IN** (read-only
  meaning-based retrieval over `seon.embed`; a capability datalog can't express).
  `my.blob` — **IN but DEFERRED** (the third storage tier is sound + specified,
  but `seon.blob` isn't built and has no consumer; build it WITH its first one).
  A `kb` CRUD facade — **OUT** (Q1: `transact!`/`query` over a real schema already
  IS the kb).
- **One render-rule** (not a utility): **tests render in context ONLY for the
  agent's CURRENT namespace**, never for other shown nses — flagged for the
  prompt/render work, coordinating with the in-flight GI-1 double-render fix.

## The two tiers — protected floor vs. owned toolkit

The reframe rests on one line: **a namespace is `my.*` (owned, editable) iff
redefining it cannot break a runtime invariant; it is `seon.*` (protected,
`:core-seed`-guarded) iff it is load-bearing for the substrate's correctness.**

| Tier | Namespaces | Origin | Agent may edit? | Renders full in context? |
|---|---|---|---|---|
| **Protected floor** | `seon.db` (aliased `db`), `seon.eval`, `seon.agent.message` (aliased `message`), `seon.agent.lifecycle` (refer'd verbs), the `*.internal` syscall nses + the wire | `:core-seed` | NO — `forget!`/override guard refuse | NO — indexed + grep-able only |
| **Owned toolkit** | `my.files`, `my.search`, `my.shell`, `my.todos`, `my.test`, `my.kb`, `my.code`, `my.schedule`, `my.recall`, `my.tile` | `:toolkit-seed` → `:agent` on first edit | YES — redefine or `forget!` | YES — full source every turn |

Why `message` + `lifecycle` stay on the floor (and are not `my.*`): they are the
loop's control verbs — the wake gate / hop-cap (`message!`) and the run-FSM
mutations (`wait`/`complete`/`pause`/`resume`/`terminate`). Redefining `wait`
breaks the FSM. The agent talks THROUGH them (aliased/refer'd into its home ns,
exactly as today), it does not own them. (If the owner later wants them `my.*`
too, the same thin-wrapper pattern applies — a `my.lifecycle` over a protected
`seon.agent.run` floor — but the default is: loop verbs are protected.)

**The protection mechanism (already built — reuse it):** the override guard
`seon.eval/core-origin-fn-syms` blocks redefining/`forget!`-ing any sym whose
current `:seon.fn/source` tx carries `:seon.db/origin :core-seed` (`eval.cljs`
~L1702-1750). So:

- The floor's syscall fns are `:core-seed` → un-clobberable. An agent that
  `(defn seon.agent.fs/read-file …)` is rejected with the existing actionable
  warning; the row is not persisted.
- The `my.*` wrappers are seeded under a DISTINCT `:toolkit-seed` origin (NOT
  `:core-seed`), so the guard — which keys on `:core-seed` only — leaves them
  editable and forgettable. The crux change is one origin keyword on the seed,
  not a new mechanism.

**Recovery is free.** Break `my.files/read-file` and: (a) `(forget!
'my.files/read-file)` removes the broken def, falling back to a re-seed; or (b)
re-transact its prior `:seon.fn/source` from `(db/history)`; or (c) a
`cluster reset` re-seeds the shipped default toolkit (cluster reset = fresh
world; the shipped `my.*` source is the default). A broken wrapper is never
fatal — the protected floor underneath it is intact.

## Isolation — shared-collective vs. per-agent (recommendation)

"Fully yours" + a SHORT shared name (`my.files`) is in tension: a shared-runtime
ns name can't be per-agent-distinct without scoping, and scoping kills the short
name. Two resolutions:

- **(a) SHARED-COLLECTIVE** — `my.*` is ONE cluster-wide seeded definition; all
  the user's agents see and evolve the same `my.files`. An edit by agent A is
  visible to agent B.
- **(b) PER-AGENT-SCOPED** — each agent gets its own copy (e.g.
  `my.agent.<id>.files`, or a per-agent registry). A's edit can't reach B.

**Recommend (a) SHARED-COLLECTIVE.** Four grounded reasons:

1. **The short name is the point.** `my.files` is catchy and reflexive
   (`(files/read-file …)` via the home-ns alias). Per-agent scoping forces
   `my.agent.<id>.files` and bespoke per-agent alias setup — it loses exactly
   what the reframe is for.
2. **Seon is a personal, single-user cluster.** One human; the orchestrator and
   all task agents serve that one user. Shared toolkit evolution is the intended
   dynamic — the user's agents improve ONE toolkit together — not a leak between
   distrusting tenants.
3. **The only real risk is already bounded.** The single hazard — a broken
   wrapper — cannot touch the `:core-seed` floor and is recoverable via
   `forget!` / the bitemporal store / cluster reset. Per-agent isolation defends
   a non-threat (cooperating agents under one human), at real cost.
4. **It is leaner — the catalog's whole thesis.** ONE seeded, indexed, and
   rendered toolkit, not N identical copies multiplying render cost on every
   agent's every turn.

**Indexer / render interaction.** `my.*` renders FULL in the namespaces section
(the existing rule (a): every `my.*` ns renders whole source —
`seon.ctx.namespaces`). So the agent SEES its toolkit source every turn and can
read + edit it — the build-your-environment payoff. This is WHY the wrappers must
stay thin: rendering them full is cheap precisely because they delegate the bulk
to the protected floor, which is NOT rendered full (only indexed + grep-able).
The crux indexer change: tag the shipped `my.*` toolkit nses `:toolkit-seed`
(not `:core-seed`) at boot index, so they render like shipped code yet remain
agent-editable; an agent's edit flips the row's origin to `:agent` and the seed
yields ownership (idempotent seed-if-absent never clobbers an edit). Shared
budget note: because the toolkit renders full for ALL agents, the per-tool
budgets below are a FIXED cluster-wide context cost — keep them tight.

## The composability backbone — four shared shapes

The design rule the whole catalog is held to: **the output of one verb is a valid
input to the next, with no reshaping at the arrow.** That needs a small set of
shapes that many wrappers reference (the register-once rule from
`docs/conventions.md`). These shared THREADING shapes belong to the protected
substrate (`seon.*`) — they are the stable contract the wrappers exchange, the
one part of a tool's I/O an edit should not casually rename. Tool-specific
payload keys are `my.<tool>/*`; the shapes you THREAD are `seon.*`. There are
exactly four.

### 1. PATH — `:seon.path/*` (the files ↔ search ↔ shell hinge) — NEW

The defect today, verbatim from the two real surfaces:

- `seon.agent.fs/read-request` keys on `:seon.agent.fs/path` (a `:string`).
- `seon.agent.search/match` keys on `:seon.agent.search/path` (a `:string`),
  with the hit's location at `:seon.agent.search/line-number` and the matching
  text at `:seon.agent.search/line-text`.

So a `grep` match cannot feed `read-file` directly — `search.cljs`'s own
docstring even shows the manual rekey: `(read-file {:seon.agent.fs/path
"<:seon.agent.search/path of the hit>"})`. That `<…of the hit>` IS the friction
this backbone removes.

```clojure
;; ONE canonical path vocabulary, on the floor, referenced by my.files / my.search
;; / my.shell.
(schema/register! :seon.path/abs     [:string {:min 1}])  ; an absolute path
(schema/register! :seon.path/line    :int)                ; 1-based line
(schema/register! :seon.path/preview :string)             ; the line / a snippet
;; A "located item": a path, optionally a line + preview. A grep match IS one;
;; a dir-listing entry IS one; read-file ACCEPTS one.
(schema/register! :seon.path/located
  [:map
   [:seon.path/abs     :seon.path/abs]
   [:seon.path/line    {:optional true} :seon.path/line]
   [:seon.path/preview {:optional true} :seon.path/preview]])

```

`my.files/read-file`, `stat`, `list-dir`/`walk-dir` entries, `my.search/grep`
matches, and `my.shell`'s `cwd` all reference `:seon.path/abs`. A grep match
returns a `:seon.path/located`; `read-file` takes a `:seon.path/located`; the two
compose with no rekey.

### 2. REF — `:seon.db/ref` (db addressing) — EXISTS, conforms

A lookup-ref `[identity-attr value]` (or a raw eid) is the universal "address of a
thing." Already canonical on the floor (`seon.schema`), already used uniformly:
`:seon.agent.todo/owner`, `:seon.agent.message/from`/`/to`, `:seon.agent/parent`.
The output of one verb is the input to the next: a message's `from` is a ref you
pass straight to `db/entity`, to `my.todos/add!`'s `:from`, or to `message/agent`.
No change — this is the shape the others imitate.

```clojure
[:seon.agent/id "iCg-2606101519"]     ; a ref — addresses an agent
(db/entity {:seon.db/ref [:seon.agent/id "iCg-2606101519"]})   ; threads in

```

### 3. ITEMS — `:seon.items/*` (self-describing collections) — FORMALIZE

A collection result is `{ok? + a vector of SELF-DESCRIBING MAPS + count +
truncated}`. Today three surfaces do this but each names the vector differently
(`:seon.agent.todo/todos`, `:seon.agent.search/matches`, `store-inventory`'s
anonymous vector) and two carry BARE STRINGS (`fs/list-dir` + `fs/walk-dir`
`:seon.agent.fs/entries` are `[:vector :string]` — a bare filename can't thread
into `stat`/`read`).

```clojure
(schema/register! :seon.items/items      [:vector :map])  ; each item self-describing
(schema/register! :seon.items/count      :int)
(schema/register! :seon.items/truncated? :boolean)
;; Mixin (referenced, never re-inlined):
;;   {<ns>/ok? true :seon.items/items [<map> …] :seon.items/count <int>
;;    :seon.items/truncated? <bool>}

```

The rule: every item is a map carrying enough keys to BE the next call's input. A
listing entry becomes a `:seon.path/located` (feeds `stat`/`read`); a grep match
already is; a todo already is. Counts/aggregates are NOT items — they stay scalars.

### 4. RESULT — `<ns>/ok?` + `:seon.error/*` (the never-throw envelope) — FORMALIZE

Every agent-facing verb returns an envelope; it never throws (Errors-Are-Values).
Two real inconsistencies to settle:

- The discriminator NAMESPACE varies by owner — CORRECT (keyword-ns = data-ns),
  it stays; formalize it so each `<ns>/ok?` references one shared
  `:seon.result/ok?` `:boolean`.
- The error VALUE varies: `db/transact!` returns a structured `:seon.error/*` MAP
  (`:seon.error/message` + `:seon.error/data` carrying `:seon.error/kind`); but
  `search`/`todo`/`fs` return a plain STRING. **Pick one: the map.** A string
  can't carry `:seon.error/kind :user-input` vs `:core-bug` — the signal the
  agent needs to decide "fix my args" vs "report it." Keep a human message at
  `:seon.error/message` so a string read still works.

```clojure
(schema/register! :seon.result/ok? :boolean)   ; the shared discriminator shape
;; :seon.error/* already on the floor (seon.db) — the ONE error map:
;;   {:seon.error/message "<guiding>" :seon.error/data {:seon.error/kind :user-input}
;;    :seon.error/raw "<underlying>"}

```

Three result FLAVORS, one discipline. The generic verb envelope covers
db/my.files/my.search/my.todos/message/my.tile/my.code/my.schedule/my.recall.
Two specialized values keep their own shape because the value IS the answer:

- **my.shell** — `{:seon.shell/ok? :seon.shell/exit :seon.shell/out
  :seon.shell/err :seon.shell/timed-out?}` (exit/out/err is the universal shell
  contract).
- **my.test** — `{:seon.test/pass? :seon.test/summary :seon.test/failures}`.
- **lifecycle** — returns a bare `:seon.derive/state` keyword on success (the
  natural value) and the envelope only on failure.

### The worked chain (the move every other chain is a variant of)

```clojure
;; my.search → my.files → transform → persist, threading with NO reshape:
(->> (search/grep {:seon.search/pattern "defn \\^:async"})  ; -> {ok? items[located]}
     :seon.items/items                                       ; vector of :seon.path/located
     (filter #(str/ends-with? (:seon.path/abs %) ".cljs"))   ; items are maps → filterable
     (map files/read-file)                                   ; located map feeds read-file
     (map :my.files/content)
     (mapcat extract-fn-names)                               ; your own pure fn
     (map (fn [nm] {:my.kb.codebase.fn/name nm
                    :my.kb/source-path "…" :my.kb/confidence :inferred}))
     (hash-map :seon.db/tx-data)
     db/transact!)                                           ; -> {:seon.db/ok? true …}

```

Every arrow is total — no `<…of the hit>` rekey anywhere.

## The catalog

Each entry carries: status, the one-line reason the agent reaches for it, its
`seon.*` floor backing, the public surface as malli-ish sketches, how it
composes, and a render budget (tokens, chars/4 — keep the FULL source cheap to
show, since `my.*` renders whole every turn for every agent).

### Protected floor — stays `seon.*` (aliased/refer'd, not owned)

#### `db` — `seon.db`, aliased `db` — PROTECTED (point 4: stays `seon.db`)

**Why it stays protected:** it is the engine, not a customizable convenience — one
bound conn, synchronous reads, a never-throwing write envelope. A `my.db` thin
wrapper would buy nothing (it adds no syscall to gate, no policy to localize) and
would put the agent's most-used, correctness-critical surface one editable layer
away from the real datahike/wire boundary. **Recommend: `db` stays `seon.db`,
aliased `db`** in the home ns, `:core-seed`-guarded.

Surface (unchanged): `new-id!`, `transact!`, `query`, `pull`, `entity`,
`as-of`/`since`/`history`, `listen!`/`unlisten!`, `store-inventory`,
`installed-schema`. **Composes:** `store-inventory` → `query`; `pull`/`entity`
emit maps; `transact!`'s `:seon.db/tempids` resolves new refs. **Gap (document,
don't over-build):** tuple `:find` returns positional vectors (not items) —
correct for counts; for "list of things" use pull-syntax `:find` or `pull`.
**Budget (public face rendered):** ≤ 2.5k tok — `db` is the one heavy ns (~15k
today); render its docstring + cheat sheet + verb signatures, keep guard/bridge
bodies in `seon.db.internal`. The accompanying `db.examples` (~600 tok) is a tiny
ns of `(comment …)` canonical CHAINS (incl. the `forget!` undo recipe).

#### `message` — `seon.agent.message`, aliased `message` — PROTECTED

**Why protected:** the single write path for `:seon.agent.message` rows, coupled
to the wake gate (`waking-inbound?`) and hop-cap. Redefining it could break the
loop's wake/halt invariants. Surface (keep): `message!`, `user`,
`agent` (refuses self→self), `waking-inbound?`/`hop-live?`, `user-ref`.

**Smell to fix on the floor:** success returns `{:seon.agent.message/ok? true …}`
but FAILURE returns `{:seon.db/ok? false :seon.db/error …}` — the failure
envelope leaks the `db` namespace. Fix: failure returns
`{:seon.agent.message/ok? false :seon.error/message …}` (own-ns ok? + shared
error map). **Budget:** ~3k tok.

#### `lifecycle` — `seon.agent.lifecycle`, refer'd verbs — PROTECTED

**Why protected:** `wait`/`complete`/`pause`/`resume`/`terminate` are run-FSM
mutations; state is derived from them. Redefining them desyncs the FSM. Refer'd
into the home ns as today. **One documented divergence from the RESULT backbone:**
the happy path is a bare `:seon.derive/state` keyword (the derived state IS the
answer), the envelope only on failure — "keyword ⇒ ok, map ⇒ error." **Budget:**
~1.8k tok. No reshape.

### Owned toolkit — `my.*` (thin, editable wrappers)

#### `my.files` — FORMALIZE (rename + reshape) — floor: `seon.agent.fs`

**Why reach for it:** the agent's eyes and hands on the user's machine, gated by
an allowlist — read, list, stat, walk — without leaving the REPL.

**Floor:** `seon.agent.fs` (+ `seon.agent.fs.internal`) — the protected node:fs
syscalls + the `SEON_FS_*` allowlist gate. `my.files` is the thin editable
wrapper; the syscall + gate stay `:core-seed`. The agent can tweak how
`my.files/read-file` shapes its result, but cannot disable the allowlist (that
lives on the floor).

Surface (keep, renamed): `grants`, `configure!`, `read-file` (paged
`from-line`/`max-lines` + honest totals), `write-file`, `list-dir`, `walk-dir`,
`stat`, `file-exists?`, `home-dir`. Map-in/map-out, never-throws, default-deny.

**Reshape (the headline backbone work):**

- `read`/`stat`/`write` requests reference `:seon.path/abs` (not the private
  `:seon.agent.fs/path`), so a `:seon.path/located` from `grep` feeds straight in.
- `list-dir`/`walk-dir` entries become `:seon.items/items` of `:seon.path/located`
  (each `{:seon.path/abs … :my.files/dir? …}`), so an entry threads into
  `stat`/`read`/`grep`. Today bare strings — the one place files breaks the chain.

**Composes:** located items → `read`/`stat`/`grep`; `grants` →
`my.search` default roots. **Budget:** ~2k tok (the wrapper is thin; the WASI
branches + syscalls live on the floor).

#### `my.search` — FORMALIZE (rename + reshape) — floor: `seon.agent.search`

**Why reach for it:** find where something lives by CONTENT (ripgrep) and land on
absolute, allowlisted hits that feed `read-file` with zero guessing.

**Floor:** `seon.agent.search` (+ `.internal`) — the protected ripgrep `execFile`
plus the fs-allowlist gate (reuses `seon.agent.fs`, so search and read agree on
reach). `my.search` is the thin wrapper.

Surface (keep): `grep` → `{ok? + items + count + truncated?}`. `^:async`,
never-throws. **Reshape (the single highest-value fix):** a match becomes a
`:seon.path/located` (`:seon.path/abs`+`:seon.path/line`+`:seon.path/preview`) and
the envelope uses `:seon.items/*`. Then `(map files/read-file matches)` just
works — the documented manual rekey DELETES. **Smell:** error STRING → the shared
`:seon.error/*` map. **Budget:** ~1.5k tok (wrapper).

#### `my.shell` — NEW (tool + floor) — floor: `seon.agent.shell` (NEW)

**Why reach for it:** run a real command — a formatter, a one-off `node`/`python`
script, a `git` query — and get `{exit out err}` back as data. The survey's
recurring "give the agent a terminal" capability (Terminal-Bench / SWE-bench
enabler) and the one genuinely missing surface.

**Floor:** a NEW protected `seon.agent.shell` (+ `.internal`) — `child_process`
`execFile` (argv, NEVER `sh -c`), the fs cwd gate, the timeout + maxBuffer caps —
modeled exactly on `seon.agent.search.internal`. `my.shell` is the thin wrapper.

```clojure
(schema/register! :seon.shell/cmd        [:string {:min 1}])  ; argv[0]
(schema/register! :seon.shell/args       [:vector :string])   ; argv — never a shell string
(schema/register! :seon.shell/cwd        :seon.path/abs)       ; gated by the fs allowlist
(schema/register! :seon.shell/stdin      :string)
(schema/register! :seon.shell/timeout-ms :int)                 ; default 30000
(schema/register! :seon.shell/run-response
  [:or [:map [:seon.shell/ok? [:= true]] [:seon.shell/exit :int]
             [:seon.shell/out :string] [:seon.shell/err :string]
             [:seon.shell/timed-out? :boolean]]
       [:map [:seon.shell/ok? [:= false]] [:seon.error/message :string]]])

(defn ^:async run
  "Run a command (argv, no shell, no injection surface). ALWAYS resolves; ok? =
   exit 0. SIGTERM at timeout-ms (default 30s), bounded maxBuffer, windowsHide.
   The cwd is gated by the seon.agent.fs allowlist; default-deny until the host
   grants SEON_SHELL." ;; [:=> [:cat :seon.shell/run-request] :seon.shell/run-response]
  )

```

**Safety:** argv-only; cwd through `seon.agent.fs/stat`; timeout SIGTERM;
maxBuffer cap (honest `timed-out?`/truncation); `SEON_SHELL` host grant
(default-deny, same posture as `SEON_FS_*`). Soft boundary against LLM accidents,
not a security boundary. **Composes:** `:seon.shell/out` → transform →
`db/transact!`; `cwd` takes a `:seon.path/abs` from a listing/grep. **Budget:**
~1.2k tok (wrapper).

#### `my.todos` — FORMALIZE (rename) — floor: `seon.db` + the `:seon.agent.todo/*` schema

**Why reach for it:** so a resumed or distracted agent always sees what's left —
open items render every turn; an empty section is the done-signal.

**Floor:** `seon.db` (the engine) + the `:seon.agent.todo/*` entity schema. A todo
is just an entity; the wrapper holds the verbs + the owner-scope default, the db
engine does the durable write. (The schema can stay registered on a protected
seon ns or move with `my.todos` — recommend the verbs in `my.todos`, the schema
referencing the protected `:seon.db/ref`/`:seon.db/id` shapes.)

Surface (keep, renamed): `add!` → `{ok? id}`, `complete!`/`reopen!` (idempotent),
`list-open` → `{ok? items}`. Map-in/map-out, never-throws, owner-scoped to the
ALS agent. **Reshape (cosmetic):** add the `:seon.items/count`/`truncated?` mixin
to `list-open` (its vector already IS the items pattern). **Budget:** ~1.6k tok.

#### `my.test` — NEW lean facade (Q2) — floor: `seon.test.runner`

**Why reach for it:** close the define→eval→**verify** loop in one call — "did
the fn I just (re)defined pass its `:test`?" — and score an edit the way every
serious benchmark does: it fixed the broken case AND didn't break the working ones.

**Floor:** `seon.test.runner` — the engine (cljs.test capture, fixtures, stash,
DB projection). Heavy (~9.5k tok, 10 fns) — stays the protected engine; `my.test`
is the lean wrapper the agent sees. Authoring stays a COLOCATION convention
(`{:test (fn [] (is …))}` meta or `deftest` — no "register a test" call).

```clojure
(schema/register! :seon.test/pass?    :boolean)
(schema/register! :seon.test/summary  [:map [:seon.test/tests :int] [:seon.test/pass :int]
                                            [:seon.test/fail :int] [:seon.test/error :int]])
(schema/register! :seon.test/failures [:vector [:map [:seon.test/var :symbol]
                                                     [:seon.test/message :string]]])
(defn ^:async check
  "Run the tests for a fn/ns → {pass? summary failures}. The verify half of
   define→eval→verify: (test/check 'my.x/add)." )
;; The FAIL_TO_PASS / PASS_TO_PASS dual-set (benchmark survey §D) — score an edit:
(defn ^:async check-edit
  "Run a must-now-pass set + a must-still-pass set; report {fixed? regressed?}." )

```

**Smell to fix in the same patch:** `seon.test.runner`'s `::summary` uses BARE
keywords (`{:test :pass :fail :error}`) — a Data-Rules violation; namespace them.
**Render-rule (owner decision — see the dedicated note below):** the
per-namespace `:seon.test` block renders into context ONLY when the rendered ns
is the agent's CURRENT namespace — the agent sees + iterates its OWN tests, never
another ns's. **Composes:** `:seon.test/failures` carry `:seon.test/var` for a
follow-up `db/pull` of `:seon.test/source`. **Budget (facade):** ~900 tok.

#### `my.kb` — EXISTS, stays `my.*` (schema + worked domain) — floor: `seon.db`

**Why reach for it:** consult what's already known before researching, and persist
a verified fact with provenance — via `db/query` + `db/transact!` over a real
per-domain schema, NOT a generic memory store.

**Floor:** `seon.db`. `my.kb` is already exactly right: it registers the shared
provenance shapes (`:my.kb/source-path`, `/source-line`, `/source-line-end`,
`/verified-at`, `/confidence`) and documents the move — design a
`my.kb.<domain>` schema, then transact ONE row mixing domain attrs with the
shared `:my.kb/*` provenance. There is deliberately no `store!`/`consult` API
(Q1). **Budget:** ~700 tok (schema only). Optionally add one more worked domain
(`my.kb.paper`).

#### `my.code` — NEW: `forget!`, the missing symbol-lifecycle verb — floor: `seon.eval` + `seon.db`

**Why reach for it:** the agent can DEFINE and REDEFINE (= upsert) a
fn/schema/test, but has no way to REMOVE one — a wrong `(defn …)` lingers as a
live binding AND a `:seon.fn` row forever. `forget!` is the missing third verb of
define→redefine→forget, so the agent cleans up its own toolkit.

Every defined thing is an entity with a UNIQUE identity sym (`:seon.fn/sym`,
`:seon.schema/key`, `:seon.test/sym`), so ONE general verb covers all three:

```clojure
(schema/register! :seon.code/sym  :symbol)
(schema/register! :seon.code/kind [:enum :seon.fn :seon.schema :seon.test])
(schema/register! :seon.code/forget-response
  [:or [:map [:seon.code/ok? [:= true]] [:seon.code/sym :seon.code/sym]
             [:seon.code/kind :seon.code/kind]]
       [:map [:seon.code/ok? [:= false]] [:seon.error/message :string]]])
(defn ^:async forget!
  "Remove a symbol you defined: retract whichever entity owns it
   (:seon.fn/:seon.schema/:seon.test) AND drop the live binding (undef from
   globalThis + the analyzer, via the same compile-state your evals use). REFUSES
   a :core-seed sym (you cannot delete the protected floor). Errors are values.
   (forget! 'my.x/old-helper)" )

```

Behavior, grounded in the real surfaces:

- **Resolve the owning entity** by trying the three identity attrs in order;
  unknown sym → a legible `{:seon.code/ok? false :seon.error/message …}`.
- **Core guard — REUSE, do not reinvent.** `seon.eval/core-origin-fn-syms`
  returns the subset whose current source tx is `:seon.db/origin :core-seed`;
  `forget!` calls it (+ the schema sibling) and refuses a floor sym with the same
  message `reject-core-overrides` warns (`eval.cljs` ~L1702-1750). One provenance
  rule. Note: a `:toolkit-seed` `my.*` wrapper is NOT `:core-seed`, so it is
  forgettable — exactly the owned-tool semantics.
- **Retract the entity** with `[:db.fn/retractEntity [<identity-attr> s]]` — a
  normal `db/transact!`, so history retains every prior value.
- **Drop the live binding** the way result-var pruning does: undef from BOTH
  globalThis and the analyzer via the agent's compile-state (the mechanism at
  `eval.cljs` ~L724/L1035). Retracting the row without undef'ing leaves a callable
  ghost the agent can't see — both halves are load-bearing.

**Placement (recommended): the new tiny `my.code` ns**, refer'd into the home ns
beside the lifecycle verbs — symbol-lifecycle is its own concern and `my.code` is
its natural home if more verbs appear (e.g. `rename!`). It requires `seon.eval`
(the floor: compile-state + provenance guard + undef) and `seon.db` (retract). It
is itself a `my.*` editable wrapper over a protected floor — consistent with the
two-tier model. (Cheaper alternative — a public `forget!` ON `seon.eval` — buries
an agent verb in the heavy protected compiler and isn't in the home-ns set; the
`my.code` wrapper is cleaner.)

**UNDO is free — no new verb.** The store is bitemporal: re-transact the sym's
prior `:seon.fn/source` from `(db/history)` and re-eval it, or read `(db/as-of
t)` before the forget. A `restore!` verb is NOT worth it (it is one
`history`/`as-of` read + the agent's normal define move); document the recipe in
`db.examples`, add `restore!` only if forgets prove common AND error-prone in
live drives. **Composes:** bare sym OR `{:seon.code/sym 'my.x/foo}` → RESULT
envelope. **Budget:** ~900 tok.

#### `my.schedule` / `remind` — IN: the agent verb over the existing cron engine — floor: `seon.agent.schedule`

**Why reach for it:** "remind me every morning," "run this at 9am," "check X in an
hour" — the assistant-flavor capability a personal AI obviously needs (survey §D:
GAIA / tau-bench register).

**Floor:** `seon.agent.schedule` ALREADY has the hard part — the
`:seon.agent.schedule/*` entity (5-field cron + a qualified fn), the pure cron
logic (`parse`/`due?`/`next-fire-at`), and `fire-due-schedules!` (the schedule
half of the ONE ticker). The GAP is purely the agent-facing verb. `my.schedule`
is the thin wrapper that transacts a schedule entity onto the agent's
`:seon.agent/schedules`.

```clojure
(schema/register! :seon.agent.schedule/say :string)   ; what to surface when it fires
(defn ^:async add!
  "Add a schedule: a 5-field cron + what to do when due. Validated via parse
   (errors are values). (schedule/add! {:seon.agent.schedule/cron \"0 8 * * *\"
   :seon.agent.schedule/say \"morning check-in\"})" )
(defn list!   [_]  #_"your schedules → the :seon.items/items envelope")
(defn ^:async cancel! [m] #_"{:seon.agent.schedule/id …} → retract one")
(defn ^:async remind! [m] #_"sugar: a daily/one-shot wake with :say text")

```

**Honest caveat (read `seon.agent.schedule` ~L224-228):** firing TODAY opens +
drives a `:schedule` run (the wake), but running the schedule's fn IN THE AGENT
SANDBOX is DEFERRED pending the one-exec-service routing. So `remind!` (wake +
`:say` surfaces in the woken run's context) works now; "every morning, run THIS
fn" is the same verb, fn-exec landing with that routing. The verb shape is
forward-compatible — ship it. **Recommend IN.** **Budget:** ~1.2k tok (verb only).

#### `my.recall` — IN: semantic (KNN) retrieval — floor: `seon.embed`

**Why reach for it:** find what's stored BY MEANING, not exact attr/keyword —
"what do I know about the user's sleep?" matching a row that never said "sleep."
The read surface behind the gym's `s32` "seeded-claim-not-found" finding (the
agent re-researches because exact datalog missed a semantically-relevant fact).
This is categorically NOT the rejected CRUD facade: that wrapped
`transact!`/`query` (which datalog already does); THIS expresses
nearest-by-meaning, which datalog CANNOT.

**Floor:** `seon.embed` — pod-side, READ-ONLY (`search`/`search-pull`). The pod
never embeds; it ships `{query, k, eids}` over the UDS to the JVM wire-server,
which embeds the query (retrieval prefix) + runs HNSW KNN, returning
`{:seon.embed/hits [{:seon.embed/eid :seon.embed/distance :seon.embed/entity}
…]}` distance-ascending. Gated by `SEON_EMBED`.

```clojure
(schema/register! :seon.recall/query :string)
(schema/register! :seon.recall/k     :int)         ; default seon.embed default-k
(defn ^:async recall
  "Semantic KNN over your store: nearest entities to QUERY by meaning. Returns the
   :seon.items/items envelope (each item the pulled entity + its distance). Gated
   by SEON_EMBED; when OFF, a legible ok?-false pointing you at store-inventory +
   datalog/grep — never an error. (recall {:seon.recall/query \"user sleep\"})" )

```

The wrapper's two jobs over `seon.embed/search-pull`: reshape `:seon.embed/hits`
→ the `:seon.items/*` envelope (so hits thread into `pull`/`transact!`), and
handle `SEON_EMBED` off as a graceful ok?-false fallback. Mark **FORMALIZE** — the
engine exists; the lean wrapper + items-envelope + off-fallback is the gap.
**Recommend IN.** **Budget:** ~900 tok.

#### `my.tile` — NEW lean facade (agent-facing call only) — floor: `seon.render.live-tile` + `seon.ui.components`

**Why reach for it:** show the human a finished view — a card, a pros/cons, a
recommendation — with ONE call and zero hiccup authoring; the agent says what it
MEANS, the human sees the picture.

**Floor / cross-lane:** the Layer-2 facade from
`docs/prds/agent-fsm/ux-toolkit-proposal.md` — Session U owns the Layer-1
`seon.ui.components` hiccup + the markdown path; `my.tile` is the agent-facing
call. It writes by transacting onto the agent's `:seon.render.live-tile/content`
(the welcome-wiring move in `seon.render.live-tile`) — a literal hiccup (built
from a U component) bypasses SCI; a fn symbol late-resolves.

```clojure
(schema/register! :seon.tile/view [:or :keyword :symbol])  ; a prebuilt view key OR your own fn sym
(defn ^:async show!
  "Set your live tile to a prebuilt VIEW rendered with DATA — transacts the built
   hiccup onto :seon.render.live-tile/content. (tile/show! {:seon.tile/view
   :pros-cons :seon.tile/data {:seon.ui/title \"…\" …}})" )
(defn ^:async card!      [m] #_"{:seon.ui/title :seon.ui/body :seon.ui/tone}")
(defn ^:async pros-cons! [m] #_"{:seon.ui/title :seon.ui/pros [..] :seon.ui/cons [..]}")
(defn ^:async recommend! [m] #_"{:seon.ui/recommendation :seon.ui/rationale :seon.ui/options [..]}")

```

**Composes:** `:seon.tile/data` is plain namespaced data the agent already has (a
`store-inventory` row, a `query` result) — show it without rendering it. For
dynamic tiles, `:seon.tile/view` is a fn SYMBOL (the agent's own
hiccup-returning fn), so the tile re-derives every render. **Budget:** ~1k tok.

#### `my.blob` — IN but DEFERRED: the third storage tier — floor: `seon.blob` (NEW)

**Why reach for it:** persist LARGE content that doesn't belong in datoms — a
benchmark run's full output, a scraped PDF, anything big the agent's domain code
produces — keeping only a hash + small projection in the DB. The `Blobs` tier of
the load-bearing three-tier storage rule (DB datoms = small indexed projections;
Blobs = persistent full content; globalThis stash = volatile per-session values).

**Floor:** a NEW protected `seon.blob` (content-addressed, on-disk zstd per the
tier spec). `my.blob` would be the thin `put!`/`get` wrapper, storing the hash on
a typed projection entity.

```clojure
(defn ^:async put! [m] #_"{:seon.blob/content \"…\"} → {:seon.blob/ok? true :seon.blob/hash \"…\"}")
(defn ^:async get  [m] #_"{:seon.blob/hash \"…\"} → {:seon.blob/ok? true :seon.blob/content \"…\"} | not-found")

```

**Verdict: IN but DEFERRED — the honest call.** `seon.blob` does NOT exist in
`src/` today (only the three-tier DESIGN doc describes it; the wire has
content-addressing infra but no agent-facing blob verb). The design is settled
and the need is real, but NOTHING currently needs it: the core
define→eval→test→persist loop fits in datoms + the globalThis stash (the test
runner exemplifies "full payload in the stash, projection in the DB"). Building a
store with no consumer is speculative. **Recommend: spec it now, build it WITH its
first consumer** (the benchmark-output harness or doc-ingest) so the on-disk
format is shaped by a real payload. **Budget (when built):** ~800 tok.

## The tests-render-rule (owner decision — for the prompt/render work)

A RENDER behavior, not a utility surface — recorded here so the toolkit spec is
complete and the prompt/render owner picks it up.

**Rule:** the per-namespace `:seon.test` block renders into the agent's context
ONLY for the agent's CURRENT namespace; never for any other rendered ns. The
agent sees and iterates ITS OWN tests (the verify half of the loop) without other
nses' tests bloating the prompt.

- **Where it lives:** the namespaces-section / `render-namespace` path
  (`seon.ctx.namespaces` + `seon.ctx/render-namespace`). That code already
  curates which nses render full (my.*, third-party, the CURRENT ns, a small
  `seon.*` whitelist) and renders `:seon.fn`/`:seon.schema`/`:seon.test` per kind.
  The change is narrow: gate the `:seon.test` per-kind emission on
  `ns == current-ns` (current-ns is already derived from the latest
  `:seon.eval/ns` datom — `seon.agent/current-ns`).
- **Coordination:** flag this to the in-flight **GI-1 double-render fix** — both
  touch the per-kind render in `render-namespace`; land them together so the
  test-gate and the de-dup don't fight. A one-conditional change, not a new
  mechanism (reactive-context: the block simply doesn't render off-current-ns).

## Build / REPL-test order

Each unit is independently evaluable against the live pod — define, eval, read the
value back. Order follows the dependency graph; the backbone shapes + the
`:toolkit-seed` origin come first because every wrapper depends on them.

1. **Backbone shapes + `:toolkit-seed` origin** — register `:seon.path/*`,
   `:seon.result/ok?`, `:seon.items/*` on the floor; confirm `:seon.error/*` +
   `:seon.db/ref` referenceable; add the `:toolkit-seed` boot-index origin for
   `my.*` nses (so `core-origin-fn-syms` leaves them editable). Test:
   `(schema/registered? :seon.path/located)`; a `(defn my.files/x …)` redef
   persists (not core-guarded) while `(defn seon.agent.fs/read-file …)` is
   refused.
2. **`my.files`** — rename wrapper over `seon.agent.fs`; reshape paths +
   located-item listings. Test: `(files/list-dir {…})` returns located maps;
   `(-> (files/walk-dir {…}) :seon.items/items first files/stat)` threads.
3. **`my.search`** — rename wrapper over `seon.agent.search`; match →
   `:seon.path/located`, items mixin, error → map. Test:
   `(->> (search/grep {:seon.search/pattern "defn"}) :seon.items/items (map
   files/read-file))` runs with no rekey. (Proves the headline fix.)
4. **`my.shell` + the `seon.agent.shell` floor** (NEW) — child_process execFile,
   fs cwd gate, timeout. Test: `(shell/run {:seon.shell/cmd "echo"
   :seon.shell/args ["hi"]})` → `{ok? exit 0 out "hi\n"}`; `timeout-ms 1` on
   `sleep` → `timed-out? true`.
5. **`my.test`** (facade over `seon.test.runner`) — namespace the engine summary
   in the same patch. Test: `(test/check 'seon.db-test/…)` → `{pass? summary
   failures}`; `check-edit` over a known fail+pass pair.
6. **`my.todos`** — rename wrapper; add the items mixin to `list-open`.
7. **`message` floor smell** — failure envelope → own-ns ok? + `:seon.error/*`.
8. **`my.code` / `forget!`** (NEW) — over `seon.eval`'s guard + undef. Test:
   `(forget! 'my.x/foo)` retracts the row AND the binding (a follow-up `foo` is
   undeclared); `(forget! 'seon.db/query)` → ok?-false (floor-guarded); the undo
   recipe restores it.
9. **`my.schedule` / `remind`** (verb over the existing engine). Test:
   `(schedule/add! {:seon.agent.schedule/cron "0 8 * * *" …})` transacts a
   schedule; the ticker fires it (already proven).
10. **`my.recall`** (over `seon.embed`). Test (SEON_EMBED on): items envelope of
    pulled entities; off → graceful ok?-false fallback.
11. **`lifecycle`** — no code change; add the "keyword ⇒ ok" docstring note.
12. **`my.tile`** (facade) — depends on U's `seon.ui.components` +
    `seon.render.live-tile`. Test: `(tile/card! {…})` transacts literal hiccup.
13. **`db.examples`** — write the canonical chains (incl. the `forget!` undo
    recipe) once the above are stable.
14. **`my.blob` + the `seon.blob` floor** (DEFERRED) — build only with its first
    consumer (benchmark output / doc-ingest).
15. **`my.kb`** — no build (convention only); optionally add a worked
    `my.kb.<domain>`.

## Candidate verdicts (summary)

| Candidate | Verdict | Floor | Why |
|---|---|---|---|
| `my.code` / `forget!` | **IN — build** | `seon.eval` + `seon.db` | The missing third lifecycle verb; one general retract+undef over the unique sym; core-guarded by the existing `:core-seed` check; undo is free (bitemporal). |
| `my.schedule` / `remind` | **IN — build the verb** | `seon.agent.schedule` | Cron entity + ticker already exist; only the agent verb is missing; core assistant capability. `remind!` works today; "run THIS fn" lands with the one-exec-service routing. |
| `my.recall` (semantic KNN) | **IN — formalize** | `seon.embed` | Read-only nearest-by-meaning over the existing wire KNN; a capability datalog can't express; the surface behind the `s32` finding. Distinct from the rejected CRUD facade. |
| `my.blob` | **IN — but DEFERRED** | `seon.blob` (NEW) | The third storage tier is sound + specified, but the floor isn't built and has no consumer; build it with its first real payload, not speculatively. |
| `kb` CRUD facade (`remember!/recall/forget`) | **OUT** | — | Duplicates `transact!`/`query`, re-grows the memory-blob anti-pattern, hides the schema-design skill (Q1). |

## The two open questions, decided

### Q1 — A `kb` CRUD facade (`remember!/recall/forget` over `my.kb`)? — NO.

(Distinct from the catalog's real `forget!` — a general symbol-RETRACT verb in
`my.code`, not a kb write — and the real semantic `my.recall` over `seon.embed`.
This rejects a CRUD wrapper around `my.kb` writes/reads.) Four grounded reasons:

1. **It duplicates `db`.** `remember!` is `transact!` with a stamped
   `:my.kb/verified-at`; `recall` is `query`; `forget` is a retract. The
   Don't-Be-A-Dumbass rule (one mechanism) and reactive-context doctrine (derive,
   don't add a subsystem) both say no.
2. **It re-grows the anti-pattern `my.kb` forbids.** A `remember!(text)` API
   biases toward a generic memory-blob; `my.kb`'s docstring explicitly says "do
   NOT build a general memory-markdown structure" — knowledge lives in
   `my.kb.<domain>` sub-namespaces with REAL schemas.
3. **It hides the skill that IS the product.** Designing a `my.kb.<domain>` schema
   is the same skill as modeling the human's data. `store-inventory` + `query`
   ("consult FIRST") is already the discoverable recall path, vocabulary-agnostic
   (the gym's `reuses-schemas` axis grades exactly this).
4. **The one real win is already covered.** Auto-stamping provenance is a
   three-line worked example, not an API — and `my.kb` already shows it.

Keep `my.kb` as the registered provenance shapes + the worked-domain exemplar; the
knowledge base's API is `seon.db`.

### Q2 — `test` as its own ns, or `:test`-meta + a runner verb? — Its own LEAN `my.test` facade.

Both halves are true and don't conflict: authoring stays a COLOCATION CONVENTION
(`{:test (fn [] …)}` or `deftest` — no "register a test" call), AND the agent gets
a small `my.test` facade because:

1. **The loop needs a one-call verify.** `(test/check 'my.x/add)` → `{pass?
   summary failures}` is define→eval→**verify** in one line; the
   `seon.test.runner` engine (10 fns, ~9.5k tok) is a floor, not a context surface.
2. **The dual-set shape is a recurring capability (survey §D).** "A test runner
   with structured pass/fail" recurs across SWE-bench/Aider/Commit0/SWE-Lancer/
   Terminal-Bench; the GAP flagged is the FAIL_TO_PASS/PASS_TO_PASS dual-set —
   `check-edit` gives it natively.
3. **It fixes a live smell.** Building the facade forces namespacing the engine's
   bare-keyword `::summary`, a Data-Rules violation, in the same patch.

So: a lean `my.test` ns (editable wrapper) over the protected `seon.test.runner`
engine; authoring is meta/deftest with no extra ceremony.
