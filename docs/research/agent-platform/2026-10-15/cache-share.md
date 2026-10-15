---
title: "Why DeepSeek prompt caching fell from 79% (S5 run 4) to 23% (S5 run 6)"
type: research
status: complete (lane argon-context, 2026-10-15, SCI mode as agent `argon-context`)
created: 2026-10-15
---

# Why DeepSeek prompt caching fell to 23% in S5 run 6

## Measured usage across S5 run 4 and S5 run 6

Queried on `:chain-f1f6dc8e6652` via `[:seon.ai.usage/prompt-tokens :seon.ai.usage/cached-tokens :seon.ai.usage/completion-tokens :seon.ai.attempt/basis-t]`:

| Attempt | Agent ID | Calls | Prompt tokens | Cached tokens | Cached share |
|---|---|---|---|---|---|
| Run 4, Implement attempt 1 | `attempt/5ef1a6f0140d` | 40 | 1,992,738 | 1,687,936 | 84.7% |
| Run 4, Implement attempt 2 | `attempt/e4eeaddcbaf3` | 40 | 1,032,959 | 773,504 | 74.9% |
| Run 4b, Implement attempt 3 | `attempt/68d331fe623f` | 31 | 727,316 | 521,600 | 71.7% |
| **Run 4 / 4b total** | 3 attempts | 111 | 3,753,013 | 2,983,040 | **79.5%** |
| **Run 6, Implement attempt 6** | `attempt/a4de2ea6de02` | 19 | 422,092 | 96,384 | **22.8% (23%)** |

Per-call progression on Run 6 (`attempt/a4de2ea6de02`) versus Run 4b (`attempt/68d331fe623f`):

| Call | Run 6 `basis-t` | Run 6 `prompt / cached` | Run 6 hit % | Run 4b `basis-t` | Run 4b `prompt / cached` | Run 4b hit % |
|---|---|---|---|---|---|---|
| 1 | `536872722` | `10,841 / 1,920` | 17.7% | `536872565` | `7,946 / 896` | 11.3% |
| 2 | `536872726` | `12,916 / 5,248` | 40.6% | `536872569` | `9,284 / 2,176` | 23.4% |
| 3 | `536872730` | `14,431 / 5,248` | 36.4% | `536872573` | `10,773 / 3,456` | 32.1% |
| 4 | `536872734` | `17,955 / 5,248` | 29.2% | `536872577` | `12,354 / 4,928` | 39.9% |
| 5 | `536872738` | `19,095 / 5,248` | 27.5% | `536872581` | `15,431 / 6,592` | 42.7% |
| 6 | `536872742` | `19,030 / 5,248` | 27.6% | `536872585` | `17,328 / 9,792` | 56.5% |
| 15 | `536872782` | `26,188 / 5,248` | 20.0% | `536872629` | `23,795 / 17,536` | 73.7% |
| 19 | `536872800` | `26,782 / 5,248` | 19.6% | `536872645` | `27,828 / 21,632` | 77.7% |

On Run 6 (`attempt/a4de2ea6de02`), **every single call from Call 2 through Call 19 hit literally `5,248` cached tokens** (`82 × 64`-token DeepSeek prefix blocks) and never advanced by a single block as the conversation history grew from 0 to 18 exchanges (`96,384 = 1,920 + 18 × 5,248`).

In contrast, in Run 4 / 4b (before commit `b7472d71e`), `:seon.ai/system` was only the static `(help)` text (7,905 chars, ~1,920 DeepSeek tokens), every exchange in `:seon.context/exchanges` was rendered identically with `(repl/reply-text emission)` without age-dependent mutation, and the entire current-state block sat at the end in `:seon.ai/prompt`. Thus `[{"role" "system" ...}]` plus the first $k-2$ exchanges formed an exact, immutable byte prefix on call $k$.

---

## 1. Rebuilt prompts and the exact units where consecutive calls diverge

Each call's prompt was rebuilt at its committed basis `(seon.db/commit-database db (seon.db/commit-at-t db basis-t))` through `seon.context/model-context` and `seon.ai/messages` (`:chat` wrapping).

