---
type: research
status: incomplete
created: 2026-09-17
tags: [contracts, error-model, class/absence-as-health]
---

# Audit three blockers — frozen-tree checkpoint

The owner requested a freeze before the reset-batch fast-forward merge and
default reset. Item 3 has an untested draft only: no fast run was launched or
passed, and no production fix is claimed. Items 1 and 2 remain unimplemented.
AGENTS.md sections 0–7, program-facts PRD §1j, the audit at commit
3d1ccde52, and the database-read-error class issue were read in full.

Captured against HEAD `bd8b12177c2b4f7f161d20ef3d0e0924ebeb39a1`.
The two-file diff below was inspected and contains only this lane's edits;
the index contains no staged changes to either file. After committing this
note, the owner-authorized checkout restores only src/seon/fn.clj and
test/seon/fn_test.clj. Other working-tree changes belong to other work and
are preserved. No tests run at this checkpoint.

Resume item 3 on the merged base before items 1 and 2. Re-evaluate the draft
against the merged symbol schemas and error propagation implementation.
The pre-merge gate-sets already checked the declared query result; the
remaining behavior requires a canonical armed regression and review of
gate-set-in and selection callers. The draft below is evidence, not an
approved patch. Its regression uses with-database, transacted!, and
program-fn-row, passing a flat refusal as the database. No schema retyping
was attempted; the pre-merge fixture helper emitted string function names
and symbol namespace names (audit F15).

Verification boundary: no fast or cold proof exists. The required Seon MCP
tools were not exposed in the initial tool listing; no live evaluation was
performed. Descriptor-only bin/seon status reported default PID 94566 alive.
Source edits triggered configured publication hooks; their convergence was
not verified. No explicit default mutation or reset was performed by this
lane. The merge freeze is the owner's explicit stop condition.

## Exact uncommitted diff

