---
type: reference
status: draft
tags: [prd, reference, database]
---
# Graph Cleanup — Research Notes

---

## Phase 0: Research Findings

### R1: Ref Join Through to Cardinality-Many Keywords

**Status:** PASS
**Question:** Does `[?e :seon.fn/output-spec ?out] [?out :seon.spec/contains-keys :seon.render/html]` work?

**Setup:** Created isolated Datalevin DB with `ingest/datalevin-schema`, transacted spec + fn entities with lookup refs.

```clojure
;; Transact
(d/transact! test-conn [{:seon.spec/key :seon.foo/bar-request
                         :seon.spec/contains-keys [:seon.foo/x :seon.foo/y :seon.foo/z]
                         :seon.spec/optional-keys [:seon.foo/z]}
                        {:seon.spec/key :seon.foo/bar-response
                         :seon.spec/contains-keys [:seon.render/html]}
                        {:seon.fn/qualified-name "seon.foo/bar"
                         :seon.fn/namespace "seon.foo"
                         :seon.fn/name "bar"
                         :seon.fn/input-spec [:seon.spec/key :seon.foo/bar-request]
                         :seon.fn/output-spec [:seon.spec/key :seon.foo/bar-response]}])

;; Query
(d/q '[:find ?qname
       :where
       [?e :seon.fn/output-spec ?out]
       [?out :seon.spec/contains-keys :seon.render/html]
       [?e :seon.fn/qualified-name ?qname]]
     @test-conn)
;; => #{["seon.foo/bar"]}
```

**Multiple candidates also work:**

```clojure
;; Added second fn with :seon.render/html in output
(d/q '[:find ?qname
       :where
       [?e :seon.fn/output-spec ?out]
       [?out :seon.spec/contains-keys :seon.render/html]
       [?e :seon.fn/qualified-name ?qname]]
     @test-conn)
;; => #{["seon.foo/bar"] ["seon.baz/qux"]}
```

**Recommendation:** Use this exact query pattern in `functions-with-output-key`. It works cleanly with cardinality-many keyword attrs via ref joins.

---

### R2: Lookup Ref Storage in link-fns-to-specs

**Status:** PASS
**Question:** Does `(assoc fn-entity :seon.fn/input-spec [:seon.spec/key input-key])` get stored as a proper ref?

```clojure
(let [eid (ffirst (d/q '[:find ?e :where [?e :seon.fn/qualified-name "seon.foo/bar"]] @test-conn))]
  (d/pull @test-conn '[*] eid))
;; => {:db/id 3,
;;     :seon.fn/name "bar",
;;     :seon.fn/output-spec #:db{:id 2},
;;     :seon.fn/qualified-name "seon.foo/bar",
;;     :seon.fn/namespace "seon.foo",
;;     :seon.fn/input-spec #:db{:id 1}}
```

**Key finding:** Lookup refs `[:seon.spec/key :seon.foo/bar-request]` are resolved at transact time and stored as entity IDs (1, 2). They are NOT stored as raw vectors. This means:

- Ref joins work because `:seon.fn/output-spec` points to a real entity ID
- The spec entity must exist BEFORE the fn entity is transacted (which `ingest-namespace!` already does -- specs transacted in step 2, fns in step 3)

---

### R3: All References to Derived Attrs

**Status:** COMPLETE

**Source code references (must rewrite):**

| File | Lines | Attr | Usage |
|------|-------|------|-------|
| `src/seon/graph/ingest.clj` | 59-63 | All 5 | Schema definition |
| `src/seon/graph/extract.clj` | 233-245 | All 5 | Computed in `link-fns-to-specs` |
| `src/seon/render.clj` | 140,160,165,174,180 | `render-input-keys` | `find-renderer` |
| `src/seon/render.clj` | 238,243,250,256 | `render-input-keys` | `resolve-renderer` |
| `src/seon/render.clj` | 718,732,737,744,754 | `render-input-keys` | `find-page-renderer` |
| `src/seon/ns/lifecycle.clj` | 221,237-238 | `page-renderer?`, `render-input-keys` | `find-page-render-fn` |
| `src/seon/ns/routes.clj` | 297 | `page-renderer?` | Comment only |

**Test references (must update):**

| File | Lines | What |
|------|-------|------|
| `test/seon/render_test.clj` | 59,92,107,131,248,263,360,375,407 | Test data uses `render-input-keys` |
| `test/seon/graph/extract_test.clj` | 125-128,189 | Asserts on `page-renderer?`, `needs-ctx?`, `render-input-keys` |
| `test/seon/health/workout_test.clj` | 140-147,163-164 | Asserts on all derived attrs |

**Doc references (update text, not code):**

- `docs/prds/refinement/renderer-resolution.md` — "What the Graph Stores" section uses old attrs
- `docs/prds/refinement/notes.md` — Multiple references in implementation notes
- `docs/prds/refinement/graph-scanner-redesign.md` — Schema listing
- `docs/prds/refinement/render-pipeline.md` — Detection descriptions
- `docs/prds/refinement/ctx-lifecycle.md` — References in lifecycle docs
- `docs/prds/spec-driven-rendering/prd.md` — Original design, heavily references old attrs
- `docs/prds/namespace-ui/prd.md` — References render-input-keys
- `docs/prds/render-pipeline/prd.md` — Verification step mentions old attr

**Summary:** 4 source files + 3 test files need code changes. ~8 doc files need text updates. No surprises — all references are in the expected places (graph extraction, render resolution, lifecycle).

---

### R4: Pull Through Refs

**Status:** PASS
**Question:** Can we `d/pull` through a ref to get spec entity data in one call?

```clojure
(let [eid (ffirst (d/q '[:find ?e :where [?e :seon.fn/qualified-name "seon.foo/bar"]] @test-conn))]
  (d/pull @test-conn [:seon.fn/qualified-name
                      {:seon.fn/input-spec [:seon.spec/key :seon.spec/contains-keys :seon.spec/optional-keys]}
                      {:seon.fn/output-spec [:seon.spec/key :seon.spec/contains-keys]}]
          eid))
;; => #:seon.fn{:qualified-name "seon.foo/bar",
;;              :input-spec #:seon.spec{:key :seon.foo/bar-request,
;;                                      :contains-keys [:seon.foo/x :seon.foo/y :seon.foo/z],
;;                                      :optional-keys [:seon.foo/z]},
;;              :output-spec #:seon.spec{:key :seon.foo/bar-response,
;;                                       :contains-keys [:seon.render/html]}}
```

**Key finding:** One `d/pull` gets the full fn+spec data. This means `functions-with-output-key` can:

1. Datalog query to find candidate entity IDs (ref join on output key)
2. Single `d/pull` per candidate to get qualified-name + input spec contains/optional keys
3. Compute `required-keys = contains-keys - optional-keys` in Clojure

No need for separate queries to fetch spec data.

---

### R5: Performance Comparison

**Status:** UNTESTED (nice-to-have per PRD)

---

## Gotchas Discovered

1. **Spec entities must be transacted before fn entities** for lookup refs to resolve. `ingest-namespace!` already does this correctly (step 2 before step 3).
2. **Cardinality-many values come back as vectors from `d/pull`**, not sets. Convert with `(set ...)` if needed for set operations like `subset?`.

---

## Design Decisions Made

1. **Use the ref join query pattern exactly as proposed in the PRD.** All three tests (R1, R2, R4) confirm it works. No alternative approach needed.
2. **`d/pull` is the right tool for step 2** of `functions-with-output-key` -- one call gets fn name + full input/output spec data including contains-keys and optional-keys.