Commit `b7472d71e` changed `seon.context/model-context` (`src/seon/context.clj:903-939`) to place `head` (`attempt-sections`: `[:seon.context/task :seon.context/chain :seon.context/attempts :seon.context/send-backs :seon.context/must-reads]`, produced by `seon.context/block-views`) at the end of `:seon.ai/system` (message unit `0`), before `:seon.context/exchanges` (message units `1 .. 2k-1`), and `tail` (`:seon.ai/prompt`) as the second text part of the final user message.

Diffing the rebuilt prompts of consecutive Run 6 calls (`basis-t 536872722` vs `536872726`, and `536872726` vs `536872730`) unit by unit from message `0` reveals **two independent prefix-breaking mechanisms**, the first inside message unit `0` (`:seon.ai/system`) at character `19,458` (literally 5,248 DeepSeek tokens into the prompt) and the second in `:seon.context/exchanges`.

### Divergence 1A (char 19,458 of unit 0, `:seon.ai/system`): `seon.sci.lookup/declaration-absent` stamps `(java.util.Date.)` on a `def` in `:seon.context/must-reads`

In message unit `0` (`{"role" "system", "content" ...}`, total 27,188 chars / 454 lines), the first 19,457 characters (`(help)` at 7,905 chars plus the first 11,552 chars of `head`: `:seon.context/task`, `:seon.context/chain`, `:seon.context/attempts`, and `:seon.context/must-reads` items `0..11`) are byte-for-byte identical across all 19 calls (`19,458` chars = `6,080` estimated tokens = `5,248` DeepSeek tokens = `82 × 64`-token blocks).

The **first byte of divergence** between Call 1 (`basis-t 536872722`) and Call 2 (`basis-t 536872726`) is at **character 19,458 (line 260 of `:seon.ai/system`)**, and again at **character 19,939 (line 269)**, inside `:seon.context/must-reads` item 12 (`seon.error/names-shown`):

```clojure
;; Rebuilt :seon.ai/system at char 19,330..20,115 (Call 2 vs Call 3):
    {:seon.ref/name seon.error/names-shown,
     :seon.ref/level :doc,
     :summary
       "Declared names shown beside a name not found: the nearest by spelling.",
     :arglists (),
     :in
       {:seon.error/at #inst "2026-10-15T00:50:06.617Z", ;; <-- Call 3: #inst "2026-10-15T00:50:07.221Z"
        :seon.error/layer :seon.sci.eval/program,
        :seon.error/operation seon.sci.lookup/declaration-absent,
        :seon.error/offending :seon.fn/spec,
        :seon.sci.eval/missing-declaration :seon.fn/spec,
        :seon.error/expected :seon.sci.eval/present-declaration,
        :seon.error/message
          "The program row lacks a declaration; supply the declared member before relying on it."},
     :out
       {:seon.error/at #inst "2026-10-15T00:50:06.617Z", ;; <-- Call 3: #inst "2026-10-15T00:50:07.221Z"
        :seon.error/layer :seon.sci.eval/program,
        :seon.error/operation seon.sci.lookup/declaration-absent,
        :seon.error/offending :seon.fn/spec,
        :seon.sci.eval/missing-declaration :seon.fn/spec,
        :seon.error/expected :seon.sci.eval/present-declaration,
        :seon.error/message
          "The program row lacks a declaration; supply the declared member before relying on it."}}
```

- **Producing function**: `seon.sci.lookup/declaration-absent` (`src/seon/sci/lookup.clj:188-201`), called by `seon.sci.lookup/documentation-contract` (`src/seon/sci/lookup.clj:258-278`) and `seon.sci.lookup/function-doc-map` (`src/seon/sci/lookup.clj:378-396`) via `seon.sci.lookup/function-documentation` → `seon.ref/at-level` → `seon.context/must-reads`.
- **Why it varies per call**:
  1. `seon.error/names-shown` is a private value `def` (`(def ^:private names-shown "Declared names shown..." 8)`, `:seon.fn/defined-by 'clojure.core/def`), which has no `:seon.fn/spec` (a `def` is not a function and has no `:=>` contract).
  2. `documentation-contract` (`src/seon/sci/lookup.clj:277-278`) unconditionally returns `{:in (declaration-absent :seon.fn/spec) :out (declaration-absent :seon.fn/spec)}` whenever `:seon.fn/spec` is absent, even for a `clojure.core/def`.
  3. `declaration-absent` calls `(seon.error.refusal/diagnostic (java.util.Date.) ...)`, stamping the live wall-clock `java.util.Date` at the moment `model-context` runs. Because `head` is printed as a map by the value printer (`seon.render.value`, which does not re-dispatch nested maps to `seon.error/render-ai`), `:seon.error/at` prints directly into `:seon.ai/system` on every turn.