```diff
diff --git a/src/seon/fn.clj b/src/seon/fn.clj
index 3ab8e4fe2..a29fc798e 100644
--- a/src/seon/fn.clj
+++ b/src/seon/fn.clj
@@ -1365,12 +1365,17 @@
   on data rows retain conservative attribute-consumer reach because those
   rows do not identify a calling function. Keyword mentions never replace
   the known owner of a function declaration."
+  {:malli/schema
+   [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
+    [:or [:set [:tuple :int :int]] :seon.error/value]]}
   [database]
-  (db/q '[:find ?caller ?target
-          :in $ %
-          :where
-          (declared-edge ?caller ?target)]
-        database declared-reference-rules))
+  (if (error/error? database)
+    database
+    (db/q '[:find ?caller ?target
+            :in $ %
+            :where
+            (declared-edge ?caller ?target)]
+          database declared-reference-rules)))

 (defn- gate-set-in
   "Walk one identity using the declaration relation acquired for this operation.
@@ -1378,7 +1383,7 @@
   target has resolved calls. File uncertainty selects only its file's tests."
   [database incoming-declared incoming-file function-symbol]
   (let [row (db/pull database [:db/id] [:seon.fn/sym function-symbol])]
-    (if (:seon.error/kind row)
+    (if (error/error? row)
       row
       (let [target (:db/id row)
             walked
@@ -1389,7 +1394,7 @@
                   (let [calls (db/datoms database :avet :seon.fn/calls entity)
                         declared (get incoming-declared entity)
                         references (db/datoms database :avet :seon.fn/references entity)
-                        refusal (some #(when (:seon.error/kind %) %) [calls references])]
+                        refusal (some #(when (error/error? %) %) [calls references])]
                     (if refusal
                       refusal
                       (let [incoming (into (into (mapv :e (concat calls references)) declared)
@@ -1397,7 +1402,7 @@
                         (recur (into (pop pending) incoming)
                                (conj seen entity) (into callers incoming))))))
                 callers))]
-        (if (:seon.error/kind walked)
+        (if (error/error? walked)
           walked
           (let [subjects (cond-> walked target (conj target))
                 by-edge (when (seq walked)
@@ -1416,7 +1421,7 @@
                                    :where [?test :seon.test/pending-subject ?target-symbol]
                                    [?test :seon.test/sym ?symbol]]
                                  database function-symbol)]
-            (or (some #(when (:seon.error/kind %) %) [by-edge by-subject by-pending])
+            (or (some #(when (error/error? %) %) [by-edge by-subject by-pending])
                 (vec (sort (set (concat by-edge by-subject by-pending)))))))))))

 (defn gate-sets
@@ -1424,7 +1429,8 @@
   Acquire schema-declared dispatch edges once and hand them to each indexed
   walk. No relation is cached beyond this operation."
   {:malli/schema
-   [:=> [:cat :seon.db/database-value [:sequential :seon.fn/sym]]
+   [:=> [:cat [:or :seon.db/database-value :seon.error/value]
+         [:sequential :seon.fn/sym]]
     [:or [:map-of :seon.fn/sym [:vector :seon.test/sym]] :seon.error/value]]}
   [database function-symbols]
   (let [declared (declared-reference-edges database)
@@ -1435,7 +1441,7 @@
                 [?target :seon.fn/sym ?symbol]
                 [?test :seon.fn/file ?file]
                 [?test :seon.test/sym]] database)
-        refusal (some #(when (:seon.error/kind %) %) [declared file-references])]
+        refusal (some #(when (error/error? %) %) [declared file-references])]
     (if refusal
       refusal
       (let [incoming (fn [edges]
@@ -1445,7 +1451,7 @@
             file-incoming (incoming file-references)]
         (reduce (fn [results function-symbol]
                   (let [selected (gate-set-in database declared-incoming file-incoming function-symbol)]
-                    (if (:seon.error/kind selected)
+                    (if (error/error? selected)
                       (reduced selected)
                       (assoc results function-symbol selected))))
                 {} (distinct function-symbols))))))
@@ -1454,15 +1460,17 @@
   "Tests gating one function identity. Unknown identities match only an
   explicitly pending subject; unresolved file references select that file's
   tests. Use gate-sets when one operation asks about several identities."
-  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
+  {:malli/schema [:=> [:cat [:or :seon.db/database-value :seon.error/value]
+                       :seon.fn/sym]
                   [:or [:vector :seon.test/sym] :seon.error/value]]}
   [database function-symbol]
   (let [result (gate-sets database [function-symbol])]
-    (if (:seon.error/kind result) result (get result function-symbol))))
+    (if (error/error? result) result (get result function-symbol))))

 (defn tests-reaching
   "Compatibility spelling for the shared gate-set derivation."
-  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
+  {:malli/schema [:=> [:cat [:or :seon.db/database-value :seon.error/value]
+                       :seon.fn/sym]
                   [:or [:vector :seon.test/sym] :seon.error/value]]}
   [database function-symbol]
   (gate-set database function-symbol))
diff --git a/test/seon/fn_test.clj b/test/seon/fn_test.clj
index 8cdeac72f..4b85e95c5 100644
--- a/test/seon/fn_test.clj
+++ b/test/seon/fn_test.clj
@@ -1501,6 +1501,27 @@
         (is (= (first results) (seon.fn/gate-set database "sample.gates/a"))
             "the old immutable value keeps its own reach")))))

+(deftest a-refused-reference-read-refuses-gate-set-derivation
+  (test-support/with-database
+    (fn [connection]
+      (test-support/transacted!
+       connection [(test-support/program-fn-row 'seon.fn/gate-refusal-fixture)])
+      (let [refusal (error/diagnostic
+                     {:seon.error/kind :seon.db/invalid-read
+                      :seon.error/message "Datahike refused the reference-edge read."
+                      :seon.error/diagnostic-layer :database-read
+                      :seon.error/diagnostic-operation 'seon.db/q
+                      :seon.error/diagnostic-member :seon.fn/references
+                      :seon.error/diagnostic-expected :seon.db/database-value
+                      :seon.error/diagnostic-offending :refused-database
+                      :seon.error/diagnostic-cause :seon.db/invalid-read
+                      :seon.error/diagnostic-evidence
+                      {:seon.fn/sym "seon.fn/gate-refusal-fixture"}})]
+        (is (error/error? refusal))
+        (is (= refusal
+               (seon.fn/gate-set refusal "seon.fn/gate-refusal-fixture"))
+            "the read refusal reaches selection whole instead of shrinking the gate set")))))
+
 (deftest gate-set-returns-the-shape-its-contract-declares
   (test-support/with-database
     (fn [connection]
@@ -1553,42 +1574,7 @@
                    (pr-str (vec (remove test-sym? gate)))))
           (is (= gate (vec (sort gate)))
               (str function-symbol " gates a sorted vector")))
-        ;; The class: a flat database refusal concatenated into the selection
-        ;; splices its map entries, and each entry reads as a two-element
-        ;; vector the declared element schema refuses.
-        (let [query db/q
-              thread (Thread/currentThread)
-              ;; A degrading cluster hands a read this flat value; the dev
-              ;; dial throws the same diagnostic, so the selection must never
-              ;; concatenate it either way.
-              refusal (error/diagnostic
-                       {:seon.error/kind :seon.db/invalid-read
-                        :seon.error/message
-                        "seon.db/q cannot read uninstalled attribute :sample.shape/uninstalled."
-                        :seon.error/diagnostic-layer :database-read
-                        :seon.error/diagnostic-operation 'seon.db/q
-                        :seon.error/diagnostic-member :sample.shape/uninstalled
-                        :seon.error/diagnostic-expected :seon.db/installed-attribute
-                        :seon.error/diagnostic-offending :sample.shape/uninstalled
-                        :seon.error/diagnostic-cause :seon.db/uninstalled-attribute
-                        :seon.error/diagnostic-evidence
-                        {:seon.fn/sym "sample.shape/many"}})
-              refused (with-redefs
-                        [db/q (fn [& arguments]
-                                (if (identical? thread (Thread/currentThread))
-                                  refusal
-                                  (apply query arguments)))]
-                        (seon.fn/gate-set database "sample.shape/many"))]
-          (is (= :seon.db/invalid-read (:seon.error/kind refusal))
-              "the injected read is a flat database refusal")
-          (is (= refusal refused)
-              "a refused read is returned whole")
-          (is (not (vector? refused))
-              "a refusal never becomes the gate set's own elements")
-          (is (empty? (filter #(and (vector? %) (= 2 (count %))
-                                    (keyword? (first %)))
-                              (when (vector? refused) refused)))
-              "no spliced map entry reaches the caller"))))))
+        ))))

 (deftest output-path-report-finds-the-shortest-bypass
   (test-support/with-database
```
