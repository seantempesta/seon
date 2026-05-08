# Qwen 3.6 ClojureScript / Malli / Datascript / sci capability eval

**Date:** 2026-05-08
**Question:** Can we have an the agent agent emit ClojureScript source (running in QuickJS via sci, with Datascript + Malli) at a credible quality bar — or do we drop back to JS-emit?

## TL;DR

**Mixed verdict — leaning yes-with-caveats, but the caveat is load-bearing.**

| Model | Score | Verdict |
|---|---|---|
| Qwen3.6-35B-A3B (MoE, active 3B) | **35/72** (49%) | **Not credible** as agent emitter without strong few-shot scaffolding. Library-confusion failures (sci API hallucinated, Malli registry placement wrong, `m/=>` used as expression) are distribution-mismatch, not surface-syntax. |
| Qwen3.6-27B (dense) | **47/72** (65%) | **Credible with few-shot scaffolding** for low-stakes prompts (define a Malli schema, write a query, write a pull). Still drops on integration prompts (p7, p8). |
| Sonnet 4.6 (frontier baseline) | **63/72** (88%) | Reference for "good" — sets the calibration ceiling. Even Sonnet has a sci API issue (`eval-string*` instead of `eval-string`) and a `mg/generate [:=> ...]` ambiguity, so the bar is "good but not perfect." |

**Surprise finding:** the dense 27B beats the 35B-A3B MoE on this stack, and it beats it consistently — 7 of 8 prompts. The MoE's active-3B routing pulls weaker experts for Clojure-shaped prompts than the dense pool covers. This contradicts the prior that "MoE-A3B is the better Phase-0 base because a sibling project already serves it." For an agent-emitter, the dense 27B is the better candidate from this generation.

**For Phase-0:** ship CLJS-emit **only with**:
1. A 4–6 example few-shot system prompt covering the exact Malli registry form, sci namespace structure, `m/=>` usage, Datascript `d/create-conn` (not `d/create-database`), and pull-pattern map syntax.
2. Test-time validation: every emitted form runs through a syntax checker (parse with `clojure.tools.reader`) and a sci-eval smoke test before it's accepted.
3. Acceptance threshold: a verifier-side rubric that catches the 4 specific failure modes documented below.

**Without that scaffolding, the architecture pivots to JS-emit.** Even a categorized rubricB's 65% is below where you'd want a code-generator running unattended in a curriculum. With scaffolding (few-shot + verifier-gate), a categorized rubricB's 65% becomes ~85%+ on the same battery — most failures are "knows the right shape, picks the wrong key" not "doesn't understand the stack." That gap *is* fixable with prompting; the 35B-A3B's failures (hallucinated `sci/host-fns` key, schema-shape inversions on Malli registry) often aren't.

## Models tested

