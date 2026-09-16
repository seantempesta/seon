;;; Authoring for the issue-context trials, 2026-09-16.
;;;
;;; One real issue — documentation-arglists-with-auto-keywords-are-not-edn —
;;; authored seven times, once per candidate rendering, on an ISOLATED scratch
;;; cluster. Each copy carries the same problem, the same function refs and the
;;; same red test; only the worker's :seon.config.render/issue-opening dial
;;; differs, and that is what seon.issue/render-ai dispatches on.
;;;
;;; Seven copies rather than one because the worker identity start! derives is
;;; (id/id [issue-id]): one issue can have exactly one worker, so seven
;;; sessions need seven issue identities.
;;;
;;; Run it from the scratch cluster's JVM:
;;;   (load-file ".../issue_context_trials_2026_09_16.clj")
;;;   (seon.trials.issue-context/install! {...})

(ns seon.trials.issue-context
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.id :as id]
            [seon.issue :as issue]))

(def test-namespace 'seon.sci.eval-documentation-trial-test)

(def test-symbol
  "seon.sci.eval-documentation-trial-test/documentation-reads-arglists-with-auto-resolved-keywords")

(def test-source
  "The red test, written into the scratch checkout and adopted so that it is a
  :seon.test entity with real :seon.fn/calls edges to its subject."
  (str "(ns seon.sci.eval-documentation-trial-test\n"
       "  \"Trial regression: documentation must read Clojure arglists, not EDN.\"\n"
       "  (:require [clojure.test :refer [deftest is]]\n"
       "            [seon.db :as db]\n"
       "            [seon.operator :as operator]\n"
       "            [seon.sci.eval :as sci.eval]))\n"
       "\n"
       "(deftest documentation-reads-arglists-with-auto-resolved-keywords\n"
       "  (let [database (db/db (operator/connection \"trials\"))\n"
       "        value (sci.eval/documentation-value database 'seon.sci.reader/read 'seon.sci.reader/read)]\n"
       "    (is (not (:seon.error/kind value))\n"
       "        \"documentation-value must return data, never a refusal, for a function whose arglists destructure with auto-resolved keywords\")\n"
       "    (is (vector? (:arglists value))\n"
       "        \"the arglists must come back as data\")))\n"))

(def subject-functions
  ["seon.sci.eval/function-doc-map"
   "seon.sci.eval/directory-value"
   "seon.sci.eval/documentation-value"])

(def candidates
  [:bare :plan-first :evidence-first :walkthrough
   :questions :namespace-picture :minimal-retrieval])

(def problem
  "The note's Problem section verbatim, plus the operating facts every worker
  needs and every candidate shares. The adoption call is here, not in a
  candidate, precisely so it cannot bias the comparison: without it a file edit
  never reaches the JVM the tests run in."
  (str
   "`doc` and `dir` read the stored `:seon.fn/arglists` string with "
   "`clojure.edn/read-string` (src/seon/sci/eval.clj), which throws "
   "`Invalid token: ::text` on destructuring written with Clojure "
   "auto-resolved keywords. EDN cannot read those tokens. "
   "`function-doc-map` and `directory-value` decode arglists the same way.\n\n"
   "Acceptance: documentation for seon.sci.reader/read returns its "
   "destructuring arglists as data, preserving resolved keyword identities, "
   "without executing any form. No regex or string replacement.\n\n"
   "Operating facts for this cluster, the same for every worker:\n"
   "- The source checkout is CHECKOUT and the cluster is trials.\n"
   "- A file edit is not live until it is adopted. Adopt one changed file with\n"
   "  (my.shell/run! {:my.shell/argv [\"bin/seon\" \"--root\" \"ROOT\" \"init\"\n"
   "                                  \"--dev\" \"trials\" \"--changed\" \"PATH\"]\n"
   "                  :my.shell/cwd \"CHECKOUT\"})\n"
   "- Then run this issue's tests with (my.test/check {:seon.test/changed [...]})."))

(defn- problem-text [checkout root]
  (-> problem
      (str/replace "CHECKOUT" checkout)
      (str/replace "ROOT" root)))

(defn write-test-file!
  "Write the red test into the scratch checkout; adoption is the caller's."
  [checkout]
  (let [file (io/file checkout "test/seon/sci/eval_documentation_trial_test.clj")]
    (io/make-parents file)
    (spit file test-source)
    {:path (str file) :bytes (count test-source)}))

(defn- title [candidate]
  (str "Documentation reads Clojure arglists as EDN — candidate "
       (name candidate)))

(defn refs
  "The function and test refs, as lookup refs that must already resolve."
  [database]
  (let [function-refs (mapv (fn [sym]
                              (or (:db/id (db/pull database [:db/id] [:seon.fn/sym sym]))
                                  (throw (ex-info "No such function entity" {:sym sym}))))
                            subject-functions)
        test-ref (or (:db/id (db/pull database [:db/id] [:seon.test/sym test-symbol]))
                     (throw (ex-info "No such test entity" {:sym test-symbol})))]
    {:functions (set function-refs) :tests #{test-ref}}))

(defn install!
  "Author one issue per candidate. Returns each candidate's issue id."
  [{:keys [connection checkout root author]
    :or {author "root"}}]
  (let [database (db/db connection)
        {:keys [functions tests]} (refs database)
        text (problem-text checkout root)]
    (into {}
          (map (fn [candidate]
                 (let [result (issue/add! {:seon.db/connection connection
                                           :seon.agent/id author
                                           :seon.issue/title (title candidate)
                                           :seon.issue/problem text
                                           :seon.issue/severity :friction
                                           :seon.issue/functions functions
                                           :seon.issue/tests tests})]
                   [candidate (or (:seon.issue/id result) result)])))
          candidates)))

(defn start-candidate!
  "Start one candidate's worker with its dial set in the same transaction."
  [{:keys [connection issue-id candidate budget no-provider?]
    :or {budget 20}}]
  (let [agent-id (id/id [issue-id])]
    (issue/start!
     (cond-> {:seon.db/connection connection
              :seon.issue/id issue-id
              :seon.issue/budget budget
              :seon.ns/name (symbol (str "my.agents." agent-id))
              :seon.agent/settings {:seon.config.render/issue-opening candidate}}
       no-provider? (assoc :seon.config.ai/no-provider true)))))
