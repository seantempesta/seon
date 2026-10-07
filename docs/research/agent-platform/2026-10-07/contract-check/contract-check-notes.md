# contract-check: the static contract check's cost (lane contract-check, 2026-10-07)

Task linked: `seon.schema/contract-check-on-three-files-exceeds-one-second`. Also fixed:
`seon.contracts-compile-test/empty-input-calls-analyzer-with-a-refused-empty-source-map` (regression
`seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings`).

## Who calls it

`seon.contracts-compile-test/check` is not part of `bin/lane commit` / `bin/seon check`; its callers are
`bin/seon-hook` (`run-contract-compile`, every Edit/Write of a src/test file, in the live root JVM, one file with its
baseline) and the namespace's own deftests (the whole-corpus one, in the check JVM). So the per-edit path is the
hot one; the whole-corpus run is the deftest.

## What it was proportional to (default JVM, armed, HEAD da0ae05dd, JVM-mode probes)

| probe | ms | allocated |
|---|---|---|
| `::paths [src/seon/schema.clj]` | 86 | 135 MB |
| hook-shaped edit of schema.clj (sources + baseline, one added throwing defn) | 168 | 323 MB |
| ten src files | 127 | 203 MB |
| whole corpus, 471 files, 10.0 M chars | 1,510 | 3,155 MB |

Whole-corpus stages: `namespace-contexts` 1,217 ms / 1,549 MB (its `through-ns-form` parsed every whole file to
find the `ns` form, then clj-kondo analysed the prefixes), `declared-contracts` 610 ms / 1,413 MB (a second whole
parse), `contract-refusal` over 2,872 contracts only 26 ms / 53 MB. One edamame parse of the corpus is 395 ms /
1,048 MB.

Repeated work found:

1. Each text was parsed by edamame up to six times per check: `through-ns-form`, `declared-contracts`,
   `incomplete-changes` (text and baseline), `uncontained-changes` (text and baseline), `containment` (again) and
   `transaction-functions` inside `containment` (again).
2. `seon.fn/qualify-schema-symbols` called `(m/default-schemas)` on every contract, constructing every Malli
   default schema object to ask `contains?` of a symbol: about 180 MB per corpus run (JFR allocation samples), and
   the indexer pays the same per contract it indexes.
3. The schema resources (204 files, 764 KB) were read and EDN-parsed twice per call (population and placement),
   about 40 MB each, on every hook edit although they rarely change.
4. `slurp` of all of `src/` when an edit adds an escaping throw: 38 MB, mostly StringWriter growth.

## The fix

- `check` reads each text once (`read-texts`) and the baseline once; `declared-contracts`, `incomplete-changes`,
  `uncontained-changes` and `containment` now take read forms (their contracts say so, so a re-read inside them is
  no longer expressible); `through-ns-form` cuts the prefix from the already-read `ns` form's end position without
  splitting the text into lines; `transaction-functions` keeps its text interface over a forms-taking
  `form-transaction-functions`.
- `seon.fn/qualify-schema-symbols` reads a set of Malli's default schema symbols derived once at load
  (EQUIVALENT: `m/default-schemas` builds a fresh map with a fixed key set, and the check is `contains?` of a
  simple symbol). It gained its contract.
- Schema resources: read once per call into one value used for the population and the placement check, parsed
  once per content: `seon.cache` kind `:seon.contracts-compile-test/schema-resources` keyed by every file's path and
  text (the key IS the content, nothing is invalidated). The profile above (about 45 MB per hook edit, of which the
  file read is under 2 MB) is the evidence it pays.
- Files are read with `Files/readString` (one exactly sized string). BEHAVIOR CHANGE only for malformed UTF-8,
  which now throws instead of being silently replaced.
- No `ns` form among the selected texts analyses nothing (the empty-input task).

## Numbers (throwaway JVM, unarmed, same JVM for before and after, pinned Datahike d9121bbbad)

Load average was 26-68 during these runs (other lanes), so milliseconds are noisy; allocation is exact per thread.

