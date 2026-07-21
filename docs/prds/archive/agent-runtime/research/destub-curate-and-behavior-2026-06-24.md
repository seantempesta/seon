---
type: research
status: active
tags: [research, agent]
---

# De-Stub the Namespaces Section: Curate-Full, Drop-Rest — Behavior + Implementation

Branch `feature/agent-fsm`. Goal: kill stubbing entirely. Render a curated set
of `seon.*` namespaces in FULL, render every `my.*` + third-party ns in FULL,
and DROP all other `seon.*` nses from the rendered context completely. Dropped
nses STAY indexed in the DB and grep-able via `seon.agent.search` — they simply
no longer appear in the `:namespaces` prompt body. No new APIs: this is
curation-data plus removal of the stub/manifest render path plus test updates.

This document is the implementation order. Everything below was verified against
live source on `feature/agent-fsm` (file:line references checked 2026-06-24).

## TL;DR

- De-stubbing is a SUBTRACTION. `render-full?` in `seon.ctx.namespaces` already
  identifies exactly the nses to render full (current ns + `full-source-ns?` +
  `third-party-ns?`). Killing stubbing = render only the `render-full?`-true
  partition and drop the rest. The signature-manifest path is deleted, not
  replaced.
- The curated `seon.*` full-source set grows from 3 to **7**: add
  `:seon.agent.fs`, `:seon.agent.message`, `:seon.agent`, `:seon.schema` to the
  existing `:seon.db`, `:seon.agent.todo`, `:seon.agent.search`. Net rendered
  source ≈ **+1100 to +1700 lines** (deep worked-example source replaces the
  broad-but-shallow signature manifest).
- The boot indexer is UNCHANGED — every core ns/fn/schema/test row is still
  emitted, so dropped nses stay queryable and grep-able. Keep the `(ns x)` stub
  source (it still feeds on-demand `render-namespace` and idempotency dedup).
- The single highest-risk follow-up is the `<system>` prose: it currently tells
  the agent the framework is "listed by NAME in a manifest at the end." With no
  manifest, that prose MUST be rewritten to point at grep / `store-inventory` /
  `render-namespace`, or the agent hallucinates fn names. Same-patch requirement
  (align-context-with-runtime law).
- INDEPENDENT BLOCKER carried from the behavior lane: the ground-truth transcript
  shows `(message/user …)` failing because `[:seon.user/id "user"]` was not
  reliably resolvable at boot. `user-ref` (`src/seon/agent/message.cljs:64-67`)
  expects seon.client to seed it. Confirm the seed fires before any
  drive-and-tune battery, else `message/user` tasks fail spuriously.

## 1. Detailed Behavior Report

This is from live drives (P1/P2/P3 fresh probes + mined drives `jje-2606240052`,
`GoM`, `BYc`). The drive-and-tune laws held: examples taught SHAPE, not literal
answers, and the agents reported what they computed.

### Todos — in depth

Build quality is HIGH. `seon.agent.todo` (already full-source whitelisted) is
the exemplar small store: map-in/map-out, error-as-value envelopes,
`register!`-per-attr, real minted ids, shared-shape refs (`:seon.db/id`,
`:seon.db/ref`).

Naturalness is task-shape-dependent and UNPROMPTED in the right shape. In
`jje-2606240052` (task "store a book in the KB", NO mention of todos), the agent
SPONTANEOUSLY created one todo per step and completed each as it landed. Exact
forms it wrote:

```clojure
(seon.agent.todo/add! {:seon.agent.todo/title "Register :my.kb.book schema (id, title, author)"
                       :seon.agent.todo/from [:seon.user/id "user"]})
(seon.agent.todo/add! {:seon.agent.todo/title "Transact 'The Left Hand of Darkness' by Ursula K. Le Guin"
                       :seon.agent.todo/from [:seon.user/id "user"]})
(seon.agent.todo/complete! {:seon.agent.todo/id "Xqk-2606240052"})
(seon.agent.todo/list-open {})
```

