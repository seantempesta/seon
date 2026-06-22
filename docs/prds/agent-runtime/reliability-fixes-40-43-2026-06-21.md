---
type: prd
status: active
tags: [prd, agent]
---

# Agent-reliability fixes #40–43 + the result-projection model

Root-cause traced + verified against live code (2026-06-21). The unifying
principle: agents work with **data**, not text — every fn returns
namespaced-keyword data; each eval's value is bound to `result/{id}` for
code to compute over; the transcript shows a **clipped, reader-safe
summary**, never a raw dump. NEVER name the downstream — use `acme`.

## Root causes (all verified)

| Bug | Root cause | Key sites |
|-----|-----------|-----------|
| **#43** wake spiral | `notify-tile-error!` forges a `:seon.user/id "user"`-FROM message (`:force true`) indistinguishable from a human message → re-arms the wake AND defeats the halt | `sci.cljs:471`, `agent.cljs:565-568` (`inbound-msg-datom?`), `agent.cljs:1336-1367` (`replied-since-inbound?`) |
| **#40** bloat | `transact!` returns the raw datahike TxReport (`:db-before`/`:db-after`/`:tx-data` echo) as the agent-visible value | `db/internal.cljs:1155`, `db.cljs:91-102` (`::transact-response` typed `:any`) |
| **#41** reader error | `render-result-edn` is a bare `pr-str` → `#datahike/DB`/`#datahike/Datom` text in `<past-evals>`; no CLJS reader for the tag → agent reads its own commit back as a failure | `eval.cljs:1848`, read at `eval.cljs:730` (ns `seon.dynamic`) |
| **#42** tile silent garbage | literal hiccup IS supported, but a misplaced attrs map falls through `structure-error-at`'s `:else nil`; serializer reads attrs only in 2nd position; no signal reaches the agent | `live_tile.cljs:248`, `html.cljc:311`, `render.cljs:429` |

`result/{id}` reuse is **solid** (`stash-result-raw!` eval.cljs:861, `bind-result-var!` 948-990) — raw value kept with no pr-str round-trip; a later bare `result/<id>` resolves to the live object. No gap.

## Part 1 — General result-projection (CENTERPIECE: #40/#41 + philosophy)

ONE value→string chokepoint: `seon.eval/render-result-edn` (eval.cljs:1822-1849). Every eval value passes through it to `:seon.eval/result-edn`, rendered verbatim as the `=> …` line by `format-eval-row` (ctx.cljs:582-602). The raw value is independently stashed for `result/{id}`. **The projection is display-only.**

Make `render-result-edn` an **agent-safe projection** (recursive walk, applied per-row inside the collection-preview branch too, not just top-level):
- Any node carrying a print-tag with **no registered CLJS data-reader** (datahike `DB`/`Datom`/`Entity`/TxReport, any future tagged record, raw JS object) → a reader-safe summary datum, e.g. `{:seon.eval/opaque "datahike/DB" :seon.eval/summary "<datoms=N max-tx=T>"}`; Datom → `{:seon.eval/datom [e a v]}`.
- Detection: type allowlist of reader-registered tags (empty today) + a print-and-check / record fallback, gated on "tag NOT registered". `#inst`/`#uuid` and any registered tag survive.
- Keep the 50-row collection cap + `cap-edn` size cap.

Invariant guaranteed structurally: **an agent never sees its own committed work read back as a reader error**, for ALL value types.

Read-side guard (recommended): also run the projection at `format-eval-row` so legacy `#datahike/DB` rows sanitize on read without a cluster reset. Write-side is source of truth; read-side is a thin net.

## Part 2 — Fn tightenings (offenders, priority order)

**P1 `transact!`** (`db/internal.cljs:1155` + `db.cljs:91-102` schema + docstrings 301-304). Replace `{::db/ok? true ::db/tx-report <raw>}` with compact data:
```clojure
{:seon.db/ok? true
 :seon.db/tempids   (:tempids report)   ; LOAD-BEARING — callers resolve tempid→eid
 :seon.db/tx        <tx-id>             ; max-tx of :db-after (confirm wire shape)
 :seon.db/tx-count  (count (:tx-data report))
 :seon.db/added <n> :seon.db/retracted <m>}
```
Drop `:db-before`/`:db-after`. Full report only behind `:seon.db/return-report? true`. Cap/omit per-datom echo by default (a bulk seed = thousands of datoms). Listeners unaffected (`build-handler-input` projects off the raw report independently; wire `tx-report->ok-map` stays). Sweep return-value callers (`record-eval!`, `inspector.cljs`, tests) in the SAME patch — no v2 fn. Update the stale agent warning at ctx.cljs:917-920.

**P2 `entity`** (db.cljs:669-691) returns a raw lazy datahike `Entity` (opaque). Agent-facing: touch + return a plain map `(into {:db/id (:db/id e)} (d/touch e))`. **Risk:** core render hot-path uses lazy `Entity` traversal (`transcript/session-turns`) — keep a lazy internal accessor, make only the public agent-facing `entity` touch+return-data, OR steer agents to `pull`.

