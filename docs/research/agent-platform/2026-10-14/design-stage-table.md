# Design stage done-proof: REPL table (2026-10-14)

Experiment lane. Source, tests and spec untouched; nothing committed. Run on default at a disposable agent branch
(`eval_clj` with agent `probe-design-a`, which minted branch `:agent-95e7fa7f7448`; retired with
`my.agent/retire-branch!`; branch name cannot be chosen through `eval_clj`, so `probe-design-<x>` became the agent id).
Tasks are plain maps `{:seon.task/plan "..."}`. Read-only JVM calls used `(seon.cluster.boot/connection "default")`.
Every `eval_clj` call records an evaluation on its agent's branch (JVM-mode and the root `doc` call recorded on main's
root agent; no data transacted by me beyond those records).

## Draft code (SCI, namespace `probe.design`; contracts omitted, so each defn returned an `:uncontracted` finding)

```clojure
(defn sections [plan]                       ; {anchor text-up-to-next-heading}
  (let [lines (vec (str/split-lines plan))
        hs (vec (:seon.ref/headings (seon.ref/document plan)))]
    (into {} (map-indexed
               (fn [i {:seon.ref/keys [anchor line]}]
                 (let [end (if-let [n (get hs (inc i))] (dec (:seon.ref/line n)) (count lines))]
                   [anchor (str/join "\n" (subvec lines line end))]))
               hs))))

(defn problems [database text]
  (filterv :seon.ref/problem-kind (seon.ref/references database {:seon.ref/text text})))

(defn fork-database [database]
  (let [branch (get-in database [:config :branch])]
    (seon.db/commit-database database (seon.cluster.registry/base-commit-id database branch))))

(defn digests [database attribute]
  (into {} (seon.db/q [:find '?k '?d :where ['?e attribute '?k] ['?e :seon.program/definition-digest '?d]] database)))

(defn changed [database attribute]
  (let [before (digests (fork-database database) attribute)]
    (into #{} (keep (fn [[k d]] (when (not= d (get before k)) k))) (digests database attribute))))

(defn design-verdict [database task]
  (let [plan (or (:seon.task/plan task) "")
        secs (sections plan)
        approach (get secs "approach" "")
        refs (seon.ref/references database {:seon.ref/text plan})
        unresolved (filterv :seon.ref/problem-kind refs)
        schema-cited? (boolean (some #(and (:seon.schema/key %) (not (:seon.ref/problem-kind %))) refs))
        fns (filterv #(seon.db/q [:find '?e '. :where ['?e :seon.fn/sym %]] database) (seon.ref/names approach))
        gaps (into {} (keep (fn [f] (let [g (seon.contracts/gaps database f)] (when (seq g) [f g])))) fns)
        checks [[:sections (every? #(not (str/blank? (get secs % ""))) ["problem" "what-exists" "approach"])]
                [:references (empty? unresolved)]
                [:data-not-modeled (or schema-cited? (boolean (seq (changed database :seon.schema/key))))]
                [:contract-gaps (empty? gaps)]]
        failed (into [] (keep (fn [[k ok]] (when-not ok k))) checks)]
    {:green? (empty? failed) :failed failed :gaps gaps}))   ; :failed lists ALL failed; first = what a short-circuit deftest reports

;; the flowing merge, pure. `declared` = every declared :seon.task/* member (needs the schema, see surprise 7)
(defn advance [finished nxt declared owned]
  (let [flowing (remove (set owned) declared)]
    {:finished (assoc finished :seon.task/outcome :done)
     :next (-> nxt (merge (select-keys finished flowing)) (assoc :seon.task/execution :running))}))
```

Schema registered on the branch for rows 6/7 (in its own request): `(seon.schema/register! :probe.workout/id :int)`
and `(seon.schema/register! :probe.workout/session [:map [:probe.workout/id :probe.workout/id]])`. Stub:
`(defn log-session {:malli/schema [:=> [:cat :probe.workout/session] :probe.workout/session]} [session] (throw (ex-info "not yet" {:seon.error/type :seon.error/unimplemented})))`.

## Table (actual)

Time = one `design-verdict` call, warm unless noted (x = cold first call in the session).

