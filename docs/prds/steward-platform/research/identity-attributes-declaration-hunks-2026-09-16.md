---
type: research
status: blocked at concurrently held owner; unapplied review material
created: 2026-09-16
tags: [program-graph, schema, wave/program-graph-indexing]
---

# S6: declaration-derived program identities, protected-owner boundary

The no-default-cluster assignment requires stopping this item if
`src/seon/program.cljc` is held. `git status --short` reported it modified at
entry and at the boundary recheck, along with
`resources/seon/schemas/seon.test.edn`. No changes to either were made.
The existing issue remains **open**:
[live resources outrun the loaded program identity list](../../../seon/issues/live-resources-outrun-the-loaded-program-identity-list.md).
Read the program-facts PRD end to end; its §4 S6 owns acceptance.

## Exact root hunk, not applied or tested

The following replaces the loaded identity vector with a derivation over the
same forms supplied to `shapes-in`. The rest of the consumers must change in
the same publication; applying only this excerpt would break vector callers.

```diff
--- src/seon/program.cljc
+++ src/seon/program.cljc
@@
-(def identity-attributes
-  "Program-row identity attributes in deterministic admission order."
-  [:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym
-   :seon.fn.file/relative-path :seon.lint/id])
+(defn identity-attributes
+  "Program identities in the admission order declared by the supplied forms."
+  {:malli/schema [:=> [:cat :map] [:vector :qualified-keyword]]}
+  [forms]
+  (let [entries
+        (into []
+              (keep (fn [[attribute definition]]
+                      (let [properties (schema.form/attr-form-properties definition)]
+                        (when (:seon.program/row-schema properties)
+                          [attribute (:seon.program/admission-order properties)]))))
+              forms)
+        orders (mapv second entries)]
+    (when-not (and (seq entries)
+                   (every? #(and (integer? %) (not (neg? %))) orders)
+                   (= (count orders) (count (distinct orders))))
+      (throw (ex-info "Program identity declarations require distinct admission orders."
+                      {:seon.error/kind :seon.program/declaration-refused
+                       :seon.program/declaration-refused true
+                       :seon.program/missing-attributes
+                       [:seon.program/admission-order]})))
+    (mapv first (sort-by second entries))))
@@ shapes-in
-        identity-attributes))
+        (identity-attributes forms)))
--- resources/seon/schemas/seon.program.edn
+++ resources/seon/schemas/seon.program.edn
@@
- [:enum :seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym
-  :seon.fn.file/relative-path :seon.lint/id]
+ :qualified-keyword
+ :seon.program/admission-order
+ [:int {:min 0
+        :description "Program identity admission order; distinct across row-schema declarations."}]
```

The schema's literal `:seon.program/identity-attribute` enum is another
rename obstacle at the same seam: deriving the list alone would still refuse
the renamed shape's key at its output contract. Membership must be checked
against the supplied declarations, not a second fixed enum.

Declare the following properties on the existing identity-attribute forms,
beside their `:seon.program/row-schema` property (dated preservation of the
current admission order, not a new production roster):

| resource under `resources/seon/schemas/` | identity | property addition |
|---|---|---|
| `seon.ns.edn` | `:seon.ns/name` | `:seon.program/admission-order 0` |
| `seon.fn.edn` | `:seon.fn/sym` | `:seon.program/admission-order 1` |
| `seon.schema.edn` | `:seon.schema/key` | `:seon.program/admission-order 2` |
| `seon.test.edn` (held) | `:seon.test/sym` | `:seon.program/admission-order 3` |
| `seon.fn.file.edn` | `:seon.fn.file/relative-path` | `:seon.program/admission-order 4` |
| `seon.lint.edn` | `:seon.lint/id` | `:seon.program/admission-order 5` |

## Remaining root integration, stopped before editing

Consumers of the literal must receive their existing forms or derived row
shapes: `seon.program/row-identity` and `row-identities`, `seon.fn` (index
reconciliation and diff), `seon.turn`, `seon.sci.eval`, `seon.cluster.source`,
and `seon.test.runner`. The latter two are also concurrently held. Do not
replace the vector with a zero-argument function rereading resources at every
leaf: hand the operation's declaration value through.

The inventory's three functions do **not** currently enumerate the same set:

- `seon.program/identity-attributes`: program row families only.
- `seon.db/identity-attributes`: all installed domain identities, consumed by
  rendering, messaging, and issues as well as indexing.
- `seon.reconcile/identity-attributes`: all declared domain identities, with
  its existing installed-attribute filter applied separately.

Thus the db/reconcile consumers cannot simply call the row-schema-only
enumerator and discard everything else: config, agent, and message identities
would disappear. Consolidation needs a common forms-based identity derivation
with the installed-set filter supplied, and the program-family selection and
admission order derived from `row-schema`/`admission-order`. Keep alias chasing
from `seon.schema/identity-attr?`; it already owns identity-property semantics.
This integration is deliberately **not implemented** across held owners.

Required acceptance after release: rename a program identity and its entity
entry in supplied resource forms without reloading a vector; `shapes-in` and
the list follow those forms, or a typed refusal names the exact resource and
loaded disagreement. Also verify a domain identity remains visible to db and
reconcile, and run the PRD S1 parity regression. No S6 test or live claim is
made in this blocked documentation commit.
