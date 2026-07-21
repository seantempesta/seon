---
type: research
status: active
tags: [research, agent, database, schema, flow]
---

# Entity-kind purge — stop the re-seeding (2026-06-28)

## TL;DR

The `:kind` entity-discriminator kept getting re-created by fresh instances
because the **agent-facing store-inventory rendered "per KIND"** — every instance
read "kind" in its context and copied it. Root-caused and purged IN PLACE:

- `seon.db/store-inventory` now returns `:seon.db/attr-groups` (rows labelled
  `:seon.db/attr-ns`) + `:seon.db/attr-ns-count`, never `:seon.db/kind(s)`.
  `core-kinds` → `core-attr-namespaces`.
- The agent-facing **inventory block** header now says "One line per attribute
  NAMESPACE … scan that attr's index — `[?e :the/attr ?v]`", not "per KIND".
- The **root dashboard** (`store-ai`/`store-hiccup`) reports "N namespaces", not
  "N kinds".
- `seon.render`'s schema-driven renderer dispatch renamed from `entity-primary-kind`
  / `renderable-kinds` / `kinds-by-kw` to `entity-primary-schema` /
  `renderable-schemas` / `schemas-by-key` (it already matched by attribute-presence;
  it just *called* the matched shape a "kind").
- The human **/data inspector** (`seon.web.debug`) browses by attribute NAMESPACE
  (`data-ns-index` / `data-ns-detail`, `?ns=` URL param), not "kind".

All callers + tests updated in the same patch. Live-proven on the default pod:
the rendered inventory block and dashboard contain **zero "kind"**.

The remaining `kind` usages classify **transient derived VALUES** (errors, warnings,
transcript events, render-value shapes) — NOT datom-entities. They are flagged for
the owner below (§4), not unilaterally refactored.

## 1. Canonical REPL-proven patterns (the four right moves)

Verified read-only against the live default store (`@seon.db/*conn*`). These are
the examples the purged code/docs/skill now carry verbatim.

```clojure
;; (1) FIND by attribute-presence — scan the attr's index (AEVT)
(seon.db/query {:seon.db/query '[:find ?e :where [?e :seon.agent/id]]})
;; => #{[1920] [1919] [1660] [1638] [1918]}

;; (2) IDENTIFY one by its :db.unique/identity attr (also how transact upserts)
(seon.db/query {:seon.db/query '[:find ?id . :in $ ?e
                                 :where [?e :seon.agent/id ?id]]
                :seon.db/inputs [1918]})
;; => "root"

;; (3) ENUMERATE stored data BY ID-ATTR PRESENCE — group entities by which
;;     :db.unique/identity attr they carry (the inventory's real job).
(let [db @seon.db/*conn*]
  (->> (seon.db/query {:seon.db/db db
                       :seon.db/query '[:find ?a :where [?s :seon.schema/key ?a]]})
       (map first)
       (filter seon.schema/identity-attr?)
       (keep (fn [a]
               (let [n (count (seon.db/query {:seon.db/db db
                                              :seon.db/query [:find '?e :where ['?e a]]}))]
                 (when (pos? n) [a n]))))
       (sort-by (comp - second))))
;; => ([:seon.schema/key 635] [:seon.fn/sym 614] [:seon.eval/id 317]
;;     [:seon.test/sym 217] [:seon.ns/name 119] [:seon.agent.turn/id 51]
;;     [:seon.agent.todo/id 32] [:seon.agent.message/id 21]
;;     [:my.kb.runtime/slug 7] [:seon.agent.run/id 7] [:seon.agent/id 5]
;;     [:my.skills/name 5] [:seon.route/name 4] [:seon.user/id 1]
;;     [:my.kb.shared/id 1])

;; (3b) the same idea, namespace-grouped, as the API surfaces it
(seon.db/store-inventory)
;; => {:seon.db/attr-groups   [{:seon.db/attr-ns :my.kb.runtime
;;                              :seon.db/attrs {:my.kb.runtime/slug 7 …}} …]
;;     :seon.db/attr-ns-count 13 :seon.db/attr-count … :seon.db/datom-count …}

;; (4) RELATE — follow a ref (component refs cascade on retract)
(seon.db/query {:seon.db/query
                '[:find ?aid . :in $ ?tid
                  :where [?t :seon.agent.turn/id ?tid]
                         [?a :seon.agent/turns ?t]
                         [?a :seon.agent/id ?aid]]
                :seon.db/inputs [<turn-id>]})
```

**Falsified:** at no point is an entity-type/`:kind` field needed. Every "what is
this / what exists / whose is it" question is answered by attribute-presence,
identity attr, or a ref. Grounding: `reference-code/datahike` (EAVT/AEVT/AVET
indexes; an entity is an eid + datoms, no kind), `datahike-primer.md` §0.

## 2. Audit table (every occurrence → category → action)

Categories: **A** = entity-inventory "kind" (the re-seeding engine — PURGE);
**B** = value-classification kind (transient derived values — FLAG, owner decides);
**C** = teaching mentions ("no `:kind`", reinforces the rule — KEEP);
**D** = archived/migration docs — LEAVE.

