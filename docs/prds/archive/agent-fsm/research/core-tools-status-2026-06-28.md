---
type: research
status: active
tags: [research, agent, toolkit]
---

# Core agent tools — status + token-cost audit (2026-06-28)

> Owner frame: "we have a token explosion (~15k/turn) in the transcript because
> our functions aren't optimized — they dump instead of returning concise, useful
> output." This audit answers, per tool: SPEC'd? BUILT? and HOW BLOATED is its
> output? Every token number below was MEASURED read-only on the LIVE default pod
> (7890) via `seon.ai.tokens/estimate` (chars/4) on what the AGENT actually sees —
> the rendered eval-row value (`pr-str`, capped at `result-body-render-cap` 16384
> chars) for verb OUTPUT, and the rendered context block for always-on cost. No
> writes, no LLM drive, no pod restart. Builds on [[my-utils-audit-2026-06-28]]
> and [[transcript-waste-characterization-2026-06-28]].

## TL;DR

- **There are TWO token explosions, and they are different problems.**
  1. **Always-on: the `:namespaces` block = 13,815 tok EVERY turn** (measured) —
     the rendered tool catalog (`my.*` full-source + the `seon.*` floor
     signatures). This is the fixed ~64% prompt cost #42 targets. It is not a
     "verb dumping"; it is the catalog itself rendered full.
  2. **Accumulated: the transcript** (root = 20,315 tok) — dominated by ONE verb's
     output: `search/grep`. A single broad `(search/grep {:pattern "defn"})`
     returns **4,246 tok** (100 matches, silently TRUNCATED). The
     [[transcript-waste-characterization-2026-06-28]] drive showed grep = 55% of
     root's whole transcript (11,230 tok across 24 calls).
- **The single worst TOOL-output offender is `seon.agent.search/grep`** — 4,246
  tok per broad call, default cap 100 matches, NO per-line length cap, and it does
  not tell the agent how many more hits exist. It is fired in floods (the verb is
  the agent's only "where is X" tool). **Priority 1.**
- **Second: `render-namespace` as a VERB** — agents call it to inspect a ns and it
  returns up to **6,916 tok** (`my.kb`); root re-issued it 4×. **Priority 3.**
- **`my.skills` is NOT a thin wrapper** — it renders **4,317 tok of full source
  always-on** (the load/unload/catalog/seed machinery), the single fattest `my.*`
  ns in the always-on block. The toolkit spec says `my.*` stays thin so full render
  is cheap; this one isn't. **Priority 4.**
- **What's CONCISE and right (leave alone):** `seon.agent.todo` (`tree` 365 tok,
  `status` 49 tok), `my.data` reducers (`sum-by`/`max-by`/`group-sum` return a
  scalar/one row), `my.kb/source-stats` (15 tok), the inventory context block
  (142 tok). The todo surface is the model the others should copy.
- **Live-state finding (flag):** `my.data`, `my.canvas`, `my.ui` are BUILT on disk
  (Jun 28) but have **zero fn rows in the live program graph** — the pod has not
  been `cluster reset` since they were added, so **agents on the live pod cannot
  see their bodies** (render-namespace shows only their schemas). Verified:
  `(nsfns-tok "my.data") => {:n 0 :tok 0}`. They will render once the pod is
  reseeded.

## STATUS TABLE

Token numbers are MEASURED on the live pod. "Output tok" = the rendered eval-row
value body for a realistic call (what lands in the transcript). "Always-on tok" =
the ns's contribution to the per-turn `:namespaces` block. Priority 1 = worst
token offender to fix first.