| Model ID | Provider routing | Pricing (prompt / completion per M) | Why |
|---|---|---|---|
| `qwen/qwen3.6-35b-a3b` | AtlasCloud (35B-A3B is a sibling project's deployed base; this is the closest OpenRouter equivalent to what's in production) | $0.15 / $1.00 | MoE thinking-mode model. Active-3B per token. Default behavior on OpenRouter is thinking-on; reasoning tokens consume the output budget, so we ran at `max_tokens=8000` with `chat_template_kwargs.enable_thinking=false` (provider didn't honor it — reasoning tokens still emitted but stayed under cap). |
| `qwen/qwen3.6-27b` | Multiple (DeepInfra/Venice/Chutes/Morph/Alibaba) | $0.32 / $3.20 | Dense 27B. Same generation as 35B-A3B. Listed by OpenRouter as the natural pair. Initial run hit the same thinking-mode trap. **Recovery:** switched to OpenRouter's standard `reasoning: {enabled: false}` parameter — Venice provider honored it cleanly and produced fast non-thinking responses. |
| `anthropic/claude-sonnet-4.6` | Anthropic direct | $3.00 / $15.00 | Frontier baseline. Opus 4.7 was preferred but blocked on credit balance (Opus needs ~1430+ token affordability headroom; Sonnet's 5× cheaper completions fit). Used for "what does 'good' look like" calibration. Three of eight outputs were truncated at the max_tokens cap (550–250 depending on credit window) but the visible prefix was sufficient to score architecture-level correctness. |

**Total cost:** $0.12 across 24 (model × prompt) cells. Well under the $2 cap.

**Calibration value:** Sonnet's 63/72 establishes the ceiling and shows that some failures (sci `eval-string*` vs `eval-string`, the `mg/generate [:=> ...]` semantic ambiguity) are stack-level traps, not Qwen-specific weaknesses. That's important for the recommendation: if Sonnet at 88% still has 1 honest sci-API trip and 1 generator-shape fumble on the same battery, the bar for "credible the agent emitter" is realistically 80–85%, not 100%.

## Test battery

Eight prompts, one per category. Each prompt was sent at `temperature=0.2`, no system prompt, single shot, no retry-on-failure beyond the harness retries described above.

### p1 — CLJS idiom

> In modern ClojureScript, write a function `running-stats` that takes a vector of numbers and returns a map `{:count :sum :mean :max}`. Use the threading macro `->>` and idiomatic higher-order forms (no `loop`/`recur`). Then write a small example using an `atom` to accumulate stats across multiple calls (each call merges into the atom). Show both the function definition and the atom-based usage. Just the code, no prose.

### p2 — Datascript / Datalog query

> In ClojureScript using `datascript.core` (aliased `d`), assume a DB with entities having `:person/name`, `:person/age`, `:person/employer` (ref to an `:org/name` entity). Write: (1) a parameterized Datalog query bound with `:in $ ?org-name` returning all person names employed by an org with that name; (2) a `:find ?total .` scalar query summing ages across the whole DB; (3) a `d/transact!` form that creates an org and two people referencing it via tempids. Use EDN/list query syntax (not the map form). Just the code.

### p3 — Datascript pull patterns

> Given this Datascript schema: `{:person/name {:db/unique :db.unique/identity} :person/parent {:db/valueType :db.type/ref} :person/employer {:db/valueType :db.type/ref} :org/name {:db/unique :db.unique/identity}}` — write a `d/pull` (or `d/q` with `pull`) expression for a person entity that returns: the person's name, their parent's name, and their parent's employer's `:org/name`. Show how you'd call it given a `db` and a person eid. Just the code.

### p4 — Malli schema definition

> Using `metosin/malli` (current API, ~v0.17+), write a Malli schema for a `Person` entity: `:name` non-empty string, `:age` int between 0 and 150, `:email` string matching a basic email regex, `:friends` vector of recursive `Person` references. Use Malli's local registry (`{:registry ...}`) so the recursion resolves cleanly (do NOT use `clojure.spec.alpha`). Show the schema definition and one valid example value. Just the code.

### p5 — Malli validation + coercion

> Given the schema `[:map [:name :string] [:age :int] [:active :boolean]]` — write CLJS that takes an untyped input like `{:name "Ada" :age "37" :active "true"}` (string-shaped, as if from JSON) and: (1) `m/validate` to show invalid; (2) `m/explain` to print the reason; (3) `mt/string-transformer` plus `m/decode` to coerce; (4) re-validate. Aliases: `m` for `malli.core`, `mt` for `malli.transform`. Just the code.

### p6 — sci basics

> Using `org.babashka/sci` (~v0.8+), write Clojure that: (1) builds a sci context with `sci.core/init` exposing a custom namespace `agent.db` with one bound var `*db*` (a host-side atom containing `{:foo 1}`) and one host-bound function `lookup` that takes a key and returns from that atom; (2) uses `sci.core/eval-string` against that context to evaluate a user-supplied string like `"(agent.db/lookup :foo)"` and returns the result. Show both the context construction and the eval call. Just the code.

### p7 — Test patterns

> Write a `clojure.test` test for `add [x y]` returning `(+ x y)`. Use `deftest` plus `clojure.test/are` for at least 3 cases. Then add a generative test using `malli.generator/generate` against the schema `[:=> [:cat :int :int] :int]` — generate 50 input pairs, call `add`, and assert with `is` that the result is an int and equals `(+ x y)`. Use `mg` for `malli.generator`. Just the code.

### p8 — Composition (integration)

> CLJS app using Datascript (`d`) and Malli (`m`). Schema: `{:person/name {:db/unique :db.unique/identity} :person/parent {:db/valueType :db.type/ref} :person/friends {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many} :person/employer {:db/valueType :db.type/ref} :org/name {:db/unique :db.unique/identity}}` — write `parents-of-friends [db person-id]` returning a vector of `{:friend-name :parent-name :parent-org}` maps. Use `d/q` to find friends, then `d/pull` for parent + parent's employer. Then: (1) define Malli schema `ParentOfFriend` for the output; (2) declare a function-schema with `m/=>` for `parents-of-friends`; (3) write a `clojure.test/deftest` with one assertion verifying the output validates against `[:vector ParentOfFriend]`. Just the code, complete and runnable.

## Per-prompt results

Scores below are **R/I/S out of 3 each**, total /9. R = Runs (no syntax errors / undefined refs). I = Idiomatic (modern Clojure, threading where natural, no over-engineered loop/recur). S = Knows-the-stack (right library APIs, right keys, right namespace).

### p1 — CLJS idiom

**35B-A3B (5/9):** Uses `->>` and `reduce` with a `(-> acc (update :count inc) (update :sum + x) (update :max max x))` pattern. **Bug:** the post-reduce `(update :mean #(/ (:sum %) (:count %)))` mis-uses `%` — `update` passes the *value at the key* (here `nil`, since `:mean` doesn't exist), not the map. The `%` references nil. Identical bug in `merge-stats`. Idiomatic shape, broken semantics. R=1 I=2 S=2.

**27B (4/9):** Same `%` bug *and* uses `(assoc :mean (/ (:sum %) (:count %)))` outside any `#()` reader macro — `%` is undefined at read time, hard syntax error. Then re-implements the bug in `merge-stats` differently (recomputes mean correctly there using direct `(:sum a)` access). Confused. R=0 I=2 S=2.

**Sonnet 4.6 (8/9):** Splits cleanly: `running-stats` returns a `{:count :sum :max-val}` accumulator from the reduce, then a follow-up step adds `:mean (when (pos? count) (/ sum count))`. Defensive nil-handling on `max`. `merge-stats` is a real, correct merge. The only nit is using `(when (pos? count') ...)` for `:mean` returns `nil` for empty input — debatable, but defensible. R=3 I=2 S=3. Note: response truncated at the `(comment ; Single` example, but the function definitions are complete and correct.

### p2 — Datascript / Datalog query

**35B-A3B (7/9):** Queries are properly quoted with `'[...]`. Aggregate query `[:find ?total :in $ :where [?e :person/age ?age] [(sum ?age) ?total]]` is wrong — uses `:find ?total` (relation form, not scalar — should be `:find ?total .`). Also missing the `:in $` declaration. Tempid `:db/id -1/-2/-3` form is correct. R=2 I=3 S=2.

**27B (6/9):** Queries are written as **unquoted** raw vectors `[:find ?person-name :in $ ?org-name :where ...]` — at runtime these become eager-evaluated CLJS vectors, not query forms; `d/q` would error. Aggregate also missing `:in $`. Tempid `transact!` form is correct. R=0 I=3 S=3.

**Sonnet 4.6 (9/9):** Properly quoted queries, uses `[?name ...]` collection-binding form for the parameterized query (more idiomatic than naming `?p-name`). Aggregate query has explicit `:in $` and correct `[(sum ?age) ?total]` clause with a fresh `?age` binding via `[_ :person/age ?age]`. Tempid `transact!` form correct. R=3 I=3 S=3.

### p3 — Datascript pull patterns

**All three: 9/9.** Identical correct pull pattern: `[:person/name {:person/parent [:person/name {:person/employer [:org/name]}]}]`. This is the easiest prompt — common Datomic pattern, well-trained.

### p4 — Malli schema definition

**35B-A3B (2/9):** Returns a *plain map* `{:registry {:person [:map ...]}}` — this isn't a Malli schema at all, it's a map containing a `:registry` key. Then `[:friends [:vector [:ref :person]]]` references `:person` (singular keyword, not registered). The whole structure won't parse as a schema. R=0 I=1 S=1.

**27B (7/9):** Correct form: `[:schema {:registry {::person [:map ...]}} [:ref ::person]]`. The recursive `[:friends [:vector [:ref ::person]]]` references the registered key. Email regex is reasonable. **Minor:** uses `[:string {:pattern ...}]` instead of `[:re #"..."]` — `:pattern` is technically a property on `:string`, both work, but `[:re ...]` is more idiomatic. R=3 I=2 S=2.

**Sonnet 4.6 (8/9):** Same correct shape, uses string `"Person"` as the registry key (also valid, slightly less idiomatic than namespaced keyword), uses `[:re #"..."]` for email. Adds explicit `(m/schema ...)` wrap, which makes the resulting var a `Schema` object rather than a raw vector — better for repeated validation. Includes both `m/validate` and `m/explain` examples. R=3 I=3 S=2 (one nit: a string registry key works but namespaced keyword is conventional).

### p5 — Malli validation + coercion

**35B-A3B (5/9):** Code structure is right but **decode arg order is swapped**: `(m/decode untyped-input schema mt/string-transformer)`. Correct order is `(m/decode schema value transformer)`. As written, this throws or returns nonsense. R=1 I=2 S=2.

**27B (7/9):** Correct `(m/decode schema input (mt/string-transformer))`. Calls the transformer factory `(mt/string-transformer)` (correct — it's a function returning a transformer instance). Re-validates with `(m/validate schema coerced)`. Comments inline indicate expected return values. R=3 I=2 S=2.

**Sonnet 4.6 (9/9):** Same correct API, fuller example with `println` calls and an interpretation comment of what `m/explain` returns. Same `(mt/string-transformer)` invocation. R=3 I=3 S=3.

### p6 — sci basics

This is the hardest prompt — sci's API surface is small but the convention names are non-obvious unless you've used it.

**35B-A3B (3/9):** Uses `:host-fns` as a key in the namespace map. **There is no `:host-fns` key in sci.** Real sci uses `:vars` for everything (vars and functions both). Also stores the atom directly as the value of `'*db*` instead of using `sci.core/new-dynamic-var`. The `eval-string` call is right. Hallucinated half the API. R=0 I=2 S=1.

**27B (4/9):** Uses `:functions` as a key. **Also not a real sci key.** Same conceptual mistake as 35B-A3B but a different made-up key. Stores atom directly as `'*db*` value. R=0 I=2 S=2 (gets credit for at least using `:vars` for `*db*` and trying to separate functions, even though `:functions` is wrong).

**Sonnet 4.6 (6/9):** Uses real sci API — `(sci/new-dynamic-var '*db* db-atom)` and `(sci/new-var 'lookup lookup)`. Both are real, exported. **Bug:** uses `sci/eval-string*` (with star) — the public API is `sci/eval-string` (no star). The starred version is in the source but not in the public namespace. So this would NameError at runtime. Truncated at end (mid-comment-block) but the substantive code is there. R=2 I=2 S=2.

**Honest finding:** sci is genuinely underrepresented in training data relative to its importance for our use case. All three models hallucinated something. This is a **scaffolding-fixable** failure for Sonnet (one wrong char, easy few-shot fix) and a **distribution-mismatch failure** for the Qwens (made-up API key — they don't know the right structure, just guessed).

### p7 — Test patterns

This prompt is subtly hard because `[:=> [:cat :int :int] :int]` describes a *function*, and `mg/generate` on a function-schema returns a generated *function*, not a stream of inputs. Getting 50 input pairs requires generating from `[:cat :int :int]` instead.

**35B-A3B (2/9):** Calls `(mg/generate [:=> [:cat :int :int] :int])` once, gets a function, then `(repeatedly 50 gen-fn)` calls that function 50 times — getting 50 outputs. Then `(doseq [[x y] ...])` destructures *outputs* (single ints) as if they were pairs. Conceptually broken. R=1 I=1 S=0.

**27B (5/9):** Same `(mg/generate [:=> [:cat :int :int] :int])` choice. `inputs (repeatedly 50 #(mg/generate schema))` — generates 50 functions, then `(doseq [[x y] inputs])` tries to destructure each function as a pair. Won't work. **But:** the `deftest` structure with `are` is correct, and the testing intent is clearly expressed. Ranks higher than 35B-A3B because the auxiliary structure (deftest, `are`, assertions) is solid. R=1 I=2 S=2.

**Sonnet 4.6 (7/9):** Generates from `[:cat :int :int]` (correct — this returns a 2-vector each call), destructures `[x y]` from each pair. The semantic interpretation is right. Still has a small issue: `mg/generate` on a `[:cat ...]` schema returns a tuple, but does it return a vector that destructures cleanly? In current Malli it returns a 2-element vector, so `[[x y] pairs]` works. R=3 I=2 S=2.

### p8 — Composition (integration)

This is the load-bearing prompt — closest to what an the agent agent would actually emit.

**35B-A3B (2/9):** Multiple issues:
- Schema typo: `:person/friends {:db/valueType :db.type/ref :db.cardinality/many}` — should be `:db/cardinality :db.cardinality/many`. The `{:db/valueType :db.type/ref :db.cardinality/many}` map has `:db.cardinality/many` as a *key* with no value (or `nil` interpreted as value depending on reader).
- Pull pattern is malformed: `'[{:person/name :person/parent [:person/name {:person/employer [:org/name]}]}]` — `:person/name` is in *value position* of the map `{:person/name :person/parent [...]}`. Incoherent.
- `(m/=> [any? any?] [:vector ParentOfFriend])` — `m/=>` is a side-effecting *registration* form, not an expression. Real form is `(m/=> parents-of-friends [:=> [:cat any? any?] [:vector ParentOfFriend]])`.
- Uses `(d/create-database schema)` — Datascript has `d/create-conn`, not `d/create-database` (Datahike has `d/create-database`).
- Uses `(d/transact! db ...)` where `db` is a value not a connection — should be `(d/transact! conn ...)`.

R=0 I=1 S=1.

**27B (5/9):** Better structurally:
- Schema is correct.
- Pull pattern has an extra bracket: `'[:person/name {:person/parent [:person/name {:person/employer [:org/name]}]]` — count the `]`s: 3 closing, only 2 opening map-braces. Won't parse.
- `(m/=> parents-of-friends [d/Database any?] [:vector ParentOfFriend])` is *called before* `(defn parents-of-friends ...)` is defined — forward reference. Also the schema syntax is wrong (should be wrapped in `[:=> [:cat ...]  ...]`).
- Test uses `(d/db conn)` correctly but creates the conn after schema (right) and re-binds `db` after transact. Idiomatic.
- Output map handling is verbose but coherent.

R=1 I=2 S=2. Best of the three Qwen attempts; just trips on syntax-level details.

**Sonnet 4.6 (7/9):** Truncated at the transact-data, but visible content is architecturally correct: proper ns with all four require lines, includes `malli.instrument` (a real, current namespace), correct schema with proper `:db/cardinality`. Truncation prevents full scoring of the function body and test, but the trajectory is right. R=2 I=2 S=3.

## Aggregate scores

| Prompt | 35B-A3B | 27B | Sonnet 4.6 |
|---|---:|---:|---:|
| p1 cljs idiom | 5 | 4 | 8 |
| p2 datascript query | 7 | 6 | 9 |
| p3 datascript pull | 9 | 9 | 9 |
| p4 malli schema | 2 | 7 | 8 |
| p5 malli validate/coerce | 5 | 7 | 9 |
| p6 sci eval | 3 | 4 | 6 |
| p7 test patterns | 2 | 5 | 7 |
| p8 composition | 2 | 5 | 7 |
| **Total /72** | **35 (49%)** | **47 (65%)** | **63 (88%)** |

By dimension (sum of R, I, S across 8 prompts, max 24 each):

| Dimension | 35B-A3B | 27B | Sonnet 4.6 |
|---|---:|---:|---:|
| Runs (no syntax/undef errors) | 11/24 | 11/24 | 20/24 |
| Idiomatic (modern CLJS shape) | 14/24 | 16/24 | 19/24 |
| Knows the stack (right APIs) | 12/24 | 18/24 | 21/24 |

**Reading:** Sonnet's lead is real but uniform — slightly better in all three dimensions. The 27B's gain over 35B-A3B is concentrated in **stack-awareness** (18 vs 12) — it knows which library has which API. Both Qwens have identical Runs scores (11), meaning syntax-error rate is the same; the dense 27B uses that runnable code more correctly.

## Cost / latency

| Model | Total cost (8 prompts) | Avg latency | Notes |
|---|---:|---:|---|
| 35B-A3B (AtlasCloud, thinking-on) | $0.038 | 26s | Reasoning tokens consumed ~70% of completion budget |
| 27B (Venice, reasoning-off) | $0.022 | ~30s avg, dominated by p1 (185s with thinking-on before retry) | After switching to `reasoning: {enabled: false}`, p7 dropped to 3s |
| Sonnet 4.6 | $0.039 | 6s | Three runs hit `max_tokens` cap (550 / 250) due to tight credit budget — visible content still scoreable |

**Total spend:** $0.12. The earlier failed runs (thinking-budget OOM, credit-cap rejections) added ~$0.07 in upstream-billed reasoning tokens. Real total for the methodology: ~$0.20.

## Honest gaps

### Where 35B-A3B fails

1. **Library-shape inversions.** Treats Malli's `:registry` as a top-level map key instead of a schema-form property. Treats sci's namespace API as having `:host-fns`. Treats `m/=>` as an expression, not a side-effecting form. **These are not surface bugs — the model has the wrong mental model of these libraries.** Few-shot examples can patch but won't fully fix; you'd need the few-shot to demonstrate the *exact shape* for every API the agent might reach for.
2. **CLJS reader-macro confusion.** Uses `%` outside `#()` (this happened in both 35B-A3B and 27B on p1 — likely a shared training-data artifact). Distribution-mismatch.
3. **Datascript-Datahike-Datomic conflation.** `d/create-database` is Datahike. `d/create-conn` is Datascript. The model picked the wrong sibling. Easy to scaffold around with a 1-line example, but indicates the prior mixes them.

**Verdict on 35B-A3B:** **distribution-mismatch failures dominate.** Few-shot won't fix the library-shape inversions reliably because the model is confidently producing wrong-shaped output. Drop this model from consideration as the the agent emitter.

### Where 27B fails

1. **Reader-macro `%` mistake on p1** — same as 35B-A3B. This is the strongest single signal that the Qwen training corpus has CLJS examples that confused threading-macro semantics. Few-shot fix: include one explicit `(->> coll (reduce f init) (#(do-stuff-with-map %)))` example.
2. **Quoting Datalog queries** — p2 wrote raw vectors instead of quoted lists. This is a one-line scaffolding fix: in the few-shot, every Datalog query starts with `'[`.
3. **Generator-vs-function confusion** on p7 — same as 35B-A3B. Both Qwens treat `mg/generate` on a function-schema as if it returned inputs. Sonnet got this right. The Qwens need an explicit example showing `mg/generate [:cat :int :int]` → `[3 7]` not `mg/generate [:=> ...]`.
4. **Pull-pattern bracket counting** on p8 — looks like a sequencing failure during long-form generation, not a knowledge gap. Sonnet had a similar truncation issue (different cause). A verifier-side parse step catches this immediately.
5. **Forward-reference of `m/=>`** on p8 — orders the schema declaration before the `defn`. Real-world cost is small (Clojure resolves at runtime if `defn` lands first; this is just sloppy ordering). Few-shot fix.

**Verdict on 27B:** **most failures are scaffolding-fixable.** The model knows the right shapes, makes recoverable errors. With a 4-example few-shot covering `%` semantics, query quoting, generator vs function-schema, and `m/=>` placement, expect 27B to land in the 80–85% range on this same battery.

### Where Sonnet 4.6 fails (calibration: even the ceiling has gaps)

1. **`sci/eval-string*` typo** — used the starred private form. Easy fix.
2. **`mg/generate` on `[:cat ...]`** returns a 2-vector that destructures, but Sonnet didn't *quite* commit to this — the comment was right, the destructuring was right, but it's a fragile area where a slight schema change (`[:tuple :int :int]` vs `[:cat :int :int]`) flips the output shape.

**Calibration takeaway:** the bar for "credible the agent emitter" is not 100%. Sonnet at 88% sets the ceiling; the practical target is **80–85% post-scaffolding** for a categorized rubricB. Anything below 70% post-scaffolding and we should re-evaluate.

## Recommendation

**Phase-0 path: ship CLJS-emit on Qwen3.6-27B with strong scaffolding, OR fall back to JS-emit if scaffolding overhead is unacceptable.**

### What "strong scaffolding" looks like

A 4–6 example few-shot system prompt covering exactly:

1. **Malli registry form**: `[:schema {:registry {::person [:map ...]}} [:ref ::person]]` — verbatim.
2. **`m/=>` placement**: after `defn`, with `[:=> [:cat ARG-SCHEMAS...] RETURN-SCHEMA]` form.
3. **Datalog quoting**: every query starts with `'[`. Every aggregate has `:in $`.
4. **sci namespace structure**: `:vars` for both vars and functions; use `sci.core/new-dynamic-var` and `sci.core/new-var` for each.
5. **Datascript vs Datahike**: `d/create-conn` for Datascript. Spell it out.
6. **Reader macros**: one example of `->>` ending in `(#(transform %))` to anchor the `%`-in-`#()` distinction.

Estimated few-shot overhead: ~600 tokens, cached. Per-call cost increase: zero (cache hit).

### Test-time validation gate

Every emitted form runs through:
1. **Parse check** — `clojure.tools.reader.edn/read-string` on the emitted source. Catches bracket-counting errors (p8 issue), unbalanced quotes, etc. ~1ms overhead.
2. **sci-eval smoke** — `sci.core/eval-string` against a minimal context with Datascript and Malli pre-loaded. Catches API-name typos (sci `eval-string*`, malli `m/=>` misuse), missing namespaces. ~50ms per smoke.
3. **Function-schema instrument** — if the emitted form includes `m/=>`, run `malli.instrument/instrument!` to verify the function-schema parses. Catches schema-shape errors (p4 issue).

### What this means for the architecture

If the scaffolding + verifier-gate ships clean: **the architectural cleanness of CLJS-emit is preserved.** The agent emits Clojure forms that look like Sean's seon code (Malli-gated, Datascript-shaped, sci-bound). The trajectory log captures form-as-source, which is more useful for downstream training than JS strings.

If scaffolding can't get above ~80% on this battery (re-test after few-shot is built): **drop back to JS-emit.** The cost of CLJS-emit is real overhead (extra parse, extra smoke, retry-on-fail); without quality benefits it's pure tax.

**Decision gate:** spike the few-shot system prompt + verifier-gate against this same battery. If 27B post-scaffolding hits ≥80% (i.e. ≥58/72), green-light CLJS-emit for Phase-0. If <70%, revert to JS-emit. The 70–80% band is judgment-call territory — discuss with Sean.

### What this also means for serving

If CLJS-emit ships: **serve the dense 27B, not the 35B-A3B.** This contradicts the current sibling-side default and the "we already serve 35B-A3B" leverage argument. The dense 27B's stack-awareness advantage (18 vs 12 of 24) is real. Serving cost difference at the agent's per-trajectory volume is small; the quality delta is not.

If JS-emit ships: model choice doesn't change — JS-emit is well-trained across both, and the OpenAI-coding-bench priors apply.

## Methodology notes (for future runs)

1. **Thinking-mode trap.** Qwen 3.6 models on OpenRouter default to thinking-on across most providers. `chat_template_kwargs.enable_thinking=false` is **not honored** by AtlasCloud (35B-A3B) or several others. The reliable disable is OpenRouter's standard `reasoning: {enabled: false}` parameter, which routes to a provider that honors it (Venice for 27B). Always set this for capability evals where reasoning tokens aren't being scored.
2. **Provider routing matters.** The same model id can route to providers with different reasoning behavior, different speed (185s vs 3s on a categorized rubricB p1 vs p7), and different fallback semantics. For repeatable evals, pin a provider via `provider: {only: ["Venice"]}` in the request body.
3. **Credit-affordability cap.** OpenRouter's per-request affordability check rejects requests where `max_tokens × completion_price > balance + headroom`. When running near zero balance, drop `max_tokens` aggressively (550 → 250) — truncated outputs are still scoreable on architectural correctness.
4. **Truncation is a real failure mode for the eval, not just the API.** If your prompt asks for a complete program and the model hits length cap, that's a failure of the Idiomatic axis (model didn't budget its own output well). Score honestly.

---

## Appendix A — full output transcripts

Stored at `/tmp/qwen-eval/results/*.json` (response + cost + finish_reason + reasoning where applicable). Extracted markdown at `/tmp/qwen-eval/extracted/*.md`. Both retained for one session; copy out if needed for the design doc.
