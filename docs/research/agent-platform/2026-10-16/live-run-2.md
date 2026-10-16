---
title: "live run 2: nine DeepSeek chains started with one chain! call each, zero hand acts (text renders return data)"
type: evidence
status: "run complete 2026-10-16: 3 merged, 2 done then refused at the merge or write-back, 4 capped"
---

# Live run 2: nine chains, one task each, observed read-only

Lane live-run-2 (Sonnet), observer only. Default JVM pid 47697, started from HEAD 25c828692; main moved to 5588d1329
(another lane's commit) during the run. Root started the nine chains at about 05:33 UTC with one `seon.task/chain!`
call each, template `seon.step.implement/template`, config `{:seon.config.ai/no-provider :seon.config/absent
:seon.config.run/max-episode-runs 40}`; the tasks are `text-render-tasks/tasks.edn`. Hand acts by this lane: 0 (JVM-mode
reads only: `seon.db/branch-database`, `seon.competency/grade`, `seon.context/step-verdict`; no SCI request as root).
Every chain had ended by 05:37:22 UTC, under four minutes after the first call, so the 60-minute poll was not needed.

## Summary

Model for every call: `deepseek-flash` (the merge commits' `Model:` trailer). Wall is first call opened to last call
closed (`seon.competency/grade`). Tokens are prompt / cached / completion.

| chain | task | end | calls (to green) | wall | tokens p / c / out | wasted calls | agent errors |
|---|---|---|---|---|---|---|---|
| `dcc8af9bc767` | `seon.ai/model-ai-returns-data` | **merged** `caa1ceb54` | 28 (28) | 92.3 s | 544,939 / 473,344 / 7,121 | 5 | 2 |
| `05dbf263a53b` | `seon.ai/provider-ai-returns-data` | done, **merge refused** `:seon.source/test-evidence-error` | 10 (10) | 22.3 s | 115,914 / 89,344 / 1,379 | 1 | 2 |
| `fddd9dcbfe66` | `seon.background/render-ai-returns-data` | done, **merge refused** `:seon.source/write-back-error` | 37 (37) | 102.4 s | 768,952 / 662,016 / 7,467 | 6 | 10 |
| `419a159aed47` | `seon.config/render-ai-returns-data` | **merged** `9d69c5e90` | 20 (20) | 53.4 s | 344,973 / 273,920 / 4,013 | 1 | 7 |
| `ce9d664c1be0` | `seon.effect/render-ai-returns-data` | **capped** (proof green at the cap, done refused) | 40 (none) | 111.0 s | 842,550 / 741,120 / 8,518 | 12 | 9 |
| `42e0e4c46b71` | `seon.error/elision-prose-returns-data` | **capped** (proof red) | 40 (none) | 105.2 s | 833,073 / 721,280 / 6,180 | 8 | 8 |
| `8d9eb337dcd2` | `seon.error/mcp-prose-returns-data` | **merged** `876ac5ca0` | 20 (20) | 43.5 s | 305,601 / 246,016 / 2,521 | 1 | 1 |
| `91a8e6815ef2` | `seon.render.transcript/render-run-ai-returns-data` | **capped** (proof red) | 40 (none) | 105.4 s | 976,012 / 853,248 / 7,691 | 5 | 6 |
| `fd3ba8395135` | `seon.repl/render-ai-returns-data` | **capped** (proof red) | 40 (none) | 108.0 s | 867,009 / 745,984 / 7,527 | 9 | 7 |

Totals: 275 model calls, 5,599,023 prompt tokens (4,806,272 cached), 52,417 completion tokens, 835 forms. Score
(`seon.competency/score`, non-green = cap + 1 = 41): 28, 10, 37, 20, 41, 41, 20, 41, 41. Median calls to green of the
five green runs: 20.

Classes per chain (forms), from `seon.competency/classify`:

| chain | read | re-read | navigation | search | probe | progress | error | unresolved | contract refusal | repeat |
|---|---|---|---|---|---|---|---|---|---|---|
| dcc8 | 44 | 6 | 24 | 6 | 5 | 6 | 1 | 1 | 0 | 0 |
| 05db | 17 | 0 | 1 | 4 | 1 | 1 | 0 | 0 | 2 | 0 |
| fddd | 50 | 6 | 7 | 4 | 10 | 6 | 3 | 3 | 4 | 0 |
| 419a | 31 | 4 | 1 | 4 | 6 | 4 | 6 | 0 | 1 | 0 |
| ce9d | 32 | 3 | 29 | 10 | 5 | 8 | 4 | 4 | 1 | 1 |
| 42e0 | 40 | 2 | 45 | 8 | 21 | 17 | 2 | 4 | 2 | 2 |
| 8d9e | 39 | 3 | 3 | 0 | 4 | 2 | 0 | 1 | 0 | 1 |
| 91a8 | 66 | 14 | 44 | 8 | 2 | 8 | 1 | 3 | 2 | 1 |
| fd3b | 48 | 12 | 41 | 7 | 4 | 5 | 4 | 2 | 1 | 0 |

Timeline (UTC): chains 05db, 419a, dcc8, fddd opened their first call at 05:33:17, so at most four ran at once; ce9d
started at 05:33:42 (after 05db ended), 42e0 at 05:34:14 (after 419a), 8d9e at 05:34:53 (after dcc8), 91a8 at 05:35:01
and fd3b at 05:35:35. Each merge landed 4 to 5 seconds after its attempt ended (419a ended 05:34:07, commit
`9d69c5e90` at 05:34:11; dcc8 05:34:46 / `caa1ceb54` 05:34:50; 8d9e 05:35:36 / `876ac5ca0` 05:35:41).

## Broken things first

1. **A green proof is not an ended task: the done rule refused `ce9d664c1be0` for two functions the green proof
   "does not reach", and nothing told the agent (it spent its last four calls hunting).** After call 36 the proof
   `seon.effect-test/the-effect-ai-render-returns-data` was green (`:seon.agent/end-green 1`), yet the attempt ran to
   call 40 and ended `:capped`. Read-only on the kept branch, `(seon.context/step-verdict branch "attempt/820a82e31349")`
   (944 ms, 2,219 MB) answers `:seon.source/refusals [{:seon.error/type :seon.source/test-evidence-error
   :seon.error/message "A replaced function reaches no green test of the named run." :seon.error/offending
   [seon.effect/payload-unit seon.effect/render-ai]}]`. The root's problem task says only "The attempt ended :capped."
   (`unfinished-task-820a82e31349`), so root cannot tell a stuck agent from an agent the gate refused. The agent's
   calls 37 to 39 are `seon.db/q` forms over `:seon.test.run/basis-t` and `payload-unit`, a hand attempt to find
   coverage evidence it has no verb for.
2. **The done rule and the merge gate disagree about the same refusal.** `05dbf263a53b` ended `:done` in 10 calls
   (done rule passed) and then root's final merge refused it with the same text, `:seon.source/test-evidence-error: A
   replaced function reaches no green test of the named run` (merge branch `merge-87a414ff6dd9`, kept; held merge step
   `provider-ai-returns-data-2` / `7b01a32b56a0`). Its whole change was one `defn seon.ai/provider-ai` with the
   generic output schema `:seon.render/ai-data`. Two chains (ce9d at the done rule, 05db at the merge) hit this
   refusal and three others did not, so the set of functions it flags is not a function of what the agent did: the
   cause is open (not reproduced; candidate: the render proof calls the replaced function through the cached render
   path, which records no call for it). CLAUDE.md "One derivation, every consumer" is the rule violated.
3. **`fddd9dcbfe66` was refused at write-back: `:seon.source/write-back-error: The reindexed candidate does not declare
   exactly the accepted program.`** (merge branch `merge-b14ff04ae163`, kept, no `:seon.unmerged/entry` on it.) The
   message names no declaration. The agent's call 25 evaluated `(defn background-error-renderers-cover-every-declared-declared-schema "test" ... [] nil)`:
   it turned an existing deftest of `my.background-test` into a plain defn (calls 27 and 36 then redefined it as a
   deftest), the likely difference between its program and the written files. Not reproduced; the refusal should say
   which declaration differs.
4. **`bin/seon check --at HEAD --test seon.render-census-test` answers `passed? false` with `fail 0 error 0`.** The
   namespace's three tests are all declared long, so a check without `--include-long` executes nothing of it and
   reports red: "executed 5 / pass 16 / fail 0 / error 0 / passed? false (3 declared long excluded)". A pass count with no
   failure and a false verdict is a misleading reply (the cause is only in the exclusion note).
5. **Capped chains write no reason beyond "The attempt ended :capped."** Four problem tasks (`unfinished-task-0d3810f9b27e`,
   `-bf156465a3b9`, `-820a82e31349`, `-fbd5fbfdc110`) say nothing of the proof state at the cap (red for 42e0, 91a8, fd3b;
   green but refused for ce9d), the calls spent, or the last refusal.
6. **A write-back of a new schema leaves ugly EDN.** The merged `resources/seon/schemas/seon.config.edn` now ends
   `...[:seon.config/rule :seon.config/rule]]],` followed by an entry indented 14 spaces on one very long line
   (`:ai-view [:map {:description ...} ...]`, inside `#:seon.config{...}`); `seon.error.edn` and `seon.ai.model.edn` the
   same (one-line schema forms, a dangling comma). It reads correctly (the namespaced map makes it
   `:seon.config/ai-view`) but it is not the repository's layout.

