---
type: prd
status: active
tags: [prd, agent]
---

# Agent toolkit — the `my.*` verb catalog over a protected `seon.*` floor

> **Target design** (present tense — the system as it is when built). Current code state + the migration path live in [[roadmap]].

The agent's whole working surface is a SMALL set of namespaces that, shown in
full, ARE its context — a few high-signal, threadable verbs instead of a 70k-char
source dump. The agent's tools live in **`my.*` namespaces, fully agent-owned and
editable** (`my.files`, `my.search`, `my.shell`, `my.todo`, `my.test`, `my.kb`,
`my.code`, `my.schedule`, `my.recall`, `my.tile`, `my.blob`). Each is a THIN
wrapper over a protected `seon.*` substrate — the real syscalls, db engine,
compiler, and wire stay `seon.*` and are `:core-seed`-guarded (un-clobberable).
Build-your-environment extends to the tools themselves: the agent tweaks
`my.files`, and if it breaks a wrapper, the protected floor still stands and
`forget!` / the bitemporal store recover it.

This doc owns the verb catalog (the action surface). The data shapes those verbs
read and write — `:my.kb.*`, `:my.todo/*`, `:my.agent/*`, the `:seon/error`
value, the entity-kind-vs-value-enum rule — live in [[data-model]]. The block /
render / tile / slot machinery the verbs surface into lives in [[ui]]. The loop,
the run, `start!`, and isolation tiers live in [[agent-runtime]].

## TL;DR

- Seon's agent does not edit files; it DEFINES functions, evals them, redefines
  (= upserts), composes, and tests in a live REPL, with code and knowledge
  persisting as datoms. Every verb is a REPL one-liner whose value is data the
  agent reads back and threads onward.
- **Two tiers.** A PROTECTED FLOOR (`seon.*`, `:core-seed`-guarded): the db
  engine (`seon.db`, aliased `db`), the compiler (`seon.eval`), the loop's
  control verbs (`seon.agent.message`, `seon.agent.lifecycle`, and root's
  `seon.agent/start!`), and the `*.internal` syscall namespaces + the wire. An AGENT-OWNED
  TOOLKIT (`my.*`, editable thin wrappers) over it.
- **Shared cluster-wide.** The `my.*` toolkit is ONE seeded definition the user's
  agents collectively evolve — short reflexive names (`(files/read-file …)`),
  one indexed+rendered copy. The per-agent home ns `my.agent.<id>` carries that
  agent's purpose and its own defns.
- **The action surface threads.** Four shared shapes (`:seon.path/*`,
  `:seon.db/ref`, `:seon.items/*`, the `<ns>/ok?` + `:seon/error` envelope) make
  the output of one verb a valid input to the next with no reshaping at the arrow.
- **Three lifecycle verbs for code:** define → redefine (= upsert) → `forget!`.
  `forget!` retracts the owning entity AND drops the live binding, core-guarded;
  undo is free from the bitemporal store.

## The two tiers — protected floor vs. owned toolkit

A namespace is `my.*` (owned, editable) iff redefining it cannot break a runtime
invariant; it is `seon.*` (protected, `:core-seed`-guarded) iff it is
load-bearing for the substrate's correctness.

