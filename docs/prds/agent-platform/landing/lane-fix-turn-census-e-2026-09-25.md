# Lane fix-turn-census-e-opus (2026-09-25)

Census e classes TURN, SHAPE (turn parts), FNSYM, ERRREC (turn part) and DRIFT
(`docs/research/agent-platform/red-baseline-census-2026-09-25e.md`). The lane wrote
tests only. Both TURN hypotheses are refuted; the producer is in the render lane's
files. No test was run by this lane (load rule). The one probe run it tried refused
before starting (below).

## Broken things first

1. **TURN has a third cause: renderers read `(:seon.db/db unit)` and get nil.**
   b195ae037 (render-dispatch, 09:15) deleted `:seon.db/db` from `render-argument`
   (render.clj:196). It relied on call preparation supplying the optional
   `:seon.db/db` entry of `:seon.render/unit`. On default's installed program that
   supply does not exist. Read-only probe on default (pid 35446, program 6ab6920a):

   ```clojure
   (let [db (seon.db/db (seon.cluster.boot/connection "default"))
         snap (seon.call-preparation/snapshot db (seon.db/carried-projection db))]
     (seon.call-preparation/plan-for db snap 'seon.render.transcript/render-runtime-ai))
   ;; => {... :seon.call-preparation/arities [{... :slots [] :entries []}]
   ;;      :seon.call-preparation/empty? true}   ; same for seon.repl/render-ai, 79 ms
   ```

   `supplied-map-entries`' own docstring (call_preparation.clj:864) says optional
   entries are not map entries. The chain in a turn test is as follows.
   - `open-turn` calls `system-turn` (turn.clj:4219).
   - `system-turn` calls `declared-sources` → `walk/neighborhood`.
   - `render-runtime-ai` → `runtime-owner` → `(db/pull (:seon.db/db unit) …)` is
     refused (transcript.clj:1038, database nil).
   - That unit's `:seon.error/value` refuses the system turn
     (`declared-sources`, turn.clj:1887).
   - The open reports `:error` and settles one error fact per pass.

   Evidence: the turn-loop prompt-and-call red (.d/060 line 13) saved its
   `(pr-str opening)` as blob `a96b0ac9…`. Reading it with
   `(seon.blob/get (seon.cluster.boot/connection "default") "a96b0ac9…")` gives
   `:seon.render.unknown/producer seon.render.transcript/render-runtime-ai,
   :refused-operation seon.db/pull`, with message "seon.db/pull refused database at []:
   … got nil … (transcript.clj:1038)". The cluster.turn-test reds agree: situations
   `[:open :open …]` with every outcome `:error` (.d/058 :387, :619, :444).
   The same read sits at run.clj:68, repl.clj:375 and error.clj:934, 1026, 1069,
   1708, 1835, 1958 and 1984. It probably also explains part of RDISPATCH and ERRREC's
   "Faults (0)".
   - **Not my files** (render.clj, seon.render.edn, transcript.clj,
     call_preparation.clj). Owner: render-dispatch-opus.
   - Smallest patch (restores the pre-b195ae037 argument, +1 line in
     render.clj:201's `select-keys`): add `:seon.db/db` back to `render-argument`.
     `invocation-argument-evidence` already strips it from evidence.
   - Alternative: make call preparation supply optional named-schema entries. That
     is new machinery and needs the owner.
2. **Hypothesis (a), the development overlay, is refuted by code.**
   - `with-cluster` applies its own `"turn-test"` row.
     `config/apply!` → `compile-manifest` (config.clj:527) builds shipped defaults
     plus that manifest, so `no-provider` is absent.
   - `provider-targets` reads `(:seon.cluster/name cluster)` = `"turn-test"`
     (turn.clj:4313).
   - The no-provider path would also report `:released`, not `:error`: it runs
     `freeze! {""}` and then sees empty sources (turn.clj:4368, :4480).
   - The web-debug "Virtual" (§WORLD) is a separate fixture that reads default's
     row.
3. **Hypothesis (b), turn.clj:4444, is refuted.** The predicate matches the prompt
   contract. `seon.cluster.prompt/prompt` returns
   `[:or :seon.cluster.prompt/rendered-context :seon.cluster.prompt/error]`.
   Every refusal arm assocs `:seon.cluster.prompt/error-agent-id`
   (prompt.clj:439, :386). The turn-loop red came from a with-redefs stub that
   returned a bare diagnostic. The census's 3-line widening would be a general
   error predicate, so no source change was made.
4. **The census probe could not run.**
   `bin/test-check default --test seon.cluster.turn-test/function-install-reads-the-case-count-from-cluster-facts`
   answered `executed 0 … source 6ab6a6d7 is published but not yet adopted`
   (10.4 s). No member started. After adoption, root can rerun it for the
   `:seon.turn.loop/error` evidence.
5. **DRIFT was not done. It is over budget and cannot be verified here.**
   - cluster.turn-test has 50 `ai/complete` redef sites (seon.turn-test has none).
     Several sites stub error completions: missing credential (:1415), transport
     and unpaid failures (:1996, :2018), usage and finish-reason (:1574), and
     streaming chunks (:3048).
   - Converting them to agent_test's local HttpServer (146b8a5fb) needs a
     provider helper of about 50 lines, fixture dials for endpoint and no-auth,
     and per-site server replies. That is several hundred changed lines, and the
     error stubs need server behaviours (a 500, a closed socket, an unset key).
   - seon.turn-test's `drift-added ["seon.ai/complete"]` is a leak. Request 058
     (cluster.turn-test) stopped at the 120 s bound with bodies still inside
     `with-redefs`, and 059 ran next in the same JVM (the agent-test leak class).
   - Recommend a separate slice after TURN turns green, so each converted site can
     be proved. Until then, running cluster.turn-test before other namespaces can
     leak the stub.
   - The one removable redef in my path is gone: turn_loop refused-generated-opening
     now proves "no provider call" by the absence of an attempt row.
6. **ERRREC, turn part: both members point outside my files.**
   - turn-loop :630 (no escalation to "supervisor"). error.clj:1443–1454 sends
     escalate-to only for an unattributed fault. The refused phase is attributed
     to "worker", so no recipient exists. That is the policy fix-fault-delivery
     landed on 2026-09-24. Root must rule: either retire the test's escalation
     expectation, or error.clj treats an attributed caught host failure as a core
     fault for escalate-to.
   - turn-loop :777 (terminal nil). `eval.drive/terminal-state` answers nil only
     when the agent is not idle or not undisposed. The run is closed (the `inst?`
     assertion passed), so `next-agent-work` still derives work. The failure
     message now prints that work, so root's rerun names it.
7. **SHAPE, edit part: my.edit/form! refuses its handler's own return value.**
   `seon.edit.jvm/edit*` (jvm.clj:117) returns the 14 `:my.fs/*-error` shapes and
   the six `:my.edit/*-error` shapes. `my.edit/form!`, `exact!` and `lines!`
   (src/my/edit.clj:53, :76, :100) declare `[:or :my.edit/result :my.edit/error]`,
   and `:my.edit/error` requires `:my.edit/error-path` and
   `:my.edit/edit-observation`, which nothing produces (only error.clj:2034 reads
   them). The honest fix is the schema: `:my.edit/error` in
   resources/seon/schemas/my.edit.edn should be the handler's declared union. That
   file is not mine. Copying the union into three contracts would be the "copied
   union" the laws forbid. my.fs has the same shape (`:my.fs/error` requires
   `:my.fs/error-path`; my/fs.clj:51, :71).

