# Seeds: does every generative test pick its random seed as a pure function of its digest?

Read-only survey of the worktree at b39cef5 (2026-10-23). Question (a) of the pure-tests work:
a result reused by reach digest (`seon.program/reach-digests`, inputs `seon.fx/inputs`) is only
honest if the test's randomness is fixed by something the digest covers.

## The finding that matters: `mg/check` ignores `:seed` and `:num-tests`

Both mg/check callers pass `{:registry ... :num-tests 100 :seed 42}`. Our malli fork
(`reference-code/malli` at 5a830e14) never reads either key:

- `malli.generator/check` (reference-code/malli/src/malli/generator.cljc:572) only installs
  `function-checker` and calls `m/explain`.
- `function-checker` (generator.cljc:540-570) reads `::mg/=>iterations` (default 100) and calls
  `(check/quick-check =>iterations (prop/for-all* ...))` at :553 with **no `:seed`**.
- test.check 1.1.1 (malli's deps.edn:5; jar in ~/.m2) `clojure.test.check/make-rng`
  (check.cljc:19-24): with no seed, the seed is `(get-current-time-millis)`, i.e. the wall clock.
- The failure map malli returns is the quick-check `:shrunk` map plus explain keys; the
  quick-check result's top-level `:seed` is dropped, so a failure **cannot be reproduced**.

So every mg/check runs on a clock seed today, and `:seed 42` in our forms is a placebo (it
happens to be 100 iterations only because 100 is malli's default). A green from one clock seed
is stored forever under a digest that does not contain that seed.

(Jolt implements `java.util.SplittableRandom` draw-for-draw, reference-code/jolt
host/chez/java/host-static-classes.ss:2950, so a fixed seed *would* be deterministic.)

## Every site

| Site (file:line) | What it randomizes | Seed fixed? | In digest? | Reproducible on failure? | Tests affected |
|---|---|---|---|---|---|
| src/seon/check.clj:390 (`seon.check/mg-check`) | `mg/check` of every pure contract (~82 vars), generated arguments | **No**: `:seed 42` is ignored by malli; test.check seeds from `currentTimeMillis` | No (the clock seed is in nothing) | **No**: the report keeps `{:smallest :problem}` only (check.clj:396-399); malli dropped `:seed` anyway | bin/check step `mg-check` (`seon.proofs/check-steps` "mg-check", all K shards) |
| test/seon/merge_test.clj:161-162 | `mg/check` of `seon.merge/conflict-value` | **No** (same cause) | No | No (`is (nil? ...)` prints the map without a seed) | `seon.merge-test/a-conflict-value-reads-a-pulled-entry` |
| test/seon/reader_test.clj:77 | `tc/quick-check 300` over generated replies | Yes, `:seed 42` (quick-check honors it) | Yes: literal in the test's form | Yes: seed is a literal; prints `[:shrunk :smallest]` | `seon.reader-test/the-laws-hold-over-generated-replies` |
| test/seon/print_test.clj:50-51 | `tc/quick-check 150` over `value-gen` x budgets | Yes, `:seed 202610071` | Yes: literal | Partly: seed literal, but the message prints neither `:smallest` nor the seed | `seon.print-test/the-text-stays-inside-its-budget` |
| test/seon/print_test.clj:129 | `java.util.Random.` drawing 600 fipp docs | Yes, 20261020 | Yes: literal | Yes: prints the first wrong doc | `seon.print-test/the-layout-is-fipps` |
| test/r3/inc_test.clj:39-63 | `java.util.Random.` per seed 1..5 driving 150 random writes (`rand-step`) | Yes, `[1 2 3 4 5]` | Yes: literal | Yes: prints `:seed :k :tx` and the diff | `r3.inc-test/incremental-equals-full` |
| test/seon/cost_test.clj:156, 158 | `shuffle` (Jolt's `rand-int`, jolt-core 21-coll.clj:15) of the input for `:sorted/assoc`, `:sort/compare` | No: Jolt's PRNG seeds from clock + pid + tid (jolt host/chez/rt.ss:2800-2812) | No | No | the host cost table's `:sorted/assoc` and `:sort/compare` rows (cost proofs; timing-based, so impure by nature, but the input permutation should not add to it) |
| test/seon/model_test.clj:24 | a TCP port `(+ 20000 (rand-int 20000))` | No (clock-seeded) | n/a (ports are excluded from config by design) | No | `seon.model-test/a-streamed-reply-ends-when-the-server-ends-it`: a collision is a flaky red, not a wrong green; outside this question (a port, not a seed) but impure |
| test/seon/db_test.clj:273 | `(random-uuid)` as a turn's `:commit` value inside measured data | No (OS entropy, jolt natives-misc.ss:26-47) | No | n/a | `seon.db-test/a-commit-costs-what-it-writes` (byte counts: 16 incompressible bytes per turn; same length every run, so compressed size can wobble by a few bytes) |
| `random-uuid` for paths, branch names, store ids: test/seon/root_test.clj, db_test.clj, check_test.clj, test_store.clj:11,30, fx_test.clj:42, program_test.clj:244, proofs_test.clj:32,148, lib_test.clj, main_test.clj, agent_test.clj, procs_test.clj, task_files_test.clj; src/seon/root.clj:683 (boot id), src/seon/e2e.clj:24 | names only | No | No | n/a | none found asserting on the value: names, not data. Pure for this purpose |
| src/seon/check.clj:106 `(gensym "await-")` | a watch key | n/a | n/a | n/a | none: a name |

No `defspec`, `random-uuid`-in-assertions, `SecureRandom`, `Math/random`, or unseeded
`gen/generate` / `mg/generate` / `mg/sample` call exists under src/ or test/. No src code that a
test reaches draws `rand` (src has only `random-uuid`, as names). Library defaults: the
test.check version is malli's deps.edn pin inside the malli submodule, and deps.edn itself is a
non-.clj tracked file, so both are in `:env`; but that covers the library's *code*, not the
clock seed it picks.

Not checked here: whether Jolt's hash iteration order (keyword/symbol hashes) is identical
across processes; if hashing is seeded per process, set/map iteration order would be a hidden
source of nondeterminism in every test. Worth one REPL probe (two processes, `(seq #{:a :b ... })`).

## Recommended fix, at the producer

1. **The malli fork (reference-code/malli, a `jolt/<topic>` branch; a dependency change, so
   root's go first, AGENTS §3).** `function-checker` reads a seed from options in malli's own
   style, beside `::mg/=>iterations`, passes it to quick-check, and keeps it in the failure:
   ```clojure
   ([?schema {::keys [=>iterations =>seed] :or {=>iterations 100} :as options}]
     ... (check/quick-check =>iterations (prop/for-all* ...) :seed =>seed)
     ... (cond-> (assoc shrunk ::m/result result :seed seed) ...)   ; seed from quick-check's result
   ```
   About 4 changed lines in generator.cljc, plus a malli test that two checks with one
   `::mg/=>seed` fail on the same `:smallest`. Upstream malli has the same gap; worth offering.
2. **One config key** (config is in `:env`, so the seed is digest-covered and changing it
   re-runs every test, which is right):
   - resources/seon/config/default.edn, in the proofs block beside `:seon.config.proofs/jobs`
     (~:269), in the file's shape:
     ```
     ;; Unit: a test.check seed (long). Every generative check (mg/check in seon.check/mg-check and
     ;; tests, tc/quick-check) draws from this seed, so a result is a function of its reach digest
     ;; (config is in :env). Provenance: pure-tests (a), 2026-10-23: mg/check ignored :seed and
     ;; drew from the wall clock. At the bound: none; a failure prints this seed as its reproduction.
     :seon.config.proofs/seed 42
     ```
   - resources/seon/schemas/seon.config.proofs.edn: `:seon.config.proofs/seed [:int {:seon.config/unit "test.check seed" :description ...}]`; seon.config.edn:65 lists it (+1 line).
3. **Callers pass it and print it:**
   - src/seon/check.clj:390: `{:registry contract/registry ::mg/=>iterations 100 ::mg/=>seed (cfg/value :seon.config.proofs/seed)}`;
     the failed row (:396-399) keeps `:seed` and a reproduction form,
     `(mg/check (m/deref (contract/schema (contract/contract #'ns/f))) ns/f {::mg/=>seed S ...})`.
   - test/seon/merge_test.clj:162: the same options.
   - test/seon/reader_test.clj:77 and print_test.clj:51: `:seed (cfg/value :seon.config.proofs/seed)` (or keep the literals, which are already covered; the gain is one place to vary the seed); print_test's message prints `[:shrunk :smallest]` like reader_test's.
   - test/seon/cost_test.clj:156,158: `(shuffle ...)` becomes a seeded permutation, e.g.
     `(let [r (java.util.Random. n)] ...)` Fisher-Yates, or `(vec (sort-by #(mod (* % 2654435761) n) (range n)))`.
   - test/seon/db_test.clj:273: `(java.util.UUID. 0 k)` instead of `(random-uuid)`.
   - model_test.clj:24 (a port, not a seed) belongs to the ports work: take a port from config/`bin/ports` or bind port 0.

**Write set:** reference-code/malli/src/malli/generator.cljc (+ a malli test) and the malli pin;
resources/seon/config/default.edn; resources/seon/schemas/seon.config.proofs.edn and
seon.config.edn; src/seon/check.clj; test/seon/merge_test.clj, reader_test.clj, print_test.clj,
cost_test.clj, db_test.clj.
**Size:** about 4 lines in malli (+ ~8 test), ~6 config/schema lines, ~6 lines in check.clj,
~8 lines across the five tests: under 35 lines, nothing deleted beyond the placebo `:seed 42` /
`:num-tests 100` keys. Proofs: a deftest that mg/check with one seed on a contract made to fail
returns the same `:smallest` and `:seed` twice (red today: different seeds, no `:seed`).

Side note, not seeds: the mg-check step's reach digest is the reach of `seon.check/mg-check`,
but the contracts it checks are found at run time (`ns-interns` over `platform-nss`); whether
`reach-digests` sees those edges decides whether a contract change re-runs mg-check. Belongs to
the reach question.