| Site | Cat | Action |
|------|-----|--------|
| `seon.db` `::kind`/`::kinds`/`::kind-count`/`::kind-set`/`::inventory-row` schemas | A | → `::attr-ns`/`::attr-groups`/`::attr-ns-count`/`::attr-ns-set`. PURGED |
| `seon.db/core-kinds` fn + 2 docstring xrefs | A | → `core-attr-namespaces`. PURGED |
| `seon.db/store-inventory` output keys + docstring | A | → `:seon.db/attr-groups`/`:seon.db/attr-ns`/`:seon.db/attr-ns-count`. PURGED |
| `seon.agent.ctx.inventory` header "One line per KIND" + ns/fn docstrings + key reads | A | → attribute-NAMESPACE framing + "scan that attr's index". PURGED (agent-facing prime) |
| `seon.render` `renderable-kinds`/`entity-primary-kind`/`kind-tables`/`kinds-by-kw`/`required-by-kind`/`!kind-cache` + `{:kind k}` | A | → `renderable-schemas`/`entity-primary-schema`/`schema-tables`/`schemas-by-key`/`required-by-schema`/`!schema-cache`/`{:schema k}`. PURGED |
| `seon.render.system` `store-hiccup`/`store-ai`/`store-summary` consuming `:seon.db/kinds` | A | → new keys; UI text "N kinds" → "N namespaces". PURGED (root dashboard — agent-facing) |
| `seon.warn` `db/core-kinds` call + docstring xref | A | → `core-attr-namespaces`. PURGED |
| `seon.web.debug` /data browser: `data-scan ::kinds`, `data-kind-index`, `data-kind-detail`, `::data-kind`, `?kind=` param, "all kinds" text | A | → `::ns-groups`, `data-ns-index`, `data-ns-detail`, `::data-ns`, `?ns=`, "all namespaces". PURGED (human inspector / "data-panel") |
| tests: `db_test`, `gym/driver_test`, `my/kb_test`, `instrument_smoke_test` | A | updated to new keys/fn name. PURGED |
| `seon.warn/kind` (`:return-is-any`/`:failed-evals`/`:unmarked-entity-kinds`/…) — warning-CLASS | B | FLAG (load-bearing warn system) |
| `seon.error/kind` + `:seon.error.kind/*` (`:user-input`/`:compile`/`:read`/malli-instrument-*) — error-CLASS | B | FLAG (load-bearing error system) |
| `seon.render.value/kind` (`:vector`/`:set`/`:seq`) — value-SHAPE for the dump renderer | B | FLAG (trivial-ish but pervasive) |
| `seon.render.chat/kind` `[:enum ::human ::agent ::peer ::system]` — message-source class | B | FLAG |
| `seon.agent.ctx.transcript ::kind` `:eval`/`:message`/`:coalesced` — transcript event class | B | FLAG |
| `seon.render.system ::kind` `[:enum :eval :message]` — activity event class | B | FLAG |
| `seon.gym.predicate/kind`, `:seon.store.wire/error-kind`, `:seon.plan.entry/kind`, `:seon.code/kind`, `:seon.async-result/kind`, `:seon.agent/kind`, `:chat/kind`, `:my.workout/kind` | B | FLAG (see §4) |
| `seon.web.debug` card `::kind` `(str nm)` — a debug-card title label | B | FLAG (display label, not entity-class) |
| `seon.warn/check-unmarked-entity-kinds`, `:seon.entity/kind` mentions, `my.kb` ns docstring "NO type/class/kind", CLAUDE.md / skills "no `:kind`" | C | KEEP (reinforce the rule) |
| `docs/**/archive/**`, `kind-removal-migration-*`, `entity-kind-discrimination-*` | D | LEAVE |

## 3. What was purged (files touched)

- `src/seon/db.cljs` — inventory schemas, `core-attr-namespaces`, `store-inventory`.
- `src/seon/agent/ctx/inventory.cljs` — header + docstrings + key reads (prime
  agent-facing surface).
- `src/seon/render.cljs` — schema-driven renderer dispatch (renamed concept).
- `src/seon/render/system.cljs` — root dashboard store section.
- `src/seon/warn.cljs` — `core-attr-namespaces` call + xref.
- `src/seon/web/debug.cljs` — /data inspector browser.
- `test/seon/db_test.cljs`, `test/seon/gym/driver_test.cljs`, `test/my/kb_test.cljs`,
  `test/seon/instrument_smoke_test.cljs` — key/fn updates.
- `seon-skills/datahike/SKILL.md` — added the REPL-proven enumeration example (§5).

**Live proof (default pod, read back from the running system):**
- `(seon.agent.ctx.inventory/inventory-block …)` → header reads "One line per
  attribute NAMESPACE … scan that attr's index — `[?e :the/attr ?v]`".
- `(seon.render.system/system-view …)` `:seon.render/ai` STORE line →
  "STORE — which attrs hold data, grouped by namespace"; `has-kinds? false`,
  `has-namespaces? true`.
