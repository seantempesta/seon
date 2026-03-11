---
type: prd
status: draft
tags: [prd, database]
---
# Spec-to-Function Linkage Audit

## How Linkage Works Today

In `src/seon/graph/extract.clj`, lines 191-214, the function `link-fns-to-specs` does a **pure string-based suffix match**:

```clojure
(defn- link-fns-to-specs [fns specs]
  (let [spec-by-key (into {} (map (juxt :seon.spec/key identity)) specs)]
    (mapv (fn [fn-entity]
            (let [qn (:seon.fn/qualified-name fn-entity)         ;; "seon.foo/bar"
                  input-key  (keyword (str qn "-request"))        ;; :seon.foo/bar-request
                  output-key (keyword (str qn "-response"))]      ;; :seon.foo/bar-response
              (cond-> ...
                (spec-by-key input-key)  (assoc :seon.fn/input-spec ...)
                (spec-by-key output-key) (assoc :seon.fn/output-spec ...))))
          fns)))

```

It constructs `:<ns>/<fn-name>-request` and `:<ns>/<fn-name>-response` keywords, then looks them up in the set of specs found by edamame scanning of `schema/register!` calls. That is the **only** linkage mechanism. There is no runtime introspection, no `:malli/schema` metadata parsing, no `m/=>` detection.

## What It Misses

### The actual convention: `:malli/schema` metadata

Per `CONVENTIONS.md`, the canonical way to attach schemas to functions is:

```clojure
(defn analyze
  {:malli/schema [:=> [:cat ::analyze-request] ::analyze-response]}
  [{::keys [ticker]}]
  ...)

```

The schema references `::analyze-request` and `::analyze-response` as registered schema keys. The `-request`/`-response` naming convention IS widely used for these registered schemas, so the string-based approach happens to work **when the naming convention is followed**.

### Where it breaks

1. **Functions with `:malli/schema` that don't use `-request`/`-response` naming.** Example from `src/seon/db/datalevin/backup.clj`: uses `::backup-result` not `::backup-response`. Similarly `::list-backups-result`, `::prune-result`, `::restore-result`. These are **invisible** to `link-fns-to-specs`.

2. **Functions with inline schemas** (not referencing registered keys). If someone wrote `:malli/schema [:=> [:cat [:map [:x :int]]] :int]`, no registered spec exists to link.

3. **The linkage is redundant with what Malli already knows.** The `:malli/schema` metadata on the var IS the authoritative source of input/output types. The graph is reconstructing this information poorly via naming convention instead of reading it directly.

### Scale of the problem

- **152 functions** have `:malli/schema [:=> ...]` metadata across 35 files
- **319 occurrences** of `-request` across 38 files (schema registrations)
- **224 occurrences** of `-response` across 30 files

Most functions follow the naming convention, so the string-based approach works for ~80-90% of cases. But it is fundamentally fragile: it only works by coincidence of naming, not by understanding the actual schema declaration.

### Functions completely missed (examples)

- `seon.db.datalevin.backup/backup!` - schema refs `::backup-result` not `::backup-response`
- `seon.db.datalevin.backup/list-backups` - refs `::list-backups-result`
- `seon.db.datalevin.backup/prune-backups!` - refs `::prune-result`
- Any function where the response schema is named `-result` instead of `-response`

## The Right Approach

### Option 1: Parse `:malli/schema` metadata at scan time (RECOMMENDED)

Malli's own `instrument.clj` shows exactly how this works (lines 43-46):

```clojure
(defn -schema [v]
  (let [{:keys [malli/schema arglists]} (meta v)]
    (or schema (as-> (seq (keep (comp :malli/schema meta) arglists)) $
                 (when (= (count arglists) (count $)) (cond->> $ (next $) (into [:function])))))))

```

For our graph, we have two sub-options:

**A) Static: Parse `:malli/schema` from clj-kondo metadata (already available)**

clj-kondo's var-definitions include `:meta` when configured with `{:var-definitions {:meta true}}` (which we already do, line 42 of extract.clj). The metadata map should contain `:malli/schema`. We can parse the schema form to extract input/output spec references directly.

**B) Runtime: Use `(mi/collect!)` + `(m/function-schemas)` via REPL**

After loading code, call `(m/function-schemas)` which returns `{ns-symbol {fn-name {:schema ...}}}`. This is the authoritative source. The graph could query the running system for this data.

**Recommendation: Option A for the static graph, Option B as enrichment.**

The static graph should parse `:malli/schema` metadata from clj-kondo analysis. This gives us the schema form (e.g., `[:=> [:cat ::analyze-request] ::analyze-response]`) from which we can extract the input spec key (second element of `:cat`) and output spec key (third element of `:=>`). This replaces the string-based suffix match entirely.

### Option 2: Keep convention but also check metadata

Hybrid: try the `-request`/`-response` lookup first, then fall back to parsing `:malli/schema`. This is more conservative but adds complexity for no benefit -- the metadata IS the source of truth.

## Implementation Scope

### Minimal fix (small, ~1 hour agent task)

1. In `extract.clj`, modify `link-fns-to-specs` to:
   - Read `:malli/schema` from clj-kondo var-definition metadata
   - Parse `[:=> [:cat <input-spec>] <output-spec>]` to extract spec keys
   - Link using those keys instead of string suffix convention
   - Fall back to convention for functions without `:malli/schema`

2. In `extract.clj`, `kondo-var-def->fn-entity` already receives the full var-definition. Add metadata extraction there.

### What clj-kondo provides

The kondo config already requests `{:var-definitions {:meta true}}`. Each var-definition should include a `:meta` map. We need to verify that `:malli/schema` appears in this metadata (it should, since kondo reads literal metadata maps).

### Does this block Phase 2?

**No.** The current approach works for most functions because the naming convention is widely followed. But it should be fixed in parallel because:

- Functions using `-result` instead of `-response` are silently missed
- The graph claims to show function-spec relationships but lies about ~10-20% of them
- Any future schema naming that deviates from convention will silently break

## Summary

| Aspect | Current | Correct |
|--------|---------|---------|
| Mechanism | String suffix `-request`/`-response` | Parse `:malli/schema` metadata |
| Source of truth | Naming convention | Var metadata (what Malli uses) |
| Coverage | ~80-90% | ~100% of schema'd functions |
| Fragility | Breaks on any naming deviation | Robust, reads actual declarations |
| Scope to fix | 1 function in extract.clj | Small change |