Clean, idiomatic, fully-namespaced keys. BUT in fresh P1 (task "Audit seon.db …
work methodically and report") the agent did NOT use todos at all — it used an
in-memory scratch atom `(def !audit (atom …))` and a `doseq` classification loop.
So: concrete imperative multi-step tasks (store X, register Y, confirm Z) trigger
todos reflexively; analytical audit/report tasks do not, even with a "work
methodically" cue.

WART (verified P2): the documented `add!` `.then` example silently requires a
live agent ENTITY in ALS scope (the owner default `[:seon.agent/id <id>]` must
resolve). Run bare under a non-existent id `"P2probe"`:

```clojure
;; bare, fake agent:
(add! …) => {:seon.agent.todo/ok? false
             :seon.agent.todo/error
             "add!: store failed — wire transact failed: ... Nothing found for entity id [:seon.agent/id \"P2probe\"]"}
;; under a REAL live agent:
(add! …)      => {:seon.agent.todo/ok? true :seon.agent.todo/id "Eld-2606240942"}
(complete! …) => {:seon.agent.todo/ok? true :seon.agent.todo/id "UDK-2606240943"}
```

The docstring example never states the live-agent precondition, so a bare REPL
replay fails confusingly with `complete!: no todo nil` or the wire error.

### Per-capability (build / naturalness / warts)

- **todo (`seon.agent.todo`)** — Build: HIGH. Naturalness: excellent +
  unprompted for imperative multi-step tasks; ABSENT for analytical audit/report
  tasks. Warts: documented `.then` example silently needs a live agent entity.
- **message (`seon.agent.message`)** — Build: HIGH; `message/user` is the
  reflexive reply verb; no-deaf + first-wake fixes confirmed working. Naturalness:
  reflexive — P3 answered 3 distinct questions in ONE message on TURN 1, dropped
  none; follow-up woke the parked `:waiting` agent first-try. Warts: (1)
  deferred-result model causes premature/duplicate messages (placeholder "I'll
  read the count from the query result above", then a corrected value a turn
  later — human got two messages); (2) spurious extra greeting-turn after
  finishing.
- **kb (`my.kb.*`)** — Build: HIGH (full by `my.*` rule). Naturalness: clean +
  unprompted — register-before-transact respected; custom `my.*` string id
  works; confirm-by-pull natural. Warts: none observed.
- **db-query (`seon.db`)** — Build: HIGH (flagship full-source; db manual renders
  verbatim). Naturalness: excellent — the ref-attr-join TRAP handled FIRST-TRY,
  joining THROUGH the ns entity, no failed keyword-equality attempt. Warts:
  cross-turn `result/<id>` var not dereferenceable (`Cannot read properties of
  undefined`), burning a turn — contradicts the masthead's "read its `;;=>` next
  turn" contract.
- **schema (`seon.schema`)** — Build: HIGH but renders SIGNATURE-ONLY today (not
  whitelisted). Naturalness: reflexive — `register!` is the default before
  transacting any new shape, one-per-line with shared-shape refs. Warts: none.
  IMPORTANT: usage stayed correct on signatures-only — the agent leans on the
  prologue convention + full `my.kb` examples, not on `seon.schema`'s own body.
  This SUPPORTS the architecture (drop non-whitelisted seon.* from render) but is
  also evidence schema's full body is lower-value than the other adds.
- **search (`seon.agent.search`)** — Build: HIGH (full-source). Naturalness:
  clean — the search→read recipe runs as one fluent idiom; the agent KNOWS the
  `:matches` envelope key (the thing signatures-only left it guessing as `:hits`).
  Warts: none.
- **complete/lifecycle (`agent/wait`, `complete`, `terminate`)** — Build: HIGH;
  the loop halts cleanly on the verb (`:halt-verb`). Naturalness: agents reliably
  park with `(agent/wait "<note>")` when idle. Warts: `complete` not exercised
  (tasks were Q&A); lifecycle is reached via the lost-task path too often — the
  agent parks AFTER re-greeting rather than after delivering the result.

### Top real problems (ranked)

1. **TASK FORGOTTEN MID-LOOP (most damaging).** The inbound `;;; ◀ from :user …
   "<task>"` line renders ONLY at the head of the turn that first sees it. The
   transcript is capped at 24000 chars (`transcript-char-budget`); after 2-3
   turns the oldest turn block is evicted along with the ONLY copy of the task.
   PROVEN in P1: the task ◀ line is in turn 0 + turn 1 prompts and GONE by turn
   2/3 (grep confirmed). By turn 3 the agent had no idea what it was assigned and
   reverted to the generic SOUL.md greeting — even though it had ALREADY computed
   the complete correct audit in turn 1. The human's ONLY received message was
   the greeting; the answer never arrived. The `transcript.cljs` message-drop fix
   surfaces pending inbound ONLY on the first wake (no-turns branch, lines
   377-389); there is NO re-surface for a still-unanswered inbound once turns
   exist — that is the gap.
2. **Documented `add!` `.then` example fails bare (verified P2)** — see todo wart
   above. The example never states the live-agent precondition.
3. **Inline-backtick prose shredded into junk evals.** P1 turn-2 produced ~25
   fake one-token "eval" rows (`` `entity ``, `` `query ``, …) because the agent
   wrote markdown prose with inline backtick symbols OUTSIDE code fences and the
   form-extractor parsed each backtick-quoted symbol as a form. Fenced
   ```` ```clojure ```` code extracts cleanly; inline-backtick prose does not.
   This buried the real `(message/user "## audit …")` delivery so the answer
   never reached the user.
4. **Cross-turn `result/<id>` var not dereferenceable.** P1 turn-1
   `(let [fns result/AIP-2606240944] …)` → `:ok? false` with `Cannot read
   properties of undefined`. The masthead promises citable result vars "next
   turn" but the handle resolved to undefined. Either honor the contract or stop
   teaching it.
5. **Deferred-result model causes premature/duplicate answers + spurious
   greeting-turns.** The agent front-runs the `;;=>` it is told to wait for, and
   re-greets into the vacuum left when the answered inbound rolls out of the
   window.
6. **Harness recipe bug (flag for next driver).** The flagship recipe's example
   agent ids (`(str "P1-" …)`) FAIL Malli — `:seon.agent/id` is
   `[:string {:min 14 :max 14}]`; a 16-char id is rejected at `create!`
   (silent: agent never created, loop halts `:halt-external` with 0 turns). Must
   mint agent ids with `(seon.db/new-id!)`.

These behaviors are mostly ORTHOGONAL to the de-stub change. The de-stub helps
problems indirectly (more worked-example source = fewer shape mistakes) but does
NOT fix #1 (task-forgotten) or #3 (inline-backtick) — those are transcript /
form-extraction issues and stay open.

## 2. Curated `seon.*` Set

The final `full-source-whitelist` (extend the def at
`src/seon/ctx/namespaces.cljs:124`):

```clojure
#{:seon.db :seon.agent.todo :seon.agent.search
  :seon.agent.fs :seon.agent.message :seon.agent :seon.schema}