| # | state | expected | actual `:failed` (all) | first | verdict | ms |
|---|---|---|---|---|---|---|
| 1 | only `## Problem` | red :sections | [:sections :data-not-modeled] | :sections | as expected | 17.6x |
| 2 | 3 sections, `seon.ref/no-such-fn` backticked | red :references | [:references :data-not-modeled] | :references | as expected | 12.8 |
| 3 | fine, no schema changed or cited | red :data-not-modeled | [:data-not-modeled] | same | as expected | 9.2 |
| 4 | bug fix: cites `seon.ref/section`, `:seon.ref/section-error` | green | [] | | as expected | 2.9 warm (7.1 first) |
| 5 | Approach cites stub with `:any` slot | red :contract-gaps | [:contract-gaps] (input 0 `:any`, output `:map` bare-map) | | as expected, but see surprise 1 | 2.8 |
| 6 | feature: registered `:probe.workout/session`, contracted stub `log-session`, plan cites both | green | [] | | as expected | 2.8 (6.6 first) |
| 7 | stub contract uses unregistered keyword | red :contract-gaps (or note) | [:references] | | differs, see surprise 2 | 4.0 |
| 8 | bare names only (`seon.ref/section`, `:seon.ref/section-error`) | note | green | | counts, see surprise 3 | 4.3 |

Extra rows: cites existing `seon.ref/locate` (has an inherited `:maybe` output gap) in Approach: red :contract-gaps
(surprise 4). Blank `## Problem`: red :sections; body `TBD`: passes. `### Approach` (level 3) and a leading `# Plan`
title: both fine. Fenced code with `(seon.ref/no-such-fn 1)` ignored; backticked `docs/nope.md` red :references;
bare `seon.ref.clj:42` not flagged at all.

Cost, warm averages over 20 calls (ms): `design-verdict` 1.6-1.8 (schema cited, so `changed` skipped);
`seon.ref/references` 1.5; `sections` 0.016; `fork-database` 0.48; `digests` for `:seon.schema/key` (3,749 entities) 0.74;
`changed :seon.schema/key` 3.3 (the worst-case path, state 3); `changed :seon.fn/sym` 7.1; `seon.contracts/gaps` 0.34.
`seon.profile/measure` returned `{:ms 2.85 :calls 1667}`, no alloc key. The 20-call loop allocated about 247 MB
for about 1,000 verdict-equivalents, so roughly 0.2 MB each (inferred from request total, not per call). All well under
the 100 ms-per-eval budget; the reply's own `:settle`/`:test-step` stages (50-250 ms) dwarf it.

## Advance (pure), results on maps

Design `{plan "v1", instructions "I-design", proofs #{a/design-handoff}, attempts-made 1}`, next Prove
`{plan "v0", instructions "I-prove", proofs #{a/prove-handoff}, :held}`:

- first advance: next = plan "v1", instructions "I-prove", proofs #{a/prove-handoff}, `:running`; finished outcome `:done`.
- rerun after send-back (Design plan "v2", attempts-made 2) onto the already-advanced next: only `plan` differs from the
  first result (`true`); instructions and proofs untouched; `attempts-made` and `outcome` do not leak.
- finished with no `plan` key: next keeps its plan. A plan cannot be cleared by flowing (absence flows nothing).

## Surprises and what the spec must change

1. **Same-request blindness.** In the request that defines a function the database does not yet contain it
   (`gaps` returned `{:seon.contracts/absent true}`, the verdict reported references red); the next request sees it.
   Fine for the runner (it judges after the request), but a proof must never be called inside the defining form.
   Also `gaps` flags the output `:map` as `bare-map`, so a stub needs named output schemas, not only inputs.
2. **Row 7: the unregistered keyword never reaches the program.** Admission refuses the defn
   (`:malli.core/invalid-schema` in `seon.schema/contract-product`), so the function has no `:seon.fn/sym`; the plan
   cites a name that does not resolve and rule 2 reds. Rule 4 never sees it (it keeps only cited names that exist), so
   `:contract-gaps` is unreachable for this input. Correct outcome, wrong reason in the spec text. The agent's
   feedback is a REPL `:diagnostics` error, not the proof; Design's Done text should say that a stub refused at the REPL
   does not exist.
3. **Bare names count when they resolve, and they satisfy rule 3 and rule 4.** `seon.ref/names` over a bare
   `seon.ref/section` returns it; a bare `:seon.ref/section-error` is a resolved schema key. An unresolved bare name is
   silently ignored (`seon.ref/no-such-bare` stays green), so a typo in prose is invisible: only backticks make the
   reference rule bite. Section 3 says exactly this; the Design instructions ask for backticks, so keep, but note a
   plan of bare names is green.