| Tool | spec'd (toolkit.md) | built | realistic OUTPUT tok | always-on tok | concise / BLOATED | priority |
|---|---|---|---|---:|---|---:|
| `search/grep` (`seon.agent.search`) | yes (`my.search`) | built+tested | **4,246** broad / 1,362 narrow | (sig) 471 | **BLOATED** — 100-match cap, no line cap, silent truncate | **1** |
| `:namespaces` block (the catalog itself) | yes (render rule) | built | n/a (always-on) | **13,815** | **BLOATED** — renders `my.*` full every turn (#42) | **2** |
| `render-namespace` (verb) | implied (`db`/ctx) | built | **6,916** (`my.kb`) / 553 (`message`) | — | **BLOATED** — full ns dump on demand, re-issued 4× | **3** |
| `my.skills` | yes | built+tested | `list` 890 | **4,317** | **BLOATED for a `my.*`** — not a thin wrapper | **4** |
| `db/query` (pull `[*]` pattern) | yes (`db`) | built+tested | **2,823** (all agents `[*]`) / 953 narrow | (sig) 2,825 | mixed — engine fine, `[*]` over-pull is the bloat | **5** |
| `my.kb` | yes | built+tested | `inventory` 642 | 2,817 | OK-ish — full render is a manual, earns it | 6 |
| `db/store-inventory` | yes (`db`) | built+tested | **647** | — (used by inventory block 142) | borderline — could be leaner | 6 |
| `my.data/rows` | (audit proposal) | built, NOT live-indexed | **1,173** (7 rows, pulls `[*]`) | 0 live | OK — aggregation needs full rows | 7 |
| `my.kb.shared` | (instructions singleton) | built | n/a | 1,275 | OK | 7 |
| `message/user` · `message/agent` (`seon.agent.message`) | yes | built | small envelope | (sig) 553 | CONCISE output — but **install-timing bug** drives the grep flood (see below) | — (bug, not bloat) |
| `wait` / `complete` (`seon.agent.lifecycle`) | yes | built | bare keyword | (refer'd) | CONCISE — same install-timing race | — (bug) |
| `seon.agent.todo` (`my.todo`) | yes | built+tested | `tree` **365**, `status` **49** | 3,696 | **CONCISE** — the model to copy | leave |
| `my.data/sum-by`·`max-by`·`group-sum` | (audit) | built, NOT live-indexed | scalar / one row | 0 live | **CONCISE** — good API (drive-proven) | leave |
| `my.kb/source-stats` | (recipe) | built+tested | **15** | (in my.kb) | CONCISE | leave |
| `schema/register!` | yes | built+tested | tx envelope | (in seon.schema) | CONCISE | leave |
| `seon.agent.fs` (`my.files`) | yes | built | paged | (sig) 1,369 | CONCISE (honest paging) | leave |
| `my.canvas` / `my.ui` | yes (`my.canvas`) | BUILT, NOT live-indexed | dual-render envelope | 0 live | unmeasured live (not seeded) | flag |
| `seon.agent.schedule` (`my.schedule`) | yes | built | envelope | — | not measured | — |
| `seon.embed` (`my.recall`) | yes | built, gated OFF (`SEON_EMBED`) | ok?-false fallback | — | n/a | — |
| `my.files`/`my.shell`/`my.test`/`my.code`/`my.blob`/`my.agent` (the `my.*` names) | yes | **NOT built as `my.*`** (live as `seon.agent.*`/`seon.test.runner`; some absent) | — | — | spec-only rename pending | — |

## Per-tool detail (measured)

### Priority 1 — `seon.agent.search/grep`: the worst per-call dumper

Measured, live pod, two realistic calls:

```clojure
;; broad — the shape the root drive fired 24× hunting verb defs:
(search/grep {:seon.agent.search/pattern "defn"
              :seon.agent.search/paths ["/Users/sean/src/seon/src/seon/agent"]})
=> {:ok true :match-count 100 :truncated? true
    :rendered-value-tok 4246 :per-match-avg 42}

;; narrow — a precise, USEFUL search:
(search/grep {:seon.agent.search/pattern "message/user"
              :seon.agent.search/paths ["/Users/sean/src/seon/src"]})
=> {:g2-tok 1362 :g2-count 25}
```

**The specific bloat:**

- **Default cap is 100 matches** (`in/default-max-results`), and it returns ALL
  100 as full `:seon.agent.search/match` maps — each carries the entire
  `:seon.agent.search/line-text` with **no per-line length cap**. A broad pattern
  → 4,246 tok in ONE eval row.
- **Truncation is silent to the planner.** `:truncated? true` is set, but the
  value gives no "100 of N — narrow your pattern" signal at the top, so the agent
  re-greps with a tweak instead of realizing it over-matched. This is exactly the
  24-grep flood in the transcript-waste doc.
- **No grouping.** 100 flat match maps, not "12 files, here are the counts +
  first hit each." The agent must visually scan 100 lines.

**What concise output should look like:**

```clojure
;; a grep result the agent can ACT on without scanning 100 lines:
{:seon.agent.search/ok? true
 :seon.agent.search/match-count 247          ; HONEST total found
 :seon.agent.search/returned 20              ; what's shown
 :seon.agent.search/truncated? true
 :seon.agent.search/hint "247 hits — too broad. Add a :glob or narrow the pattern."
 :seon.agent.search/by-file                  ; GROUPED, count-first
 [{:seon.agent.search/path "…/message.cljs" :seon.agent.search/count 9
   :seon.agent.search/first {:line 142 :text "(defn user …"}}
  …]}                                         ; ~20 file-rows, each ~30 tok ⇒ ~600 tok, not 4,246
```

Cap returned hits low (≈20) by DEFAULT, group by file with counts, lead with the
honest total + a narrowing hint, and cap each preview line (e.g. 200 chars). Keep
the full flat list available behind an explicit `:seon.agent.search/full? true`.
A grouped+capped grep is ~600 tok — a **7× cut on the broadest call** and it kills
the "re-grep because I can't tell I over-matched" loop at the source. (This is
recommendation #3 of the transcript-waste doc, with the measured number.)

### Priority 2 — the `:namespaces` block: 13,815 tok, always-on

```clojure
(seon.db/with-agent "root"
  (fn [] (nsblk/namespaces-block {:seon.agent/id "root"})))
=> "tok=13815 chars=55260"
```

This is the rendered tool catalog and the dominant fixed per-turn cost (#42). It
renders every `my.*` ns in FULL plus the `seon.*` floor as signatures. Per-ns
full-source contributions (sum of `:seon.fn/source` tokens, measured):

```clojure
{"my.kb" {:n 13 :tok 1615}  "my.kb.shared" {:n 3 :tok 684}
 "my.skills" {:n 6 :tok 1401}  "seon.agent.todo" {:n 11 :tok 1914}
 "my.data" {:n 0 :tok 0}  "my.canvas" {:n 0 :tok 0}  "my.ui" {:n 0 :tok 0}}
;; (the 0-row nses are built-but-not-seeded; see live-state finding)
```

Not a "verb dumps" problem — it's the catalog-renders-full rule. The fix is #42
(signatures by default, one worked example, full body on demand, config-driven).
Already owned by Core; called out here for completeness because it, not any single
verb, is the literal "~13k of the prompt."

### Priority 3 — `render-namespace` (verb): up to 6,916 tok per call

```clojure
(defn rn [ns] (T (ctx/render-namespace {:seon.ns/name ns})))
{:my.kb 6916 :seon.agent.todo 8913 :seon.db 2825 :my.skills 11396
 :seon.agent.message 553 :my.data 218 :seon.agent.fs 1369 :seon.agent.search 471}
```

(`my.data` shows 218 only because its fns aren't seeded live — it rendered
schemas only; a seeded `my.data` would be far larger.) When an agent calls
`render-namespace` to orient, it can pull 6–11k tokens into a single eval row, and
the transcript-waste doc shows it re-issued 4× (`render-namespace … :seon.agent.message`
appears in the top duplicate-forms list). Same fix family as #42: default this
verb to signatures + docstring, full body on `{:seon.render/detail :full}`.

### Priority 4 — `my.skills` is a fat `my.*`

```clojure
(nsfns-tok "my.skills") => {:n 6 :tok 1401}     ; fn bodies
(rn :my.skills)         => 11396                ; render-namespace verb output
(sk/catalog-block …)    => 886                  ; the always-on catalog block
(sk/list {})            => 890                   ; the list verb output
```

The toolkit spec ([[toolkit]] §"thin wrappers") says `my.*` stays thin precisely
so rendering it whole every turn is cheap. `my.skills` carries the load/unload +
SKILL.md scan + catalog/skill render machinery inline — it is the heaviest `my.*`
in the always-on block (4,317 tok via the full-source path earlier in the
namespaces measurement). Candidate: push the scan/render plumbing to a
`seon.agent.skills.internal` floor and keep only `load`/`unload`/`list` in `my.skills`.

### Priority 5 — `db/query` with `pull [*]`: 2,823 tok

```clojure
(T (seon.db/query '[:find [(pull ?e [*]) ...] :where [?e :seon.agent/id]]))  => 2823
(T (seon.db/query '[:find [(pull ?e [:my.kb.runtime/title :my.kb.runtime/finding]) ...]
                    :where [?e :my.kb.runtime/finding]]))                    => 953
```

The engine is fine; the bloat is the `[*]` over-pull pattern (a known gym anti-goal,
x12). Not a verb bug — but the `db` manual (`my.kb`) and the always-on guidance
should steer agents to named-attr pulls. `store-inventory` (647 tok, below) is the
right "what's here" entry; the `[*]`-on-everything reflex is the waste.

### Concise — measured, leave alone

```clojure
(seon.db/store-inventory)            => 647 tok   ; grouped attr census — borderline, could trim counts
(blk inventory-block)                => 142 tok   ; the always-on store census block — tight
(with-agent best (todo/tree {}))     => 365 tok   ; 10-todo tree, id+title+status only — EXEMPLARY
(with-agent best (todo/status {}))   => 49 tok    ; roll-up counts — EXEMPLARY
(kb/source-stats)                    => 15 tok
(kb/inventory)                       => 642 tok
(data/rows {:my.data/attr :my.kb.runtime/finding}) => 1173 tok (7 rows, pulls [*] — fine for aggregation)
```

`seon.agent.todo` is the template: every collection verb returns a vector of
**3-key** maps (id/title/status) + a derived roll-up, never full entities. If
`grep`, `render-namespace`, and the `[*]` pattern returned data shaped like
`todo/tree`, the transcript explosion would not exist.

### The correctness bug behind half the waste (not output bloat)

`message/user` / `wait` / `complete` have CONCISE output, but their
`init-message-verbs!` install-timing race + undiscoverability is what PRODUCED
root's 11k-token grep flood (the agent greps `src/seon/agent/` 24× because the
verb won't resolve). Fixing grep's output caps the symptom; fixing the verb
install/discoverability removes the cause. Both are needed; they are orthogonal.
(Charter P0, [[transcript-waste-characterization-2026-06-28]] rec #1.)

## RANKED iteration order (by token-waste-per-use)

1. **`seon.agent.search/grep`** — 4,246 tok/broad call, fired in floods; cap
   returned hits (~20) + group-by-file + honest total/hint + per-line cap. Biggest
   per-call dumper AND the dominant transcript content (55% of root). Highest
   waste-per-use of any single verb. **Pairs with** the message-verb install fix,
   which removes the *reason* for the floods.
2. **`:namespaces` block (#42)** — 13,815 tok every turn. Biggest single fixed
   cost; render-trim `my.*` to signatures + one worked example, config-driven.
   Already Core-owned; the measured headline number for the work.
3. **`render-namespace` verb** — up to 6,916 tok/call, re-issued; default to
   signatures+docstring, full on `:detail :full`. Same fix family as #2.
4. **`my.skills`** — 4,317 tok always-on; demote it from "fat `my.*`" to a thin
   wrapper over a `seon.agent.skills.internal` floor.
5. **`db/query` `[*]` over-pull** — 2,823 tok; not a verb bug but a guidance/manual
   fix (steer to named-attr pulls; promote `store-inventory` as the census entry).
6. **`db/store-inventory`** — 647 tok; minor trim (drop per-attr datom counts, or
   gate them) if a clean sweep is wanted.
7. **No action:** `seon.agent.todo`, `my.data` reducers, `my.kb/source-stats`,
   `schema/register!`, `seon.agent.fs`, the inventory block — already concise.

## Verbatim REPL measurements (live pod 7890, read-only)

```clojure
;; render path confirmed: agent sees pr-str of value, capped at 16384 chars
;; (result-body-render-cap); tokens = chars/4 via seon.ai.tokens/estimate.

(seon.db/store-inventory)            ;=> tok 647
(nsblk/namespaces-block {…"root"})   ;=> "tok=13815 chars=55260"

;; render-namespace verb, per ns:
{:my.kb 6916 :seon.agent.todo 8913 :seon.db 2825 :my.skills 11396
 :seon.agent.message 553 :my.data 218 :seon.agent.fs 1369 :seon.agent.search 471}

;; fn-source totals from the program graph (full-source render unit):
{"my.kb" {:n 13 :tok 1615} "my.kb.shared" {:n 3 :tok 684}
 "my.skills" {:n 6 :tok 1401} "seon.agent.todo" {:n 11 :tok 1914}
 "my.data" {:n 0 :tok 0} "my.canvas" {:n 0 :tok 0} "my.ui" {:n 0 :tok 0}}

;; grep broad:
{:ok true :match-count 100 :truncated? true :rendered-value-tok 4246 :per-match-avg 42}
;; grep narrow ("message/user"):
{:g2-tok 1362 :g2-count 25}

;; todo (agent BnP, 10 todos):
{:todo-tree-tok 365 :todo-status-tok 49}

;; my.data / kb / queries:
{:data-rows-tok 1173 :data-rows-count 7
 :kb-inventory-tok 642 :kb-source-stats-tok 15
 :pull-all-agents-tok 2823 :narrow-query-tok 953}

;; always-on context blocks:
{:inventory-block 142 :skills-catalog-block 886 :skills-list-tok 890}

;; live-state: my.data/my.canvas/my.ui have zero fn rows on the pod (built post-reset)
(nsfns-tok "my.data") ;=> {:n 0 :tok 0}
```

## Smells flagged (not fixed — read-only)

- **`grep` has no per-line length cap** (`seon.agent.search` returns raw
  `:line-text`); a single long matched line can blow a row. Add a preview cap.
- **`my.skills` violates the "`my.*` is thin" invariant** (toolkit.md §thin
  wrappers) — 1,401 tok of fn bodies + the SKILL.md scan inline. Floor-split candidate.
- **`my.data`/`my.canvas`/`my.ui` built but unseeded on the live pod** — agents can't
  see their bodies until `bin/seon cluster reset default`. The `my-data-gym-drive`
  doc already noted adoption tracks render-prominence; on the live pod right now
  `my.data` renders schemas-only, so live agents won't reach for it.
- **`render-namespace` and the `:namespaces` block share the full-source path** —
  fixing one (signature-default) should fix both; don't build two trim paths.
```