```

Sizes verified by `wc -l` on disk (2026-06-24):

| ns | lines | status | why |
| --- | --- | --- | --- |
| `:seon.db` | 1154 | KEEP | The database API every agent uses; the file IS the db manual (datalog cheat-sheet, per-fn worked examples, lookup-ref + ref-join idioms). Irreducible — render is whole-file. |
| `:seon.agent.todo` | 258 | KEEP | THE exemplar small store + the agent's planning surface (`add!`/`complete!`/`reopen!`/`list-open`). Open todos render every turn. Teaching file for "how a tiny domain store is built and called." |
| `:seon.agent.search` | 362 | KEEP | The search half of search→read; `grep` over allowed roots, pattern is a REGEX, no-match = success. Body carries the `:matches` envelope (signatures-only left agents guessing `:hits`). |
| `:seon.agent.fs` | 604 | ADD | The read+write half of search→read — the agent's eyes/hands on disk, default-deny allowlist (`read-file`/`write-file`/`list-dir`/`walk-dir`/`stat`/`grants`/`configure!`). `grep` returns paths that feed `read-file`. Body teaches the default-deny-envelope-is-PASS vs thrown-error-is-FAIL distinction. |
| `:seon.agent.message` | 289 | ADD | The conversation verbs — the agent's only way to talk to its human or peers (`user`/`agent` over `message!`). Documents fan-out (`to` = vector of refs), the hop/ping-pong guard, the `{:ok? …}` envelope, and the loud self→self refusal. |
| `:seon.agent` | 782 | ADD (reservation) | Lifecycle verbs to end work cleanly: `wait` (park until message), `complete` (finish, still wakeable, routes result to parent), `terminate` (orchestrator-only, the one UNWAKEABLE state) + the `:seon.agent/state` enum + state-machine block comment. Without it the agent busy-loops or hallucinates the verb shape. Costliest add; ns-granular render pulls in framework internals (`create!`/`boot!`/`fresh-wake!`/`set-state!`) + the layout verbs. |
| `:seon.schema` | 630 | ADD | `register!` is the single source of truth for attribute schemas; discovery reads (`registered?`, `registered-schemas`, `schemas-in-namespace`, `enum-members`, `identity-attr?`) let the agent see existing shapes before inventing one. Defining data shapes is a first-order capability (the `my.kb` doctrine depends on it). NOTE: behavior lane shows usage stays correct on signatures-only — lower-confidence add; keep but revisit if bloat hurts. |

Curated 7 full = 4079 lines total (verified). NOT in the whitelist: `my.*`
(`my.kb`, `my.soul`, the agent's `my.agent.<id>` home ns) and third-party
`acme.*` — they render full via the separate `my-ns-name?` / `third-party-ns?`
rules and are unaffected.

### Drop list (everything else `seon.*`)

DROPPED from render entirely (no stub, no manifest) — STAYS indexed +
grep-able + reachable via `(seon.ctx/render-namespace {:seon.ns/name …})`:

- CONTEXT/RENDER MACHINERY — `seon.ctx` + all `seon.ctx.*` section fns
  (inventory, live-tile, namespaces, relevant, transcript, warnings,
  your-entity). The agent reads their OUTPUT, never calls them. EXCEPT the
  `render-namespace` escape-hatch teaching, which must move into `<system>`
  prose.
- EVAL/RUNTIME — `seon.eval`, `seon.repl`, `seon.flow`, `seon.platform`,
  `seon.dev.runtime-id`.
- LLM ADAPTERS — `seon.ai`, `seon.ai.anthropic`, `seon.ai.openai-compat` (the
  agent IS the LLM).
- DISPATCH/REGISTRY — `seon.handler`, `seon.handler.match`, `seon.handlers.*`.
- WARNINGS — `seon.warn` + `seon.ctx.warnings` (the agent reads the derived
  `<WARNINGS>` section).
- FRAMEWORK INTERNALS / BOOT — `seon.client`, `seon.analyzer-info`, `seon.debug`,
  `seon.fn`, `seon.error`, `seon.error.malli`, `seon.repair`, `seon.test` /
  `seon.test.runner`, `seon.store.wire`, `seon.web.*`, `seon.agent-view`,
  `seon.embed`.
- LOOP/SESSION INTERNALS — `seon.agent.fsm`, `seon.agent.session`,
  `seon.agent.turn`, `seon.agent.inspect`.

### Net context delta

Today's render = `db(1154)+todo(258)+search(362)` ≈ 1774 lines full PLUS a
signature manifest of ~50-80 framework nses (~600-1200 compact lines).

New render = the 7 curated nses full ≈ 4079 lines, ZERO manifest.

Net ≈ **+1100 to +1700 rendered lines** (+2305 from the 4 added full nses, minus
the ~600-1200-line manifest). The deliberate trade: broad-but-shallow names →
deep, eval'able, worked-example source for exactly the nses an agent types. A
later comment-refinement pass (trim `fs` why-sync block, `db` `*conn*` rationale,
`agent` layout-verb internals) recovers some of the inflation WITHOUT a new
per-fn-slicing API — the render path is ns-granular, not per-fn.

## 3. De-Stub Implementation Spec (file:line edits)

All references verified against live source on `feature/agent-fsm`.

### 3.1 Extend the curation data

- `src/seon/ctx/namespaces.cljs:124` — add the 4 new keywords to
  `full-source-whitelist`:
  `#{:seon.agent.todo :seon.db :seon.agent.search :seon.agent.fs :seon.agent.message :seon.agent :seon.schema}`.
  Update the docstring (lines 102-123) to list the 4 added tools and their
  one-line why, and change the closing clause "while the rest of the framework is
  a name manifest" → "while the rest of the framework is DROPPED from render
  (still indexed + searchable)."

