---
type: research
status: complete
tags: [database, research, cljs]
---

# Database hop and shared-query evidence

## Conclusion

The retained Bun/ClojureScript-to-JVM database path adds about one millisecond
at the warm median while preserving exact Datahike query reuse. That cost is
small enough that the architecture should remain unchanged until a complete
render or agent drive identifies a larger consumer-side bottleneck.

Identical queries over the same immutable database value compute once even
when requested concurrently by separate Bun processes. The JVM is therefore
the shared indexed-value and query-computation owner without serializing
independent reads through one execution lane.

## Dependency and mechanism ledger

- Datahike source: `reference-code/datahike`, revision
  `4c55791be1fb8bb8d9332f21c576f5c20b85b760`.
- JVM authority owner: `src/seon/db/writer.clj` and
  `src/seon/db/query_cache.clj`.
- Typed database protocol: `src/seon/db/protocol.cljc`.
- Bun/ClojureScript client: `src/seon/db.cljs` over the native Unix-domain
  socket session in `src/seon/db/client.cljs`.
- Cross-process proof: `test/seon/authority_density_test.clj`.

## Method

All samples used the live default authority and one captured immutable database
value at basis transaction `536871500`. The query counted agents:

```clojure
[:find (count ?e) . :where [?e :seon.agent/id]]

```

The direct sample called Datahike inside the JVM authority. The complete-path
sample called `seon.db/query-with-evidence` from the running Bun/ClojureScript
pod, including Transit encoding, native Unix-domain socket framing, authority
admission, query-cache lookup, response encoding, and Promise delivery.

The cross-process fixture used a separate 400-row query so a nonempty result
could prove cache ownership and joining. It opened independent physical Bun
sessions rather than multiplexing calls through one client object.

## Results

### Direct JVM Datahike

- cold: 0.670 ms;
- 500 warm samples: 0.0188 ms p50, 0.0270 ms p95, and 0.0775 ms p99.

### Complete Bun/ClojureScript path

- cold: 3.862 ms;
- 200 sequential warm samples: 1.033 ms p50, 2.366 ms p95, and 2.936 ms p99;
- maximum: 3.862 ms; and
- the cold request owned the computation while warm requests were cache hits.

### Concurrent reuse

- 32 simultaneous identical calls through the running pod completed in
  19.637 ms total;
- evidence reported one cache owner and 31 hits; and
- eight independent Bun processes reading the same 400-row result reported one
  owner and seven joined callers, with every caller receiving all 400 rows.

The focused cross-process writer proof passes one test and 51 assertions.

## Interpretation

The approximately one-millisecond warm hop is protocol, framing, scheduling,
and Promise overhead rather than duplicate Datahike computation. It is visible
but not presently an architectural bottleneck. Removing the authority hop
would also remove the one shared computation and indexed database owner that
the multi-process design requires.

The next useful measurement is the complete Datastar path: database
invalidation, affected render-unit selection, query reuse, Hiccup construction,
serialization, optional gzip, socket pressure, and browser morph. That evidence
can determine whether expensive closed surfaces or identical output are still
being computed; micro-optimizing the already warm database hop cannot.
