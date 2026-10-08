---
type: research
status: proposed
date: 2026-10-08
lane: cards-storage-point
---

# Storage point: decide a stored value's form once, refuse re-copies at the writer

Lane `cards-storage-point`, 2026-10-08. Design and cards only; no source changed. Cards: `SP-1a`, `SP-1b`, `SP-1c`, `SP-2`,
`SP-3`, `SP-4` in `docs/seon/schedule/cards/`.

What exists: `seon.blob/inline-or-stage` (`src/seon/blob.clj:250-270`, SR-B `9fc503097`) decides inline text versus a
staged blob by UTF-8 bytes against `:seon.config.eval.result/blob-threshold`; model replies, test failure texts and
(SR-D `0c97d0bdd`) recorded errors' shown text use it. Everything else decides its own way, and recorded errors
still build a second stored form, `:seon.error/data-edn`, by building the whole value's EDN and cutting it down.
The principal approach: the producer emits only the value printer's shown text (bounded by the printer's budget),
`inline-or-stage` chooses its form once, the full value stays live under its result handle (bound through the SCI context's `bind-result!` today, `error.clj:527-529`; `seon.cache` after SR-H), and
the checked writer refuses any stored text over the dial so a later re-copy fails loudly at its source.

## The trigger, read at the source

`seon.error/prepare` (`src/seon/error.clj:548-723`) builds `full-edn`, the canonical EDN of the whole admitted
evidence (admission bound `:seon.config.eval.result/max-bytes`, 8,388,608 on default), then `fit-fact-payload`
(`:448-490`) cuts the fact's fields to `:seon.config.error/max-evidence-bytes` (16,384) by halving until `pr-str` of
the whole fact fits. For `:seon.error/normalization-error` the observation also carries `full-edn` whole
(`:604-606`), because that schema REQUIRES `:seon.error/data-edn` (`resources/seon/schemas/seon.error.edn:424`).
`commit-call` then merges `(select-keys (:seon.error/source request) diagnostic-attributes)` over the fact's evidence
(`:1365-1366`), so the whole text replaced the fitted one: 300 KB inline. `b233d838f` copies the fitted value back
into the observation (`:713-717`). The value was built whole (copy), cut (copy), the whole was carried on (copy) and
the cut was restored over it (copy): four handlings of a value that should never have been built.

The producer is the defect: a second serialization (`canonical-edn` of an admission, "data-edn") beside the shown
text, which the storage-roles rule forbids ("never a second serialization of a live value",
`storage-roles/storage-roles.md` §1). Deleting `data-edn` removes the copy, the cut, the carried whole and the
restoration together (card SP-1a).

## 1. One decision point and one dial

Rule: a stored text's form is decided once, by `seon.blob/inline-or-stage` (text) or by `seon.blob/stage-binary!`'s
own staged write (bytes, which already compares against the same dial, `blob.clj:42-59,272-300`), at the producer of
the stored form, against `:seon.config.eval.result/blob-threshold` (4,096 bytes, owner-approved default for 16 GB).

The other dials that decide inline or blob, read on default 2026-10-08 (JVM probe, `config` facts):

| Dial | Default | Read at | Ruling |
| --- | --- | --- | --- |
| `:seon.config.error/max-evidence-bytes` | 16,384 | `error.clj:558`, plumbed through `instrument.clj:623-669,1085-1109`, `sci/eval.clj:768-797,2315,2388`, `cluster.clj:2369,2597,3317`, `turn.clj:3189,4155`, `schedule.clj:552`, `test.clj:2196,2232`, `fault.clj:168-206`, `seon.turn.loop.edn:252` | **Deleted** (SP-1a). It bounds the `data-edn` fitting, which is deleted; error shown text follows the one dial |
| `:seon.config.shell/inline-output-bytes` | 4,096 | `shell/jvm.clj:307` (`output-descriptor`) | **Deleted** (SP-3). `stage-binary!` already decided by the one dial; shell decides a second time |
| `:seon.config.web/max-inline-bytes` | 8,192 | `web/jvm.clj:284,417` (`capture-body!` `:193-208`) | **Deleted** (SP-3). Same double decision, and a different number: a 6,000-byte body is inline by web's rule and over the threshold by the blob's |
| `:seon.config.fs/max-inline-bytes` | 8,192 | `fs/jvm.clj:518` (`read` window), `edit/jvm.clj:137` (edit context window) | **Kept, justified**: it decides nothing about storage. It is the default of a read request's `:my.fs/max-bytes`, the size of the slice an agent asked for, at the owner's 2,000-token raw-return rule (8,192 bytes is about 2,000 tokens). Its edit use is an agent-facing clip outside the printer, already in the printer-rules census (`printer-rules/printer-rules-opus-printer-rules.md:149`); that census owns it |
| `:seon.config.eval.result/max-bytes` | 8,388,608 | `sci/eval.clj:4152-4180` (printed output), `sci/admit.clj:757-778` | **Kept, justified**: it is not an inline/blob decision but the runaway bound on what one evaluation may capture before refusing (a print loop). It caps work, not form; SR-C stores the captured text by the one dial |
| `:seon.config.shell/preview-bytes` | 1,024 | `shell/jvm.clj:308` | **Not a storage dial**: a stored prefix copy for display, a clip outside the printer, already in the printer-rules census (`printer-rules-opus-printer-rules.md:150`), which owns it; SP-3 leaves it unchanged |

`turn.clj:4204-4212` makes a sixth decision with no dial of its own: model reasoning compares `count` (characters)
to the blob threshold and stages by hand; under 4,096 characters a reasoning of up to 12 KB stays inline. SP-4 routes
it through `inline-or-stage`.

## 2. The writer refuses re-copies

Every stored-text attribute is declared with one registered schema, `:seon.blob/inline-text`:

```clojure
:seon.blob/inline-text [:string {:seon.db/max-bytes-dial :seon.config.eval.result/blob-threshold}]
```

The checked writer refuses a transaction asserting a value of such an attribute whose UTF-8 byte size exceeds the
dial's value on the database the write leaves. A later step that re-copies a full text into storage therefore fails
at its source with a typed refusal naming the attribute, its size and the dial.

**Property the writer checks, not a Malli `:fn`.** Malli compiles a validator per schema and Seon caches it per
projection (`seon.schema/projection-cache-value`, `schema.clj:432`); a `:fn` predicate receives only the value, so it
cannot read the dial without an ambient database (a dynamic var: a hidden world, refused by "pass the world
explicitly"), and an inline function is an anonymous contract function (refused by "contract every admitted
function"). A literal `:max` is the hardcoded number the owner forbids. The writer already has both seams this needs:

- it reads a policy dial from the database it leaves, `:seon.config.db/validation-node-limit`, in
  `validate-write-report` (`src/seon/db.clj:5411-5443`), minimum over every asserted value;
- it derives attribute rules from schema properties once per projection, as `retention-rules` does for
  `:seon.db/append-only-after` (`db.clj:5445-5455`).

So the rule is data on the schema (`:seon.db/max-bytes-dial` names the dial), the attribute set is derived once per
projection by walking `:seon.schema.projection/forms` and `m/deref-all` (`reference-code/malli/src/malli/core.cljc:2872`)
to the registered schema's properties, and the check runs where the node limit is read. Cost per write: one set
lookup per asserted datom, a byte count only for strings of those attributes, one AEVT read of the dial. Card SP-2.

The attributes it covers, and the card that brings each under the one decision first (declaring an attribute
`:seon.blob/inline-text` before its producer stages over the threshold would refuse today's writes: 20 of 604
`:seon.eval/shown` values on default exceed 4,096 bytes):

| Attribute | Producer decides by the one rule since | Declared `:seon.blob/inline-text` by |
| --- | --- | --- |
| `:seon.error/shown` | SR-D `0c97d0bdd` | SP-2 |
| `:seon.turn/reply` | SR-B `9fc503097` | SP-2 |
| `:seon.test.failure/message`, `/expected`, `/actual` | SR-B | SP-2 |
| `:seon.error/message`, `:seon.error.occurrence/message` | fixed short text by the errors rule (max 301 bytes on default) | SP-2 |
| `:seon.ai.attempt/reasoning` | SP-4 | SP-4 |
| `:seon.cluster.eval/output` | SR-C | SR-C (line added to its card) |
| `:seon.eval/shown` | SR-F | SR-F (line added to its card) |
| `:seon.effect/result-edn` | SR-EFFECT (ledger row, no card yet) | SR-EFFECT |

Not covered, by ruling: `:seon.fn/source` (program source, OWNER DECISION D of `storage-audit-opus.md`, 286
definitions over 4,096 bytes); `:seon.ai.attempt/usage-edn` and `settings-edn` (SR-G deletes them).

## 3. Copy-then-trim census

Script (one search, committed here as the command; output was 295 lines, classified by hand):

```sh
rg -n --no-heading -e '\(subs ' -e 'max-[a-z-]*bytes' -e 'fit-[a-z-]+' -e '\bbounded-text\b' \
   -e 'bounded-admission' -e '\btrim[a-z-]*\b' -e '\(take [0-9a-z-]+ \(pr-str' -e 'utf8-size \(pr-str' \
   -e 'count \(pr-str' -e 'canonical-edn' -e 'admit/admit' src
```

Excluded as not copy-then-trim: parser and source-span `subs` (`sci/reader`, `sci/forgive`, `seon.edit` spans,
`turn/batch.clj:194-202`, `fn.clj:129-194`, `cluster/source.clj`), id prefixes (`id.clj:261`, `eval/drive.clj:368-381`),
class-name parsing (`error/refusal.clj:91-140`, `schema.clj:1023`), the printer itself (`print.cljc`, `render/value`,
`render/walk`), and the log rotation bound (`maintenance.clj:240-246`). What remains stores or carries a value:

| Site | What is copied | Size class (default, 2026-10-08) | Fix |
| --- | --- | --- | --- |
| `error.clj:568-569` `bounded-error-admission` then `value-edn` | The whole error evidence as canonical EDN | up to `eval.result/max-bytes` (8 MB); observed 300 KB | Delete; the shown text from `prepare-result` (`:492-530`) is the one stored form (SP-1a) |
| `error.clj:448-490` `fit-fact-payload` | Re-admits the source per halving step, `pr-str` of the whole fact per step | log2(16,384) = 14 steps × (value walk + fact print) | Delete (SP-1a) |
| `error.clj:604-606`, `:713-717` | `full-edn` carried on the observation, then the fitted copy restored over it (b233d838f) | as above | Delete with `data-edn`; the restoration reverts (SP-1a) |
| `error.clj:1365-1366` `commit-call` merges source diagnostic members over the fact's evidence | any declared observation member, whole | bounded only by admission (8 MB) | Kept as the store of the error's own declared members, now under the writer's byte refusal for text (SP-2); text members become shown text (SP-1b) |
| `error.clj:356-374` `bounded-text`; `:seon.instrument/expected`, `args`, `actual` | Each value admitted and printed as canonical EDN, a second printer | observed max 911 bytes; bound 16 KB | Delete the three stored texts; the offending value is what `prepare-result` renders into `:seon.error/shown` (`:510-512`); args live in the cached error map (SP-1b) |
| `error.clj:422-432` `project-observation`; `admitted-size` `:434-446` | Write attempt operations and schema declarations admitted and printed as EDN into `:seon.error.projection` components | bound 8 MB | Delete; shown text plus the cached value (SP-1b) |
| `effect.clj:539-559` `staged-result` | Canonical EDN of the whole admitted result kept inline AND as a blob over the threshold | up to 8 MB, stored twice | SR-EFFECT: `inline-or-stage` on `result-edn` |
| `effect.clj:866`, `render.clj:1110` | Canonical EDN of an unknown-evidence marker | bytes | None: a small fixed value |
| `shell/jvm.clj:303-322` `output-descriptor` | Re-reads a prefix of the staged capture as `text` or `preview`, decided by a second dial | 4 KB inline, 1 KB preview copy | Decide from the staged write (SP-3); the preview copy is the printer-rules census's row |
| `web/jvm.clj:193-208` `capture-body!` | Reads `max-inline + 1` bytes, then stages the rest by a different dial | 8 KB | Stage once with `stage-binary!`, decide from its staged write (SP-3) |
| `turn.clj:4204-4212` | Reasoning compared in characters, staged by hand | up to 12 KB inline | `inline-or-stage` (SP-4) |
| `test/runner.clj:1394-1405,1433-1438` `thrown-artifact` | Thrown test failure admitted then printed as a value artifact, staged as a blob | up to 8 MB | SR-E (card exists): shown text only |
| `sci/eval.clj:4152-4180` printed output | `StringBuilder` grown to the runaway bound, `toString` copy, stored inline whole | up to 8 MB inline datom | SR-C: one dial decides the stored form; the builder's single copy stays (it is the producer) |
| `sci/eval.clj` settlement of `:seon.eval/shown` | Printer text stored inline at any size | max 4,862 bytes, 20 of 604 over 4,096 | SR-F |
| `my/program.clj:867` snippet `(subs t 0 97)` | A display clip outside the printer | 100 chars | Printer-rules census owns it (not storage) |

## 4. Reverting the patch

b233d838f's restoration (`error.clj:710-717`) is reverted by SP-1a, in the commit that deletes `data-edn`, because
from then on the observation carries no text that the fact cut. The guard test
`seon.error-evidence-test/a-capped-turn-fault-says-it-is-capped` stays and changes its subject: it asserts the
occurrence stores no `:seon.error/data-edn` and that its `:seon.error/shown` is at most the blob threshold in UTF-8
bytes, else `shown-blob` and `shown-size` are present. The task
`seon.error/commit-tx-stored-the-whole-admitted-data-edn-over-the-fitted-one` (`resources/seon/tasks/seon.error.edn`)
names that test as its proof; SP-1a updates its intent.

## 5. Algorithm: what each path is proportional to

V = size of the value, S = shown text (bounded by the printer budget), B = blob threshold (4,096), T = threshold for
the fact (16,384 today), N = datoms touching the leaf the text lives in.

| Path | Now | After |
| --- | --- | --- |
| Error recording (`seon.error/prepare`) | Admission walk of the evidence (V, up to 8 MB), `canonical-edn` copy (V), up to log2(T) = 14 fit steps each re-admitting the source and printing the fact (14 × min(V, T)), `admitted-size` re-admits the offending value (V), `bounded-text` × 3 fields, `prepare-result` printer pass (S); stored: `data-edn` ≤ T inline and `shown` inline or blob; observation carries `full-edn` (V) to the writer. About 17 passes, 3 to 4 copies of V | One printer pass (S, lazy, budgeted: proportional to what is shown, not to V), one `inline-or-stage` (S bytes counted once), the value bound in the cache by reference. 1 pass, 1 stored copy of S |
| Eval request recording | Shown text S stored inline at any size; printed output up to 8 MB stored inline; each inline text is rewritten with every later commit touching its index leaf (storage-roles §1: about 2.5 further copies of a 64 KB string in the next 10 commits) | S and output inline only under B, else one blob written once and deduplicated by digest; the leaf carries a digest and a size. Per-commit rewrite cost proportional to B at most, not to the text |
| Test failure | Message, expected, actual by `inline-or-stage` (done, SR-B); thrown actual: admission (V) + artifact print (V) + blob | SR-E: one printer pass (S) and `inline-or-stage` |
| Model reply | Reply by `inline-or-stage` (done); reasoning counted in characters, staged by hand | Reasoning by `inline-or-stage`; one byte count |
| Effect result (shell, web, fs) | Producer decides inline by its own dial; effect stores canonical EDN inline and again as a blob over B | Producer decides by the staged write (one dial); SR-EFFECT stores once |
| Writer | No size check: a re-copy reaches storage silently | One set lookup per asserted datom plus a byte count of covered strings: proportional to the bytes the transaction writes, which the write already pays |

The byte count itself allocates: `seon.blob/utf8-size` builds the whole UTF-8 array to read its length
(`blob.clj:217-221`). Guava's `com.google.common.base.Utf8/encodedLength` answers the same number with no allocation
(probe on default: both answer 1,200,000 for a 300,000-repetition string of `"abé"`); Guava is on the classpath
transitively, not declared. SP-2 uses `utf8-size` unchanged; switching needs Guava declared in `deps.edn` and is left
to the owner (stop reinventing cuts both ways: a transitive dependency is not ours to lean on).

## Cards and order

Ordered around the file holders of 2026-10-08 (`bin/lane status`):

| Card | What | Waits on |
| --- | --- | --- |
| SP-3 | Shell and web take the one threshold; two dials deleted | nothing (files unheld): first |
| SP-1a | Error stores shown text only; `data-edn`, the fitting loop and the b233d838f restoration deleted | `cards-audits` releases `src/seon/error.clj` |
| SP-1b | No admitted copies of offending values (`bounded-text`, `project-observation`) | SP-1a |
| SP-1c | `:seon.config.error/max-evidence-bytes` and its carriers deleted (scripted sweep) | SP-1b; `f4a` releases `src/seon/turn.clj`; before E-SDELETE |
| SP-2 | Writer refuses stored text over the dial; `:seon.blob/inline-text` | `led-cacheb` releases `src/seon/db.clj`, `blob-konserve` releases `seon.blob.edn`; SP-1b |
| SP-4 | Reasoning by `inline-or-stage` in bytes | SP-2; `f4a` |
| SR-C, SR-F, SR-EFFECT | Their producers stage over the threshold and declare their attribute `:seon.blob/inline-text` | SP-2 (line added to SR-C and SR-F) |

## Findings outside this assignment

- `seon.cluster.status` reports `:faulted` with 8 open faults and 65 errored receipts on default at the time of the
  probe (`runtime_status`, basis 536872163); not investigated here.
- The ledger (`docs/seon/schedule/agent-loop-ledger.md`, held by `cards-audits`) row SR-T is superseded by SP-1a,
  SP-3 and SP-4 (SR-T's proof name `seon.blob-threshold-test/every-text-writer-uses-the-one-threshold` is split into
  the cards' proofs, and `:seon.config.fs/max-inline-bytes` is kept, not retired). Root updates the row.
