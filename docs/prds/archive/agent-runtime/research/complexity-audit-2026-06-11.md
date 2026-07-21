---
type: research
status: active
tags: [research, agent]
---

# Complexity audit — ctx / render / gym (2026-06-11)

Read at sha `2f7de5a` (branch `feature/agent-runtime`). Working tree
had ONE concurrent modification during the audit: a 12-line diff in
`test/seon/gym/driver.cljs` removing the `:context-fidelity` axis —
the in-flight structural-gate ripout. Everything else read clean.

## TL;DR

The user's worry is justified in degree but not in kind. None of the
three subsystems is architecturally wrong — the composer core, the
value-or-fn render dispatch, and the gym's seed→drive→score pipeline
are all sound, small designs. What has accumulated around them is
**incident-driven scar tissue**: ~13 numeric tuning knobs, ~18
special-case rules, 4 copies of one guard fn, 3 truncation helpers, 2
names for the same twin key, 1 attr doing two jobs, and a 190-line
hand-written prose string inside a fn whose docstring claims it is
"DERIVED, never hardcoded". The single clearest signal that speed
outpaced design today: the gym's structural gates were **built
(4070e2c) and ordered deleted (context-v4 r2) on the same day**.

The good news: the simplification is already specced. Context-v4 r2
(`context-v4-repl-realism-2026-06-11.md`) and live-tiles §8 together
name almost every removal this audit independently found, and the v4
governing rule ("MUCH simpler — no lists, no budgets, no structural
coupling") is the correct prescription. The honest summary of ctx:
**roughly 55-60% of `seon.ctx` is scheduled to die under v4**, and
the simple composer that remains is ~700-800 lines. The risk is not
the destination but the temptation to start the ladder before
tomorrow's demo. **Pre-demo: finish the gym gate ripout (in flight),
then freeze. Post-demo: run V4-2 → V4-3 → render one-path sweep →
gym boot-sharing, in that order.**

## 1. seon.ctx — 2,269 lines, 73 defns, 26 register! calls

### 1a. Complexity inventory (counted)

- **Section fns: 10** in `substrate-default-ctx` (system,
  instructions→`my.kb.instruction`, capabilities, exemplars,
  schema-catalog, functions-catalog, namespace-context, warnings,
  open-todos→`seon.agent.todo`, transcript, prompt) + 2 seeds
  (`:purpose` text, `:your-sections` fn).
- **Numeric config surfaces: 11 in this file** — `default-turns-cap`
  20, `eval-render-cap` 1500, `message-render-cap` 4000,
  `transcript-char-budget` 24000, `agent-section-char-budget` 8000,
  `fn-source-inline-threshold` 240, `member-doc-clip` 280,
  `fn-catalog-brief-max` 8, transcript default n 50, evals default
  n 20, messages default n 50 — plus the 13-value priority ladder
  (10, 12, 13, 15, 20, 22, 25, 27, 30, 40, 45, 50, 99), which is a
  list-shaped coupling in disguise.
- **Truncation/clipping fns: 5** — `truncate-edn`, `cap-result`,
  `cap-result-body` (same job, plus a "narrow your query" guide that
  must NOT fire on errors — a special case on a special case),
  `clip`, `squash-one-line`.
- **Special-case rules: ~18.** The big ones: `uncounted-kind-id-attrs`
  carve-out + `fuzzy-count` bucketing (both exist ONLY because live
  counts sit in the cacheable prefix); message-exemption in transcript
  eviction; the `relevant-roots` set + test-sibling rule + stub-source
  detection; the empty-ns nudge + the own-ns "not in db" mislabel
  suppression (a string-compare against the section's own output —
  line 1619); `finding-claims-block` selecting attrs literally NAMED
  `claim` (`(= "claim" (name %))` — magic-name dispatch, a direct
  violation of the project's own uniformity-canary principle); the
  curated `capability-syms` list; the three-tier turn-pressure nudges;
  the hop-cap filter in `turns-since-inbound`; the FilteredDB
  `db-schema` guard (copy 1 of 4).
- **Two divergent read patterns**: `messages` queries the log directly
  (the turn-walk "was the run-3 demo killer"), but `evals` and
  `current-ns` still walk agent → sessions → turns → evals. The same
  bug class that killed messages is latent in the eval leg.
- **`capabilities-section` is ~190 lines of string concatenation.**
  Only the ~7 signature lines are derived from `:seon.fn` rows; the
  remaining ~170 lines are hand-authored doctrine prose. The section
  banner says "DERIVED, never hardcoded" — adversarially: that claim
  is about 10% true. This is the file's single largest block.

### 1b. Load-bearing vs scar tissue

**Load-bearing (keep):**

- The composer itself — `merge-sections` (override-by-name),
  `render-section` (string|symbol slot + inline-error guard),
  `assemble-context`. ~150 lines, clean, the one-composer invariant is
  real (gym + inspector + agent all call it).
- `eval-render-cap` + `message-render-cap` + the message-exemption
  eviction: each fixed an OBSERVED failure (9.7M-char pull blowing
  the prompt; S-12's user message evicted by an eval burst). These
  survive v4 (§2.8 keeps them explicitly).
- The provenance classifier rule (agent-id present AND NOT
  `:substrate-seed` origin) — validated against the live store, shared
  with `seon.warn`.
- The static→volatile ordering contract (real provider-cache money).

**Scar tissue / scheduled to die (v4 names every one):**

- `capabilities-section` + `callable-sigs`/`arglist-vectors` (string
  re-parsing of stored arglists) — V4-6.
- `exemplars-section` + `relevant-roots` + `relevant-ns?` +
  `relevant-sort-key` + `namespace-context-section` (two sections, one
  job: "show namespace source"; plus the empty-ns nudge and the
  mislabel patch) — V4-2 collapses to ONE rule, one section.
- `schema-catalog-section`'s 4 sub-blocks + `fuzzy-count` +
  `uncounted-kind-id-attrs` + `catalog-type-str` + the claim-name
  magic, and `functions-catalog-section` + `fn-catalog-brief-max` —
  V4-3's `<store>` at the volatile tail. Note the elegance: moving the
  inventory BELOW the cache boundary deletes the entire
  cache-stability epicycle (fuzzy counts, uncounted carve-out) as a
  side effect. That's the "one structural rule replaces N nominal
  ones" pattern done right.
- `agent-section-char-budget` + `apply-agent-budget` (the
  lowest-priority truncation walk) — v4 r2 says no budgets except the
  transcript's. ~60 lines.
- `:your-sections` seed + `own-sections-section` — replaced by
  `<your-entity>` (a pull pretty-printed).
- `render-namespace`'s dual `:ai`/`:html` format — the html leg serves
  only the inspector ns view; once entity cards are the one html
  surface it can go or move.

### 1c. The simplest v4-serving composer (concrete fn list)

```text
;; composer (unchanged core)         ~150 lines
assemble-context  merge-sections  render-section  decode-section
agent-sections    apply nothing (no agent budget)

;; section fns — 9, each small      ~350 lines total
system-section      ;; ~30 lines, byte-stable, no agent id
soul-section        ;; my.soul rows (exists, eeeb562)
namespaces-section  ;; ONE rule: all seon.*+my.* except *.internal,
                    ;; recency-ordered; <namespace name=…> tags
your-entity-section ;; pull own entity, pprint map, 3-line header
your-tile-section   ;; the ::ai twin + wired pointer (tiles U5)
store-section       ;; one line per kind: kind · id-attr · LIVE count
warnings-section    ;; unchanged (delegates to seon.warn)
transcript-section  ;; unchanged mechanics + REPL rendering (V4-4)
prompt-section      ;; status line + <ns>=> (V4-5)

;; read API                          ~150 lines
resolve-id  messages  evals  current-ns  current-session
turns-since-inbound  turns-cap  home-ns

;; transcript formatting             ~150 lines
format-eval-row  format-message-row  message-label
ONE cap fn (replaces cap-result/cap-result-body/truncate-edn/clip)
```

Estimate: **~750-850 lines, ~35 defns** — almost exactly one third of
today's file. Config surfaces drop from ~11+priorities to 4
(turns-cap, eval cap, message cap, transcript budget).

### 1d. Migration cost

The v4 ladder IS the migration: 7 units (V4-0 … V4-6), each ≤7 files,
each gym-scored. V4-3 is correctly flagged riskiest (consult-first
leaned on the catalog's attr listing; the gate is the S-32/S-12 paid
runs). Nothing in this audit suggests the ladder is mis-sized. Add
ONE unit the PRD doesn't have: **unify the eval read leg with the
message read leg** (query the eval log directly, drop the turn-walk)
— same bug class, ~1 unit.

## 2. seon.render.* + inspector — the render paths

Files: `render.cljs` 589, `render/live_tile.cljs` 358,
`render/chat.cljs` 184, `render/default.cljs` 208,
`web/inspector.cljs` 1,442. 15+8+10+0 register! calls across the four
render files.

### 2a. Path inventory (counted)

**Distinct HTML production paths: 5 (+1 floor).**

1. **The prompt's html mirror** — none today for sections (ai only),
   but `render-namespace :html` renders ns cards via FOUR per-kind
   handlers (`seon.handlers.{ns,fn,schema,test}/render-html`).
2. **Entity cards** — `visible-entities` (per-kind `d/datoms` AEVT
   scan → per-tx provenance memo → `render-cap` 100 → pull →
   `entity-primary-kind` specificity match → subsumed-kinds drop) →
   `render-entity-html` → `entity-html-sym` (per-entity override →
   kind default) → `html-render`.
3. **The agent tile** — `render-agent-tile` → `wired-content` (3-step:
   `::content` → legacy `:seon.render/html` → welcome) →
   `html-render` → `error-response` on throw.
4. **Chat bubbles** — `chat/conversation` → `bubble-stream` →
   `bubble` (3-way case), built on `default/recent-messages`.
5. **Inspector hand-hiccup** — `finding-card`, `unknown-entity-card`,
   `agent-grid-tile` (with its own inline mini-default-tile, drift
   item 4), header/pane fragments — ~25 private hiccup fns.
6. Floor: `default/pretty-html`.

**Distinct AI/text paths: 3** — assemble-context sections;
`render-entity-ai` (kind/per-entity, used by inspector cards); the
tile's `::ai` twin. Plus `default/pretty-ai` floor.

**Distinct dispatch MECHANISMS: 3** — (a) priority-sorted section
maps; (b) value-or-fn slot (`html-render`'s 3-way cond — the good
one); (c) kind-specificity matching (`entity-primary-kind`: every
required-attr present, most-required wins, alpha tiebreak, plus the
`kind-tables` identity-keyed cache, plus the `subsumed-kinds` set,
plus the installed-schema gate, plus the belt-and-suspenders comment
admitting the gate exists twice).

### 2b. Confirmed drift (verified at this sha)

- **`:seon.render/html` double duty** — confirmed: `entity-html-sym`
  reads it for cards AND `wired-content` step 2 reads it as the legacy
  tile slot. Live-tiles §8.1 already orders the fallback deleted.
- **`:seon.render/text` vs `:seon.render/ai`** — confirmed;
  `:seon.render/ai-response` is literally an `:or` of two map shapes
  papering over the two producer families. §8.2 orders the sweep.
- **Forwarding def** — `seon.render/valid-hiccup?` forwards to
  live-tile (render.cljs:66), self-documented as "delete after the
  sweep".
- **4th copy of the FilteredDB schema guard** — confirmed exactly 4:
  `render.cljs:347`, `warn.cljs:341`, `ctx.cljs:1124`,
  `live_tile.cljs:251` (the last one's own docstring says "fourth
  copy; wants a home in seon.db").
- **`:seon.db/conn` double registration** — NOT reproduced at this
  sha: exactly one `register!` (render.cljs:50). The REAL defect is
  adjacent: `:seon.db/db`/`:seon.db/conn` are registered in
  `seon.render`, not `seon.db` (violates schemas-live-with-the-owner),
  which forces TWO more inline-`:any` copies in load-order-earlier
  nses (`live_tile` `::user-name-request`, `chat`
  `::conversation-request`) — each with its own apology comment.
- **THREE representations of "hiccup"**: the deep recursive registered
  `:seon.render/hiccup` ("kept for documentation purposes"), the
  shallow `::hiccup` bound in live-tile, and the plain
  `valid-hiccup?` predicate. One concept, three artifacts, two
  rejected-approach essays explaining why.

### 2c. How many paths are needed — the ONE-path sketch

Needed: **one dispatch mechanism (value-or-fn), two twins (html/ai),
two resolution steps (per-entity attr → kind default).** Concretely:

```text
html-render / ai-render        ;; the ONE value-or-fn dispatch (exists)
resolve-render-slot            ;; per-entity attr → :seon.schema kind
                               ;; default (entity-html-sym/-ai-sym,
                               ;; merged into one fn, one decode)
render-entity                  ;; entity → {html, ai} via the above
visible-entities               ;; selection only (provenance + window)
;; the tile = render-entity OF the agent's own entity with the
;; ::content attr as its per-entity slot — not a separate path
;; chat bubble / welcome / per-kind handlers = ordinary renderer FNS
;; reached through the one dispatch, not separate mechanisms
```

`entity-primary-kind`'s specificity scoring can likely collapse to
"the kind whose id-attr is present" (id-attrs are how candidates were
DISCOVERED anyway — the AEVT scan already knows the kind; carrying
`disc-kinds` through and picking the most-specific only matters for
multi-kind entities, which the subsumption set mostly excludes).
Verify with one query before deleting.

### 2d. Migration cost

- live-tiles §8 items 1+2+4 (retire html double-duty, text→ai sweep,
  inline fallback) — **1-2 units** (mostly deletions + one rename
  sweep; the producers are `seon.handlers.*` + `pretty-ai`).
- `db-schema` guard → ONE fn in `seon.db`, delete 4 copies, move the
  two `:seon.db/db`/`conn` registrations into `seon.db` and delete
  the inline-`:any` apologies — **1 unit**, pure consolidation.
- Delete the deep `:seon.render/hiccup` + the forwarding def — rides
  the sweep unit.
- Tile-as-entity-render unification — **1 unit**, post-demo only
  (touches the demo's hero surface).

## 3. Gym driver — 1,424 lines, 39 defns, 74 register! calls

### 3a. Inventory

- **350 lines (25% of the file) are schema registrations.** 74
  `register!` calls for the scenario/predicate/result/scorecard
  vocabulary. Honest assessment: this is the house style applied
  faithfully, and it's what makes scenarios validatable EDN — but the
  `expect` DSL (7 operators), the 10-kind predicate enum, and the
  turn-profile shapes are a lot of vocabulary for 4 active scenarios.
- **Predicate machinery: 10 kinds** (datalog, transcript-includes/
  excludes, first-eval-matches, eval-count-matching, domain-attrs,
  3 prompt-blob kinds, llm-judge) × 7 expect operators.
- **4 LLM drive modes** (per-turn-script, scripted-replay, rejecting,
  deepseek) — each exists for a named reason (the stub self-wake bug;
  provider-failure fixture; paid tier). Defensible.
- **Structural profiles (U3): ~130 lines** (`capture-turn-profile`,
  `prefix-section-texts`, `first-char-diff`, `prefix-diff-detail`,
  `structural-results`, the profile schemas, the scorecard field) —
  built today, condemned today (v4 r2 §1), partially ripped out in
  the concurrent working-tree diff. The scorecard schema still
  REQUIRES `:seon.gym.scorecard/turn-profiles` at this sha — the
  ripout must remember to make it optional/delete it or every card
  fails its own validation.
- **`seed-scenario-world!`: ~130 lines that hand-mirror
  `seon.client/start-agent!`** — same calls, same order, by copy. The
  file's own docstring records this parity drifting TWICE (iteration
  1 missing entity-schema decomposition → hid the S-32 catalog bug
  for a whole sweep; iteration 2 missing the test roster → exemplars
  rendered 4/7 blocks). This is the gym's most dangerous complexity:
  not big, but a standing divergence generator.
- Ceremony that is actually load-bearing: the self-bait check (a real
  observed false-pass class), run-id uuids (real double-fire class),
  fixture date placeholders, the fail-RED-never-silent discipline in
  prompt predicates, the conn/registry restore in `finally`. Keep all.
- One workaround encoding an upstream bug: `agent-reply-text`
  fetch-then-filters in CLJS because datahike-cljs mis-binds
  double-identity-attr joins — correctly documented, but it means a
  query-engine bug is now load-bearing gym behavior.

### 3b. What the user's stated job needs

Seed a world, drive turns, capture prompt blobs + db state, run
behavioral predicates + LLM judge, emit scorecards. Against that:

- **Core: ~60%** — load/validate, seed, the 3 drive paths, predicate
  eval, judge, scorecard, restore.
- **Harness ceremony: ~25%** — the schema vocabulary breadth (could
  serve 40 scenarios; serves 4), the expect-operator DSL beyond
  non-empty/count, multi-agent designators (used by one scenario).
- **Condemned: ~10%** — structural profiles + `:context-fidelity`
  (in-flight removal).
- **Wrong-shaped: ~5%** — the hand-mirrored world seed.

### 3c. The simpler shape (fn list)

```text
load-scenarios!  check-self-bait!                 ;; keep
seed-world!   ;; CALLS the same fn start-agent! calls — extract
              ;; seon.client/boot-seed! and share it; the gym adds
              ;; only the prior-agent layer (~40 lines, not 130)
run-scenario! ;; conn swap + drive + score + finally  (keep, minus
              ;; profile capture)
drive: scripted-llm  replay-llm  rejecting-llm  drive-stub-turns!
       drive-loop!  ensure-agent!  send-user-message!     ;; keep
score: eval-predicate (10→8 kinds after gate ripout)
       expect-pass?  prompt-blob readers  judge fns       ;; keep
print-scorecard!                                          ;; keep
```

Estimate post-ripout + boot-sharing: **~1,000-1,050 lines** — the gym
is the LEAST oversized of the three; its problem was pointing
predicates at context structure, not its size.

### 3d. Migration cost

- Finish the gate ripout (in flight) — **0 extra units**; verify the
  scorecard schema's `turn-profiles` requirement goes with it.
- Extract `seon.client/boot-seed!` and call it from both the pod boot
  and the gym — **1 unit**, kills the twice-bitten parity class
  permanently.

## 4. Ranked simpler path forward

Demo is 2026-06-12. Ordered by correctness-leverage per unit of
effort; pre-demo-safety marked explicitly.

| # | Action | Leverage | Effort | Demo-safe? |
|---|--------|----------|--------|------------|
| 1 | Finish the gym structural-gate ripout (incl. making `turn-profiles` optional/deleted in the scorecard schema) | high — un-couples the referee from every future context change | in flight | **YES — pre-demo** (net deletion, gym-only) |
| 2 | **Freeze everything else until after the demo.** No V4 unit, no render sweep. The current context passes the paid scenarios; every item below changes prompt bytes or the hero surface | highest single decision available | zero | **YES (it IS the demo-safety)** |
| 3 | V4-2: namespaces one-rule (`relevant-roots` + exemplars + namespace-context die; ~250 lines) | high — deletes the fragile list AND a whole duplicate section | 1 unit | post-demo |
| 4 | V4-3: catalogs → `<store>` at the volatile tail (fuzzy-count, uncounted carve-out, claim-name magic, arglists parsing all die; ~450 lines) | highest line-count + special-case kill in the codebase; gym-gated (consult-first risk is real — respect the PRD's hard gate) | 1 unit + paid runs | post-demo |
| 5 | Gym boot-sharing: extract `boot-seed!`, gym calls it | high per line — closes the recurring parity-drift class | 1 unit | post-demo (touches boot) |
| 6 | Render drift sweep: retire `:seon.render/html` tile duty, text→ai rename, delete forwarding def + deep hiccup schema, `db-schema` → `seon.db` (+ move the db/conn registrations home) | medium-high — collapses 4-copy + 2-name + double-duty drift in one pass | 1-2 units | post-demo |
| 7 | V4-0/V4-1/V4-6: instructions→soul, system rewrite, capabilities dissolution (the 190-line string dies) | medium — big token + prose wins, behavioral risk gym-absorbed | 3 units (per PRD) | post-demo |
| 8 | Collapse the 5 truncation helpers into one cap fn; delete `apply-agent-budget` (v4: no budgets) | medium — removes the cap-on-cap special cases | 1 unit | post-demo |
| 9 | Unify the eval read leg with the message leg (drop the turn-walk in `evals`/`current-ns`) — NOT in any PRD yet | medium — same latent bug class that killed run 3 | 1 unit | post-demo |
| 10 | `entity-primary-kind` → id-attr-presence rule (verify multi-kind overlap first) | low-medium | 1 unit | post-demo |

### The honest outside-view paragraph

Today's ~15 commits were not reckless individually — almost every
special case cites a real observed incident, and the PRDs faithfully
record the debt they create. The fragility comes from the AGGREGATE:
three subsystems each carrying one day's worth of patches on top of
yesterday's patches, with the correction (v4 r2's "MUCH simpler")
arriving the same day as some of the patches it condemns (gym U3
lived under 12 hours). The system self-diagnosed correctly — the v4
PRD's governing rule and ladder match this audit's independent
findings almost item for item. What the audit adds: the eval
turn-walk (item 9) and the gym scorecard-schema landmine (item 1) are
not in any PRD; the `:seon.db/conn` "double registration" in the
folklore is actually a wrong-home registration plus two inline-`:any`
copies; and the strongest single move available is the cheapest one —
**don't start the ladder until the demo is over.**