### 3.2 Remove the manifest/signature render path (the core change)

- `src/seon/ctx/namespaces.cljs:276-287` — REPLACE the
  `group-by`/`full-tags`/`sig-tags`/`manifest`/`blocks` let-bindings. Keep only
  the full partition; drop the rest. Replace:

  ```clojure
          {full-rows true manifest-rows false}
          (group-by (fn [[nm _tx]] (render-full? nm cur-ns)) rows)
          full-tags (keep (fn [[nm _tx]] (render-one db nm :full)) full-rows)
          sig-tags  (keep (fn [nm] (render-one db nm :signature))
                          (sort (map first manifest-rows)))
          manifest  (when (seq sig-tags)
                      (str/join "\n\n" (cons manifest-pointer sig-tags)))
          blocks    (cond-> (vec full-tags)
                      manifest (conj manifest))]
  ```

  with:

  ```clojure
          ;; ONLY curated-full nses render: current ns, full-source-ns?
          ;; (my.* + the seon.* full-source-whitelist), and third-party
          ;; (acme) roots. Every OTHER seon.* framework ns is DROPPED from
          ;; the rendered section — it stays indexed + grep-able via
          ;; seon.agent.search, just not dumped here.
          full-rows (filter (fn [[nm _tx]] (render-full? nm cur-ns)) rows)
          blocks    (keep (fn [[nm _tx]] (render-one db nm :full)) full-rows)]
  ```