## Per chain

For every merged chain: the proof went green on main (check below), the merge commit carries `Assignment:`,
`Merge-commit:`, `Agent: attempt/<id>` and `Model: deepseek-flash` trailers (a0a0f2f41) and no `Co-Authored-By` line, the
task is `:stopped` on main (3714d84a8; `[:seon.task/id ...] :seon.task/execution :stopped`, attempts 1), and no
`:seon.unmerged/entry` exists on any chain or merge branch.

- **dcc8 `seon.ai/model-ai-returns-data`: merged `caa1ceb54` (`resources/seon/schemas/seon.ai.model.edn`, `src/seon/ai.clj`,
  +70/-25).** Grade: 28 calls, 5 wasted, 92 s. The agent registered `:seon.ai.model/ai-face` and made `model-ai` return
  it; `registry-ai` still returns `:string`, so it added a private `model-ai-text` that renders the data back to prose
  (one more string consumer kept alive). No leftover defs. Where its calls went: calls 0 to 16 read renderer and
  support internals (`seon.render/*`, `seon.render.value/*`, `:seon.render/ai-data`, 17 model schema keys one by one,
  calls 14 to 15); call 17 a hand-written sample map with an odd number of forms; calls 17 to 19 found the pinned
  sample by reading `seon.render-bench/read-samples` and defining `samples-fallback` (a junk def, unmapped at call 19);
  `(in-ns 'seon.ai)` re-sent in 24 of 28 calls.
