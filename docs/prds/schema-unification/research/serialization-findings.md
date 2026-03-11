# Serialization Research Findings

**Date:** 2026-03-05
**Researcher:** Claude Opus 4.6 (research agent)
**Status:** Complete

## Summary

Seon's data pipeline has three serialization boundaries. Datalevin uses Nippy for both LMDB value storage and client-server wire protocol (defaulting to Nippy, with Transit+JSON as an alternative). Seon's own inter-JVM channel uses length-prefixed EDN. Fressian is not used anywhere in the stack.

---

## 1. What Datalevin Uses for Serialization

### LMDB Value Storage (bits.clj)

Datalevin encodes each value type into LMDB ByteBuffers with its own binary format. For known Datalog types (long, double, float, instant, uuid, keyword, symbol, string, boolean, bigint, bigdec, bytes, tuple), it uses **type-specific encoders** defined in `datalevin.bits` (lines 140-157 of `constants.clj` define the type headers; the put/get functions are in `bits.clj`).

For the **fallback `:data` type** (used by KV store and for any value that doesn't match a known Datalog type), Datalevin serializes via Nippy:

```clojure
;; bits.clj:99-108
(defn serialize ^bytes [x]
  (binding [nippy/*freeze-serializable-allowlist* ...]
    (nippy/fast-freeze x)))

;; bits.clj:110-118
(defn deserialize [^bytes bs]
  (binding [nippy/*thaw-serializable-allowlist* ...]
    (nippy/fast-thaw bs)))

;; bits.clj:373
(defn- put-data [^ByteBuffer bb x] (put-bytes bb (serialize x)))

;; bits.clj:231-237
(defn- get-data [^ByteBuffer bb] (when-let [bs (get-bytes bb)] (deserialize bs)))
```

**Key finding:** For Datalog (our use case), the individual value types (string, long, keyword, etc.) are NOT nippy-serialized. They have custom binary encoders optimized for LMDB's sorted key comparisons. Nippy is only the fallback for the `:data` KV type and for dump/restore operations.

**File:** `reference-code/datalevin/src/datalevin/bits.clj` lines 97-118, 231-237, 373, 481-486

### Client-Server Wire Protocol (protocol.clj)

The client-server protocol on port 8898 supports two message formats, identified by the first byte of each message:

| Format byte | Constant | Format |
|---|---|---|
| `0x01` | `message-format-transit` | Transit+JSON |
| `0x02` | `message-format-nippy` | Nippy (DEFAULT) |

**File:** `reference-code/datalevin/src/datalevin/constants.clj` lines 348-349

The default is **Nippy**. The 2-arg `write-message-bf` always uses Nippy:

```clojure
;; protocol.clj:104-108
(defn write-message-bf
  ([bf msg]
   (write-message-bf bf msg c/message-format-nippy))  ;; <-- default
  ([^ByteBuffer bf msg fmt] ...))
```

**File:** `reference-code/datalevin/src/datalevin/protocol.clj` lines 92-117

Neither `server.clj` nor `client.clj` ever reference Transit directly -- they call `p/write-message-blocking` which uses the default Nippy format. Transit support exists in protocol.clj but appears to be for backward compatibility or external tools, not active use.

The reader dispatches on format byte:

```clojure
;; protocol.clj:135-139
(defn read-value [fmt bs]
  (case (short fmt)
    1 (read-transit-bytes bs)
    2 (nippy/fast-thaw bs)))
```

### Datom Serialization (datom.clj)

Datalevin extends Nippy to handle its own types:

```clojure
;; datom.clj:207-222
(nippy/extend-freeze Datom :datalevin/datom ...)
(nippy/extend-thaw :datalevin/datom ...)
```

Similarly for `Entity` (entity.clj:249-264) and `SpillableVector` (spill.clj:326-334).

For Transit, custom handlers exist for `Datom` and `SpillableVector` in `protocol.clj:31-41`.

### Implications

- When Seon calls `d/transact!` or `d/q` over TCP (client mode), the entire request and response travel as **Nippy-encoded bytes** over the wire.
- The individual datom values inside LMDB are NOT Nippy -- they use Datalevin's custom binary format with type-specific encoders.
- Fressian is **not used anywhere** in Datalevin's source.

---

## 2. Nippy's Type System

**Version on classpath:** 3.6.0 (confirmed via REPL)

### Native Types (from types-spec, nippy.clj:94-267)

| Category | Types | Type IDs |
|---|---|---|
| **Primitives** | nil, true, false, char, byte, short, integer | 3, 8, 9, 10, 40, 41, 42 |
| **Longs** | long-0, long-pos-sm/md/lg, long-neg-sm/md/lg, long-xl | 0, 87-89, 93-95, 43 |
| **Floats** | float, double, double-0 | 60, 61, 55 |
| **Strings** | str-0, str-sm, str-md, str-lg | 34, 96, 16, 13 |
| **Keywords** | kw-sm, kw-md | 106, 85 |
| **Symbols** | sym-sm, sym-md | 56, 86 |
| **Temporal** | uuid, util-date, sql-date, time-instant, time-duration, time-period | 91, 90, 92, 79, 83, 84 |
| **Collections** | vec (0/2/3/sm/md/lg), set (0/sm/md/lg), map (0/sm/md/lg), map-entry, list (0/sm/md/lg), seq (0/sm/md/lg), sorted-set, sorted-map, queue | multiple |
| **Big numbers** | bigint, biginteger, bigdec, ratio | 44, 45, 62, 70 |
| **Arrays** | byte-array (0/sm/md/lg), int-array, long-array, float-array, double-array, string-array, object-array | multiple |
| **Other** | regex, uri, meta, reader-tagged, type, records, serializable | multiple |

**File:** `reference-code/nippy/src/taoensso/nippy.clj` lines 94-267

### Nil Handling

Nippy has **first-class nil support**. Type ID 3 is `:nil` with zero-byte payload:

```clojure
;; nippy.clj:100
{3 [:nil []]}

;; nippy.clj:1060
(freezer nil id-nil true nil)

;; nippy.clj:1570 (thaw)
id-nil nil
```

**REPL verification:**

```clojure
(nippy/thaw (nippy/freeze {:foo "bar" :baz nil}))
;; => {:foo "bar", :baz nil}
;; nil values in maps roundtrip perfectly
```

### Thaw Transducer (*thaw-xform*)

Nippy provides `*thaw-xform*`, a dynamic var that accepts a transducer applied during thawing of collection types. Defined at nippy.clj:400-430:

```clojure
(enc/defonce ^:dynamic *thaw-xform*
  "Experimental, subject to change. Feedback welcome!
   Transducer to use when thawing standard Clojure collection types..."
  nil)
```

**REPL verification** that the transducer works for data inspection/transformation:

```clojure
(binding [nippy/*thaw-xform*
          (map (fn [entry]
                 (if (and (map-entry? entry) (= (key entry) :secret))
                   (clojure.lang.MapEntry/create :secret "***REDACTED***")
                   entry)))]
  (nippy/thaw (nippy/freeze {:secret "password123" :name "Alice"})))
;; => {:secret "***REDACTED***", :name "Alice"}
```

The thaw transducer is applied via `read-into` (nippy.clj:1381-1391) and `read-kvs-into` (nippy.clj:1393-1406). It wraps the reducing function used to build collections during thaw.

**Note:** The var is marked "Experimental, subject to change" as of v3.6.0. However, it has existed since v3.3.0 (2023-08-02) and the API has been stable.

### Custom Type Extension (extend-freeze/extend-thaw)

```clojure
;; nippy.clj:1937-1967
(defmacro extend-freeze [type id [x out] & body] ...)

;; nippy.clj:1969+
(defmacro extend-thaw [id [in] & body] ...)
```

Two ID modes:

- **Byte ID** (1-128): Zero overhead, but caller manages uniqueness
- **Keyword ID** (namespaced): 2-byte overhead, hashed to 16-bit int automatically

Datalevin uses keyword IDs: `:datalevin/datom`, `:datalevin/entity`, `:spillable-vec`.

### Implications

- Nippy natively handles EVERY type that Datalevin supports, plus many more (Instant, Duration, Period, Ratio, arrays, metadata, records).
- Nil is a first-class type -- no special handling needed.
- The thaw transducer maps naturally to a flow step-fn concept: inspect/transform data as it's deserialized.
- `fast-freeze`/`fast-thaw` (used by Datalevin) skip the 4-byte header, compression, and encryption for maximum speed.
- Metadata preservation is on by default (`*incl-metadata?*`).

---

## 3. Fressian

### What It Is

Fressian is a binary serialization format created by Rich Hickey for Datomic. It is Java-only (no Clojure wrapper in the repo). The type system (from `Codes.java`) includes:

- Primitives: NULL (0xF7), TRUE (0xF5), FALSE (0xF6), INT (0xF8), FLOAT (0xF9), DOUBLE (0xFA)
- Collections: MAP (0xC0), SET (0xC1), LIST, arrays (LONG_ARRAY, DOUBLE_ARRAY, etc.)
- Rich types: UUID (0xC3), REGEX (0xC4), URI (0xC5), BIGINT (0xC6), BIGDEC (0xC7), INST (0xC8), SYM (0xC9), KEY (0xCA)
- Extension: STRUCTTYPE (0xEF), STRUCT (0xF0), META (0xF1)

**File:** `reference-code/fressian/src/org/fressian/impl/Codes.java`

### Relevance to Seon

**Datalevin does not use Fressian.** Zero references to fressian in the Datalevin source. The grep for "fressian" across all Datalevin source files returned no matches.

Fressian is comparable to Nippy in type coverage but:

- Pure Java (no Clojure-native experience)
- No thaw transducer
- No metadata preservation
- Not a dependency of anything in the Seon stack
- Designed for Datomic's internal use; Datalevin chose Nippy instead

**No further investigation warranted.**

---

## 4. malli-datomic's Approach

### What It Does

`malli-datomic` (in `datomic_schema_gen.cljc`) walks a Malli `:map` schema and derives Datomic schema attributes. Key function: `derive-value-type` (lines 41-85).

### Serialization Concerns

**malli-datomic has NO opinions about serialization.** It is purely a schema derivation library. It:

1. Maps Malli types to `:db/valueType` keywords (e.g., `int?` -> `:db.type/long`)
2. Copies `:db/*` properties from Malli entry options (`:db/doc`, `:db/unique`, `:db/isComponent`, etc.) -- see `datomic-copied-props` at line 88
3. Handles enums by creating `{:db/ident kw}` entities for keyword enums (Datomic pattern)
4. Warns (doesn't error) on non-keyword enums (line 137-138)
5. Maps composite types (`:set`, `:map`, `:vector`, `:sequential`) to `:db.type/ref` (line 76-80)
6. Supports Datomic tuples (`:db/tupleType`, `:db/tupleTypes`, `:db/tupleAttrs`)
7. Throws on unsupported types via `derive-value-type` (line 83-84)

**It does NOT handle:**

- Value coercion before transact
- Nil stripping
- Serialization format selection
- Roundtrip validation

**File:** `reference-code/malli-datomic/src/blasterai/malli_datomic/datomic_schema_gen.cljc`

### Notable Design Choice

The `:db/valueType` property in Malli options takes precedence over derivation (line 177):

```clojure
value-type (or (:db/valueType spec-item-options) ...)
```

This is the same pattern Seon's bridge already uses.

### Implications

- malli-datomic validates the schema derivation is possible but does not validate that actual data will survive serialization.
- The "copy `:db/*` properties" approach is identical to what Seon already does.
- malli-datomic's tuple support (`:db/tupleType`, `:db/tupleAttrs`) may be relevant if Seon wants Datalevin tuples, but note: Datalevin's tuple support may differ from Datomic's.

---

## 5. spectomic's Approach

### What It Does

spectomic (in `core.clj`) generates Datomic schema from `clojure.spec` by **generating samples and inferring types**:

1. Generate 100 samples from a spec (line 75-77)
2. Map each sample's Java class to a Datomic type via `class->datomic-type` (lines 9-20)
3. Filter out nils (for `s/nilable` specs) (line 49)
4. If all samples map to one type, use it. If multiple types, throw. (lines 91-109)

### Serialization Concerns

**spectomic has NO opinions about serialization** either. It is purely about type inference.

### Notable Design Choices

1. **`nil` handling**: Filters nil samples when determining type. This means `s/nilable` specs produce the same Datomic type as the non-nilable version. (line 48-49)
2. **`class->datomic-type` map**: Simple Java class dispatch -- `String -> :db.type/string`, `Long -> :db.type/long`, etc. (lines 9-20)
3. **Maps are always `:db.type/ref`**: `(map? obj) :db.type/ref` (line 34)
4. **Custom type resolver**: Extension point for types not in the default map (line 37)
5. **Collection detection**: If all samples are sequential/set, it's `:db.cardinality/many` (line 45-46)

### Implications

- The "generate samples and check consistency" approach is powerful for validation. It could be adapted to Malli: generate N samples from a schema, verify all samples produce the same Datalevin type.
- spectomic's nil-filtering approach aligns with the "absence = no value" model for Datalevin.

---

## 6. Seon's Current Inter-JVM Channel

### Location and Implementation

**File:** `src/seon/flow/harness/channel.clj`

The channel uses **length-prefixed EDN over TCP**:

```clojure
;; channel.clj:27-34 (read)
(defn- read-message! [^DataInputStream dis]
  (let [len (.readInt dis)
        buf (byte-array len)]
    (.readFully dis buf)
    (msg/read-edn (String. buf "UTF-8"))))

;; channel.clj:36-42 (write)
(defn- write-message! [^DataOutputStream dos msg]
  (let [^bytes bs (.getBytes (pr-str msg) "UTF-8")]
    (.writeInt dos (alength bs))
    (.write dos bs)
    (.flush dos)))
```

EDN reader with tagged literal support is in `src/seon/flow/msg.clj`:

```clojure
;; msg.clj:20-28
(def edn-readers
  {'time/instant #(Instant/parse %)
   'inst         #(java.util.Date/from (Instant/parse %))})

(defn read-edn [s]
  (edn/read-string {:readers edn-readers} s))
```

A custom `print-method` for `Instant` is also defined (msg.clj:14-18) to emit `#time/instant "..."` tagged literals.

### What Data Flows Through

The channel carries **flow message envelopes** (defined in `msg.clj`):

- `:request` -- function call requests from orchestrator to agent
- `:reply` -- execution results from agent back to orchestrator
- `:event` -- observability events

The `::msg/args` field is `[:vector :any]` and `::msg/value` is `:any` (msg.clj:40, 79). These carry arbitrary Clojure data as function arguments and return values.

### EDN Serialization Verification in Bridge

The bridge (bridge.clj:153-168) explicitly validates EDN roundtrip:

```clojure
;; After executing a function, verify result is serializable
(try
  (msg/read-edn (pr-str result))  ;; <-- EDN roundtrip check
  ...
(catch Exception e
  ;; Returns :serialization error if result can't roundtrip
  ...))
```

### Known Issues with Current EDN Approach

1. **byte[] not supported**: `pr-str` of byte arrays produces `#object["[B" 0x... "[B@..."]` which is not valid EDN. Confirmed in REPL.

2. **Float -> Double coercion**: EDN reader produces `Double` for all floating-point literals. `(float 3.14)` prints as `3.14` and reads back as `Double`. Confirmed in REPL.

3. **No metadata preservation**: EDN does not preserve Clojure metadata.

4. **Performance**: EDN roundtrip is approximately 3.7x slower than Nippy for a typical flow message payload. REPL measurement: EDN ~57.5 us/op vs Nippy ~15.7 us/op (10,000 iterations, warmed up).

5. **`::msg/args` and `::msg/value` are `:any`**: The schemas use `:any` for function arguments and return values, which means any Clojure data could flow through. If a function returns a byte array or a Float, the channel silently corrupts the data.

6. **Tagged literal maintenance burden**: Each non-EDN-native type requires a custom `print-method` and a reader entry. Currently only `Instant` is handled. Any future type additions require updating `msg.clj`.

---

## 7. REPL Verification Results

### nil on core.async channels

```clojure
(let [ch (async/chan 1)]
  (async/put! ch {:foo nil :bar/baz nil})
  (async/poll! ch))
;; => {:foo nil, :bar/baz nil}
```

Maps containing nil values flow through channels without issue.

### Nippy nil roundtrip

```clojure
(nippy/thaw (nippy/freeze {:foo "bar" :baz nil :nested {:a 1 :b nil}}))
;; => {:foo "bar", :baz nil, :nested {:a 1, :b nil}}
```

Nippy preserves nil values in all positions.

### Nippy fast-freeze/fast-thaw (Datalevin's functions)

```clojure
(let [data {:foo "bar" :baz nil :float-val (float 3.14) :bytes (byte-array [1 2 3])}]
  (nippy/fast-thaw (nippy/fast-freeze data)))
```

- nil preserved
- Float type preserved (not coerced to Double)
- byte[] preserved with exact content
- frozen size: 53 bytes for this payload

### Nippy metadata preservation

```clojure
(let [data (with-meta {:foo "bar"} {:my-meta true})]
  (meta (nippy/thaw (nippy/freeze data))))
;; => {:my-meta true}
```

### Nippy thaw transducer

```clojure
(binding [nippy/*thaw-xform*
          (map (fn [entry]
                 (if (and (map-entry? entry) (= (key entry) :secret))
                   (clojure.lang.MapEntry/create :secret "***REDACTED***")
                   entry)))]
  (nippy/thaw (nippy/freeze {:secret "password123" :name "Alice"})))
;; => {:secret "***REDACTED***", :name "Alice"}
```

### EDN failure modes confirmed

- `(pr-str (byte-array [1 2 3]))` -> `#object[...]` (not valid EDN)
- `(edn/read-string (pr-str (float 3.14)))` -> `java.lang.Double` (type lost)
- `(edn/read-string (pr-str (with-meta {:a 1} {:m true})))` -> no metadata

### Performance comparison (10,000 iterations, warmed)

| Operation | EDN (pr-str + read-string) | Nippy (fast-freeze + fast-thaw) |
|---|---|---|
| Per-op latency | ~57.5 us | ~15.7 us |
| Ratio | 1.0x (baseline) | 3.7x faster |

Payload: typical flow message envelope (~274 bytes EDN, ~300 bytes Nippy). Nippy is slightly larger for small text-heavy payloads (keyword-heavy maps), but 3.7x faster to roundtrip.

---

## Trade-off Summary

| Criterion | EDN (current) | Nippy | Transit+JSON |
|---|---|---|---|
| **Type fidelity** | Partial (no byte[], Float->Double, no metadata) | Complete (all JVM types, metadata) | Partial (extensible, but needs custom handlers per type) |
| **nil handling** | Supported (reads as nil) | First-class (type-id 3) | Supported |
| **Human readable** | Yes | No (binary) | Semi (JSON with tags) |
| **Performance** | ~57 us/msg | ~16 us/msg | Not measured (between the two) |
| **Already a dependency** | Yes (clojure.edn) | Yes (via Datalevin) | Yes (Datalevin has it, but doesn't use it by default) |
| **Extension mechanism** | Tagged literals (manual) | extend-freeze/extend-thaw | Write/read handlers |
| **Thaw transducer** | N/A | Yes (*thaw-xform*) | N/A |
| **Wire compatibility with Datalevin** | No (Datalevin uses Nippy on wire) | Yes (same format Datalevin uses) | Technically yes (format byte 0x01) but not default |
| **Debugging** | Easy (text) | Harder (binary, need tooling) | Moderate (JSON with tags) |

---

## Open Questions for Orchestrator

1. **Is human readability of wire messages important?** EDN's only real advantage is being human-readable. If log inspection of wire traffic is needed, EDN or Transit might be preferred. If not, Nippy is strictly superior.

2. **Should Seon adopt Datalevin's wire format directly?** Datalevin already has `write-message-bf`/`read-value` with format negotiation. Seon could use the same length-prefixed-with-format-byte protocol instead of reinventing it.

3. **What about the `::msg/args` and `::msg/value` being `:any`?** This is a schema problem independent of serialization format. Even with Nippy, allowing `:any` means no validation. The design doc bans `:any` -- should these schemas be tightened?

4. **Thaw transducer for validation?** Nippy's `*thaw-xform*` could be used as a validation/coercion step in the flow pipeline. Is this desirable, or should validation remain a separate step?

5. **Performance vs debuggability trade-off for inter-JVM?** The 3.7x speedup matters less for low-frequency flow messages (agent function calls) than it would for high-frequency data streaming. The type fidelity argument is stronger than the performance argument for this use case.