- `src/seon/ctx/namespaces.cljs:180-194` — DELETE the entire `manifest-pointer`
  def (unreferenced once the manifest is gone).

- `src/seon/ctx/namespaces.cljs:196-203` — EDIT `namespaces-header` to drop the
  "rest shown as PUBLIC fn SIGNATURES … manifest at the end" clause. New text
  describes only the curated FULL set + recency ordering, and instructs grep/query
  for the rest:

  ```clojure
  (str ";; Real loaded code, CURATED: the few namespaces you USE or OWN are\n"
       ";; shown in FULL (your my.* code, third-party business code, your\n"
       ";; current namespace, and a curated seon.* tool set) — each its whole\n"
       ";; file. The rest of the seon framework is NOT shown here; query any\n"
       ";; ns or fn by name (it stays indexed + searchable). Full namespaces\n"
       ";; are ordered by RECENCY: most-recently-modified LAST.")
  ```

- `src/seon/ctx/namespaces.cljs:205-229` — `render-one`: its `detail` param is
  now always `:full` from the section. MINIMAL: leave it as-is (harmless;
  `render-namespace` still uses `:signature`). It stays correct either way.

### 3.3 Docstring sweep (same patch — align-context law)

- `src/seon/ctx/namespaces.cljs:1-34` (ns docstring), `166-178`
  (`render-full?` docstring), `231-256` (`namespaces-section` docstring) — REWRITE
  every mention of SIGNATURE-MANIFEST / signatures / name-manifest / bodies-elided
  / "the rest of the framework is shown as signatures" to "the rest of the seon
  framework is DROPPED from the rendered section (still indexed + searchable)."
  `render-full?`'s "Everything else is a seon.* framework ns → NAME-MANIFEST only"
  → "Everything else is DROPPED from the rendered section."