**P3 `pull`** — readable EDN already (scalar patterns); a `[*]`/ref pull yields `{:db/id N}` maps (data, not directly actionable). No change; Part-1 projection backstops any transitive datahike object.

**Clean (no change):** `query`, `installed-schema`, `store-inventory`, `core-kinds`, `agent.search`, `agent.turns`, `todo`. Spot-check `seon.agent` `(messages)`/`(evals)` + `seon.embed.cljs` (un-audited).

## Part 3 — #43 wake/halt deterministic fix

Fix at message-provenance + wake-gate (NOT `reply!` — halt is a derived timestamp comparison; legitimate non-reply turns exist; a `reply!` flag would miss them and double-count).

1. **`message.cljs`** — register `:seon.agent.message/origin [:enum :human :agent :core]`, add to the entity schema, stamp in `message!` (`:human` for the HTTP/user adapter, `:agent` for agent sends).
2. **`sci.cljs` `notify-tile-error!`** — **DROP the fabricated message** (the reactive twin already surfaces breakage; see Part 4). If any nudge is kept it MUST be `:origin :core`, FROM the agent's own ref, and `:force`+origin must not let an agent message masquerade as core.
3. **`agent.cljs` `inbound-msg-datom?` (565-568) AND `replied-since-inbound?` (1336-1367)** — anchor BOTH to `origin ∈ {:human :agent}` (exclude `:core`) ∧ `from≠me`. A `:core` message never wakes and never moves the halt baseline. A turn that replied AND broke a tile still halts: `outbound(reply).at > inbound(human).at`. Agent↔peer consults still wake the peer; human follow-ups mid-wake still re-arm.

## Part 4 — #42 tile contract + actionable error