| probe | before ms / MB | after ms / MB |
|---|---|---|
| typical hook edit (src/seon/task.clj, 42 KB, adds a throwing defn) | 165 / 188 | 57 / 80 |
| large hook edit (src/seon/schema.clj, 205 KB) | 261 / 315 | 107 / 154 |
| one file by path (schema.clj) | 131 / 128 | 48 / 39 |
| ten files by path | 173 / 144 | 68 / 50 |
| whole corpus, 472 files | 2,973 / 3,070 | 1,191 / 1,699 |

The per-edit path now meets the 200 ms / 100 MB target for typical files; a 200 KB file costs about 150 MB
(two parses of it plus containment over every definition in it, which the same-file fixpoint needs). The
whole-corpus run is linear in source characters (about 170 bytes per character) but NOT far under 1 GB: its floor
here is one edamame parse of 10 M characters (about 1,040 MB) plus clj-kondo over 471 `ns` prefixes (about
400 MB).

## Not fixed here (out of the lane's files)

- edamame allocates about 100 bytes per source character. JFR: `edamame.impl.parser/read-token` and
  `parse-string*` call `(.append sb ch)` with a boxed `Character`, which resolves to `append(Object)` and allocates a
  `String` per character (about 23% plus 8% of edamame's allocation); location metadata `assoc`/`vary-meta` is
  another 20%. Probe: patching the three appends to `(.append sb (char ch))` in a throwaway JVM cut a corpus parse
  from 1,042 to 832 MB. edamame is upstream borkdude (`reference-code/edamame` 196ea5e4ef, not a fork), so this is
  an upstream patch, not a lane edit. Task: `seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing`.
- clj-kondo analysis of a short `ns` prefix costs about 0.85 MB per file (`seon.fn.analyzer/invoke-kondo`).
- Lint warnings that remain on the file (pre-existing): `pr-str` in `declared-contracts` (the indexer's own EDN
  round trip, which the check mirrors), `incomplete-changes`, `contract-refusal` and `finding` messages. Covered by
  the printer-cut work, not this lane.
- `bin/seon status`: default `:degraded` with seven over-bound warnings (`seon.sci.eval/call-detecting-program-change`
  2.8 s max, task "10d1523434db"; `seon.schema/call-with-registration-delta`, `seon.sci.observe/observe`,
  `seon.await/await-port-operations`, `seon.sci.batch/around`), not this lane's.
- `seon.fn-test/static-index-preserves-the-jvm-entity-contract` (the test that covers Malli predicate symbols
  staying themselves) is red on HEAD itself in `bin/seon check`: the check JVM's clj-kondo dependency cache has no
  analysis of `clojure.test.check.clojure-test`, so `seon.fn/program-entities` refuses with
  `:seon.fn/dependency-analysis-missing-error` (run 68dfc952e753). Task `seon.fn.analyzer/check-jvm-lacks-test-check-dependency-analysis`
  (id 19535b06216c, created on default). The commit names the other reaching tests instead.
- `reference-code/datahike` in the shared tree is checked out at ee5ac0bd with uncommitted query edits (lane
  dh-fix2); a JVM from the working tree failed to compile `datahike/query.cljc:1221` (`resolve-context`) mid-edit,
  so the measurements above use a `git archive` of the pinned d9121bbbad.

## Design check

The design held: the reads were the cost and every consumer could take forms. Awkward: the read-forms schema is the
same long `[:any {exemption …}]` pasted on each new contract, as the file already does; a registered name for
"top-level source forms" belongs in a schema resource (skill §4.2), which this lane did not add. The first
contract I wrote used a bare `:vector` and the namespace's own corpus deftest refused it: the gate works on itself.

Which step of the one path it serves: the edit hook every filesystem agent's write passes through. What it deletes:
four redundant parses per text and a per-contract registry construction in the indexer. No new mechanism besides
one `seon.cache` kind.

No default restart or reset is needed for correctness; root's next `start --head` installs the hook-side speedup
(the live root `require`s the checker from its archive).