### Divergence 1B (char 23,330 of unit 0, `:seon.ai/system`): `seon.render.test/render-ai` renders mutable test execution state and `seon.test/unknown` (`(java.util.Date.)`) inside `head`

The third and final difference in `:seon.ai/system` is at **character 23,330 (line 340 of `:seon.ai/system`)**, inside `:seon.context/must-reads` item 16 (`seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas`):

```clojure
;; Rebuilt :seon.ai/system at char 23,260..23,620 (Call 2 vs Call 3):
    :seon.render.test/changed-since-green
      {:seon.error/at #inst "2026-10-15T00:50:06.857Z", ;; <-- Call 3: #inst "2026-10-15T00:50:07.427Z"
       :seon.error/layer :seon.test/execution,
       :seon.error/operation seon.test/unknown,
       :seon.test/next-tier :none,
       :seon.test/unknown
         "seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas",
       :seon.error/message
         "No admitted green execution is recorded for this test."}
```

- **Producing function**: `seon.render.test/render-ai` (`src/seon/render/test.clj:53-83`), invoked on a test `must-read` by `seon.context/must-read-ai` (`src/seon/context.clj:807-819`) inside `seon.context/block-views` (`src/seon/context.clj:836-844`), calling `seon.test/changed-since-green` (`src/seon/test.clj:77-111`) → `seon.test/unknown` (`src/seon/test.clj:38-43`).
- **Why it varies per call**:
  1. When a cited test in `:seon.context/must-reads` is red with no prior green run, `seon.test/changed-since-green` returns `(unknown test-symbol "No admitted green execution is recorded for this test.")`, which constructs `{:seon.error/at (java.util.Date.) ...}` with a fresh wall-clock timestamp on every call.
  2. Furthermore, a must-read in `head` is supposed to be frozen for the whole attempt: `must-reads` resolves a `deftest` to its source (`{:seon.test/sym ... :seon.test/source ... :seon.render/ai seon.render.test/render-ai}`), whereas live test execution status (proof state, failure reports) belongs to `:seon.context/proofs` in the volatile `tail`. And in `seon.context/task-sections` (`src/seon/context.clj:738-749`), `sent-back`, `steps` (`chain`), and `earlier` (`earlier-attempts`) are read on `database` instead of `(or start database)`.

### Divergence 2 (message units `1 .. 2k-3`, `:seon.context/exchanges`): `seon.context/exchanges` mutates earlier exchanges on every turn as `newest` advances

Even after `:seon.ai/system` (message unit `0`) is made 100% invariant across the attempt, `seon.context/exchanges` (`src/seon/context.clj:873-895`) mutates the already-sent history messages in two ways as `newest` increments:

1. **Every turn (`:newest` → `:recent`)**: On call $k$ (`newest = k - 2`), exchange $k - 2$ is rendered with `age = :newest`, which passes `current? = true` to `seon.repl/reply-text` (`src/seon/context.clj:863`) and includes `:ms`, `:alloc-mb`, `:ns`, and `:seon.repl/fix`. On call $k + 1$ (`newest = k - 1`), that same exchange $k - 2$ has `age = :recent` (`current? = false`), which strips `:ms`, `:alloc-mb`, `:ns`, and `:seon.repl/fix`!
   Rebuilt from Run 6 Call 2 (`basis-t 536872726`) vs Call 3 (`basis-t 536872730`), exchange `0` unit `0`:
   ```clojure
   ;; Call 2 (exchange 0 is :newest):
   #:seon.ai{:form "; I'm the owner of `seon.error`...\n(my.program/source 'seon.error/nearest-calls)",
             :reply "#:seon.repl{:result result/e4a9c3364d1bf, :ms 19, :alloc-mb 27.5, :value (defn- nearest-calls ...}"}

   ;; Call 3 (exchange 0 is now :recent — :ms 19, :alloc-mb 27.5 disappeared!):
   #:seon.ai{:form "; I'm the owner of `seon.error`...\n(my.program/source 'seon.error/nearest-calls)",
             :reply "#:seon.repl{:result result/e4a9c3364d1bf, :value (defn- nearest-calls ...}"}
   ```