- **419a `seon.config/render-ai-returns-data`: merged `9d69c5e90` (`seon.config.edn`, `config.clj`, +16/-11).** 20 calls,
  7 errors, 1 wasted. Calls 0 to 11 are reads plus two cyclic-load refusals in call 0 (`seon.config` cannot require
  `seon.text-render-support` or `my.task`: "Cyclic load dependency"), a `[:maybe ...]` defn refused in call 13 ("bare
  nilable return") and fixed in call 18 with `[:or :seon.config/ai-view [:= :seon.config/absent]]` after reading
  `seon.effect/render-ai` and `seon.cluster/view` as models (calls 14 to 17). No debris.
- **8d9e `seon.error/mcp-prose-returns-data`: merged `876ac5ca0` (`seon.error.edn`, `error.clj`, +7/-5).** 20 calls, 1 error,
  the cleanest run: 39 reads, then `register!` at call 18 and `defn` at call 19 passed first try. It spent calls 5 to 14
  chasing the pinned sample (`my.fs/section` of an EDN file, `eval` of `string/split` of `with-out-str`, probing
  `seon.render-bench/directory` and `samples-path`) before it needed none of it.
- **05db: see Broken 2.** 10 calls, 22 s (fastest, 17 reads, 4 searches), first `defn` at call 9.
- **fddd: see Broken 3.** 37 calls. First `defn` at call 14 failed (`:malli.core/invalid-schema`, a seven-key `[:or ...]`
  over three error schemas), call 16 passed with `:map`; calls 17 to 36 then went to its consumers' test (the deftest
  rewrite above) and to `seon.schema/handed-projection` / `projection-validator` probes (call 33) to learn whether a map
  satisfies `:seon.render/ai`.
- **ce9d: see Broken 1.** `payload-unit` (call 19) failed to resolve because it was defined after its user (call 20);
  `register!` of `:seon.effect/view` failed `:malli.core/invalid-schema` twice (calls 23, 30; fixed in call 33 after
  reading `:seon.schema/inst`, `:seon.turn/id`); call 34 "bare nilable return"; call 36 admitted.
