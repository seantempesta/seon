---
title: "Independent review of ~/src/seon-jolt/next against Seon's laws and the 20 ideas"
type: research
status: final
created: 2026-10-20
---

# Review of `next/` (the Jolt rebuild), 2026-10-20

**Status: final.** Findings 1-7 and 11 were reproduced by runs in a copy (outputs below; probe files `../probes/p1.clj`, `p2.clj`, `p3.clj` in the copy). Findings 8-10 and 12 are read from the source by exact symbol and were not run. A first partial version was committed as 4b92ee96f.

**Subject.** `~/src/seon-jolt/next` at HEAD `09e81cde9`, **plus an uncommitted working tree** (lane R5
in flight: 20 files, +711/−309, mostly `seon.reconcile`, `seon.turn` and `seon.e2e`). I reviewed the
working tree as copied. Nothing under `~/src/seon-jolt` was modified.
- Copy: `/private/tmp/claude-501/-Users-sean-src-seon/1b1c4b04-347e-4da0-8ce2-a44f5b41bc76/scratchpad/next-review/next`
- Probe runner: `HOME=$PWD/target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run <file>` (about 10 s per run).

Findings are ranked by their effect on agents finishing tasks in few turns.

## Ranked findings

### 1. The value printer is `pr-str` with `*print-length* 20`, `*print-level* 4` and a 20,000-char cut. Idea 6 is not met, but IDEAS-MAP says "done (seon-min)" (REPRODUCED)

- **Where.**
  - `seon.agent/print-value` and `seon.agent/builtin-print`.
  - `seon.agent/*value-printer*` is declared nil and never bound anywhere in `src/`. `grep -rn value-printer src` shows only its definition and its use.
  - Seon's printer (`seon-min`, fipp) is not in `next/`, though `fipp` is in `deps.edn`.
- **Law / idea.**
  - Idea 6: "a cut value says its size in estimated tokens and prints the plain clojure.core form that reads the rest".
  - AGENTS.md "one printer… runnable elision forms… sizes in estimated tokens".
- **Reproduction.** `../probes/p1.clj`:

  ```
  (println "vec30:" (:printed (a/print-value (vec (range 30)))))
  (println "deep:"  (:printed (a/print-value {:a {:b {:c {:d {:e 1}}}}})))
  -> vec30: [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 ...]
  -> deep: {:a {:b {:c {:d #}}}}
  -> str: 20002 xxxxx …        (a 30,000-char string, cut with no continuation)
  ```

- **Expected.** A cut says what is cut, in tokens, and gives `(drop 20 result/hN)` / `(get-in result/hN [:a :b :c :d])`.
- **Actual.** It prints `...` and `#`, which are unreadable EDN with no continuation and no `:tokens`. `wrap-print`'s `seon/tokens`, `seon/truncated` and `seon/elisions` keys are therefore never set.
- **Impact.** The model sees a cut it cannot follow, so it spends a turn re-querying blind. The same printer prints the whole context (finding 2).

### 2. The context is printed through that printer under a 48,000-char cut, so the last keys (`:last-evals`, `:unmerged`) are the first silently dropped (REPRODUCED)

- **Where.**
  - `seon.context/messages` binds `{:length 64 :level 8 :chars 48000}` and calls `seon.agent/printer` on the block.
  - `seon.context/current-state` builds the block as an array map in the order `:task :turn :proofs :references`, then `:evidence :unmerged :last-evals`.
  - Every `:references` entry carries the whole `:source` string (`seon.context/resolve-ref`), and so does every proof (`:source (source-of db p)`).
- **Law / idea.**
  - Idea 1: the context is the whole briefing.
  - Spec red (`spec-reds.md`): "a warm model request allocates in proportion to what it shows".
  - AGENTS.md: "never build a big value and trim it later; elisions name bound, count, path".
- **Expected vs actual.** A plan that names a few large functions pushes the block past 48,000 chars. The text is then cut mid-EDN with `" …"`, and the agent loses what its last forms evaluated to, which is the feedback the next turn needs. Nothing reports the cut.
- **Reproduction (run, `p2.clj`).**

  ```
  (seon.context/messages {:task {} :references [{:symbol 'a/b :source (apply str (repeat 50000 "x"))}] :last-evals [{:form "(f)" :value "42"}]})
  ```

  ```
  -> F2 content chars: 48002 has :last-evals? false tail: "xxxxxxxxxxxxxxxxxx …"
  ```

### 3. `seon.reconcile/references` turns any backticked span containing `/` into a "qualified symbol" (REPRODUCED)

