# structural-hash — notes (lane structural-hash, 2026-10-07)

STATUS: STOPPED before coding seon.id, for an owner decision (task handles) and held files. Only these notes claimed,
nothing committed. Prototype: `digest-prototype.clj.txt` beside this file (scratch ns, evaluated on default in JVM mode,
then `remove-ns`'d).

## What exists

`seon.id/id` = SHA-256 of `pr`'s text under fixed bindings (iterative printer, 77c9f5459), streamed into the digest;
`digest` = `id` of `(vec parts)`. A second text hash lives in `seon.schema/canonical-data-string` (framed text, maps
and sets sorted by encoded text), used by `seon.program/definition-digest` (canonical text, then printed again into
`id/digest`), `typed-key-facts`, `seon.schema.edn/declaration-digest`, `seon.schema/entry-fingerprint`
(`String.hashCode`) and `seon.db/read-result-digest`. A third: `pr-str` into hasch/SHA in `seon.test.cache`,
`seon.test.runner/report-identity`, `script/seon/dev/dependency_digest.clj`, `script/seon/operator.clj`.

## Design: one structural digest

`seon.id/id` feeds the value's structure into SHA-256 through an 8 KB reusable byte buffer, with an explicit
`ArrayDeque` of frames (depth costs heap, never JVM stack). Encoding (one tag byte, then payload):

- nil, false, true: tag only. Boolean objects by value.
- Integers (Long, Integer, Short, Byte, BigInt, BigInteger): tag `long` + 8-byte big-endian when the value fits a
  long, else tag `bigint` + length-prefixed two's-complement bytes. So `(= 1 1N)` hashes equal, as Clojure `=` and
  `hash` agree.
- Double, Float: tag + `Double/doubleToLongBits` of the double value, with -0.0 folded to 0.0 (`(= 0.0 -0.0)`) and
  NaN canonical (doubleToLongBits). `1` and `1.0` differ (`=` false).
- Ratio: tag + numerator and denominator bytes. BigDecimal: `stripTrailingZeros`, then tag + scale + unscaled bytes
  (`(= 1.0M 1.00M)` true). BigDecimal and long differ (`=` false).
- String: tag + UTF-8 byte length (8 bytes) + UTF-8 bytes encoded in place (a counting pass, then the bytes; lone
  surrogates as 3-byte sequences, so no two strings collide). Keyword / symbol: own tag + namespace presence byte
  + length-prefixed namespace + name. Metadata is ignored.
- Char: tag + code unit. UUID: tag + two longs. Date: tag + millis. Instant: own tag + seconds + nanos (a Date and
  an Instant are not `=`). Pattern: tag + pattern text + flags. TaggedLiteral: tag + tag symbol + form.
- Vector (MapEntry included): tag, children, end tag. Seq/list/any other `Sequential`: its own tag, children, end
  tag. DELIBERATELY STRICTER THAN `=`: `[1 2]` and `(1 2)` differ, because code identity needs it (a list is a call,
  a vector a binding form).
- Map / set / record: each member (map: key then value) is digested by its own SHA-256 instance (one per nesting
  level, reused), and the 32-byte member digests are SUMMED mod 2^256 (four longs with carry). Then tag (map, set,
  or record + class name) + count + the sum. Order-independent with no sort and no per-collection array; a sum,
  not XOR, so equal-digest members (NaN, patterns) never cancel. sorted-map = hash-map, sorted-set = hash-set, as
  `=`.
- Anything else throws `ex-info` with `{:seon.error/type :seon.id/unhashable-error, :seon.id/class ...}` (needs a
  registered variant); never printed.

Probed laws (all true): 1/1N equal; 1/1.0 differ; 0.0/-0.0 equal; 0.5/(float 0.5) equal; 1.0M/1.00M equal; map and
set entry order irrelevant; sorted = hash; vector/list differ; list = equal range; nil/false, 'abc/"abc", :a/b /
'a/b, :a/"a", [1 [2]] / [[1] 2], {:a nil}/{}, #{}/{}, []/() all differ; #{[:a 1]} = #{(first {:a 1})}; metadata
ignored; Integer 5 = 5; \a ≠ 97; nested maps and sets equal in any order; 10,000-deep vector and 10,000-deep
map/set nest hash; `Object` refuses with the typed error.

Retained memory O(depth) (frames + one MessageDigest per unordered nesting level). Transient garbage stays
proportional to the number of map entries (Clojure's map iteration allocates a MapEntry and a seq step each), so the
strict "O(depth) allocation" target is not reachable without walking maps by `reduce-kv` (recursive).

## Measurements (default pid 30874, JVM mode, median of 3 after one warm call; same inputs both sides)

| input | printed into SHA-256 | structural prototype |
|---|---|---|
| projection forms `(seon.schema.edn/packaged-forms)`, 641,443 chars | 14 ms, 17.4 MB | 15 ms, 9.5 MB |
| `(vec (range 1e5))` | 8 ms, 12.0 MB | 5 ms, 1.6 MB |
| 10,000-deep vector | StackOverflowError on default's loaded (pre-77c9f5459) printer | 5 ms, 3.0 MB |
| 5,618 definition entities, canonical text then printed (definition-digest path), 13.0 M chars | 821 ms, 909 MB | 253 ms, 187 MB |

The projection forms are map-heavy: each entry finalizes its own SHA-256 block, so time is level with printing.
Armed `seon.id/id` on the 10,000-deep vector also overflowed on default (the armed wrapper, not the printer: the
unarmed `printed-digest` was measured separately) — worth a check once default runs HEAD.

## Why stopped: needs an owner decision

1. TASK HANDLES ARE `id/id [ns name kind]` WRITTEN LITERALLY. `seon.task/task-handle` derives them;
   `seon.task.edn` says ingestion refuses a supplied handle that differs from its aspect. Changing the encoding
   changes all 130 handles, which appear 593 times in 88 files outside docs/tmp (task EDN ids, `:seon/tasks`
   annotations, tests), plus `docs/`. 19 of those files are held by 8 lanes right now (commit-validation,
   context-examples, deep-review, error-frames, heap-retained, printers-rest, reach-review; list from
   `rg -l -F` of the 130 task handles × `bin/lane status`).
   Options:
   - A (recommended, simplest that keeps the law): one scripted sweep old→new handle over every file, landed with
     the seon.id change in a quiet window root arranges (all lanes released); default reset after.
   - B: handles become minted-once values (ingestion checks uniqueness, no re-derivation), so existing literals stay
     and only new tasks use the structural id. Fewer edits; drops the "handle = hash of aspect" check.
   - C: keep printing for task handles only. A second hashing path; against the ruling. Not recommended.
2. HELD FILES ON THE CORE SITES, independent of 1: `src/seon/program.cljc`, `src/seon/fn.clj`, `src/seon/cluster.clj`
   (printers-rest), `src/seon/db.clj` (`read-result-digest`, commit-validation), `src/seon/test/runner.clj`
   (`report-identity`, reach-review). The `id/digest` callers there convert by themselves when `id` changes, but
   `definition-digest`, `typed-key-facts` and `read-result-digest` hash `canonical-data-string` text and must be
   edited to drop the text.

## Slice once unblocked

- `src/seon/id.clj`: structural `id` (prototype → top-level private helpers with primitive hints; no reflection),
  `printed-digest` deleted, `printed` stays only for stored text (callers `seon.schema.datahike`, `seon.sci.admit`,
  `seon.turn`, `seon.render.schema`, `seon.mcp`, `seon.instrument`: value-storage lane / printers-rest).
- Register `:seon.id/unhashable-error`.
- Convert text hashes to `id/id`: `seon.program/definition-digest` (`(id/digest 64 [parts])`), `typed-key-facts`,
  `seon.schema.edn/declaration-digest`, `seon.schema/entry-fingerprint` (60-bit long from `id/id ... 15`, bump
  `projection-fingerprint-version`), `seon.db/read-result-digest`, `seon.test.cache/input-evidence-digest`,
  `seon.test.runner/report-identity`, `script/seon/dev/dependency_digest.clj`, `script/seon/operator.clj`
  `program-classes`. Then `canonical-data-string` keeps only the sort in `seon.program` (sort-by-print task) and its
  own tests.
- Literal ids in tests: `test/seon/id_test.clj` ("ba7816bf8f01", "15564fc125fc"; becomes the law table),
  `test/resources/publication-program/seon/id_test.clj.txt`, `test/seon/fn_test.clj` (`'seon.id/printed-digest`),
  plus every task-handle literal (decision 1).
- RESET NEEDED after landing: every stored definition digest, evaluation/turn/effect/schedule/error/test ids, task
  handles, source and schema digests, projection fingerprints and analysis digests re-derive.