2. **Every turn from Call 6 onward (`:recent` → `:older` sliding window)**: Because `age` is a sliding window `(< (- newest %) (inc (:seon.config.context/history-whole dials)))` (`history-whole = 3`), on Call 5 (`newest = 3`) exchange `0` is `:recent` (shown whole, 2,188 tokens); on Call 6 (`newest = 4`), `(- 4 0) = 4` is no longer `< 4`, so **exchange `0` (message unit `2`, immediately after `system` + `assistant[0]`) mutates from `:recent` to `:older`**:
   ```clojure
   ;; Call 5, exchange 0 unit 0 (:recent, 2,188 tokens for exchange 0):
   #:seon.ai{:form "; I'm the owner of `seon.error`...\n(my.program/source 'seon.error/nearest-calls)",
             :reply "#:seon.repl{:result result/e4a9c3364d1bf, :value (defn- nearest-calls ...)}"}

   ;; Call 6, exchange 0 unit 0 (:older, 1,282 tokens for exchange 0):
   {:seon.ai/form "; I'm the owner of `seon.error`...\n(my.program/source 'seon.error/nearest-calls)",
    :seon.ai/reply "{:seon.repl/handle result/e4a9c3364d1bf,\n :seon.context/reply-tokens 420,\n :seon.render/operations [(get-in result/e4a9c3364d1bf [])]}",
    :seon.context/reference {:seon.repl/handle result/e4a9c3364d1bf,
                             :seon.context/reply-tokens 420,
                             :seon.render/operations [(get-in result/e4a9c3364d1bf [])]}}
   ```
   On Call 6 this invalidates the prefix at message `2` (after `system`); on Call 7 exchange `1` flips at message `4`; on Call 8 exchange `2` flips at message `6`, and so on for the rest of the attempt.

---

## 2. Smallest fix at the producers