- **Where.** `seon.reconcile/references`. It splits on backticks and keeps any span with a slash and no whitespace.
  - `` `:seon.task/plan` `` → `(symbol ":seon.task/plan")`, whose namespace is `":seon.task"`.
  - `` `docs/DRAGONS.md` `` → the symbol `docs/DRAGONS.md`.
  - `` `(my.task/current)` `` → a symbol whose namespace is `"(my.task"`.
- **The contract does not catch it.** These values pass the output contract `[:vector :qualified-symbol]`, because `qualified-symbol?` only checks that a namespace is present.
- **Consumers.**
  - `seon.context/current-state`: each bogus reference becomes `{:symbol … :unresolved true}` in the agent's context.
  - `seon.reconcile/hairy`: the plan's references decide `:deleted-by-them-referenced`.
- **Law / idea.**
  - Idea 7: references are found "by one rule" and classified (symbol, keyword, path#anchor).
  - AGENTS.md: "no text parsing… References are typed by plain EDN".
  - Use edamame to read the span.
- **Expected vs actual.** A plan written the way AGENTS.md teaches (keywords and paths in backticks) fills the context with false "unresolved" entries. That is noise the model may chase.
- **Reproduction (run, `p2.clj`, `p3.clj`).**

  ```
  (seon.reconcile/references "`:seon.task/plan` `docs/DRAGONS.md` `(my.task/current)` `app.calc/add`")
  ```

  ```
  -> F3 references: [:seon.task/plan docs/DRAGONS.md (my.task/current) app.calc/add]
  -> F3 ns/qualified?: ([":seon.task" true] ["docs" true] ["(my.task" true])
  -> F3 in context: [{:symbol :seon.task/plan, :unresolved true} {:symbol docs/DRAGONS.md, :unresolved true}]
  ```

  Expected `[app.calc/add]`, with the keyword and the path resolved by their own kinds.

### 4. A reply with no code fence is evaluated whole as code, prose included (REPRODUCED; idea 8 drift)

- **Where.** `seon.turn/reply->source`: `(if-not (str/includes? text "```") text ...)`.
- **Law / idea.**
  - Idea 8: "prose becomes comments".
  - Spec reds `a]a` and idempotence. These are still open. `IDEAS-MAP` lists idea 8 as missing.
- **Expected vs actual.** A model that answers "I'll fix add.\n(defn add …)" without a fence gets one `:err` item per prose token ("Unable to resolve symbol I'll"…) ahead of its real form, which wastes context and a turn.
- **Reproduction (run).** `(seon.turn/reply->source "I'll fix add now.\n(defn add [a b] (+ a b))")` gives `-> "I'll fix add now.\n(defn add [a b] (+ a b))"`. The prose is unchanged and goes to `seon.repl/eval-string` as code.

### 5. A recorded definition loses its metadata, and a staged plain def loses its quoting, so merged code changes meaning on main (REPRODUCED; idea 13 drift)

- **(a) Metadata is dropped.** `seon.program/record-tx` stores `:var/src` as `(pr form)` with `*print-meta* false`.
  - `(def ^:dynamic *x* 1)` is recorded as `(def *x* 1)`.
  - `(defn ^:private h …)` loses `:private`.
  - On main (`seon.program/load-src!`) the var is no longer dynamic, so `binding` throws there.
- **(b) Quoting is dropped.** `seon.harvest/stage-tx` stores an EDN-valued plain def as `(pr-edn (list 'def name @v))`.
  - `(def ops '(inc 1))` becomes `(def ops (inc 1))`, which loads as `2`.
  - `(def syms '[a b])` becomes `(def syms [a b])`, a load error ("Unable to resolve a").
  - Both contradict harvest's docstring: "carried as its VALUE when the value reads back equal".
- **Law / idea.** Idea 13 (the merge carries the change) and idea 15 (exact source). Source should be the read span (edamame positions), not a reprint.
- **Reproduction (run, `p2.clj`).**

  ```
  (:var/src (first (program/record-tx db0 v '(def ^:dynamic *x* 1))))
  -> F5a record-tx src: (def *x* 1)
  (#'seon.harvest/pr-edn (list 'def 'ops '(inc 1))) then (program/load-src! 'app.vals src), @app.vals/ops
  -> F5b staged src: (def ops (inc 1)) load-src!: nil value: 2
  (program/load-src! 'app.vals2 (#'seon.harvest/pr-edn (list 'def 'syms '[a b])))
  -> {:ns app.vals2, :message Unable to resolve symbol: a in this context (did you mean *, +, -?)}
  ```

### 6. Defs in a namespace not already in the db are never recorded, and `ns` forms (refers, imports) are never recorded (REPRODUCED)

- **Where.**
  - `seon.repl/wrap-record` records only when `seon.program/program-ns?` holds, that is, when an `:ns/name` already exists.
  - `seon.program/record-tx` writes `:var/ns [:ns/name nsym]` as a lookup ref.
  - `seon.harvest/stage-tx` records aliases only.
  - `seon.program/def-form?` excludes `ns`.
- **Consequences.**
  - An agent's helper or test namespace is scratch: it never merges, and nothing says so in the reply (no `seon/recorded`).
  - A test written with `(:require [app.calc :refer [add]])` loads on main into `seon.program/ensure-ns!`'s namespace, which refers only clojure.core and clojure.test, so it fails with "Unable to resolve add".
  - `seon.program/reaching-tests` maps an unqualified `add` to `own-ns/add`, so such a test is never selected as reaching `app.calc/add`.
  - The fixtures hide all of this: every proof calls `app.calc/add` fully qualified (`seon.fixtures/calc`).
- **Law / idea.** Idea 15 ("a new namespace gets its ns form"), idea 2/9 (reaching tests feed the done rule) and "make failure local and loud".
- **Reproduction (run, `p2.clj`).** The db is built as in `seon.merge-test/empty-db`. `app.calc/add` has two tests in `app.calc-test`: `add-test` calls `(add 1 2)`, and `add-test-q` calls `(app.calc/add 1 2)`.

  ```
  (program/reaching-tests db #{'app.calc/add})            -> #{app.calc-test/add-test-q}      ; add-test missed
  (program/program-ns? db 'app.new)                       -> false                           ; its defs are never recorded
  (program/load-src! 'app.calc-test2 "(deftest add-test (is (= 3 (add 1 2))))")
  -> {:ns app.calc-test2, :message Unable to resolve symbol: add in this context (did you mean and?)}
  ```

### 7. A second printer inside contract refusals: `pr-str` with its own limits and a 400-char clip (REPRODUCED)

- **Where.**
  - `seon.contract/*printer*`: `(binding [*print-length* 8 *print-level* 3] (pr-str v))`, cut at 400 chars with `" …"`.
  - Its docstring says agents rebind it to the value printer, but nothing in `src/` binds it.
- **Other `pr-str` copies.**
  - `seon.repl/wrap-caught`, `admit` and `record-tx` (the stored eval record is a pr-str'd map).
  - `seon.turn/observe` and `end!` key unmerged and changed program keys by `pr-str` text.
  - `seon.merge/three-way` sorts by `pr-edn`.
- **Law.** AGENTS.md "exactly ONE printer… no printing to hash, sort, compare or store a copy".
- **Reproduction (run).** `(seon.contract/*printer* (vec (range 100)))` gives `-> [0 1 2 3 4 5 6 7 ...]`. A 1,000-char string comes back as 402 chars.

### 8. Errors come in several shapes beside `seon.contract/error` (static)

- **Where.**
  - `seon.root/no-agent` fabricates reply items (`{:id -1 :tag :err :err {:message why}}`).
  - Other shapes: `{:ok false :error msg}` (writer, `merge-main!`, `branch-diff`), `{:merged? false :error …}` (`seon.root/merge!`) and `{:error "too many live agents"}` (`ext-op :spawn`).
  - `seon.agent/error-map` returns `{:message :class :data}`, which has no `:seon.error/type`.
- **Law.** AGENTS.md "Errors are ordinary values of one shape"; idea 5.
- **Impact.** The agent sees refusals that carry no type and no fix.

### 9. Silence reads as an empty value: `seon.root/state` derefs with a 5 s timeout and falls back to `{}` (static)

- **Callers fed that fallback.**
  - `seon.root/health` reads `(:failed (state sys))` and so reports every proc healthy when the api is wedged.
  - `seon.root/agent-entry` returns nil, which `merge!` and `branch-diff` turn into "no agent".
- **Law.** AGENTS.md "Health is a positive observation: silence… reads as unknown".

### 10. `seon.root/health` checks a hand list of proc ids beside `resources/seon/system.edn` (static)

- **Where.** It hard-codes `[:api :writer :fork-server :flusher :reconciler :judge]`. The declaration also builds `:fx/echo`, `:fx/file` and `:fx/model` owners and their lanes (`seon.system/build-fx`), and health never pings those.
- **Law.** "A hand list beside a schema silently drops the next key."
- **Fix direction.** Derive the ids from `(:procs built)`.

### 11. Policy numbers in code, and dials read from the environment rather than held as facts (static; the default reproduced; idea 19)

- **Hard-coded policy numbers.**
  - `seon.reconcile/default-max-turns` 8, `default-max-merging` 8 and `merge-dials` `{3 2 2}`.
  - The 30000 ms test bound in `seon.turn/observe` and `seon.turn/ci!`.
  - `seon.agent/run-tests-forked`'s child trip of 16 MB.
  - `seon.root/set-agent`'s `take-last 20`.
- **Dials outside `seon.config`.** `SEON_TRIP_MB` and `SEON_AGENT_HEAP_MB` are read in `seon.agent`.
- **Merge-train default drift.**
  - Commit `b7459c3` says "merge trains (off by default)" and DECISIONS says trains stay off (`:seon.config.merge/max-running 1`).
  - But `seon.task/boot-tx` writes no merge dial, so `seon.reconcile/actions` uses `default-max-merging` 8.
  - At HEAD this means trains are on with no combined test run. R5's uncommitted `seon.turn/ci!` adds that run.
  - **Reproduction (run, `p3.clj`).** `(:max-merging {:boot "" :max-running 4} seon.reconcile/default-max-merging)` gives `-> 8`. That is root's index entry when boot set no merge dial.

### 13. The census gate fails on the current working-tree build, and most contracts are not generated (REPRODUCED)

- **Gate result.** `target/seon census` (the binary built 16:17 from the in-flight tree) ends `:result :CENSUS-FAILED`, with:
  - `{:contracts 488 :generated-and-checked 74 :not-generated 171}`;
  - failures in R5's new code: `seon.reconcile/branch-of` (both input and output: `get-in` can answer nil against `:seon.db/branch`), `seon.reconcile/running` (output), `seon.reconcile/actions` (input), and two `nil where a java.lang.String is required`.
- **What this means.** The gate works: it catches R5's non-holding contracts before commit.
- **Weak contracts the gate never checks.**
  - 43 of the 465 `:malli/schema` declarations in `src/` answer `:any` (a static count).
  - The host predicates `:seon.host/object` and `:seon.host/flow-graph` are `some?`, and `:seon.host/void` is `(constantly true)` (`seon.contract/host-schemas`).
  - Examples of contracts that guarantee nothing: `seon.turn/act!`'s output `[:or :map :map]` and `seon.contract/refusal`'s `:any`.
- **Law.** "Contract every admitted function… complete"; the census findings in IDEAS-MAP.

### 12. Per-turn cost follows the program's size, not what changed (static)

- **Where.** `seon.turn/observe` runs once per turn, and each pass does all of this:
  - `seon.turn/changed-keys` derives `seon.merge/program` twice, over every `:var/*` datom.
  - `seon.harvest/uncontracted` walks every var of every program namespace.
  - `seon.program/reaching-tests` re-runs `read-string` on every `:var/src` of the program on every call.
- **Law.** AGENTS.md "Make cost follow changed inputs".
- **Evidence.** R5's notes already measure trains' latency growing with program size (`option3-R5-trains.md` §2.3).
- **Untested owner.** `reaching-tests` has no deftest in `test/`.

## Notably good (matches an idea well)

- **Idea 9.** `seon.reconcile/done-refusals` is a small pure function, and a proof with no result is `:none`, which is refused. So an assertionless or missing proof is never green.
- **Idea 2.** Tests run in a fork of the agent (`seon.agent/run-tests-forked`), and "green at the start" ends the attempt with no model call (`seon.turn/work!`'s first `observe`).
- **Idea 13.** `seon.merge/three-way` is one pure derivation over program maps, used by `merge-main!`, the done rule's unmerged entries and the train.
- **Idea 10.** Contracts are checked from the definition (`seon.contract/defn`), so a direct-linked caller cannot bypass them.
- **Dragons.** `docs/DRAGONS.md` with `bin/dragons` names every catch site by function, a mechanical census of the two allowed catches.
- **Idea 12.** The reconciler is incremental over the tx report's touched entities, and its ready set is ordered deepest-first.

## Not checked (limits of this review)

- **Not run.**
  - Findings 8-10 and 12 are static. No cost was measured for finding 12.
  - Whether `seon.root/api-proc` blocks inside its step on `flow/update!`, which awaits the exit of stopped procs (step-fn rule 4), is not established.
  - `bin/check` was not run: it spawns many processes, and its end-to-end modes belong to root.
- **What was run.** Only `contracts` and `census` from `target/seon`. `contracts` answered `:CONTRACTS-OK`: 488 refused wrong arities, and the direct-linked caller was refused.
- **Tree state.** The working tree is mid-lane (R5). Findings 1-12 also hold at HEAD `09e81cde9`: the symbols cited are unchanged by the diff, except `seon.turn/ci!` and `branch-of`, which are R5's.