- **42e0:** capped at call 40, never defined `seon.error/elision-prose`'s replacement. Calls 17 to 25 (nine calls, 27 forms)
  fought `my.fs/read` of the 1,101,276-byte `render-bench/samples.edn`: `my.fs/read` with a string (contract refusal),
  then `clojure.edn/read-string` of the truncated text twice ("EOF while reading", calls 19, 21), then raising
  `:my.fs/max-bytes` to 200000, 5000000 and 50000000, then `:my.fs/byte-offset`. It then found the sample was
  loadable by `seon.render-bench/read-samples` (call 25), the form the plan's reference `seon.text-render-support/ai-face`
  already wraps. Calls 27 to 34 build the whole test world with `support/with-running-agent`.
- **91a8:** capped, never wrote a render. 66 reads and 14 re-reads over 40 calls: 28 distinct `seon.render.transcript/*`,
  `seon.render/*`, `seon.render.value/*` sources, `render-call` read five times. Call 2 is a model error ("Your reply
  began with a response; send a form."). Calls 26 to 28 were its one probe, building a database by hand in the test
  namespace; calls 36 to 38 were five `my.program/search` forms refused or unresolved (symbol where a string is
  required, `seon.program/search!`).
- **fd3b:** capped. First `defn` at call 18 (a helper), the render itself refused four times: invalid schema (call 32),
  bare nilable return (calls 35, 38), `[:nil ...]` refused at schema publication naming `:seon.cluster.eval/receipt`
  (call 37); a 12-times re-read of `seon.repl/*` and `seon.render.value/show`.

## Defects found (exact forms and values)

- Done rule vs gate vs the agent's context: Broken 1 and 2.
- `my.program/search` takes `(search database query)` or `(search query)` with a string, and refuses a symbol or keyword:
  `my.program/search refused argument 1 `query`: expected :my.program/search-query (a string), got a symbol
  seon.render.transcript/render-run-ai` (91a8, call 37; same in fddd calls 4, 7, 21; 42e0 call 39; 419a call 16 for
  `my.program/history` taking a map). Eleven refusals over five chains, every one a name the agent could resolve with
  `my.program/source`.
- Names agents invent that do not resolve (18 unresolved forms): `seon.program/search`, `seon.program/search!`,
  `my.program/declarations`, `my.program/ns-declarations`, `my.fs/find`, `seon.source/refusals`,
  `seon.render.bench/read-samples` (the real namespace is `seon.render-bench`), `text-render-support/ai-face` without a
  require, `(with-ai-face ...)` outside its namespace.
- `my.fs/read` of a file larger than its default byte bound answers text that is silently a prefix: `clojure.edn/read-string`
  of it throws "EOF while reading"; the reply carries `:my.fs/eof?` and `:my.fs/bytes-read` only if the agent asks
  (42e0 calls 19 to 24).
- "Cyclic load dependency: seon.config cannot require seon.text-render-support / my.task" (419a call 0), "seon.background
  cannot require my.task" (fddd call 10), "seon.repl cannot require seon.render-bench" (fd3b call 19): three chains copied a
  test-side idiom into the source namespace. The refusal is correct and costs one call each; the plan never says where
  a sample comes from.
- "bare nilable return" refusal repeated across chains (419a, ce9d, fd3b twice) although `seon.effect/view` and
  `seon.cluster/view` show the accepted shape; the agent re-derives `[:or X [:= :absent]]` each time.
- A refused schema blocks the rest of the request: the same `:malli.core/invalid-schema` reached 419a, fddd, ce9d (twice),
  fd3b with no named subschema in the message ("derive: :malli.core/invalid-schema").
- Probe cost by this lane (not a system path): a `d/q` over `(namespace ?a)` on a merge branch took 6,044 ms and
  26,880 MB allocated; `seon.context/step-verdict` on a branch costs 944 ms and 2,219 MB for one answer (over the
  one-second law's neighbourhood).
- `seon.competency/classify` calls every `(in-ns ...)` navigation: 195 of 835 forms (23%) are `(in-ns 'ns)` re-sent
  before another form, almost always the agent's own namespace; the agent's namespace persists between requests (MCP
  doc: "default: where the agent's previous run ended"), so the help's in-ns-first teaching
  (`seon.bootstrap/help-teaches-in-ns-first-and-no-probing`, filed) costs a form in most calls.

## Wasted-turn classes, ranked across all nine (275 calls, 835 forms)

1. **Reading the renderer pipeline to learn what "returns data" means: 367 read forms (44%), 50 re-reads.** Every
   chain read `seon.render/render-ai`, `seon.render.value/render-ai`, `:seon.render/ai-data`, `seon.render/raw-output`,
   `seon.text-render-support/*` (18 distinct sources across the nine). The three merged runs and 05db needed 17 to 44
   reads; the four capped chains 32 to 66 and wrote nothing durable by call 17, 17, 18, never (42e0, 91a8).
2. **Finding or loading the proof's pinned sample: about 45 calls over six chains** (42e0 calls 14 to 25, fddd 3 and
   12, fd3b 15 to 21, dcc8 17 to 19, 8d9e 5 to 14, ce9d 9 to 11, 91a8 26 to 31). The proof reads it through
   `seon.text-render-support/ai-face`, but the plan names it by id only; its fields are in no context.
3. **`(in-ns ...)` before nearly every call: 195 forms.** Cheap per form, but it is 23% of forms and defeats the
   "form per call" accounting; capped chains show 29 to 45 of them.
4. **Wrong API shape: 18 unresolved + 13 contract refusals + 21 other errors = 52 forms** (`my.program/search`,
   `my.fs/read`, `my.fs/section` with extra arguments, `seon.schema/register!` with one argument).
5. **Schema and contract admission loops: 12 calls** (bare nilable return x5, `:malli.core/invalid-schema` x6, cyclic
   load x3, a helper defined after its user).
6. **Refusals the agent cannot see: ce9d's last 4 calls** (Broken 1), and 05db's refusal arrived only at the merge.
7. **Rewriting an existing test: fddd 6 calls** (Broken 3).

## HEAD after the merges

`bin/seon check --at HEAD` (candidate 5588d1329, three merges included) with `--test
seon.ai-test/the-model-ai-render-returns-data --test seon.config-test/the-config-ai-render-returns-data --test
seon.error-test/the-mcp-error-ai-render-returns-data --test seon.render-census-test --test
seon.babashka-load-test/declared-namespaces-load-in-babashka --include-long` (run `5be13ed0033b`, 7.7 s, 8 executed): the
three proofs pass and `seon.babashka-load-test/declared-namespaces-load-in-babashka` passes (a9e4e9904's break is
gone); `seon.render-census-test` has all three tests red: `no-ai-render-returns-text` lists 19 AI renders still
returning strings (none of the three merged renders is in the list; the six unmerged tasks' renders are), and the two
printer-site tests list sites such as `seon.ai/attempt-ai uses seon.render.value/render-ai`. These reds belong to the
other renders and tasks, not the merges (not run on the parent: no parent run exists in this lane). Without
`--include-long` the census run answers `passed? false` with no failure (Broken 4).

## Top three context fixes the evidence supports

1. **Put the target shape and a worked example in the task plan or the context, once.** The plan says "returns data
   under a declared output schema" and names `seon.text-render-support/ai-face`; the merged runs found the shape in
   `seon.effect/render-ai` and `seon.cluster/view` (419a calls 14 to 17) and the others read 17 to 66 sources hunting
   it. A reference to one converted render (`seon.config/render-ai` on main now), the nilable form
   `[:or :ns/view [:= :ns/absent]]`, and the sample's keys inline would have saved the reads in ranks 1 and 2 and the
   admission loops of rank 5, about half the calls of the capped chains.
2. **Show a green-but-refused task its refusal, and make the done rule and the merge gate one derivation.** Add
   `:seon.source/refusals` (the step-verdict value the agent can already call) to the context whenever the proofs are
   green and the done rule is not, with the offending functions and the form that shows which test should reach them;
   put the refusal in the capped problem task's reason. Without it ce9d spent calls 37 to 40 guessing and root sees only
   "capped".
3. **Make the program's own verbs forgiving where the name is exact.** `my.program/search` should accept a symbol or
   keyword (answering what `my.program/source` would find plus its callers) or reply with the one corrected form;
   `my.fs/read` should state `truncated at N of M bytes; (my.fs/read {... :my.fs/max-bytes M})` in its first key when it
   cuts. Fifty-two forms in this run were errors of this kind.
