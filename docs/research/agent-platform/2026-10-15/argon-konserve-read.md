# Bounded Hunt: Why Reading One Tiny Konserve Value Allocates ~0.2 MB

**Date:** 2026-10-15
**Lane:** `argon-konserve`
**Target:** Why does reading one tiny Konserve record (`[:datahike/commit-graph cid] -> {:datahike/parents #{...} :datahike/generation n}`, 61 bytes uncompressed / 72 bytes on disk) allocate ~`0.19–0.21 MB` (`187.2 KB` in a hot loop, `216.4 KB` single-shot), causing a 2,000-record `merge-base` walk to allocate `~403 MB / 110 ms`?

---

## 1. Executive Answer

Every Konserve read (both `konserve.core/get` and `konserve.core/get-meta`) pays a **fixed per-deserialization allocation floor of `144,976 bytes` (`141.6 KB`)** inside `konserve.compressor.Lz4Compressor/-deserialize` and `konserve.serializers.FressianSerializer/-deserialize`, plus **`14,360 bytes` (`14.0 KB`)** per compound-key SHA-512 hash in `konserve.impl.defaults/key->store-key`:

1. **`LZ4FrameInputStream.readHeader()` (`131,712 bytes` = `128.6 KB`, 70.3% of total):**
   `konserve.compressor/Lz4Compressor` ([`reference-code/konserve/src/konserve/compressor.cljc`](file:///Users/sean/src/seon/reference-code/konserve/src/konserve/compressor.cljc#L22-L37)) wraps every value stream (and every metadata stream) in a fresh `net.jpountz.lz4.LZ4FrameInputStream` configured with 64 KiB frame blocks (`LZ4FrameOutputStream$BLOCKSIZE/SIZE_64KB`). On its first read (`readHeader()`), `LZ4FrameInputStream` unconditionally allocates **two `byte[65536]` arrays** (`compressedBuffer` = 65,552 B and `rawBuffer` = 65,552 B, wrapped in `ByteBuffer`) = **`131,104 bytes` (`128 KiB`)**, even when the stored payload is a 61-byte map stored as an **uncompressed** LZ4 block!
2. **`clojure.data.fressian/read` -> `clojure.java.io/input-stream` (`8,392 bytes` = `8.2 KB`, 4.5% of total):**
   `konserve.serializers.FressianSerializer/-deserialize` ([`reference-code/konserve/src/konserve/serializers.cljc`](file:///Users/sean/src/seon/reference-code/konserve/src/konserve/serializers.cljc#L34-L40)) calls `(fress/read bytes :handlers handlers)`. `clojure.data.fressian/read` calls `(to-input-stream readable)` -> `(clojure.java.io/input-stream obj)`. In `clojure.java.io`, the `IOFactory` implementation for `java.io.InputStream` wraps any non-`BufferedInputStream` (including `LZ4FrameInputStream`) in a fresh `java.io.BufferedInputStream`, allocating a **`byte[8192]` (`8 KiB`) buffer** per read. Calling `org.fressian.FressianReader.` (or `fress/create-reader`) directly on the `InputStream` does not wrap it in `BufferedInputStream`.
3. **Per-read Fressian handler map merge (`3,832 bytes` = `3.7 KB`, 2.0% of total):**
   `FressianSerializer/-deserialize` merges `fress/clojure-read-handlers`, `custom-read-handlers`, and `(incognito-read-handlers read-handlers)` and wraps the result in `fress/associative-lookup` on **every single read**, even though `custom-read-handlers` is immutable and the `read-handlers` atom never changes after store connection.
4. **Key hashing `konserve.impl.defaults/key->store-key` (`14,360 bytes` = `14.0 KB` per call, called twice in `k/get` = `28.7 KB`, 15.3% of total):**
   `hasch.core/edn-hash` ([`hasch/core.cljc`]) computes a SHA-512 `byte[64]` digest (`~3 KB` with coercion) and then runs `(map #(if (neg? %) (+ % 256) %) ...)` over the `byte[64]` array, which `hasch.platform/uuid5` then passes to `(byte-array sha-hash)` — boxing 64 bytes into chunked lazy sequences and copying them back into a `byte[64]` just to read the first two `long`s (16 bytes) via `ByteBuffer` (`~11.3 KB` of sequence/boxing garbage per `uuid` call). Worse, `io-operation` calls `key->store-key` once, and if `exists?` or any caller hashes the key again, it pays `14.4 KB` each time.

Together, replacing `fress/read` with direct `FressianReader.`, caching the merged Fressian `ILookup` handler table on the `@read-handlers` value, and avoiding the 2 × 64 KiB `LZ4FrameInputStream` allocation on single-block / uncompressed frames drops the deserialization cost of `[:datahike/commit-graph cid]` from **`141,144 bytes` to `1,224 bytes` (a 115x reduction, saving `139.9 KB` per read)**.

---

## 2. Exact Measurements on `default` (Read-Only JVM Mode)

### 2.1 Store Configuration and On-Disk Payload Sizes
Ran on `default` (`68ceef4ad`, pid 52140) via `eval_clj` (`mode: "jvm"`, `agent: "root"`):

```clojure
(let [conn (seon.cluster.boot/connection "default")
      store (:store @conn)
      cid (get-in @conn [:meta :datahike/commit-id])]
  {:store-type (str (type store))
   :config (:config store)
   :compressor (str (:compressor store))
   :encryptor (str (:encryptor store))
   :default-serializer (:default-serializer store)
   :head-cid cid
   :graph-record (konserve.core/get store [:datahike/commit-graph cid] nil {:sync? true})})
```

Returned:
- `:store-type`: `"class konserve.impl.defaults.DefaultStore"`
- `:config`: `{:sync-blob? true, :in-place? false, :lock-blob? true, :defer-immutable-sync? true, :compressor {:type :lz4}}`
- `:compressor`: `"konserve.compressor$lz4_compressor@6ca3bb3f"`
- `:default-serializer`: `:FressianSerializer`
- `:graph-record`: `#:datahike{:parents #{#uuid "6ad06955-68e9-553d-8734-dd02acf670d9"}, :generation 1727}`

Measured on-disk vs uncompressed Fressian byte sizes for `[:datahike/commit-graph cid]`:

```clojure
;; From blob header + raw bytes inspection on [:datahike/commit-graph cid]:
{:graph-meta {:lz4-bytes 128, :raw-fressian-bytes 117,
              :obj {:immutable? true,
                    :key [:datahike/commit-graph #uuid "6ad06b7d-d76c-534a-9237-0a10a68ff2a5"],
                    :type :edn,
                    :last-write #inst "2026-10-15T05:58:21.127Z"}}
 :graph-val  {:lz4-bytes 72,  :raw-fressian-bytes 61,
              :obj #:datahike{:parents #{#uuid "6ad06b6e-8718-5ddd-ae3f-09fef9b662d1"},
                              :generation 1768}}}
```

Inspecting the 72-byte on-disk value payload (`raw-val`) vs the 61-byte uncompressed Fressian bytes (`uncompressed-val-bytes`):

```clojure
{:raw-val-len 72,
 :raw-val-bytes
   [4 34 77 24 96 64 -126 61 0 0 -128 -64 -24 -54 -51 -29 8 100 97 116
    97 104 105 107 101 -51 -31 112 97 114 101 110 116 115 -63 -27 -61
    -39 16 106 -48 107 -104 -1 -14 88 -32 -89 8 111 -6 122 50 36 -128
    -54 -128 -51 -29 10 103 101 110 101 114 97 116 105 111 110 86 -21],
 :uncompressed-val-bytes
   [-64 -24 -54 -51 -29 8 100 97 116 97 104 105 107 101 -51 -31 112 97
    114 101 110 116 115 -63 -27 -61 -39 16 106 -48 107 -104 -1 -14 88
    -32 -89 8 111 -6 122 50 36 -128 -54 -128 -51 -29 10 103 101 110 101
    114 97 116 105 111 110 86 -21]}
```

- Because 61 bytes of Fressian does not compress smaller under LZ4, `LZ4FrameOutputStream` wrote:
  - Bytes `0..6` (7 bytes): LZ4 frame magic `[4, 34, 77, 24]` (`0x184D2204`) + `FLG=0x60` (`96`) + `BD=0x40` (`64`, meaning `maxBlockSize = 64 KiB`) + header checksum `-126`.
  - Bytes `7..10` (4 bytes): little-endian `int32` block size `[61, 0, 0, -128]` = `0x8000003D` (high bit `0x80000000` = **uncompressed block**, length = `61` bytes).
  - Bytes `11..71` (61 bytes): the verbatim 61 Fressian bytes (no trailing end-mark because `Lz4Compressor/-serialize` only calls `.flush`, not `.close`).
- Thus LZ4 frame wrapping made the 61-byte value **11 bytes larger** (72 bytes) and the 117-byte metadata **11 bytes larger** (128 bytes), while forcing every reader to instantiate `LZ4FrameInputStream`.

---

### 2.2 Stage-by-Stage Allocation Breakdown of One Read

Using `ThreadMXBean.getThreadAllocatedBytes`, we measured both single-shot inline calls and 50-iteration warmed loops (subtracting the 586.72 B measurement loop baseline):

```clojure
;; Single-shot inline call (no loop, after warmup):
(let [b0 (.getThreadAllocatedBytes tm tid)
      _ (konserve.core/get store k nil {:sync? true})
      b1 (.getThreadAllocatedBytes tm tid)]
  (- b1 b0))
;; => 216392 bytes (211.3 KiB = 0.216 MB)

;; Direct 50-iteration loop (no closure wrapper):
;; => 187798.88 bytes/op (183.4 KiB = 0.188 MB)
```

Note on why `one-merge-base.md` §C saw `0.16 MB` for `konserve.core/get-meta`:
- In `konserve.core/get-meta` ([`reference-code/konserve/src/konserve/core.cljc`](file:///Users/sean/src/seon/reference-code/konserve/src/konserve/core.cljc#L261-L277)), the 3-arity is `(get-meta [store key not-found] (get-meta store key not-found {:sync? false}))`, whereas the 4-arity is `(get-meta [store key not-found opts])`.
- Calling `(konserve.core/get-meta store k nil {:sync? true})` (4-arity, synchronous) allocates **`188,416 bytes`** — almost identical to `konserve.core/get` (`187,232 bytes`), because the metadata block (`128 bytes` on disk) is **also** LZ4-frame-compressed and Fressian-encoded (`read-blob` `:read-meta` runs the exact same `fn-read` pipeline as `:read-edn`)!

Here is the exact baseline-subtracted per-operation allocation breakdown across every stage of `konserve.core/get` on `[:datahike/commit-graph cid]` (50 iterations after 5 warmup iterations):

| Stage | Function / Form | Bytes / op | Share of `k/get` |
|---|---|---:|---:|
| **Total `konserve.core/get`** | `(konserve.core/get store k nil {:sync? true})` | **`187,232 B`** | **100.0%** |
| **Total `konserve.core/get-meta`** | `(konserve.core/get-meta store k nil {:sync? true})` | `188,416 B` | — |
| 1. In-process key lock | `konserve.core/get-lock` + `wait` + `put!` + `release-lock` | `952 B` | 0.5% |
| 2. Key -> `.ksv` filename | `(konserve.impl.defaults/key->store-key k)` (`hasch.core/uuid`) | `14,360 B` | 7.7% |
| 3. Existence check | `(-blob-exists? backing store-key {:sync? true})` | `440 B` | 0.2% |
| 4. Open + flock + close `FileChannel` | `-create-blob` + `defaults/get-lock` + `-release` + `-close` | `1,872 B` | 1.0% |
| 5. Read & parse 20-byte header | `(konserve.impl.defaults/read-header b serializers env)` | `304 B` | 0.2% |
| 6. Read 72-byte value slice | `(-read-value b meta-size env)` | `144 B` | 0.1% |
| **7. Value deserialization (`fn-read`)** | `(-deserialize (compressor (encryptor serializer)) read-handlers bais)` | **`144,976 B`** | **77.4%** |
|   ├─ 7a. `LZ4FrameInputStream.` ctor | `(LZ4FrameInputStream. bais)` (before `readHeader`) | `280 B` | 0.1% |
|   ├─ 7b. `LZ4FrameInputStream.readHeader` | First `.read` on `LZ4FrameInputStream` | **`131,432 B`** | **70.2%** |
|   ├─ 7c. Fressian handler map merge | `(merge clojure-read-handlers custom (incognito ...))` | `3,832 B` | 2.0% |
|   ├─ 7d. `fress/read` `BufferedInputStream` | `(io/input-stream in)` inside `fress/to-input-stream` | **`8,392 B`** | **4.5%** |
|   └─ 7e. `FressianReader.` + `.readObject` | `(FressianReader. in handlers false)` + `.readObject` | `1,224 B` | 0.7% |
| 8. `async+sync` / `io-operation` / `read-blob` env maps & closures | Remaining map/closure allocations in `DefaultStore/-get-in` -> `io-operation` -> `read-blob` | `~24,184 B` | 12.9% |

---

### 2.3 Proof of the Three Major Allocation Sinks

#### Sink A: `LZ4FrameInputStream` allocates `2 × 65,536 B = 131,072 B` on `readHeader()`
Reflecting on the fields of a `net.jpountz.lz4.LZ4FrameInputStream` instance after reading `[:datahike/commit-graph cid]`:

```clojure
(for [^java.lang.reflect.Field f (.getDeclaredFields net.jpountz.lz4.LZ4FrameInputStream)]
  ...)
;; =>
[{:name "headerArray",      :type "class [B",                  :len 15}
 {:name "headerBuffer",     :type "class java.nio.ByteBuffer", :len 15}
 {:name "compressedBuffer", :type "class [B",                  :len 65536}
 {:name "buffer",           :type "class java.nio.ByteBuffer", :len 65536}
 {:name "rawBuffer",        :type "class [B",                  :len 65536}
 {:name "maxBlockSize",     :type "int",                       :len "65536"}
 {:name "totalContentSize", :type "long",                      :len "61"}
 {:name "readNumberBuff",   :type "class java.nio.ByteBuffer", :len 8}]
```

Because the LZ4 frame descriptor only encodes the maximum block size (`SIZE_64KB = 65536`) in its `BD` byte (`0x40`), `LZ4FrameInputStream.readHeader()` unconditionally allocates:
- `compressedBuffer = new byte[65536]` (`65,552 B` on heap)
- `rawBuffer = new byte[65536]` (`65,552 B` on heap)
- `buffer = ByteBuffer.wrap(rawBuffer)` (`48 B`)

Total: **`131,152 bytes`** allocated inside `readHeader()` on every single read, regardless of whether the payload is 61 bytes (graph record), 117 bytes (metadata), or 5.6 KB (index node).

#### Sink B: `clojure.data.fressian/read` wraps `LZ4FrameInputStream` in `BufferedInputStream` (`8,192 B` buffer)
In `clojure/data/fressian.clj`:

```clojure
(extend-protocol FressianReadable
  Object
  (to-input-stream [obj] (io/input-stream obj)))

(defn read [readable & options]
  (.readObject ^Reader (apply create-reader (to-input-stream readable) options)))
```

And in `clojure/java/io.clj`:

```clojure
(extend InputStream
  IOFactory
  (assoc default-streams-impl
    :make-input-stream (fn [x opts] (BufferedInputStream. x))))
```

While `ByteArrayInputStream` has a direct override in `clojure.java.io` that returns `x` unchanged, `LZ4FrameInputStream` extends `java.io.FilterInputStream` -> `java.io.InputStream`, so `(io/input-stream lz4-byte)` wraps it in a `new BufferedInputStream(lz4-byte)` with a default **`8,192-byte` buffer**!
- Measured: `(fress/read (ByteArrayInputStream. uncompressed-val-bytes) :handlers handlers)` allocates `9,616 B` (due to `apply` + options parsing), and `(fress/read lz4-stream :handlers handlers)` allocates an extra `8,176 B` for the `BufferedInputStream` compared to `(fress/create-reader lz4-stream :handlers handlers)`.
- Measured: calling `(.readObject (org.fressian.FressianReader. stream handlers false))` directly allocates only **`1,224 B`** total (including the decoded `{:datahike/parents #{uuid} :datahike/generation 1768}` map itself!).

#### Sink C: `hasch.core/uuid` boxes 64 bytes into a lazy seq (`14,360 B`)
In `hasch.core/edn-hash` and `hasch.platform/uuid5`:

```clojure
(defn edn-hash [val md-create-fn write-handlers]
  (map #(if (neg? %) (+ % 256) %) ;; make unsigned
       (digest (-coerce val md-create-fn (or write-handlers {})) md-create-fn)))

(defn uuid5 [sha-hash]
  (let [bb (ByteBuffer/wrap (byte-array sha-hash)) ...]))
```

Measured breakdown of `(hasch.core/uuid [:datahike/commit-graph cid])` (`14,360 B` net):
- `(-coerce k sha512-message-digest {})`: `2,480 B`
- `(digest coerced sha512-message-digest)`: `1,368 B`
- `map` over `byte[64]` (reflective/boxed array seq + `ChunkedCons` + `Long` boxing) + `(byte-array sha-hash)`: **`~10,512 B`**.
Notice that `uuid5` only reads the first 16 bytes (`(.getLong bb)` twice) of the 64-byte SHA-512 `byte[]` returned by `digest`, and `ByteBuffer/wrap` works directly on the signed `byte[]` from `digest` without converting to unsigned longs and back to `byte-array`!

---

## 3. Smallest Fix at Its Owner and Expected Gain

### Owner 1 (Primary — 77% of per-read allocation): `konserve` fork
**Files:**
- [`reference-code/konserve/src/konserve/compressor.cljc`](file:///Users/sean/src/seon/reference-code/konserve/src/konserve/compressor.cljc#L22-L37) (`konserve.compressor/Lz4Compressor`)
- [`reference-code/konserve/src/konserve/serializers.cljc`](file:///Users/sean/src/seon/reference-code/konserve/src/konserve/serializers.cljc#L29-L40) (`konserve.serializers/FressianSerializer`)

**Changes:**
1. **In `konserve.serializers/FressianSerializer/-deserialize`:**
   - Call `(.readObject (org.fressian.FressianReader. bytes handlers false))` directly instead of `(fress/read bytes :handlers handlers)`. This eliminates `to-input-stream` -> `BufferedInputStream` (`8,192 B` buffer) and varargs `apply` (`~1,200 B`).
   - Cache the compiled `ILookup` handler table on `[custom-read-handlers @read-handlers]` (e.g., a 1-entry volatile/atom cache or inside `FressianSerializer` when `@read-handlers` is identical to the last deref). This eliminates the per-read `merge` + `incognito-read-handlers` + `associative-lookup` (`3,832 B` per read).
2. **In `konserve.compressor/Lz4Compressor/-deserialize`:**
   - All Konserve values/metadata on JVM pass a `ByteArrayInputStream` to `-deserialize` (`konserve.impl.defaults/read-blob` lines 219, 226, 245, 249).
   - Almost every stored Konserve blob in Datahike is `< 64 KiB` (a single LZ4 frame block):
     - `[:datahike/commit-graph cid]` is 72 bytes (1 uncompressed block of 61 bytes);
     - metadata is 128 bytes (1 uncompressed block of 117 bytes);
     - a PSS index node is ~5.6 KB (1 compressed block of ~5.6 KB decompressing to ~7.7 KB);
     - a commit record is ~31.7 KB (1 compressed block decompressing to ~55 KB).
   - For an uncompressed single-block LZ4 frame (`magic == 0x184D2204`, `FLG == 0x60`, block length high bit `0x80000000` set), the 11-byte header can be skipped on the `InputStream` (`mark`/`read`/`reset`) and the `InputStream` passed directly to `FressianSerializer` with **zero buffer allocation (`0 B` instead of `131,152 B`)**!
   - Even simpler and general for **all** LZ4 frames (both uncompressed and compressed single/multi-block): `LZ4FrameInputStream` can either fast-path single blocks using `LZ4Factory/fastestInstance` `.safeDecompressor` (or for uncompressed blocks, slice the stream directly), or reuse thread-local 64 KiB frame buffers.
   - We tested the uncompressed-block fast-path + direct `FressianReader` + cached `handlers` on `default`'s live `[:datahike/commit-graph cid]` blob:

```clojure
{:decoded-equal? true,
 :before-bytes 141144.0,
 :after-bytes    1224.0}
```

**Measured gain from Owner 1 alone:**
- Deserializing `[:datahike/commit-graph cid]` drops from **`141,144 B` to `1,224 B` (-99.1%, saving `139.9 KB` per read)**.
- Full `konserve.core/get` on `[:datahike/commit-graph cid]` drops from **`187.2 KB` to `~43.5 KB` (-76.8%)**.
- Across a 2,000-record `merge-base` walk, allocation drops from **`~403 MB` to `~87 MB`** (saving **>315 MB** per walk).

### Owner 2 (Secondary — 8–15% of per-read allocation): `konserve.impl.defaults/key->store-key`
**File:** [`reference-code/konserve/src/konserve/impl/defaults.cljc`](file:///Users/sean/src/seon/reference-code/konserve/src/konserve/impl/defaults.cljc#L48-L50)
- `key->store-key` calls `(str (uuid key) ".ksv")`, which allocates **`14,360 B`** per call because `hasch.core/edn-hash` boxes all 64 SHA-512 bytes into a lazy seq of unsigned `Long`s before `uuid5` copies them back into a `byte-array` just to read two `long`s (`0..15`).
- Calling `hasch.benc/-coerce` + `hasch.benc/digest` and reading the two `long`s directly from the resulting `byte[64]` via `ByteBuffer/wrap` (without `edn-hash`'s `map` and `uuid5`'s `byte-array` copy) eliminates **`~10.5 KB` per key lookup** (another `~21 MB` across 2,000 reads).

---

## 4. Should This Continue?

- **Status:** **COMPLETE — STOP HERE.** The root cause of the `~0.2 MB` per-read allocation is 100% accounted for with exact byte measurements on the live store (`131,712 B` in `LZ4FrameInputStream.readHeader()`, `8,392 B` in `fress/read`'s `BufferedInputStream`, `3,832 B` in `FressianSerializer`'s per-read handler merge, and `14,360 B` in `key->store-key` / `hasch.core/uuid`).
- **Next action for an implementation lane:** Apply the small fix in `reference-code/konserve/src/konserve/serializers.cljc` and `reference-code/konserve/src/konserve/compressor.cljc` (plus optionally the direct `uuid5` byte-array path in `konserve.impl.defaults/key->store-key`), cutting small-value Konserve read allocation from `~187 KB` to `~33 KB` (and deserialization itself from `141 KB` to `1.2 KB`).