- `(seon.db/store-inventory)` keys → `(:seon.db/attr-groups :seon.db/attr-ns-count
  :seon.db/attr-count :seon.db/datom-count)`.

## 4. Category B — owner decision list (do NOT touch without a call)

These classify **transient derived VALUES**, not datom-entities, so they don't
re-seed entity-kind modeling. The standing rule is "the namespaced keyword IS the
discriminator", which these mostly satisfy. Reframing them is a real refactor of
load-bearing systems. Per-item recommendation:

- **`:seon.error/kind` + `:seon.error.kind/*`** — STRUCTURAL. The error system
  dispatches on this everywhere (`config`, `db`, `eval`, `schema/internal`,
  `web.reactive.transform`, `error/instrument`). It is errors-as-values
  classification, a core invariant. Recommendation: **KEEP the word** here — an
  error genuinely has a `kind` and it's not an entity. If purging the literal word
  is desired, it's a dedicated refactor (rename to `:seon.error/class` or
  `/category`), not part of this pass.
- **`:seon.warn/kind`** — STRUCTURAL. Every warn check tags its output
  `{:seon.warn/kind :failed-evals …}`; `render-warnings`/dedup key on it.
  Recommendation: same as error — KEEP, or dedicated rename to `:seon.warn/class`.
- **`:seon.render.value/kind`** (`:vector`/`:set`/`:seq`) — value-SHAPE for the
  collapsible dump renderer. Trivial-ish but used in 2 `case` dispatches in
  `render/value.cljs`. Recommendation: **safe rename** to `:seon.render.value/shape`
  if the word must go; low risk, self-contained.
- **`seon.render.chat/::kind`** (message source), **`seon.agent.ctx.transcript/::kind`**
  (transcript event), **`seon.render.system/::kind`** (activity event) — render-event
  classification, namespace-local. Recommendation: **safe rename** to `::class` or
  `::event` each; self-contained per file.
- **`:seon.gym.predicate/kind`, `:seon.plan.entry/kind`, `:seon.code/kind`,
  `:seon.async-result/kind`, `:seon.store.wire/error-kind`, `:seon.agent/kind`,
  `:chat/kind`, `:my.workout/kind`** — assorted value classifications. Recommend
  auditing case-by-case; `:my.workout/kind` is downstream/sample data (leave).
- **`seon.web.debug` card `::kind`** — a debug-card *title* string, not a class.
  Recommendation: rename to `::title` for clarity; trivial.

**The decision for the owner:** purge the WORD "kind" everywhere (a multi-file
rename of error/warn/render-event classification to `class`/`shape`), or stop at
entity-kinds (this pass) and leave value-classification as-is. The recurrence
driver was ONLY the entity-inventory surface (A), which is now gone — so B is a
consistency/taste call, not a correctness one.

## 5. Consolidation proposal (one authoritative home, the rest cross-link)

The no-kinds rule is currently taught in FULL in ~5 places: root `CLAUDE.md`,
`seon-skills/datahike/SKILL.md`, `seon-skills/data-modeling/SKILL.md`,
`seon-skills/data-oriented-clojure/SKILL.md`, and `datahike-primer.md` §0
(deepest), plus a pointer in MEMORY.md.

**Recommendation — `seon-skills/datahike/SKILL.md` "There are NO entity kinds" is
the authoritative home** (it already has the four-moves block; I added the
REPL-proven enumeration example there in this pass). The others should keep a
ONE-LINE statement of the rule + a cross-link, not re-teach the full block:

- **`data-oriented-clojure/SKILL.md`** — owns the *mindset/OO-reflex* angle; keep
  its short "Entity = attributes, no kinds" para + "EAV query syntax: see the
  `datahike` skill" (already cross-links). No change needed.
- **`data-modeling/SKILL.md`** — owns *design* ("model attributes not a table of
  records"); keep its Step 0 but trim the mechanics to a link to the datahike
  skill (it already declares "mechanics belong to the `datahike` skill").
- **`datahike-primer.md` §0** — the deepest source-grounded treatment; keep as the
  depth reference, link to it from the skill (already done).
- **root `CLAUDE.md`** — keep the rule (it must be always-in-context) but it can be
  the tightest statement + "full patterns: `/datahike` skill". Already concise.

Net: collapse the THREE full skill copies to ONE (datahike) + two one-liners +
cross-links. No information lost; one place to update when the patterns evolve.
This is the structural fix for "re-derived three times" — and pairs with the code
fix (the inventory no longer SHOWS "kind", so agents stop copying it).

## Settled

- The recurrence driver was the **agent-facing inventory rendering "per kind"** —
  now purged and live-proven gone. That is the load-bearing fix.
- Entity grouping is **always by an attribute** (a namespace or an id-attr), never
  a kind. `store-inventory` → `:seon.db/attr-groups` keyed by `:seon.db/attr-ns`.
- Category B (error/warn/render-event value-classes) is a separate, owner-decided
  taste call — not touched here.