| Tier | Namespaces | Origin | Agent may edit? | Renders full in context? |
|---|---|---|---|---|
| **Protected floor** | `seon.db` (aliased `db`), `seon.eval`, `seon.agent.message` (aliased `message`), `seon.agent.lifecycle` (refer'd verbs) + root's `seon.agent/start!`, the `*.internal` syscall nses + the wire | `:core-seed` | NO — `forget!`/override guard refuse | NO — indexed + grep-able only |
| **Owned toolkit** | `my.files`, `my.search`, `my.shell`, `my.todo`, `my.test`, `my.kb`, `my.code`, `my.schedule`, `my.recall`, `my.tile`, `my.blob` | `:toolkit-seed` → `:agent` on first edit | YES — redefine or `forget!` | YES — full source every turn |

`message` and `lifecycle` stay on the floor because they are the loop's control
verbs — the wake gate / hop-cap (`message!`) and the run-FSM mutations
(`wait`/`complete`/`pause`/`resume`/`terminate`) plus the spawn verb
(`seon.agent/start!`, an alias of `create!`).
The agent talks THROUGH them (aliased/refer'd into its home ns), it does not own
them.

**The protection mechanism.** The override guard `seon.eval/core-origin-fn-syms`
blocks redefining or `forget!`-ing any symbol whose current `:seon.fn/source` tx
carries `:seon.db/origin :core-seed`. So:

- The floor's syscall fns are `:core-seed` → un-clobberable. An agent that
  `(defn seon.agent.fs/read-file …)` is refused with the actionable warning and
  the row is not persisted.
- The `my.*` wrappers are seeded under `:toolkit-seed` (NOT `:core-seed`), so the
  guard leaves them editable and forgettable; an agent's edit flips the row's
  origin to `:agent`. The distinction is one origin keyword on the seed, not a
  separate mechanism.

**Recovery is free.** Break `my.files/read-file` and: `(forget!
'my.files/read-file)` removes the broken def; or re-transact its prior
`:seon.fn/source` from `(db/history)`; or a `bin/seon cluster reset` re-seeds the
shipped default toolkit. A broken wrapper is never fatal — the protected floor
underneath it is intact.

## Shared cluster-wide ownership

The `my.*` toolkit is ONE cluster-wide seeded definition; all the user's agents
see and evolve the same `my.files`. Seon is a personal, single-user cluster —
shared toolkit evolution is the intended dynamic, not a leak between distrusting
tenants. The short name is the point: `my.files` is catchy and reflexive
(`(files/read-file …)` via the home-ns alias). The only real hazard — a broken
wrapper — cannot touch the `:core-seed` floor and is recoverable. And it is
leaner: one seeded, indexed, and rendered toolkit, not N identical copies
multiplying render cost on every agent's every turn.

Two `my.*` axes do NOT collide:

- **Shared fn definitions** — `my.files`/`my.search`/… are one `:seon.fn` corpus
  the whole cluster calls.
- **Scoped data** — global vs per-agent is the DATA's agent-ref, never the ns and
  never a stored kind: `:my.kb.*` rows carry no ref → global (one KB, all
  agents); `:my.todo/*` rows carry `:my.todo/agent` → per-agent (each sees its
  own). The verb's render fn scopes by what it queries. Full scoping rule:
  [[data-model]].

The toolkit renders FULL every turn (the build-your-environment payoff — the
agent SEES and edits its toolkit source), which is why the wrappers stay thin:
rendering them whole is cheap precisely because they delegate the bulk to the
protected floor, which is indexed + grep-able only, never rendered full. The
per-tool budgets below are a FIXED cluster-wide context cost — keep them tight.
The full render-curation rule (index everything, render `my.*` whole) lives in
[[data-model]].

The per-agent home ns is **`my.agent.<id>`** — the one place a single agent's own
state lives: its `:my.agent/purpose`, its `refine` fn, its self-refining purpose
block, and any `defn`s it authors for itself. See `my.agent` in the catalog.

## The composability backbone — four shared shapes

The rule the whole catalog is held to: **the output of one verb is a valid input
to the next, with no reshaping at the arrow.** A small set of shapes that many
wrappers reference (the register-once rule) carries the threading. Tool-specific
payload keys are `my.<tool>/*`; the shapes you THREAD are `seon.*`. There are
exactly four.

### 1. PATH — `:seon.path/*` (the files ↔ search ↔ shell hinge)

One canonical path vocabulary on the floor, referenced by `my.files`,
`my.search`, and `my.shell`, so a grep hit feeds `read-file` directly:

```clojure
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

### 2. REF — `:seon.db/ref` (db addressing)

A lookup-ref `[identity-attr value]` (or a raw eid) is the universal "address of a
thing," canonical on the floor (`seon.schema`) and used uniformly:
`:my.todo/agent`, `:seon.agent.message/from`/`/to`, `:seon.agent/parent`. The
output of one verb is the input to the next — a message's `from` is a ref you pass
straight to `db/entity`, to `my.todo/add!`'s owner, or to `message/agent`.

```clojure
[:seon.agent/id "iCg-2606101519"]     ; a ref — addresses an agent
(db/entity {:seon.db/ref [:seon.agent/id "iCg-2606101519"]})   ; threads in
```

### 3. ITEMS — `:seon.items/*` (self-describing collections)

A collection result is `{<ns>/ok? + a vector of SELF-DESCRIBING MAPS + count +
truncated?}`. Every item is a map carrying enough keys to BE the next call's
input — a listing entry IS a `:seon.path/located` (feeds `stat`/`read`), a grep
match IS one, a todo IS addressable.

```clojure
(schema/register! :seon.items/items      [:vector :map])  ; each item self-describing
(schema/register! :seon.items/count      :int)
(schema/register! :seon.items/truncated? :boolean)
;; Mixin (referenced, never re-inlined):
;;   {<ns>/ok? true :seon.items/items [<map> …] :seon.items/count <int>
;;    :seon.items/truncated? <bool>}
```

Counts/aggregates are NOT items — they stay scalars.

### 4. RESULT — `<ns>/ok?` + `:seon/error` (the never-throw envelope)

Every agent-facing verb returns an envelope and never throws (errors are values).
The envelope is `{<ns>/ok? <bool> …}`; on failure `{<ns>/ok? false :seon/error
<error value>}`. The discriminator namespace tracks the owning data ns
(`:seon.db/ok?`, `:my.files/ok?`, …), each referencing one shared
`:seon.result/ok? :boolean` shape. The error value is the one `:seon/error`
base — `:seon.error/message` (humanized via `malli.error/humanize`) +
`:seon.error/data` (the malli explain map) + where/symbol/hint, plus the
value-enum fault tag `:seon.error/kind :user-input|:core-bug` the agent reads to
decide "fix my args" vs "report it." `:seon.error/kind` is a value flavor on a
non-entity error value, not a stored entity discriminator. The full `:seon/error`
shape + the entity-kind-vs-value-enum rule live in [[data-model]].

```clojure
(schema/register! :seon.result/ok? :boolean)   ; the shared discriminator shape
```

Two specialized values keep their own shape because the value IS the answer:

- **my.shell** — `{:seon.shell/ok? :seon.shell/exit :seon.shell/out
  :seon.shell/err :seon.shell/timed-out?}`.
- **my.test** — `{:seon.test/pass? :seon.test/summary :seon.test/failures}`.
- **lifecycle** — a bare `:seon.derive/state` keyword on success (the derived
  state IS the answer), the envelope only on failure.

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

Every arrow is total — no rekey anywhere.

## The catalog

Each entry carries the one-line reason the agent reaches for it, its `seon.*`
floor backing, the public surface as malli-ish sketches, how it composes, and a
render budget (tokens, chars/4 — keep the FULL source cheap to show, since `my.*`
renders whole every turn for every agent).

### Protected floor — `seon.*` (aliased/refer'd, not owned)

#### `db` — `seon.db`, aliased `db`

The engine: one bound conn, synchronous reads, a never-throwing write envelope.
A thin wrapper would buy nothing and put the agent's most-used,
correctness-critical surface one editable layer away from the real
datahike/wire boundary — so `db` stays `seon.db`, `:core-seed`-guarded.

Surface: `new-id!`, `transact!`, `query`, `pull`, `entity`,
`as-of`/`since`/`history`, `listen!`/`unlisten!`, `store-inventory`,
`installed-schema`. **Composes:** `store-inventory` → `query`; `pull`/`entity`
emit maps; `transact!`'s `:seon.db/tempids` resolves new refs. **Budget:** ≤ 2.5k
tok — `db` is the one heavy ns; render its docstring + cheat sheet + verb
signatures, keep guard/bridge bodies in `seon.db.internal`. The worked DB chains
and the cheat sheet live in `my.kb` (the DB manual), not a separate examples ns.

#### `message` — `seon.agent.message`, aliased `message`

The single write path for `:seon.agent.message` rows, coupled to the wake gate
(`waking-inbound?`) and the hop-cap. Surface: `message!`, `user`, `agent`
(refuses self→self), `waking-inbound?`/`hop-live?`, `user-ref`. Success returns
`{:seon.agent.message/ok? true …}`; failure returns `{:seon.agent.message/ok?
false :seon/error …}` (own-ns `ok?` + the shared error value). **Budget:** ~3k
tok.

#### `lifecycle` — `seon.agent.lifecycle`, refer'd verbs

`wait`/`complete`/`pause`/`resume`/`terminate` are run-FSM mutations the agent
makes on its OWN run; the agent's state is DERIVED from those primitives via
`seon.derive/derive-state`, never stored. They return a bare `:seon.derive/state`
keyword on success ("keyword ⇒ ok, map ⇒ error"), the envelope only on failure.

`seon.agent/start!` is the lifecycle SPAWN verb — a core verb GRANTED to root (and to any
agent holding the spawn capability), an alias of `create!`, called through the
SAME `/call` capability gate. It transacts an IDLE child agent and writes
`:seon.agent/parent` = the caller; the child's quiet bootstrap runs and leaves it
IDLE — a message is its first trigger. Roles are capability-SETS (which `:seon.fn`s
are granted + which bootstrap ran), differentiated by Datomic presence/absence at
the gate, NOT a stored `:kind`/`:role`. The full lifecycle — `:seon.agent/parent`,
roles-as-capabilities, the root base case, bootstrap-as-seeded-forms — lives in
[[agent-runtime]]. **Budget:** ~1.8k tok.

### Owned toolkit — `my.*` (thin, editable wrappers)

#### `my.agent` — the per-agent home ns `my.agent.<id>`

**Why it exists:** the one place a single agent's own state and code live. It
carries `:my.agent/purpose` (a markdown goal string), a `refine` fn, a
self-refining purpose block, and the `defn`s the agent authors for itself. It is
the first per-agent seed worked-example: the bootstrap registers the
`:my.agent/purpose` schema + the refine fn + the block, so the agent OWNS and SEES
its own purpose and can rewrite it. The schema lives in [[data-model]];
purpose-as-seed and the quiet bootstrap that installs it live in [[agent-runtime]].
**Budget:** the home ns renders full every turn — keep authored fns lean.

#### `my.files` — floor: `seon.agent.fs`

**Why reach for it:** the agent's eyes and hands on the user's machine, gated by
an allowlist — read, list, stat, walk — without leaving the REPL.

**Floor:** `seon.agent.fs` (+ `seon.agent.fs.internal`) — the protected node:fs
syscalls + the `SEON_FS_*` allowlist gate. The agent shapes how `my.files`
returns results but cannot disable the allowlist (that lives on the floor).

Surface: `grants`, `configure!`, `read-file` (paged `from-line`/`max-lines` +
honest totals), `write-file`, `list-dir`, `walk-dir`, `stat`, `file-exists?`,
`home-dir`. Map-in/map-out, never-throws, default-deny. `read`/`stat`/`write`
requests reference `:seon.path/abs`; `list-dir`/`walk-dir` entries are
`:seon.items/items` of `:seon.path/located` (each `{:seon.path/abs …
:my.files/dir? …}`), so a located item from `grep` or a listing threads straight
into `read`/`stat`/`grep`. **Budget:** ~2k tok.

#### `my.search` — floor: `seon.agent.search`

**Why reach for it:** find where something lives by CONTENT (ripgrep) and land on
absolute, allowlisted hits that feed `read-file` with zero guessing.

**Floor:** `seon.agent.search` (+ `.internal`) — the protected ripgrep `execFile`
plus the fs-allowlist gate (reuses `seon.agent.fs`, so search and read agree on
reach). Surface: `grep` → `{ok? items count truncated?}`; a match IS a
`:seon.path/located` (`:seon.path/abs` + `:seon.path/line` + `:seon.path/preview`).
`^:async`, never-throws. Then `(map files/read-file matches)` just works.
**Budget:** ~1.5k tok.

#### `my.shell` — floor: `seon.agent.shell`

**Why reach for it:** run a real command — a formatter, a one-off `node`/`python`
script, a `git` query — and get `{exit out err}` back as data.

**Floor:** the protected `seon.agent.shell` (+ `.internal`) — `child_process`
`execFile` (argv, NEVER `sh -c`), the fs cwd gate, the timeout + maxBuffer caps.

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
(default-deny, same posture as `SEON_FS_*`). A soft boundary against LLM
accidents, not a security boundary. **Composes:** `:seon.shell/out` → transform →
`db/transact!`; `cwd` takes a `:seon.path/abs` from a listing/grep. **Budget:**
~1.2k tok.

#### `my.todo` — floor: `seon.db` + the `:my.todo/*` schema

**Why reach for it:** so a resumed or distracted agent always sees what's left —
open items render every turn; an empty list is the done-signal.

**Floor:** `seon.db` (the engine) + the `:my.todo/*` entity schema. A todo is just
an entity; the wrapper holds the verbs + the owner-scope default, the db engine
does the durable write.

Surface: `add!` → `{ok? id}` (accepts an optional `:my.todo/parent` ref),
`complete!`/`reopen!` (idempotent), `list-open` → the `:seon.items/*` envelope.
Map-in/map-out, never-throws, scoped per-agent by the `:my.todo/agent` ref.

**Planning IS the todo tree.** A todo's `:my.todo/parent` ref makes the work-list
a plan tree (top = plans/milestones, leaves = actions); a parent's progress is a
DERIVED roll-up of its children's status — there is no separate plan system. The
`:my.todo/*` schema, the tree shape, the roll-up derivation, and per-agent scoping
live in [[data-model]]. **Budget:** ~1.6k tok.

#### `my.test` — floor: `seon.test.runner`

**Why reach for it:** close the define→eval→**verify** loop in one call — "did the
fn I just (re)defined pass its `:test`?" — and score an edit the way every serious
benchmark does: it fixed the broken case AND didn't break the working ones.

**Floor:** `seon.test.runner` — the engine (cljs.test capture, fixtures, stash, DB
projection) stays the protected floor; `my.test` is the lean wrapper. Authoring is
a COLOCATION convention (`{:test (fn [] (is …))}` meta or `deftest` — no "register
a test" call).

```clojure
(schema/register! :seon.test/pass?    :boolean)
(schema/register! :seon.test/summary  [:map [:seon.test/tests :int] [:seon.test/pass :int]
                                            [:seon.test/fail :int] [:seon.test/error :int]])
(schema/register! :seon.test/failures [:vector [:map [:seon.test/var :symbol]
                                                     [:seon.test/message :string]]])
(defn ^:async check
  "Run the tests for a fn/ns → {pass? summary failures}. The verify half of
   define→eval→verify: (test/check 'my.x/add)." )
;; Score an edit with the FAIL_TO_PASS / PASS_TO_PASS dual-set:
(defn ^:async check-edit
  "Run a must-now-pass set + a must-still-pass set; report {fixed? regressed?}." )
```

A namespace's `:seon.test` colocated tests render into context ONLY for the
agent's CURRENT namespace — the agent sees and iterates its OWN tests, never
another ns's (the render-curation rule lives in [[data-model]]). **Composes:**
`:seon.test/failures` carry `:seon.test/var` for a follow-up `db/pull` of
`:seon.test/source`. **Budget:** ~900 tok.

#### `my.kb` — floor: `seon.db`

**Why reach for it:** consult what's already known before researching, and persist
a verified fact with provenance — via `db/query` + `db/transact!` over a real
per-domain schema, NOT a generic memory store.

**The knowledge base's API IS `seon.db`.** There is deliberately no
`remember!`/`recall`/`forget` CRUD facade: `remember!` would just be `transact!`
with a stamped `:my.kb/verified-at`, `recall` is `query`, and a CRUD wrapper
re-grows the memory-blob anti-pattern while hiding the schema-design skill that IS
the product. Designing a `my.kb.<domain>` schema is the same skill as modeling the
human's data; `store-inventory` + `query` is the discoverable recall path.

`my.kb` registers the shared provenance shapes (`:my.kb/source-path`,
`/source-line`, `/source-line-end`, `/verified-at`, `/confidence`); domain
knowledge lives in `my.kb.<domain>` schemas, each row mixing domain attrs with the
shared `:my.kb/*` provenance. KB rows carry no agent-ref → the KB is GLOBAL (one
base all agents share). `my.kb` is also the **DB manual** — it carries the worked
`db` chains and the cheat sheet the agent consults (the role a separate examples
ns would otherwise play). The `:my.kb.*` schemas + global scoping live in
[[data-model]]. **Budget:** ~700 tok (schema + the worked chains).

#### `my.code` — floor: `seon.eval` + `seon.db`

**Why reach for it:** the agent can DEFINE and REDEFINE (= upsert) a
fn/schema/test, but otherwise has no way to REMOVE one — a wrong `(defn …)`
lingers as a live binding AND a `:seon.fn` row. `forget!` is the missing third
verb of define→redefine→forget, so the agent cleans up its own toolkit.

Every defined thing is an entity with a UNIQUE identity sym (`:seon.fn/sym`,
`:seon.schema/key`, `:seon.test/sym`), so ONE general verb covers all three:

```clojure
(schema/register! :seon.code/sym  :symbol)
;; :seon.code/kind is a DERIVED response label, NEVER stored on any row.
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

Behavior:

- **Resolve the owning entity** by which identity attr the sym carries; an
  unknown sym → a legible `{:seon.code/ok? false :seon.error/message …}`.
- **Core guard.** `forget!` calls `seon.eval/core-origin-fn-syms` (+ the schema
  sibling) and refuses a floor sym whose source tx is `:seon.db/origin
  :core-seed`. A `:toolkit-seed`/`:agent` `my.*` wrapper is NOT `:core-seed`, so
  it is forgettable — the owned-tool semantics.
- **Retract the entity** with `[:db.fn/retractEntity [<identity-attr> s]]` — a
  normal `db/transact!`, so history retains every prior value.
- **Drop the live binding** by undef'ing from BOTH globalThis and the analyzer via
  the agent's compile-state. Retracting the row without undef'ing would leave a
  callable ghost — both halves are load-bearing.

**`:seon.code/kind` is a DERIVED response label, never stored.** The forget
response names which kind was removed, and that value is COMPUTED from which
identity attribute the entity carried — `:seon.fn/sym` present → `:seon.fn`,
`:seon.schema/key` → `:seon.schema`, `:seon.test/sym` → `:seon.test`. Its enum
values ARE those namespace keywords. No row ever stores a `:seon.code/kind` field;
it is a value-enum label produced at return time, exactly the
entity-kind-vs-value-enum distinction (a stored field that selects a row's schema
is banned; a derived/value label is fine — [[data-model]]).

**Undo is free — no new verb.** The store is bitemporal: re-transact the sym's
prior `:seon.fn/source` from `(db/history)` and re-eval it, or read `(db/as-of t)`
before the forget. The recipe lives in `my.kb` (the DB manual). **Composes:** a
bare sym OR `{:seon.code/sym 'my.x/foo}` → the RESULT envelope. **Budget:** ~900
tok.

#### `my.schedule` — floor: `seon.agent.schedule`

**Why reach for it:** "remind me every morning," "run this at 9am," "check X in an
hour" — the assistant-flavor capability a personal AI obviously needs.

**Floor:** `seon.agent.schedule` — the `:seon.agent.schedule/*` entity (5-field
cron + a qualified fn), the pure cron logic (`parse`/`due?`/`next-fire-at`), and
`fire-due-schedules!` (the schedule half of the ONE ticker). `my.schedule` is the
thin wrapper that transacts a schedule entity onto the agent's
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

A due schedule fires via the ticker: it opens a `:schedule` run (the wake), the
`:say` text surfaces in the woken run's context, and the schedule's fn runs in the
agent's sandbox through the one exec service. The ticker + run mechanics live in
[[agent-runtime]]. **Budget:** ~1.2k tok (verb only).

#### `my.recall` — floor: `seon.embed`

**Why reach for it:** find what's stored BY MEANING, not exact attr/keyword —
"what do I know about the user's sleep?" matching a row that never said "sleep."
This is categorically NOT a CRUD facade: that would wrap `transact!`/`query` (which
datalog already does); THIS expresses nearest-by-meaning, which datalog CANNOT.

**Floor:** `seon.embed` — pod-side, READ-ONLY. The pod never embeds; it ships
`{query, k, eids}` over the wire to the JVM writer, which embeds the query
(retrieval prefix) + runs HNSW KNN, returning hits distance-ascending.

```clojure
(schema/register! :seon.recall/query :string)
(schema/register! :seon.recall/k     :int)         ; default seon.embed default-k
(defn ^:async recall
  "Semantic KNN over your store: nearest entities to QUERY by meaning. Returns the
   :seon.items/items envelope (each item the pulled entity + its distance). Gated
   by SEON_EMBED; when OFF, a legible ok?-false pointing you at store-inventory +
   datalog/grep — never an error. (recall {:seon.recall/query \"user sleep\"})" )
```

The wrapper reshapes `:seon.embed/hits` → the `:seon.items/*` envelope (so hits
thread into `pull`/`transact!`) and handles `SEON_EMBED` off as a graceful
ok?-false fallback. **Budget:** ~900 tok.

#### `my.tile` — floor: the UI tile/component layer ([[ui]])

**Why reach for it:** show the human a finished view — a note, a pros/cons, a
recommendation — with ONE call and zero hiccup authoring; the agent says what it
MEANS, the human sees the picture.

`my.tile` is the agent-facing call over the tile/render machinery in [[ui]]. It
writes by transacting the built hiccup onto the agent's `:seon.render/html`
(the agent's own html render — its live tile) — a
literal hiccup (built from a UI component) bypasses SCI; a fn symbol late-resolves
SCI-bounded and re-derives every render.

```clojure
(schema/register! :seon.tile/view [:or :keyword :symbol])  ; a prebuilt view key OR your own fn sym
(defn ^:async show!
  "Set your live tile to a prebuilt VIEW rendered with DATA — transacts the built
   hiccup onto the agent's :seon.render/html. (tile/show! {:seon.tile/view
   :pros-cons :seon.tile/data {:seon.ui/title \"…\" …}})" )
```

Prebuilt views (`:seon.tile/view` keys): `:note`, `:pros-cons`, `:recommendation`.
**Composes:** `:seon.tile/data` is plain namespaced data the agent already has (a
`store-inventory` row, a `query` result) — show it without rendering it. For
dynamic tiles, `:seon.tile/view` is the agent's own hiccup-returning fn SYMBOL, so
the tile re-derives every render. The tile / slot / render mechanism lives in
[[ui]]. **Budget:** ~1k tok.

#### `my.blob` — floor: `seon.blob`

**Why reach for it:** persist LARGE content that doesn't belong in datoms — a
benchmark run's full output, a scraped PDF, anything big the agent's domain code
produces — keeping only a hash + small projection in the DB. The `Blobs` tier of
the three-tier storage rule (DB datoms = small indexed projections; Blobs =
persistent full content; the globalThis stash = volatile per-session values).

**Floor:** the protected `seon.blob` (content-addressed, on-disk zstd). `my.blob`
is the thin `put!`/`get` wrapper, storing the hash on a typed projection entity.

```clojure
(defn ^:async put! [m] #_"{:seon.blob/content \"…\"} → {:seon.blob/ok? true :seon.blob/hash \"…\"}")
(defn ^:async get  [m] #_"{:seon.blob/hash \"…\"} → {:seon.blob/ok? true :seon.blob/content \"…\"} | not-found")
```

**Budget:** ~800 tok.

## Detail docs

- [[data-model]] — the `:my.kb.*` / `:my.todo/*` (tree) / `:my.agent/*` schemas +
  data-agent-ref scoping; the `:seon/error` value; the entity-kind-vs-value-enum
  rule; index-everything / show-`my.*`-full.
- [[agent-runtime]] — the loop/run/turn/FSM; creation-as-idle; bootstrap-as-seeded
  forms; `start!` / `:seon.agent/parent` / roles-as-capabilities / root base case;
  the ticker; isolation tiers.
- [[ui]] — block / render / tile / slot / layout; reitit routing + the `/call`
  capability gate; the seed-copy + variadic `install!`/`remove!` model.
- [[architecture]] — the map: the glossary, the cross-cutting principles, the
  deployment topology.
- [[datahike-primer]] — the source-grounded "work in datahike's grain" mindset.