### 3.4 `<system>` prose (LOAD-BEARING — highest-risk follow-up)

- `src/seon/ctx.cljs:922-934` — EDIT the "THE NAMESPACES BELOW" paragraph
  (verified at these exact lines). Today it says "The rest of the seon framework
  is NOT dumped; it is listed by NAME in a manifest at the end … To read any
  manifested ns … query it by name (the manifest shows the exact query)." Replace
  the manifest references with: the rest of the framework is NOT shown but stays
  queryable/searchable; instruct the agent to use `seon.agent.search` (grep),
  `(seon.db/store-inventory {:seon.db/system? true})`, or
  `(seon.ctx/render-namespace {:seon.ns/name :the.ns})` to discover and read any
  non-shown ns. Drop the word "manifest" entirely. The `render-namespace`
  escape-hatch worked example currently embedded in the deleted `manifest-pointer`
  MUST be relocated here and made louder — if the agent cannot discover dropped
  nses it will hallucinate their source.

### 3.5 Indexer — NO CODE CHANGE (docstring-only)

- `src/seon/client.cljs:1101-1136` (`ns-row`) — NO CODE CHANGE. KEEP the
  `(ns x)` stub source for bulk nses. It is still consumed by: (1) on-demand
  `(seon.ctx/render-namespace {:seon.ns/name :seon.foo})` (renders
  `:seon.ns/source` as the `(ns …)` line); (2) `core-index-tx`'s idempotency
  dedup (compares stored vs freshly-built `:seon.ns/source`); (3) the DB-layer
  load/reconstitution path (core nses are excluded from `agent-ns-set`, so core
  stubs aren't evaluated, but the attr is still expected to exist). Only the
  DOCSTRING drifts: lines 1114-1118 say "the `:namespaces` section
  compact-renders them from their indexed member rows (API surface, bodies
  elided)" — that consumer is gone. Update to: "the `:namespaces` section no
  longer renders these (only the curated full set is shown); the stub keeps the
  `:seon.ns/name` row + lookup-ref target for indexed members and the on-demand
  `render-namespace` path."

- `index-core!` / `var->fn-row` / `index-schemas` / `index-tests` — UNCHANGED.
  Every core ns/fn/schema/test row is still emitted for EVERY core ns. This is
  what keeps dropped nses queryable + grep-able. Net indexer code change: ZERO.

### 3.6 OPTIONAL (owner's call — flagged, not forced)

- `src/seon/ctx.cljs` `render-namespace` / `render-one-ns-ai` / `fn-block-ai`
  (~1129-1232) — NO CODE CHANGE required. The `:signature` detail stays a valid
  on-demand option of `render-namespace`. If the owner wants ZERO signature
  surface anywhere, delete the `:signature` branches in `render-one-ns-ai` and
  `fn-block-ai`, and drop `:signature` from the `:seon.render/detail` enum + the
  `render-namespace` docstring (so instrumentation does not accept a dead enum
  value). Recommend KEEPING `:signature` (it is a documented on-demand option).

### 3.7 `seon.agent` open question (do NOT block on it)

`seon.agent` is the costliest add (782 lines) and render is ns-granular — it
cannot show only `wait`/`complete`/`terminate`; including it dumps framework
internals + layout verbs into every context. Options: (a) accept the whole file
(simplest, matches "kill stubbing") — RECOMMENDED for now; (b) extract the 3
lifecycle verbs into a `seon.agent.lifecycle` ns and whitelist THAT (cleaner but
a real refactor — flag, don't do in this patch); (c) teach the verbs as
`<system>` prose and drop `seon.agent`. Go with (a); revisit if bloat hurts.

### 3.8 Searchability preserved (verified)

The DROP is purely a render-layer filter in `namespaces-section` — it touches
nothing in the index. After the change the DB still holds, for every dropped
`seon.*` ns: its `:seon.ns/name` row, its `:seon.ns/source` (stub), and every
`:seon.fn` / `:seon.schema` / `:seon.test` row pointing at it. So: (a)
`seon.agent.search` (ripgrep over the actual `.cljs` files on disk) is entirely
unaffected — it greps the filesystem, not the render; (b) the Datalog
fn-source-by-name query still works verbatim; (c) `render-namespace` still
reconstitutes the whole-ns view on demand. The only thing lost is the passive,
always-on signature listing — replaced by active grep/query, which the
`<system>` edit (3.4) must teach.

## 4. Test Updates

Verified assertion locations in `test/seon/ctx_test.cljs`,
`test/seon/index_core_test.cljs`, `test/seon/teachings_test.cljs`. Per the
iterate-live-before-hardening law: UPDATE+KEEP tests covering the kept
full-source mechanism that broke on the whitelist change; INVERT/DELETE only the
assertions pinning the deleted signature-manifest path. Run the full
`bin/test-cljs` suite ONCE after all edits land (batch checkpoint), not per edit.

- `test/seon/ctx_test.cljs:142` `namespaces-section-curated-full-vs-manifest-recency`
  — MAJOR REWRITE (do NOT delete; the FULL-render + recency + reconstitution
  assertions are the kept mechanism). KEEP the `my.agent.a1` / `acme.widget`
  full-block + unclipped-body assertions. INVERT/DELETE all manifest assertions
  (lines ~196-230): `seon.warn` must now be ASSERTED ABSENT
  (`(not (str/includes? txt "seon.warn"))`); the `seon.frob` signatures-block
  assertions (`(seon.frob/widget [a b])`, "body elided", the
  `;; ── namespace seon.frob (signatures) ──` header at line ~211) and the
  `manifest-pointer` assertion (line ~230) must be DELETED — `seon.frob` is a
  non-whitelisted framework ns and is now simply ABSENT. Rename the deftest to
  e.g. `namespaces-section-curated-full-only-recency`.
- `test/seon/ctx_test.cljs:~295-308` (the `*-test` member / framework-manifest
  deftest, "the framework ns is NAMED in the manifest" at line ~308) — INVERT to
  ABSENT. Keep the `*.internal` / `*-test` exclusion assertions.
- `test/seon/ctx_test.cljs:104-120` `full-source-ns?` rule test — UPDATE the
  whitelist membership table: lines ~104-117 enumerate which nses are/aren't
  full-source. The 4 added nses (`:seon.agent.fs`, `:seon.agent.message`,
  `:seon.agent`, `:seon.schema`) must MOVE from the false-set to the true-set.
- `test/seon/ctx_test.cljs:65-92` `included-ns?` test — NO CHANGE (selection rule
  untouched).
- `test/seon/index_core_test.cljs:127-141` `core-ns-rows-stub-bulk` — this asserts
  `:seon.schema` source is the `(ns seon.schema)` stub (line ~139-141). Since
  `:seon.schema` is now FULL-source, this assertion BREAKS: either change the
  example ns to a still-non-whitelisted bulk ns (e.g. `:seon.warn`, `:seon.eval`)
  or assert `:seon.schema` now carries real `(defn …)` text. RECOMMEND switching
  the example ns to a still-dropped one to keep testing the stub mechanism (which
  is unchanged).
- `test/seon/index_core_test.cljs:143` `core-ns-rows-stub-bulk-full-source-whitelist`
  — UPDATE: the whitelist set referenced here (line ~147) must match the new
  7-member set; `:seon.agent.fs` / `:seon.schema` move from stub-expectation to
  full-expectation.
- `test/seon/index_core_test.cljs:114` `no-stub-source-anywhere` (no `,,,`
  placeholder) — NO CHANGE (the `(ns x)` stub is not a `,,,` placeholder; this
  asserts a different invariant).
- `test/seon/index_core_test.cljs` full-source re-emit / idempotency tests — NO
  CHANGE; they depend on `ns-row` stub behavior, which we keep.
- `test/seon/teachings_test.cljs:308-321` (`namespaces-section` header
  surface-examples) — UPDATE the expected header prose to match the rewritten
  `namespaces-header` (drop the "signatures/manifest" clause). The test extracts
  the header prose ABOVE the first block and lints it for stray examples / bare
  prose; the new header is shorter prose and must still pass the no-bare-prose /
  eval'able check.
- `test/seon/teachings_test.cljs:~294-300` (full-source rows surface) — NO CHANGE
  (iterates `full-source-ns?` rows; the mechanism is untouched, though there are
  now more such rows).
- `test/seon/agent_render_namespace_test.cljs` (whole file) — NO CHANGE: it tests
  `render-namespace` directly (full + signature detail, depth recursion). If the
  owner DROPS the `:signature` option (3.6), the signature-detail assertions in
  this file must be removed too — grep this file for
  `:seon.render/detail :signature` before deciding.

## 5. Risks

- **`render-full?` escape hatch.** The agent's CURRENT ns is force-rendered full
  even when it is a non-whitelisted framework ns (`(= nm cur-ns)`). PRESERVED by
  keeping `render-full?` as the partition predicate (we only drop its false
  branch). Do NOT replace `render-full?` with a bare `full-source-ns?` check or an
  agent working inside, say, `seon.eval` loses its own full source.
- **LOST PASSIVE DISCOVERY (highest).** Today the agent passively sees every
  framework fn signature. After the drop it must actively grep/query. The
  `<system>` edit (3.4) is LOAD-BEARING — if it still says "listed by NAME in a
  manifest at the end," the agent looks for a manifest that no longer exists and
  may hallucinate fn names. MUST land in the same patch (align-context law). The
  behavior lane gives partial reassurance: schema usage stayed correct on
  signatures-only because the agent leans on prologue conventions + `my.kb`
  examples, not the framework's own bodies — but that is for verbs ALREADY taught
  in prose; novel framework fns become discover-only.
- **Stub-drop temptation.** It is tempting to also stop writing the `(ns x)` stub
  source for dropped nses. DON'T in this patch — it breaks on-demand
  `render-namespace` (loses the `(ns …)` line), `core-index-tx` idempotency
  dedup, and 3+ `index_core_test`s, for zero benefit to the request. Flagged so a
  later agent doesn't "clean it up" and regress.
- **Cache prefix.** `namespaces` is in the byte-stable prefix (`assemble-context`
  `stable?` set). Dropping the manifest SHRINKS the stable prefix but it stays
  deterministic (recency-sorted full blocks only). The manifest was name-sorted +
  stable anyway, so net effect is a smaller, still-stable prefix. LOW risk,
  beneficial (fewer tokens).
- **Cross-reference / require-recursion.** `namespaces-section` calls
  `render-one` with `:seon.render/depth 0` (flat, no require-recursion), so
  dropping a ns from the section does NOT break a sibling's render — each renders
  standalone. LOW risk.
- **Signature-option orphan.** If `render-one-ns-ai`'s `:signature` branch is KEPT
  (recommended) it becomes reachable only via `render-namespace`'s `:signature`
  detail. Fine — a documented on-demand option. If DELETED, also remove
  `:signature` from the `:seon.render/detail` enum or instrumentation accepts a
  dead value.
- **Context bloat from `seon.agent` (782) + `seon.schema` (630).** Whole-file
  render is the cost. Mitigation is a comment-refinement pass (no new API), not a
  per-fn slicer. Revisit if drives show the larger prompt hurts.
- **INDEPENDENT BLOCKER (not this change, but gates the next drive battery).** The
  ground-truth transcript shows `(message/user …)` failing because
  `[:seon.user/id "user"]` was not reliably resolvable at boot. `user-ref`
  (`src/seon/agent/message.cljs:64-67`) expects `seon.client` to seed the
  `:seon.user/id "user"` entity. Confirm the seed fires before any drive-and-tune
  battery, else `message/user` tasks fail spuriously and contaminate the eval.
- **`pull-by-name` does not exist.** `seon.db`'s read verbs are `pull` + `entity`.
  Any teaching/doc referencing `pull-by-name` is stale — scrub it.