Literal hiccup IS supported; the bug is silent degrade. Fix in the EXISTING `structure-error-at`/`hiccup-structure-error` walk (live_tile.cljs:208-260) — NOT a new validator. Detect the unambiguous misplaced-attrs case (2nd slot a non-map child AND an attrs-looking map at child index ≥1) → specific `::structure-message`: *"attrs map must be the SECOND element (immediately after the tag), before any children — got an attrs-looking map at child index N."* This throws via `render-agent-tile` → `error-response` → the `:seon.render/ai` awareness twin. **Conservatism load-bearing:** fire only on the clear displaced-attrs shape (`[:h3 "x" {:k v}]` is genuinely ambiguous — restrict narrowly); leave `->string` permissive (do NOT make `render-content`'s `:else` throw — it 500s the page). Pin the attrs-position rule in the docstring (currently unstated). Agent learns via the twin, not a forced message.

## Part 5 — Decisions + sequencing

**Decisions:** (D1) message provenance = new `:seon.agent.message/origin` attr [rec] vs join tx `:seon.db/origin`. (D2) drop the active tile-error push [rec, reactive] vs keep as `:core`. (D3) `transact!` tx-id = max-tx of `:db-after` — confirm wire report empirically. (D4) read-side legacy sanitize [rec yes]. (D5) `:core` ever wakes idle agent [rec never].

**Tasks:** A (#42, `live_tile.cljs` — independent) ‖ B (Part-1 projection, `eval.cljs`). Then C (`transact!`, `db/internal.cljs`+`db.cljs`, sweeps `eval.cljs` callers — order AFTER B, shared file). Then D (#43, `agent.cljs`+`message.cljs`+`sci.cljs`, atomic, after D1/D2).

**STATUS (2026-06-21): Parts 1 + 2 (tasks B + C + P2 `entity`) DONE + live-verified.**
- B (Part-1 projection): `seon.eval/project-agent-safe` + `render-result-edn` rewritten — recursive walk, applied per-node incl. inside the collection-preview branch. datahike DB/Entity/Connection → `{:seon.eval/opaque "datahike/DB" :seon.eval/summary "max-tx=… max-eid=…"}`; Datom → `{:seon.eval/datom [e a v]}`; records / JS objects summarized; `#inst`/`#uuid`/plain data survive verbatim. Read-side D4 net `seon.eval/sanitize-result-edn` wired into `ctx/format-eval-row` (re-read + re-project legacy `#datahike/…` strings). Live-proven re-readable by `cljs.reader`; `result/<id>` stays RAW (display-only).
- C (#40 `transact!`): `internal/transact-success-envelope` — COMPACT by default `{:seon.db/ok? true :seon.db/tempids … :seon.db/tx <max-tx of :db-after> :seon.db/tx-count … :seon.db/added … :seon.db/retracted …}`; `:db-before`/`:db-after` dropped from the agent value; raw report only behind `:seon.db/return-report? true`. `::transact-response` schema → concrete types. Listeners + wire `tx-report->ok-map` untouched (project off the raw report independently — live-verified listener fired). D3 confirmed empirically: wire success report = `{:db-after(:max-tx) :tx-data(Datoms) :tempids :tx-meta :db-before}`, `tx` = `(:max-tx (:db-after report))`. Callers swept in-patch (record-eval!, test/runner mock, db_test/origin_guard/envelope tests); stale agent warning at ctx ~917 updated.
- P2 `entity`: public `seon.db/entity` now returns a TOUCHED plain map `(into {:db/id …} (touch e))`; new internal `seon.db/entity-lazy` keeps the raw lazy Entity for the render hot-path (all `seon.ctx`/`ctx.transcript` deep-walk callers switched to it — transcript render live-verified, no regression).
- Tests: full `bin/test-cljs` green except 2 PRE-EXISTING failures from the PAUSED rendering work (`ctx_test/selection-rules`→`full-source-ns?`, `index_core_test/core-ns-rows-carry-the-minimal-stub`) — unrelated to these changes.

**STATUS (2026-06-21 PM): Parts 3 + 4 (#43 wake/halt + #42 tile contract) DONE + live-verified.**
- #43 provenance (D1): new `:seon.agent.message/origin [:enum :human :agent :core]` registered + on the `:seon.agent.message` entity schema. `message!` DERIVES it (`from` = user ⇒ `:human`, else `:agent`); explicit `:origin :core` wins (substrate nudges). HTTP/user adapter relies on the derived `:human`; agent sends/consults/replies on derived `:agent`. Live-verified: user-ref send ⇒ `:human`, agent send ⇒ `:agent`.
- #43 wake gate (`inbound-msg-datom?`) + halt side (`replied-since-inbound?`) + the mirrored loop window (`ctx/turns-since-inbound`) all anchored to `origin ∉ {:core}` ∧ `from ≠ me` (legacy rows: absent origin ⇒ `:human` via `get-else`). Live-proven: a `:core` message neither wakes (gate false) nor moves the halt baseline (`replied-since-inbound?` stays true after a `:core` lands post-reply); a `:human` wakes + re-arms; an `:agent` PEER consult wakes; a self-send never wakes. Decisive: the inbound-baseline query returns the human's `at` WITH the origin filter vs the `:core` nudge's `at` WITHOUT it.
- #43 D2/D5: `notify-tile-error!`'s forged push DROPPED ENTIRELY (plus the now-dead `note-tile-ok!` + `!error-notified` dedup atom, and the two `render.cljs` call sites). Broken-tile awareness is a PURE DERIVED surface only: `error-response`'s `:seon.render/ai` twin ("YOUR LIVE TILE IS BROKEN — …") re-derived into the `:live-tile` context section every turn (no stored flag, self-healing on the next clean render). No `:core` message wakes an idle agent.
- #42 tile contract (D… narrow): the misplaced-attrs case added INSIDE the existing `structure-error-at` walk (NOT a new validator) — fires ONLY when the 2nd slot is a non-map child AND a (non-raw) attrs-looking map sits at child index ≥ 1 → a specific `::structure-message` naming the child index; routes via `render-agent-tile` → `error-response` → the twin. `html`/`->string` left PERMISSIVE. Live-verified: misplaced tile (`[:div "hello" {:class "x"} "world"]`) yields the structure-message in the twin while the human still gets the calm placeholder; valid tiles (`[:div {:k 1} "x"]`, `[:h3 "x"]`, nested) never trip. Attrs-position rule pinned in the `structure-error-at` docstring.
- Tests added: `live_tile_test/structure-error-locates-misplaced-attrs` (incl. conservatism cases), `agent_loop_test/replied-since-inbound?-ignores-core-origin`, origin assertions on `message_test`'s fully-formed tests. Stale gym fixture (`driver_test` `:every-in` message-attr list) updated to include `"origin"`. Full `bin/test-cljs`: 2 failures, both the PRE-EXISTING paused-rendering ones above.
- NO live agent drive performed (reserved for Integration).

**Verify (each task):** live read-only evals (tile structure-error fires on the misplaced map but not on valid tiles; projected value is re-readable; compact transact! has no `#datahike/DB`; listeners still fire). **Final:** ONE bounded live DeepSeek drive — a message that requires reply + tile-affecting work; assert the loop halts after exactly one answered turn, no phantom self-wakes, transact `=>` shows the compact envelope, `result/<id>` reads clean, breakage surfaces via the awareness twin. Then `bin/test-cljs` once.

## Uncertainties (not papered over)
- `transact!` wire-report `:db-after`/`:tempids` availability — confirm with one live-report read before pinning `:seon.db/tx`.
- `(messages)`/`(evals)`/`seon.embed.cljs` return shapes un-audited (Part-1 backstops them).
- #43 phantom-inbound `.at > reply .at` ordering inferred — confirm with a read-only query of turns 2-6 before committing the halt-side filter.
- `entity` lazy-vs-touch split is a real design call (perf hot-path).
- #42 misplaced-attrs heuristic generalization — ship the narrow single-case rule first.
