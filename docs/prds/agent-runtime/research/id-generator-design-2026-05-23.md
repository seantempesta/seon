---
type: research
status: active
tags: [research, id, agent, schema, design]
---

# Unified Id Generator — Design for LLM-as-Primary-Reader

## TL;DR

Replace the 12-char base62 `seon.agent/new-id!` with a unified **typed-prefix +
time-encoded letter-leading base52 body** generator living in a new
`seon.id` namespace. Shape: `<kind>_<10-char-base52-time><4-char-base52-rand>` —
e.g. `agt_mFkPqRtVxB7yQ4` (19 chars). This satisfies every constraint that
matters: **always letter-leading** (namespace-derivation safe, no special-case
for `home-ns`); **lex-sortable by time** (base52 alphabet `A…Za…z` is monotonic
in ASCII at every position); **LLM-readable kind** (`agt_`, `msg_`, `trn_`,
`ses_`, `evl_` are unambiguous in eval-log narration); **LLM-readable time**
(coarse time visible from the first 2-3 chars — `m…` is later than `k…` in a
given epoch window). Land this BEFORE the SQLite flip (Task #3) so no persisted
ids are migrated. UUIDs / random-UUID call sites elsewhere (konserve, SSE
clients, flow request-ids) are out of scope — they have different requirements
(opacity, JVM/cljs parity via `:uuid` schema type) and changing them adds
migration burden without LLM-readability payoff.

## 1. Current state — every id generator and call site

### 1a. CLJS pod (the lane being changed)

| Generator | File:line | Shape | Used for |
|---|---|---|---|
| `seon.agent/new-id!` | `src/seon/agent.cljs:132` | 12-char base62 (`0-9A-Za-z`); 8-char `Date.now()` base62 + 4-char random | All agent entity ids — `:seon.session/id`, `:seon.turn/id`, `:seon.message/id`, `:seon.eval/id` (via local copy) |
| `seon.eval/new-eval-id` | `src/seon/eval.cljs:493-509` | Same algorithm, duplicated to avoid eval↔agent require cycle | `:seon.eval/id` for every form in `eval-batch!` |
| `seon.agent/default-id` | `src/seon/agent.cljs:404-408` | Hardcoded `"seon"` (4 chars) | V0 default agent id; **violates `:min 12 :max 12`** on `:seon.db/agent-id` (db.cljs:328) |
| `seon.agent/home-ns` | `src/seon/agent.cljs:277-281` | `(symbol (str "seon.agent." agent-id))` | Derives ns from id; **breaks if id starts with digit** |
| `(random-uuid)` — konserve store ids | `client.cljs:137,163,285`, `db_test.cljs:61`, `wasm_smoke.cljs:42`, `web/serve.cljs:138`, `web/reactive/demo.clj:62,145-146` | UUID v4 | Opaque store/connection identity; not read by the agent |

**Call sites of `new-id!` in agent.cljs:** 446 (message), 519 (session), 610 (turn message), 636 (turn), 707 (system message). Also referenced in user-visible help text at `render/default.cljs:285` and `client.cljs:474`.

**Identity-attr schemas requiring `{:min 12 :max 12}`** (must update if length changes):

- `:seon.message/id` — agent.cljs:171
- `:seon.eval/id` — agent.cljs:183
- `:seon.session/id` — agent.cljs:218
- `:seon.turn/id` — agent.cljs:226
- `:seon.db/agent-id` — db.cljs:328
- `:seon.db/session-id` — db.cljs:329
- `:seon.db/turn-id` — db.cljs:330
- `:seon.db/eval-id` — db.cljs:331

`:seon.agent/id` (agent.cljs:146) is currently SCHEMA-LOOSE (`[:string {:seon.db/identity true}]`, no length) — explicit holdout while `default-id = "seon"`. The comment at agent.cljs:212-215 documents this.

### 1b. JVM side (out of scope; documented for completeness)

| Generator | File:line | Shape | Used for |
|---|---|---|---|
| `seon.runtime/generate-id` | `src/seon/runtime.clj:297` | UUID-ish, with optional prefix | Shared id factory; called by `ctx.clj:196`, `session.clj:321`, `ai.clj:315`, `web/browser.clj:295`, `ns/lifecycle.clj:391`, `orchestrator/session.clj:188` |
| `(random-uuid)` | Many JVM sites (flow, repl, sse, datahike) | UUID v4 | Flow `::msg/id`, trace-ids, sse client-ids — all carried as Malli `:uuid` type |

JVM ids are `:uuid`-typed at the schema layer and serialized to Datahike's `:db.type/uuid`. Switching them to base52 strings would force schema-type churn across ~25 files for zero LLM-readability payoff (these ids never appear in the agent's eval log). **Leave JVM uuids alone.**