4. **Rule 4 punishes citing existing functions.** 14 of the sampled `seon.ref` functions carry inherited gaps (`:maybe`
   outputs, `bare-map`, missing-spec); `seon.ref/locate` in Approach alone made the proof red, and the agent cannot fix
   it without taking on a contract rewrite. Proposed: rule 4 judges the functions cited in Approach AND new or changed
   on the branch (`(changed database :seon.fn/sym)`), i.e. new work only. Existing-function contract tightening
   belongs to Implement (CLAUDE.md: bug fix tightens the contract at the producer).
5. **Rule 4 misses uncited stubs.** A stub the agent defined but did not cite (`probe.stub/any-stub` stayed in
   `changed :seon.fn/sym` with 2 gaps while rows 6 and 12 stayed green). Judging gaps over `changed :seon.fn/sym` instead
   of the cited set closes that and also makes uncited scratch helpers fail the Prove "unmap scratch" hygiene early.
   Cost: `changed :seon.fn/sym` is 7 ms warm; acceptable. Inferred: it needs a namespace-agnostic definition because
   chain branches normally carry only chain work.
6. **Rule 3 is cheap to satisfy.** Citing any resolved schema key (even `:seon.ref/section-error` in a plan that really is
   about something else) passes; so does registering any scratch schema. It checks presence of thought, not that the
   data is right. Honest: it is a floor. An alternative cheap tightening (inferred, untested): require the cited key
   to appear in Approach (not only What exists) or in the contract of a changed function.
7. **`advance` needs the declared-member set.** `[finished next owned-keys]` cannot tell flowing from system facts
   (`attempts-made`, `outcome`, `next`) without it; spec step 5 reads "every other declared member". Proposed signature
   `advance [finished next declared owned]` with both derived from the schema (`:seon.task/owned true`), or flowing as
   its own marker. Also: a send-back from Prove to Design that reruns Design overwrites any plan edits Prove made
   (finished always wins). Intended per spec; say so, and have Prove's edits arrive via `my.task/send-back!` reason text.
8. **Section content check is only non-blank.** `TBD` passes; level of heading is ignored (`### Approach` counts);
   duplicate headings collapse in the anchor map (not tested). Fine for version 1; do not describe rule 1 as
   "has content".
9. **The verdict should return all failures, report the first.** The spec's short-circuit `when` hides later reds, but
   the agent's block benefits from the full list (rows 1 and 2 also fail rule 3). If the deftest short-circuits, the
   block shows one red at a time; each fix costs a full request (about 0.3-0.5 s of settle).
10. Not a spec issue: `eval_clj` cannot name the branch; `seon.schema/register!` is the registration path at the REPL
    and is admitted on the branch, creating `:seon.schema/key` entities with digests (changed set was exactly
    `#{:probe.workout/id :probe.workout/session}`).

## Proposed edits to TASK-CHAIN section 4

- Helpers: `changed` as written (two `digests` maps; no Datalog on both sides needed). Add `verdict` shape: the deftest
  asserts rules in order, but reports via a helper returning the full failed list.
- Rule 2: say "`problems` of the whole plan, backticked or resolved bare names; an unresolved bare name is not a
  problem".
- Rule 3: "`(changed database :seon.schema/key)` non-empty, OR a resolved schema key is cited in `## Approach`"
  (Approach, not anywhere), and state it is a floor.
- Rule 4: "Every function in `(changed database :seon.fn/sym)` has empty `seon.contracts/gaps`; the cited-existing
  functions are not judged" (replaces cited-and-existing).
- Design Done text: add "a stub the REPL refuses (unregistered schema name) does not exist; read the diagnostics".
- Section 2 step 5: signature `advance [finished next declared owned]`; absent flowing key flows nothing.

## Rows needing the platform (S3)

Green at start (a plan already done ends the task with no model call); turn budget and `:capped`; restart durability
of the verdict; `(seon.test/task)` binding and `(seon.db/db)` in a deftest run by the runner; reaching-test selection
for the judged deftest; the merge-time contract-completeness gate on the branch's carried schema; reruns reusing
results by reach digest; the real `:seon.task/plan` attribute and `{:seon.task/owned true}` marker.
