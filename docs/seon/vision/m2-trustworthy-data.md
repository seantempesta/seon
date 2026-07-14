---
type: milestone
status: partial
order: 2
---
# M2: Trustworthy Data

When this milestone is crossed, data cannot lie. Every attribute in the system has a Malli schema registered through `schema/register!`. Every write to the Datalog store is validated before it reaches storage. Serialization across JVM boundaries preserves types exactly -- no silent coercion, no lossy conversion. Generative tests prove that data roundtrips through the full pipeline (Malli validation, Datahike storage, Nippy serialization) without loss or corruption.

There is no `:any` anywhere in a persisted schema. There is no `nil` stored as a value. There is no schema that says one thing while the data says another. The schemas are the single source of truth, and every boundary enforces them.

## The Scenario

An agent writes a new trading signal function. It registers a schema for the signal and attempts to transact a result.

```clojure
(schema/register! ::signal-type [:enum :buy :sell :hold])
(schema/register! ::confidence [:double {:min 0.0 :max 1.0}])
(schema/register! ::signal-id [:string {:seon.db/identity true}])

;; Agent attempts a write with a typo -- :buuy instead of :buy
(db/transact! :seon [{:seon.trading/signal-id "sig-001"
                      :seon.trading/signal-type :buuy
                      :seon.trading/confidence 0.85}])
;; => ExceptionInfo: Validation failed for :seon.trading/signal-type
;;    Expected: [:enum :buy :sell :hold]
;;    Got: :buuy

```

The invalid data never reaches the database. The error message names the exact attribute, the expected schema, and the actual value. The agent fixes the typo and retries. The write succeeds, and the data is now guaranteed to match its schema.

Later, the same signal is read by another agent in a different JVM. The data crosses the wire via Nippy:

```clojure
;; In the remote agent JVM
(db/pull :seon [:seon.trading/signal-id "sig-001"])
;; => {:seon.trading/signal-id "sig-001"
;;     :seon.trading/signal-type :buy
;;     :seon.trading/confidence 0.85}

```

The keyword `:buy` came back as a keyword, not a string. The double `0.85` came back as a double, not a float. Nippy preserved the exact Clojure types. The generative pipeline tests prove this for every registered schema type, not just the ones we happened to test manually.

## What This Requires

**Single schema registration point.** `schema/register!` is the only way to declare attribute schemas. No raw Datalog schema literals scattered through the codebase. The bridge function (`seon-db-props->db-props`) translates Malli types to Datahike schema declarations automatically. Adding a new attribute means one `register!` call, not updates to multiple files.

**Validation gate on writes.** `db/transact!` validates every attribute and value against the Malli registry before calling Datahike. Invalid data is rejected at the boundary with a clear error. This is not optional -- there is no bypass for production writes.

**Nippy wire protocol.** Inter-JVM communication uses Nippy serialization (`fast-freeze`/`fast-thaw`), not EDN. Nippy preserves Clojure types that EDN cannot: keywords, sets, dates, UUIDs, byte arrays. The harness TCP bridge uses length-prefixed Nippy frames.

**No `:any` in persisted schemas.** Every attribute that touches the database or crosses the wire has a concrete type. The startup consistency check scans the registry and rejects `:any` and `[:maybe X]`. Wire protocol messages that carry arbitrary function arguments need a design solution (tagged unions or schema-per-message-type), not `:any`.

**Absence, not nil.** Optional fields use `{:optional true}`. If a key is present, its value must be valid. To clear a field, use `[:db/retract eid :attr]`. No nil values stored anywhere.

**Generative pipeline roundtrip tests.** For every registered schema type, property-based tests prove: generate a value from the Malli generator, validate it, transact it to Datahike, pull it back, serialize through Nippy, deserialize, and confirm the result matches the original. This is the `assert-pipeline-roundtrip!` utility.

**Schema deduplication.** Common schemas like `::db-name` and `::namespace` are registered once in a canonical location and referenced everywhere else. Not copied 14 or 20 times across the codebase.

## What Already Exists

- [[vision/capabilities/validated-writes]] -- complete. `db/transact!` validates via Malli before Datahike. Per-DB locking. Nippy wire protocol.
- [[vision/capabilities/data-contracts]] -- complete. `schema/register!` as sole registration. Three custom types (`:inst`, `:seon.db/ref`, `:seon.flow/dynamic`). Runtime instrumentation. Startup consistency check. Generative roundtrip tests.
- [[vision/capabilities/resilient-writes]] -- partial. Per-batch error isolation in graph ingest, timeouts, retry. DB writer step-fn lacks circuit breaker.
- [[vision/capabilities/database-platform]] -- complete. Datahike embedded in-process `[JVM track — paused]`, connection manager, multiple logical databases.

## What Remains Honest

- [[issues/archive/any-in-wire-protocol]] -- `::msg/args`, `::msg/payload`, `::msg/value` in `flow/msg.clj` use `:any` because they carry arbitrary function arguments. This is the hardest `:any` to remove -- it requires a design decision about how to type the wire protocol.
- [[issues/archive/any-in-render-html]] -- render response schemas use `:any` for the rendered value. Needs a union of known renderable types or a principled escape hatch.
- [[issues/archive/dup-db-name-schema]] -- `::db-name` registered 14 times. Single canonical registration needed.
- [[issues/archive/dup-namespace-schema]] -- `::namespace` registered 20+ times. Same problem.
- [[issues/archive/dup-connection-error]] -- `connection-error?` duplicated in db.clj and conn.clj.
- [[issues/archive/dup-get-conn-runtime]] -- `get-conn` for `:seon.runtime` in 3 places.
- [[issues/archive/coupling-render-db]] -- render.clj reaches into `db.datahike.*` connection internals directly, bypassing `seon.db`.
- [[issues/archive/map-in-map-out-compliance]] -- many public functions still use positional arguments.
- [[issues/archive/state-three-mechanisms]] -- three state registries hold partial truths.

The validation gate works. Nippy works. Generative roundtrip tests exist. The gaps are the `:any` holdouts in the wire protocol and render system, and the schema duplication that makes the "single source of truth" claim only partially true.

## How to Verify

```clojure
;; Every registered schema has a concrete type (no :any)
(let [registry (malli.core/default-schemas)
      violations (schema/find-any-violations)]
  (assert (empty? violations)
          (str "Found :any in schemas: " violations)))

;; Validation gate rejects bad data
(try
  (db/transact! :seon [{:seon.test/id 123}])  ;; id is :string, not :int
  (assert false "Should have thrown")
  (catch Exception e
    (assert (str/includes? (ex-message e) "Validation failed"))))

;; Generative roundtrip for all registered types
(user/run-tests 'seon.db.pipeline-test)
;; => {:pass-count N, :fail-count 0}

;; Nippy preserves types across the wire
(let [original {:k :keyword :d 3.14 :i (java.util.Date.) :s #{:a :b}}
      roundtrip (nippy/fast-thaw (nippy/fast-freeze original))]
  (assert (= original roundtrip)))

;; No duplicate schema registrations
;; (grep -r "schema/register! ::db-name" src/ returns exactly 1 hit)

```

**M2 is fully crossed when:** zero `:any` in the Malli registry, zero duplicate schema registrations, and `seon.db.pipeline-test` passes for every registered type.

## Dependencies

M2 depends on M1 (reliable runtime). Without stable database connections and deterministic startup, validated writes are meaningless -- the validation gate cannot protect against a database that crashes mid-write.

M3 (convention uniformity) depends on M2. The convention that every function uses map-in/map-out with namespaced keys only works if those keys have concrete schemas. M2 provides the schema infrastructure; M3 applies it universally.