## What landed

| commit | slice | lines |
|---|---|---|
| 158aa2a3b | FNSYM: string `:seon.fn/sym`/`:seon.test/sym` → symbols in cluster/turn_test (17 sites: lookup refs, rows, the quoted query literal, `:in` bindings, symbol locals, the compared call set), rereads_test:164, edit_test:345, :419. Scripted by `tmp/fix-turn-census-e-opus/fnsym.py`, then the hand residue | +23 −23 |
| 2a40d478c (published as 6ab6a945) | SHAPE/ERRREC tests: the refused-generated-opening stub returns a declared `:seon.cluster.prompt/error` and the ai/complete redef is dropped; the-committed-set substitutes a listed entity (`:seon.turn/turn`) for the never-read `:seon.test/test`; the clean-last-form message names the work; turn-test refused-generated-form asserts the declared message (errors carry no kind name since 9bf22ecd9) | +24 −12 |

Net: +47 −35 test lines, no source. clj-kondo reports 0 errors on all five files,
and the warning count is unchanged against HEAD's copies.

REPL proof for the-committed-set: I built the projection from default's carried
forms with `:seon.turn/turn` replaced by `[:map [:seon.test/first :string]
[:seon.test/second :string]]`, both with and without a properties map. Both give
`#{:seon.test/first :seon.test/second}`, in 300 ms for the two builds.

## Root runs (serially, after adoption)

```
bin/test-check default --test seon.cluster.turn-test/function-install-reads-the-case-count-from-cluster-facts   ; TURN (red until item 1 lands)
bin/test-check default --ns seon.turn-loop-test      ; the-committed-set, refused-generated-opening, clean-last-form message
bin/test-check default --test seon.turn-test/a-refused-generated-form-records-its-refusal
bin/test-check default --ns seon.rereads-test        ; FNSYM :164
bin/test-check default --ns seon.edit-test           ; FNSYM :345 :419 (edit-refusals stays red: item 7)
bin/test-check default --ns seon.cluster.turn-test   ; after item 1; FNSYM members :1150 :1171 :2670
```

## Verification limits

- No test was executed. The only request made refused before starting (item 4).
- The TURN producer is proved by read-only evidence: the blob of a recorded red and
  the installed empty invocation plan. The render fix itself is not proved.
- The other turn-test SHAPE members (wake-meeting :415, open-turn-read-refusal
  :955, settlement-divergent :1491, fault-unit :1950, virtual-turns :573) were not
  changed. The first four fail at open with `:error` and likely share item 1. Rerun
  them after item 1 before triaging.
- Timings: every probe was under 100 ms except the projection builds (300 ms).
  No hot path was changed.
- RESET NEEDED: none.
