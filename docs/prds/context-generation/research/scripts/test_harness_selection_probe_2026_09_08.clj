;;; Probe: bare `bin/test` selects by :seon.fn/calls reach, never by mtime or
;;; by a filename convention. Run: clojure -M -i tmp/harness-probes/selection_probe.clj
(require '[seon.fn :as seon.fn] '[seon.test.selection :as selection]
         '[clojure.java.io :as io])

(def manifest (seon.fn/build-manifest {:seon.fn/roots selection/graph-roots}))
(def artifacts (selection/manifest-relative-artifacts "." manifest))

;; 1. A change to one production function selects the tests that reach it.
(def subject "src/seon/test/selection.clj")
(def reached (selection/reaching-tests artifacts [subject]))
(println "1. tests reaching" subject "=>" (count reached))
(doseq [t (take 10 reached)] (println "   " t))

;; 2. The relation is TRANSITIVE over call edges, not a filename match.
;;    No selected test's file name shares a stem with the changed file.
(println "2. selected tests whose namespace name contains \"selection\":"
         (count (filter #(clojure.string/includes? % "selection") reached))
         "of" (count reached))

;; 3. An unreached file selects nothing at all.
(println "3. tests reaching a file with no callers (docs):"
         (count (selection/reaching-tests artifacts ["AGENTS.md"])))

;; 4. MTIME IS NOT CONSULTED. Touch a file; digests are byte-identical.
(def before (selection/input-digests "."))
(.setLastModified (io/file "src/seon/test/selection.clj")
                  (- (System/currentTimeMillis) 100000))
(def after (selection/input-digests "."))
(println "4. digests identical after an mtime change:" (= before after))
(println "   changed-inputs after touch:"
         (:seon.test.selection/changed (selection/changed-inputs before after)))

;; 5. A CONTENT change is what selects.
(def mutated (assoc before "src/seon/test/selection.clj" "0000"))
(println "5. changed-inputs after a content change:"
         (:seon.test.selection/changed (selection/changed-inputs mutated before)))

;; 6. Widening inputs are named, not guessed.
(println "6. widening? resources/seon/schemas/x.edn =>"
         (selection/widening-path? "resources/seon/schemas/x.edn")
         "| src/seon/db.clj =>" (selection/widening-path? "src/seon/db.clj"))
(System/exit 0)