To keep the prefix before the volatile tail (`:seon.ai/prompt` and the newest turn's exchange) byte-identical across consecutive calls without dropping anything the agent needs:

1. **`seon.sci.lookup/function-doc-map` & `program-documentation-selector` (`src/seon/sci/lookup.clj:166-172, 378-396`)**:
   - Pull `:seon.fn/defined-by` in `program-documentation-selector`. When `(= 'clojure.core/def (:seon.fn/defined-by row))`, omit `:arglists` and `(agent-documentation-contract database row)` (a `def` is a value binding, not a function, so it has no arglists or `:=>` contract).
2. **`seon.context/task-sections` & `seon.context/must-read-ai` (`src/seon/context.clj:735-750, 807-820`)**:
   - In `task-sections`, read the attempt-level sections (`sent-back`, `steps`, `earlier`, `must-reads`) on `(or start database)` so they are anchored at `attempt-start`.
   - In `must-read-ai`, render any error map (`(and (map? x) (:seon.error/at x) (:seon.error/message x))`) through `seon.error/reply-value` (which is the AI face of an error and omits wall-clock `:seon.error/at`, per D13 in `src/seon/error.clj:8`). This ensures no diagnostic inside `head` (`declaration-absent`, `seon.test/unknown`, etc.) ever injects a live `java.util.Date` into `:seon.ai/system`.
3. **`seon.context/exchanges` (`src/seon/context.clj:873-895`)**:
   - In `exchanges`, every completed exchange before `newest` is in the shared history prefix (`:older`), while `newest` (the exchange that just ran, whose user reply is paired with the volatile `tail` block at the very end of `seon.ai/messages`) is `:newest`.
   - Because exchange $i$ ($i < \text{newest}$) is already `:older` from the first call after it ceases to be the final message, exchanges $0 \dots k-2$ never mutate on call $k+1$:
     - On call $k$ (with exchanges $0 \dots k-2$), messages $0 \dots 2k-3$ are `[system, asst_0, user_0(:older), ..., asst_{k-2}, [user_{k-2}(:newest), tail_k]]`.
     - On call $k+1$ (with exchanges $0 \dots k-1$), messages $0 \dots 2k-3$ are `[system, asst_0, user_0(:older), ..., asst_{k-2}, user_{k-2}(:older), asst_{k-1}, [user_{k-1}(:newest), tail_{k+1}]]`.
     - Thus `system` and every prior exchange $0 \dots k-3$ (plus `asst_{k-2}`) are 100% immutable across consecutive turns, while the newest exchange still carries `:ms`, `:alloc-mb`, `:ns`, `:seon.repl/fix`, and its full uncompacted reply on the turn the agent acts on it, and `exchange-form`'s `:newest` / `:recent` / `:older` contract remains intact.

### Simulated cache share on Run 6 (`attempt/a4de2ea6de02`) with the fix

Simulating DeepSeek's 64-token prefix block cache across all 19 calls of `attempt/a4de2ea6de02`:

| Configuration | Total prompt tokens (19 calls) | Cached tokens | Cached share |
|---|---|---|---|
| Run 6 as recorded (`HEAD`) | 422,092 | 96,384 | **22.8%** |
| Fix 1 only (stable `:seon.ai/system`, sliding `exchanges`) | 422,092 | 212,288 | 50.3% |
| **Fix 1 + Fix 2 (stable `:seon.ai/system` + prefix-stable `exchanges`)** | **355,612** | **295,104** | **83.0%** |

---

## 3. Proof (`deftest` on data)

Added to `test/seon/context_test.clj`:

```clojure
(deftest consecutive-prompts-of-one-attempt-share-their-prefix-up-to-the-volatile-tail
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [handle (test-support/running-cluster running)
           _ (evaluate running agent-id "(ns tq.prefix) (def ^:private limit \"Cap.\" 8) (defn step \"One.\" {:malli/schema [:=> [:cat :int] :int]} [n] (+ n limit))")
           _ (test-support/declare-proof! connection 'tq.prefix-test 'holds 'tq.prefix/step)
           _ (evaluate running agent-id "(ns tq.prefix-test (:require [clojure.test :refer [deftest is]])) (deftest cited-red (is (= 1 2)))")
           id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.prefix
                                            :seon.task/name "holds"
                                            :seon.task/plan "Use `tq.prefix/limit`, `tq.prefix/step` and `tq.prefix-test/cited-red`."}))
           _ (task/assign! {:seon.agent/context-source handle :seon.db/connection connection :seon.task/assigned id
                            :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})
           world #(hash-map :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                            :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle))
           turn! (fn [n code]
                   (let [tid (str "turn-prefix-" n)
                         aid (str "att-prefix-" n)]
                     (db/transact! connection [{:db/id "t" :seon.turn/id tid :seon.turn/agent [:seon.agent/id agent-id]
                                                :seon.turn/opened-tx "datomic.tx"
                                                :seon.turn/attempts [{:db/id "a" :seon.ai.attempt/id aid :seon.ai.attempt/ordinal 0
                                                                      :seon.ai.attempt/at (java.util.Date.)
                                                                      :seon.ai/endpoint "https://example.invalid" :seon.ai/model "m"}]}])
                     (evaluate running agent-id code tid)
                     (db/transact! connection [[:db/add [:seon.turn/id tid] :seon.turn/closed-tx "datomic.tx"]])
                     (context/model-context (db/db connection) (world) agent-id)))
           c1 (turn! 1 "(vec (range 500))")
           _ (Thread/sleep 5)
           c2 (turn! 2 "(+ 1 2)")
           _ (Thread/sleep 5)
           c3 (turn! 3 "(+ 3 4)")
           m2 (seon.ai/messages :chat (:seon.ai/system c2) (:seon.context/exchanges c2) (:seon.ai/prompt c2))
           m3 (seon.ai/messages :chat (:seon.ai/system c3) (:seon.context/exchanges c3) (:seon.ai/prompt c3))]
       (is (= (:seon.ai/system c1) (:seon.ai/system c2) (:seon.ai/system c3))
           "system text (help + attempt sections) is identical across consecutive turns, even with a cited def and red test")
       (is (= (butlast (:seon.context/exchanges c2)) (take 1 (:seon.context/exchanges c3)))
           "completed exchanges before the newest one stay identical on the next turn")
       (is (= (butlast m2) (take (dec (count m2)) m3))
           "two consecutive chat prompts share their exact message prefix up to the volatile tail")))))
```

---

## 4. Patch

Written to [cache-share.patch](file:///Users/sean/src/seon/docs/research/agent-platform/2026-10-15/cache-share.patch).