## 2. Constraints the new generator must preserve

1. **Lex-sortable by creation time** — `seon.agent/new-id!` docstring promises it; downstream queries rely on id ordering instead of a separate `:created-at` index.
2. **Namespace-safe** — `home-ns` derives `'seon.agent.<id>` directly. Clojure ns segments must start with a letter (Unicode `Letter` class; ASCII `[A-Za-z]` is the safe subset). **First character of the id body MUST be a letter.**
3. **URL-safe / file-path-safe** — `/chat?agent=<id>` (agent.cljs:406) and any future file persistence. Alphanumeric + `_` is the safe set; avoid `/`, `+`, `=`.
4. **No on-disk migration burden** — V0 runs `:memory`; SQLite flip (Task #3 in resume-design) hasn't shipped. Land the new ids first.
5. **Schema-stable shape** — fixed length so the `{:min N :max N}` constraint stays tight (collision-detection at the boundary).
6. **Single source of truth** — eval.cljs and agent.cljs both implement the same algorithm today (require-cycle workaround). The new namespace must be require-able from both without cycles.

## 3. ASCII / lex-sort proof for candidate alphabets

ASCII codepoints: `0`-`9` = 48-57, `A`-`Z` = 65-90, `_` = 95, `a`-`z` = 97-122.

| Alphabet | Lex-sorts (positional, fixed-width)? | Letter-leading possible? |
|---|---|---|
| Base62 `0-9A-Za-z` (current) | Yes — alphabet is monotonic in ASCII | **No** — `Date.now()` in base62 has digit-leading prefix until 2059 (when ms count ≥ `A*62^7` ≈ 10⁻ ms past epoch, won't happen) |
| Base52 letter-only `A-Za-z` | Yes — `A`(65) < `Z`(90) < `a`(97) < `z`(122) monotonic | **Yes always** — every char is a letter |
| Crockford base32 `0-9A-HJKMNPQRSTVWXYZ` (ULID) | Yes — `0-9` < `A-Z` monotonic | **No** — same digit-leading issue as base62 |
| Base36 `0-9A-Z` | Yes | **No** — digit-leading |

**Verification:** for any fixed-width encoding `enc(n, w)` over a monotonic alphabet, `n1 < n2 ⟹ enc(n1, w) ≤ enc(n2, w)` lexicographically. The current base62 implementation (`int->base62` at agent.cljs:114) already pads with `'0'` which preserves order. Switching the alphabet to `A-Za-z` and the pad char to `'A'` gives the same property with letter-leading guarantee.

**Density:** base52 over 10 chars encodes `52^10 ≈ 1.4×10^17` ms. `Date.now()` today is ~`1.78×10^12` ms → fits in 10 chars with 5 orders of magnitude of headroom (year ~6000 AD). Base52 over 4 random chars = `52^4 ≈ 7.3×10^6` — same collision-risk profile as the current 4-char base62 random (`62^4 ≈ 1.5×10^7`); both fine for single-pod-session use.

## 4. Design alternatives

### (a) ULID — `01HXYZABC...QRSTVWXYZ` (26 chars)

**Pros:** industry standard (LLMs see it in training data); monotonic within a millisecond; URL-safe Crockford base32; 128 bits of entropy.

**Cons:** **starts with a digit** until year 2065 — same `home-ns` bug we already have. Would require either prefixing (`agt_01HX…`) or special-casing ns derivation. 26 chars is a lot of visual noise in the eval log. Crockford base32 excludes I/L/O/U for human disambiguation — irrelevant to LLMs. No kind information in the id itself.

### (b) Letter-leading base52 time-encoded body — `MfKpQrTvXb7y` (12 chars)

**Pros:** drop-in length-compatible with the current 12 chars (`:min 12 :max 12` schemas don't change!). Always letter-leading (ns-safe). Lex-sorts by time. Minimal churn.

**Cons:** no kind prefix → LLM still has to infer `:seon.message/id` vs `:seon.turn/id` from surrounding context. Loses ~2 bits per char vs base62 (collision-irrelevant). Mixed-case looks similar across kinds — `MfKpQrTvXb7y` could be anything.

### (c) Typed-prefix + letter-leading base52 body — `msg_MfKpQrTvXb7y` (16 chars; with 4-char prefix `kind_`)

**Pros:** **LLM-self-documenting** — `agt_…`, `msg_…`, `trn_…`, `ses_…`, `evl_…` are instantly parseable in narration ("the agent received `msg_MfKp…` and emitted `evl_MfKr…`"); kind disambiguation works even without surrounding `:seon.message/id` attribute. Letter-leading (prefix is letters). Time-sortable within a kind. Prefix-stripped tail still works as a unique-attr value. Sortable AND grouped in textual output. Forward-compatible (new kinds get new prefixes).

**Cons:** longer by 4 chars (16 vs 12) → every `{:min :max}` schema updates. Need a per-kind `new-id!` API (`(new-id! :message)` or `(msg-id!)`). Cross-kind lex-sort is alphabetic by prefix not by time (`agt_…` < `msg_…` regardless of when created); time-sort is per-kind. (This is usually what you want.)

### (d) Crockford base32 with leading-letter forcing

Like ULID but force first char to a letter by rotating/biasing the time field. Possible but ugly — breaks the simple "encode the ms" mental model and produces ids that look like ULIDs but aren't.

### (e) Pre-trained-format keep-current

Stay with base62, just fix the two bugs narrowly (rotate to letter alphabet for the prefix char; make `default-id` call `new-id!`). Smallest change. **Misses Sean's framing** — doesn't make the LLM smarter at reading ids.

## 5. Recommendation — option (c)

**Adopt typed-prefix base52: `<kind>_<10-char-time><4-char-rand>` = 16 chars total.**

```clojure
;; src/seon/id.cljs
(ns seon.id)

(def ^:private alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz") ; 52 chars

(def ^:private kinds
  {:agent   "agt"
   :session "ses"
   :turn    "trn"
   :message "msg"
   :eval    "evl"})

(defn- encode [n width]
  (loop [n n acc ""]
    (if (and (zero? n) (>= (count acc) width))
      acc
      (recur (js/Math.floor (/ n 52))
             (str (nth alphabet (mod n 52)) acc)))))

(defn- pad [s width]
  (str (apply str (repeat (- width (count s)) "A")) s))

(defn new-id!
  "Generate a fresh typed id. Shape: <kind-prefix>_<10-time><4-rand>.
   Lex-sorts by creation time within a kind. Always letter-leading
   (namespace-derivation safe). 16 chars total."
  [kind]
  (let [prefix (or (kinds kind)
                   (throw (js/Error. (str "Unknown id kind: " kind))))
        t      (pad (encode (.now js/Date) 10) 10)
        r      (apply str (repeatedly 4 #(nth alphabet (rand-int 52))))]
    (str prefix "_" t r)))

(defn kind-of
  "Return the kind keyword of an id, or nil if unrecognized."
  [id]
  (when (and (string? id) (>= (count id) 4) (= \_ (nth id 3)))
    (some (fn [[k p]] (when (= p (subs id 0 3)) k)) kinds)))
```

**Why this and not the others:**

- **LLM-readability is a primary goal, not an afterthought.** Sean's framing — "the LLM who can see the id and have some understanding" — only pays out with kind prefixes. Time-encoding alone (option b) gives ordering but not categorization, and in eval-log narration the id often appears without its attribute name.
- **`home-ns` becomes safe by construction.** No need to special-case digit-leading or rotate alphabets — every id starts with a letter, period. The agent-ns is `(home-ns "agt_MfKp…")` = `'seon.agent.agt_MfKp…` — valid ns segment.
- **Per-kind ordering matches query intent.** Queries that sort by id almost always filter by attribute first (`:seon.message/id` etc.); cross-kind sort is rarely meaningful.
- **ULID's brand recognition isn't worth its bug surface.** ULIDs starting with a digit means we'd re-hit the exact `home-ns` failure. Adding a prefix to ULID (`agt_01HX…`) makes them 30 chars — long without proportional benefit.
- **`default-id` becomes `(new-id! :agent)`.** No more hardcoded `"seon"`. Each pod boot mints a fresh agent. The agent's ns becomes `'seon.agent.agt_<time><rand>` — agents can coexist in the same compile-state with no name collisions (forward-comp with multi-agent V1).

**Honest trade-offs:**

- All identity-attr `:min`/`:max` schemas change from 12 to 16. ~10 sites in agent.cljs + db.cljs. Mechanical edit, fully greppable.
- Test fixtures and any test that hardcodes a 12-char id need updating. Likely 5-15 sites; the dev hook will surface them via instrumentation failures.
- The eval log gets visibly longer ids. Trade-off worth it: `agt_MfKp…` is 16 chars and parseable; `MfKpQrTv` is 8 chars and ambiguous.
- Per-kind `new-id!` API forces callers to declare intent. Slightly more typing; far less confusion. The current bare `(new-id!)` lets you accidentally use a message id where an eval id was wanted; no schema catches it because all four attrs are `[:string {:min 12 :max 12}]` — type-indistinguishable. Per-kind prefixes restore type discipline at the data layer.

## 6. Migration plan

**Ordering: land BEFORE Task #3 SQLite flip** so no persisted ids exist in the new schema.

### Step 1 — create `src/seon/id.cljs`

New namespace, no upstream deps, require-able from both `seon.agent` and `seon.eval` (breaks the existing cycle that forced the duplicate algorithm in eval.cljs).

### Step 2 — update identity-attr schemas

| File | Line | Change |
|---|---|---|
| `src/seon/agent.cljs` | 146 | `:seon.agent/id` → `[:string {:min 16 :max 16 :seon.db/identity true}]` (tighten — currently loose) |
| `src/seon/agent.cljs` | 171 | `:seon.message/id` `{:min 12 :max 12}` → `{:min 16 :max 16}` |
| `src/seon/agent.cljs` | 183 | `:seon.eval/id` same |
| `src/seon/agent.cljs` | 218 | `:seon.session/id` same |
| `src/seon/agent.cljs` | 226 | `:seon.turn/id` same |
| `src/seon/db.cljs` | 328-331 | `::agent-id`, `::session-id`, `::turn-id`, `::eval-id` all `{:min 12 :max 12}` → `{:min 16 :max 16}` |

### Step 3 — replace generator call sites

| File | Lines | Old | New |
|---|---|---|---|
| `src/seon/agent.cljs` | 132-136 | `new-id!` impl | Remove; `(:require [seon.id :as id])` |
| `src/seon/agent.cljs` | 404-409 | `(def default-id "seon")` | Delete; remove `default-ns`; update `:or {id default-id}` defaults in inspector fns (lines 791, 807, 818, 832, 844) to instead pull the live agent's id from a runtime atom set by `boot!` |
| `src/seon/agent.cljs` | 427 | `(create! {:seon.agent/id default-id})` | `(create! {:seon.agent/id (id/new-id! :agent)})` |
| `src/seon/agent.cljs` | 446 | `(new-id!)` | `(id/new-id! :message)` |
| `src/seon/agent.cljs` | 519 | `(new-id!)` | `(id/new-id! :session)` |
| `src/seon/agent.cljs` | 610 | `(new-id!)` | `(id/new-id! :message)` |
| `src/seon/agent.cljs` | 636 | `(new-id!)` | `(id/new-id! :turn)` |
| `src/seon/agent.cljs` | 707 | `(new-id!)` | `(id/new-id! :message)` |
| `src/seon/eval.cljs` | 493-509 | `new-eval-id` impl | Delete; `(:require [seon.id :as id])`; call `(id/new-id! :eval)` at the one use site |
| `src/seon/client.cljs` | 474 | help text snippet | Update to show `(seon.id/new-id! :message)` |
| `src/seon/render/default.cljs` | 285 | help text snippet | Same |

### Step 4 — `default-id` removal cascade

`default-id` and `default-ns` are referenced from inspector fns (recent-messages, etc.) as `:or {id default-id}` defaults. The replacement: `boot!` stores the live agent id in a top-level atom `!current-agent-id` after `create!`; the `:or` defaults resolve via `(deref !current-agent-id)`. This is the V0.5 "one agent per pod" assumption — fine until V1 multi-agent.

URL `/chat?agent=seon` (used in docs/comments at agent.cljs:406) becomes `/chat?agent=agt_…`. Update any hardcoded URLs in `src/seon/web/`.

### Step 5 — test fixture sweep

Grep `rg '"[A-Za-z0-9]{12}"' test/` and `rg ':min 12' test/` to find hardcoded 12-char id literals. Update to 16-char letter-leading literals (e.g. `"agt_AAAAAAAAAAA"` as a deterministic stub).

### Step 6 — verify

- `(user/run-tests)` for JVM-side tests (should be no-op — JVM ids untouched)
- Pod smoke test: `node out/client/main.js`, chat a message, confirm `(seon.agent/recent-messages)` shows `msg_…` ids
- Reload the pod (`bin/seon restart pod`), confirm agent re-boots with a FRESH `agt_…` id (no collision with the previous run's in-memory state)
- Confirm `home-ns` returns a valid symbol (`'seon.agent.agt_MfKp…`) and that `setup-agent-ns!` can create it
- Same-pod-session test in `src/seon/db_test.cljs:61` — `(random-uuid)` for `:store :id` is fine (out of scope); confirm the test still passes

### Step 7 — out-of-scope but tracked

- JVM-side `seon.runtime/generate-id` and `(random-uuid)` call sites stay as-is. They're not read by the LLM agent; switching them adds Malli type churn (`:uuid` → `:string`) across ~25 files.
- If a future agent surfaces an LLM-readability complaint about JVM ids in the eval log (unlikely — they're carried as `tx-meta`, not chat-visible), we'd extend `seon.id` to a `.cljc` and migrate the JVM side then.

## 7. Open questions

1. **Does ClojureScript `cljs.js/eval` actually accept `seon.agent.agt_MfKpQrTvXb7y` as a valid namespace symbol?** The Clojure reader accepts ns segments matching `[a-zA-Z][a-zA-Z0-9*+!_'?<>=.-]*`. Underscore is permitted **inside** but not as the first char. `agt_…` satisfies that (starts with `a`). Worth a 30-second REPL check during implementation:
   ```clojure
   (cljs.js/eval cs '(ns seon.agent.agt_MfKpQrTvXb7y) {} (fn [r] (prn r)))
   ```
2. **Should the prefix be `kind_` or `kind-`?** Underscore is more conventional in id formats (Stripe `cus_…`, GitHub `gho_…`, ULID extensions); also distinguishes the prefix-sep from the kebab in `agent-id`. **Underscore.**
3. **Crockford-style ambiguous-char exclusion?** Skip — LLMs don't confuse `0`/`O` and the base52 alphabet has no digits anyway. Keeping the full A-Za-z range maximizes density.
4. **Should `:seon.agent/id` keep its `{:seon.db/identity true}`?** Yes — required for `[:seon.agent/id <id>]` lookup refs (used throughout `db.cljs`).
5. **Versioning?** No version byte for now. If we ever need to migrate id format again (V2 multi-pod?), the kind prefix gives us a place to bolt on a version suffix (`agt2_…`) without breaking parsers.
6. **`kind-of` precision:** the proposed implementation matches by literal 3-char prefix. If two kinds ever shared a prefix (`msg` vs `mst`), we'd need a longer prefix or registry-driven match. Current 5 kinds are unambiguous.

## 8. Files touched (summary)

**New:** `src/seon/id.cljs`

**Modified:**

- `src/seon/agent.cljs` — remove `new-id!` impl, `default-id`, `default-ns`; update 5 call sites; tighten `:seon.agent/id` schema; update 4 `:min 12 :max 12` schemas to 16; update `:or` defaults
- `src/seon/eval.cljs` — remove duplicated `new-eval-id`; call `id/new-id! :eval`
- `src/seon/db.cljs` — update 4 `{:min 12 :max 12}` to `{:min 16 :max 16}`; update doc comment at 322-327
- `src/seon/client.cljs` — update help-text snippet
- `src/seon/render/default.cljs` — update help-text snippet
- Test fixtures with hardcoded 12-char ids

**Not touched:** all JVM-side `(random-uuid)` and `seon.runtime/generate-id` call sites; `seon.flow/msg`, `seon.flow/trace`, `seon.web/sse/flow`, `seon.db/relay`, `seon.repl`, `seon.dev/context`, `seon.phase2/demo`. All konserve `:store :id` random-uuids (different semantic — store-instance identity, never agent-visible).
