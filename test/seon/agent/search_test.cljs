(ns seon.agent.search-test
  "Envelope-contract tests for `seon.agent.search/grep` (the exemplar
   npm-package wrapper).

   The contract under test:

   1. `grep` NEVER rejects — every outcome RESOLVES to a
      `:seon.agent.search/grep-response` envelope (same contract as
      seon.db/transact!, model: test/seon/db/envelope_test.cljs).
   2. Hits are GROUPED BY FILE (:by-file) — each file row carries path +
      count + the first line-number + line-text the agent can feed straight
      into seon.agent.fs/read-file (search → read).
   3. No matches is SUCCESS (rg exit 1): ok? true, empty :by-file.
   4. The seon.agent.fs allowlist gates search roots — an out-of-scope path
      resolves to the guiding denied envelope; no roots configured =
      default-deny envelope.
   5. max-results clips FILE ROWS with :seon.agent.search/truncated? true +
      a narrowing :seon.agent.search/hint; honest totals always reported.
   6. Bad regex (rg exit 2) → guiding message + raw stderr preserved.
   7. :seon.agent.search/glob filters filenames.
   8. :full? true returns the flat :seon.agent.search/matches list.

   Fixtures: a small dir under tmp/search-test/ (gitignored) created
   at runtime; seon.agent.fs config is SAVED before each test, pointed at
   the fixture dir, and RESTORED after — live pod config is untouched
   across the run."
  (:require
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [cljs.test :as t :refer [deftest is testing async use-fixtures]]
    [clojure.string :as str]
    [seon.agent.fs :as fs]
    [seon.agent.fs.internal :as fs-int]
    [seon.agent.search :as search]
    [seon.db :as db]
    [seon.test.async :refer [settle!]]))

;; ---------------------------------------------------------------------------
;; Fixture — tmp/search-test/ with known content + scoped fs allowlist.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  ;; Per-PROCESS unique dir (pid-scoped). grep searches a REAL fs dir, so the
  ;; fixture must be hermetic: a SHARED tmp/search-test lets concurrent
  ;; test processes write/teardown the same files mid-grep — skewing the
  ;; honest count/path assertions intermittently. A pid-scoped dir, wiped on
  ;; :before and removed on :after, makes every count deterministic.
  (.resolve npath (str "tmp/search-test-" (.-pid js/process))))

(def ^:private alpha-path (.join npath fixture-dir "alpha.md"))
(def ^:private beta-path  (.join npath fixture-dir "beta.cljs"))
(def ^:private many-path  (.join npath fixture-dir "many.txt"))

;; Lane-sibling fixtures: a paused `.clj` + its active `.cljs` (same base,
;; same dir) reproduce the #86 trap — file grep must surface the canonical
;; `.cljs` and suppress the dead `.clj`. `solo.clj` has NO `.cljs` sibling
;; (standalone — must always survive).
(def ^:private lane-clj-path  (.join npath fixture-dir "lane.clj"))
(def ^:private lane-cljs-path (.join npath fixture-dir "lane.cljs"))
(def ^:private solo-clj-path  (.join npath fixture-dir "solo.clj"))

(defonce ^:private !saved-fs-config (atom nil))

(defn- setup! []
  ;; Wipe any residue first so ONLY the three known files are searchable —
  ;; no stray file (from a crashed prior run) can pollute the grep counts.
  (.rmSync nfs fixture-dir #js {:recursive true :force true})
  (.mkdirSync nfs fixture-dir #js {:recursive true})
  (.writeFileSync nfs alpha-path "# Title\n\nthe needle-alpha is here\n")
  (.writeFileSync nfs beta-path "(ns beta)\n\n(defn hello [] :needle-beta)\n")
  (.writeFileSync nfs many-path
                  (str/join "\n" (map #(str "dup-needle line " %) (range 20))))
  ;; Lane-sibling pair: same base+dir. `siblingtoken` is in BOTH; the active
  ;; cljs defines the fn `^:async` so a `"defn shared-fn"` regex matches ONLY
  ;; the paused clj (the #86 trap). `solotoken` lives in the standalone clj.
  (.writeFileSync nfs lane-clj-path
                  "(ns lane)\n;; siblingtoken\n(defn shared-fn [] :clj)\n")
  (.writeFileSync nfs lane-cljs-path
                  "(ns lane)\n;; siblingtoken\n(defn ^:async shared-fn [] :cljs)\n")
  (.writeFileSync nfs solo-clj-path
                  "(ns solo)\n;; solotoken\n(defn solo-fn [] :x)\n")
  ;; Save the live config, then scope the allowlist to the fixture dir.
  (reset! !saved-fs-config @fs-int/!config)
  (fs/configure! {:seon.agent.fs/allowed-roots [fixture-dir]
                  :seon.agent.fs/read-only?    true}))

(defn- teardown! []
  ;; Restore the exact saved config (configure! merges both keys).
  (fs/configure! @!saved-fs-config)
  ;; Remove the pid-scoped fixture dir — don't litter tmp/ across runs.
  (.rmSync nfs fixture-dir #js {:recursive true :force true}))

(use-fixtures :each {:before setup! :after teardown!})

(defn- resolves!
  "Attach a .catch that FAILS the test — grep's contract says it
   resolves on every path."
  [p]
  (.catch p (fn [err]
              (is false (str "grep REJECTED — envelope contract violated: "
                             err))
              ::rejected)))

;; ---------------------------------------------------------------------------
;; 1. Match found — path + line + text correct, feeds seon.agent.fs/read-file.
;; ---------------------------------------------------------------------------

(deftest match-found-with-path-line-text
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "needle-alpha"}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file
                     n       :seon.agent.search/match-count
                     fc      :seon.agent.search/file-count
                     trunc?  :seon.agent.search/truncated?}]
                 (is (true? ok?))
                 (is (= 1 n) "honest total match count")
                 (is (= 1 fc) "one file")
                 (is (false? trunc?))
                 (let [{p :seon.agent.search/path
                        c :seon.agent.search/count
                        l :seon.agent.search/line-number
                        t :seon.agent.search/line-text} (first by-file)]
                   (is (= alpha-path p) "absolute, allowlisted path")
                   (is (= 1 c) "per-file hit count")
                   (is (= 3 l) "1-based line number")
                   (is (= "the needle-alpha is here" t)
                       "line text, newline stripped")
                   ;; The search→read recipe: the hit's path goes
                   ;; straight into seon.agent.fs/read-file.
                   (let [r (fs/read-file {:seon.agent.fs/path p})]
                     (is (true? (:seon.agent.fs/ok? r)))
                     (is (str/includes? (:seon.agent.fs/content r)
                                        "needle-alpha"))))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 2. No matches = SUCCESS (rg exit 1 is not an error).
;; ---------------------------------------------------------------------------

(deftest no-match-is-ok-and-empty
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "zzz-never-present"}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file
                     n       :seon.agent.search/match-count
                     fc      :seon.agent.search/file-count
                     trunc?  :seon.agent.search/truncated?}]
                 (is (true? ok?) "no matches is ok? true")
                 (is (= [] by-file))
                 (is (= 0 n))
                 (is (= 0 fc))
                 (is (false? trunc?))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 3. Allowlist gate — out-of-scope path + default-deny.
;; ---------------------------------------------------------------------------

(deftest denied-path-outside-allowlist
  (async done
    (let [outside (.resolve npath "src")]
      (-> (resolves! (search/grep {:seon.agent.search/pattern "needle"
                                   :seon.agent.search/paths   [outside]}))
          (.then (fn [{ok?   :seon.agent.search/ok?
                       error :seon.agent.search/error}]
                   (is (false? ok?))
                   (is (re-find #"ask your human to grant access" error)
                       "guiding message tells the agent what's wrong")
                   (is (re-find #"seon\.agent\.fs/configure!" error)
                       "names the fix")))
          (settle! done)))))

(deftest default-deny-when-no-roots
  (async done
    ;; Empty the allowlist (fixture :after restores the live config).
    (fs/configure! {:seon.agent.fs/allowed-roots []})
    (-> (resolves! (search/grep {:seon.agent.search/pattern "needle"}))
        (.then (fn [{ok?   :seon.agent.search/ok?
                     error :seon.agent.search/error}]
                 (is (false? ok?))
                 (is (re-find #"default-deny" error))
                 (is (re-find #"seon\.agent\.fs/configure!" error))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 4a. Grouping — 20 hits in ONE file roll up to a single file row, honest
;;     count, NOT truncated (the concise win: one row, not 20 lines).
;; ---------------------------------------------------------------------------

(deftest hits-group-into-one-file-row-with-honest-count
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "dup-needle"}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file
                     n       :seon.agent.search/match-count
                     fc      :seon.agent.search/file-count
                     trunc?  :seon.agent.search/truncated?}]
                 (is (true? ok?))
                 (is (= 20 n) "honest total hit count")
                 (is (= 1 fc) "all 20 in one file")
                 (is (= 1 (count by-file)) "one file row, not 20 lines")
                 (is (= 20 (:seon.agent.search/count (first by-file)))
                     "per-file count is the honest 20")
                 (is (false? trunc?) "one file, nothing clipped")))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 4b. max-results clips FILE ROWS + flags truncated + emits a narrowing hint.
;; ---------------------------------------------------------------------------

(deftest max-results-clips-file-rows-and-hints
  (async done
    ;; needle-(alpha|beta) hits 2 files; cap to 1 file row.
    (-> (resolves! (search/grep {:seon.agent.search/pattern     "needle-(alpha|beta)"
                                 :seon.agent.search/max-results 1}))
        (.then (fn [{ok?      :seon.agent.search/ok?
                     by-file  :seon.agent.search/by-file
                     n        :seon.agent.search/match-count
                     fc       :seon.agent.search/file-count
                     returned :seon.agent.search/returned
                     hint     :seon.agent.search/hint
                     trunc?   :seon.agent.search/truncated?}]
                 (is (true? ok?))
                 (is (= 2 n) "honest total across both files")
                 (is (= 2 fc) "honest file count")
                 (is (= 1 returned) "only one file row returned")
                 (is (= 1 (count by-file)))
                 (is (true? trunc?) "clip is reported")
                 (is (string? hint) "narrowing hint present when clipped")
                 (is (re-find #"(?i)narrow" hint))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 4c. :full? true returns the flat per-line matches (drill escape hatch).
;; ---------------------------------------------------------------------------

(deftest full-returns-flat-matches
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern     "dup-needle"
                                 :seon.agent.search/full?       true
                                 :seon.agent.search/max-results 50}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     matches :seon.agent.search/matches
                     n       :seon.agent.search/match-count
                     by-file :seon.agent.search/by-file}]
                 (is (true? ok?))
                 (is (= 20 n) "honest total")
                 (is (= 20 (count matches)) "every line, flat")
                 (is (nil? by-file) ":by-file absent in :full? mode")
                 (is (every? #(= many-path (:seon.agent.search/path %)) matches))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 5. Bad regex → guiding envelope + raw stderr preserved.
;; ---------------------------------------------------------------------------

(deftest bad-regex-envelope
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "(unclosed"}))
        (.then (fn [{ok?       :seon.agent.search/ok?
                     error     :seon.agent.search/error
                     raw-error :seon.agent.search/raw-error}]
                 (is (false? ok?))
                 (is (re-find #"REGEX" error)
                     "guiding message explains pattern is a regex")
                 (is (some? raw-error) "raw rg stderr preserved")
                 (is (re-find #"regex parse error" raw-error))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 6. Glob filtering.
;; ---------------------------------------------------------------------------

(deftest glob-filters-filenames
  (async done
    ;; needle-(alpha|beta) hits alpha.md AND beta.cljs without a glob…
    (-> (resolves! (search/grep {:seon.agent.search/pattern "needle-(alpha|beta)"}))
        (.then (fn [{n :seon.agent.search/match-count}]
                 (is (= 2 n) "sanity: pattern is in two files")
                 ;; …and only alpha.md with *.md.
                 (resolves! (search/grep {:seon.agent.search/pattern "needle-(alpha|beta)"
                                          :seon.agent.search/glob    "*.md"}))))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file
                     n       :seon.agent.search/match-count}]
                 (is (true? ok?))
                 (is (= 1 n))
                 (is (every? #(str/ends-with? (:seon.agent.search/path %) ".md")
                             by-file))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 7. Case-insensitive flag.
;; ---------------------------------------------------------------------------

(deftest case-insensitive-flag
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "NEEDLE-ALPHA"}))
        (.then (fn [{n :seon.agent.search/match-count}]
                 (is (= 0 n) "case-sensitive by default")
                 (resolves!
                   (search/grep {:seon.agent.search/pattern           "NEEDLE-ALPHA"
                                 :seon.agent.search/case-insensitive? true}))))
        (.then (fn [{ok? :seon.agent.search/ok?
                     n   :seon.agent.search/match-count}]
                 (is (true? ok?))
                 (is (= 1 n))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 8. Blank pattern → envelope, not a full-tree match-everything.
;; ---------------------------------------------------------------------------

(deftest blank-pattern-envelope
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "  "}))
        (.then (fn [{ok?   :seon.agent.search/ok?
                     error :seon.agent.search/error}]
                 (is (false? ok?))
                 (is (re-find #"pattern" error))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 8b. context-lines — N lines around each hit; context flagged, not counted.
;; ---------------------------------------------------------------------------

(deftest context-lines-full-mode-interleaves-flagged-context
  (async done
    ;; alpha.md: "# Title\n\nthe needle-alpha is here\n" — hit on line 3.
    (-> (resolves! (search/grep {:seon.agent.search/pattern       "needle-alpha"
                                 :seon.agent.search/context-lines 1
                                 :seon.agent.search/full?         true}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     matches :seon.agent.search/matches
                     n       :seon.agent.search/match-count}]
                 (is (true? ok?))
                 (is (= 1 n) "match-count counts the HIT only, not context lines")
                 (let [by-line (into {} (map (juxt :seon.agent.search/line-number identity) matches))]
                   (is (contains? by-line 2) "the line-2 context is emitted")
                   (is (contains? by-line 3) "the hit line 3 is emitted")
                   (is (true? (:seon.agent.search/context? (by-line 2)))
                       "context line flagged :context? true")
                   (is (nil? (:seon.agent.search/context? (by-line 3)))
                       "the actual match is NOT flagged context"))))
        (settle! done))))

(deftest context-lines-by-file-widens-the-sample
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern       "needle-alpha"
                                 :seon.agent.search/context-lines 1}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file}]
                 (is (true? ok?))
                 (let [lt (:seon.agent.search/line-text (first by-file))]
                   (is (re-find #"3\tthe needle-alpha is here" lt)
                       "the sample widens to a numbered window incl. the hit")
                   (is (re-find #"\n" lt) "…and spans more than one line"))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 8c. multiline? — a pattern may span newlines (rg -U --multiline-dotall).
;; ---------------------------------------------------------------------------

(deftest multiline-lets-a-pattern-span-lines
  (async done
    ;; beta.cljs: "(ns beta)\n\n(defn hello [] :needle-beta)\n" — the pattern
    ;; spans the ns form to the defn across a blank line.
    (-> (resolves! (search/grep {:seon.agent.search/pattern    "ns beta.*defn hello"
                                 :seon.agent.search/multiline? true}))
        (.then (fn [{ok? :seon.agent.search/ok?
                     n   :seon.agent.search/match-count
                     bf  :seon.agent.search/by-file}]
                 (is (true? ok?))
                 (is (= 1 n) ". crosses line boundaries under multiline?")
                 (is (str/ends-with? (:seon.agent.search/path (first bf)) "beta.cljs"))))
        (settle! done))))

(deftest multiline-off-by-default-no-cross-line-match
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "ns beta.*defn hello"}))
        (.then (fn [{n :seon.agent.search/match-count}]
                 (is (= 0 n) "without multiline?, . does not cross newlines")))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; Sanity: defaults documented in the request schema actually apply
;; (paths default = allowed roots — test 1 already proves it implicitly;
;; this one pins the testing label for readers).
;; ---------------------------------------------------------------------------

(deftest default-paths-are-the-allowed-roots
  (testing "omitting :seon.agent.search/paths searches the seon.agent.fs roots"
    (async done
      (-> (resolves! (search/grep {:seon.agent.search/pattern "needle-beta"}))
          (.then (fn [{ok?     :seon.agent.search/ok?
                       by-file :seon.agent.search/by-file}]
                   (is (true? ok?))
                   (is (= [beta-path]
                          (mapv :seon.agent.search/path by-file)))))
          (settle! done)))))

;; ---------------------------------------------------------------------------
;; 9. Lane-correctness (#86) — the active CLJS pod lane is canonical; a paused
;;    `.clj` lane-sibling is suppressed in FILE grep.
;; ---------------------------------------------------------------------------

(deftest sibling-pair-shows-only-cljs
  ;; (a) foo.clj + foo.cljs in the same dir, pattern in BOTH → only the
  ;; canonical .cljs survives; the paused .clj is dropped.
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "siblingtoken"}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file}]
                 (is (true? ok?))
                 (let [paths (mapv :seon.agent.search/path by-file)]
                   (is (some #{lane-cljs-path} paths)
                       "active .cljs sibling surfaces")
                   (is (not (some #{lane-clj-path} paths))
                       "paused .clj sibling is suppressed"))))
        (settle! done))))

(deftest standalone-clj-still-shown
  ;; (b) a .clj with NO .cljs sibling is untouched.
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "solotoken"}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file}]
                 (is (true? ok?))
                 (is (= [solo-clj-path] (mapv :seon.agent.search/path by-file))
                     "standalone .clj (no .cljs sibling) is never suppressed")))
        (settle! done))))

(deftest explicit-clj-path-reaches-it
  ;; (c) explicitly naming the .clj via :paths bypasses suppression.
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "siblingtoken"
                                 :seon.agent.search/paths   [lane-clj-path]}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file}]
                 (is (true? ok?))
                 (is (= [lane-clj-path] (mapv :seon.agent.search/path by-file))
                     "explicitly-targeted .clj reaches the caller")))
        (settle! done))))

(deftest explicit-clj-glob-reaches-it
  ;; (c') a `*.clj` glob restricts to clj-only → explicit, so not suppressed.
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "siblingtoken"
                                 :seon.agent.search/glob    "*.clj"}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file}]
                 (is (true? ok?))
                 (is (some #{lane-clj-path} (mapv :seon.agent.search/path by-file))
                     "a `*.clj` glob is explicit clj-targeting — reaches it")))
        (settle! done))))

(deftest trap-defn-async-no-longer-hands-dead-clj
  ;; (d) THE trap: `"defn shared-fn"` matches ONLY the paused .clj (the active
  ;; cljs is `defn ^:async shared-fn`). The fix must NOT hand the agent the
  ;; dead .clj. With a regex matching BOTH, the canonical .cljs surfaces.
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern "defn shared-fn"}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     by-file :seon.agent.search/by-file}]
                 (is (true? ok?))
                 (is (not (some #{lane-clj-path}
                                (mapv :seon.agent.search/path by-file)))
                     "the paused .clj is no longer handed to the agent")
                 ;; a pattern matching BOTH siblings surfaces the canonical .cljs:
                 (resolves! (search/grep {:seon.agent.search/pattern "shared-fn"}))))
        (.then (fn [{by-file :seon.agent.search/by-file}]
                 (let [paths (mapv :seon.agent.search/path by-file)]
                   (is (some #{lane-cljs-path} paths)
                       "active db.cljs-analog surfaces, not the paused .clj")
                   (is (not (some #{lane-clj-path} paths))))))
        (settle! done))))

;; ===========================================================================
;; grep-graph — the PROGRAM-GRAPH counterpart. Same envelope shape (capped
;; rows grouped by a container, honest totals + hint + :full?), but the
;; container is the NAMESPACE and the corpus is :seon.fn/:seon.schema/:seon.ns
;; rows returned through the public database authority seam.
;; ===========================================================================

(def ^:private graph-database
  {:db-name "search-test" :t 42 :as-of nil :since nil :history false
   :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000042"})

(def ^:private graph-rows
  {:seon.fn
   [["test.alpha/widget-make" "(defn widget-make [] :widgetized)" "makes a widget"]
    ["test.alpha/widget-poke" "(defn widget-poke [] :poked)" ""]
    ["test.beta/gadget-make" "(defn gadget-make [] :gadgetized)" "uses widget internally"]]
   :seon.schema [[:test.alpha/widget-size ":int"]]
   :seon.ns [[:test.alpha "(ns test.alpha)"]
             [:test.beta "(ns test.beta)"]]
   :seon.eval []})

(defn- with-graph
  "Run one graph search against a recording public database authority."
  [request verify]
  (let [original-db db/db
        original-execute-many db/execute-many
        targets (or (:seon.agent.search/targets request)
                    [:seon.fn :seon.schema :seon.ns])]
    (set! db/db (fn
                  ([] (js/Promise.resolve graph-database))
                  ([_request] (js/Promise.resolve graph-database))))
    (set! db/execute-many
          (fn [request]
            (is (= graph-database (::db/db request))
                "all graph queries share one captured database value")
            (is (= (count targets) (count (::db/members request)))
                "one bounded query is sent for each selected target")
            (js/Promise.resolve
             {::db/results (mapv graph-rows targets)})))
    (-> (search/grep-graph request)
        (.then verify)
        (.finally (fn []
                    (set! db/db original-db)
                    (set! db/execute-many original-execute-many))))))

;; widget hits: test.alpha = widget-make + widget-poke (fns) + widget-size
;; (schema) = 3; test.beta = gadget-make (doc "uses widget internally") = 1.

(deftest graph-groups-by-namespace-with-honest-counts
  (async done
    (-> (with-graph
          {:seon.agent.search/pattern "widget"}
          (fn [{ok?   :seon.agent.search/ok?
                   by-ns :seon.agent.search/by-ns
                   n     :seon.agent.search/match-count
                   nc    :seon.agent.search/ns-count
                   trunc? :seon.agent.search/truncated?}]
              (is (true? ok?))
              (is (= 4 n) "honest total matching members")
              (is (= 2 nc) "two namespaces")
              (is (false? trunc?))
              (let [alpha (first (filter #(= "test.alpha" (:seon.agent.search/ns %)) by-ns))]
                (is (= 3 (:seon.agent.search/count alpha)) "alpha rolls up 3 members")
                (is (= :seon.fn (:seon.agent.search/target alpha))
                    "fns sampled first for the row")
                (is (str/starts-with? (:seon.agent.search/member alpha) "test.alpha/widget")
                    "member is a concrete matching fn"))))
        (settle! done))))

(deftest graph-no-match-is-ok-and-empty
  (async done
    (-> (with-graph
          {:seon.agent.search/pattern "zzz-never-present"}
          (fn [{ok?   :seon.agent.search/ok?
                   by-ns :seon.agent.search/by-ns
                   n     :seon.agent.search/match-count
                   nc    :seon.agent.search/ns-count}]
              (is (true? ok?) "no matches is ok? true")
              (is (= [] by-ns))
              (is (= 0 n))
              (is (= 0 nc))))
        (settle! done))))

(deftest graph-max-results-clips-and-hints
  (async done
    (-> (with-graph
          {:seon.agent.search/pattern "widget"
           :seon.agent.search/max-results 1}
          (fn [{ok?      :seon.agent.search/ok?
                   by-ns    :seon.agent.search/by-ns
                   nc       :seon.agent.search/ns-count
                   returned :seon.agent.search/returned
                   hint     :seon.agent.search/hint
                   trunc?   :seon.agent.search/truncated?}]
              (is (true? ok?))
              (is (= 2 nc) "honest namespace count")
              (is (= 1 returned) "only one ns row returned")
              (is (= 1 (count by-ns)))
              (is (true? trunc?))
              (is (string? hint))
              (is (re-find #"(?i)narrow" hint))))
        (settle! done))))

(deftest graph-full-returns-flat-members
  (async done
    (-> (with-graph
          {:seon.agent.search/pattern "widget"
           :seon.agent.search/full? true}
          (fn [{ok?     :seon.agent.search/ok?
                   matches :seon.agent.search/matches
                   n       :seon.agent.search/match-count
                   by-ns   :seon.agent.search/by-ns}]
              (is (true? ok?))
              (is (= 4 n) "honest total")
              (is (= 4 (count matches)) "every matching member, flat")
              (is (nil? by-ns) ":by-ns absent in :full? mode")
              (is (every? :seon.agent.search/member matches))))
        (settle! done))))

(deftest graph-targets-filter
  (async done
    (-> (with-graph
          {:seon.agent.search/pattern "widget"
           :seon.agent.search/targets [:seon.schema]}
          (fn [{ok?   :seon.agent.search/ok?
                   by-ns :seon.agent.search/by-ns
                   n     :seon.agent.search/match-count}]
              (is (true? ok?))
              (is (= 1 n) "only the schema matches when targets = [:seon.schema]")
              (is (= :seon.schema (:seon.agent.search/target (first by-ns))))))
        (settle! done))))

(deftest graph-bad-regex-envelope
  (async done
    (-> (with-graph
          {:seon.agent.search/pattern "(unclosed"}
          (fn [{ok?   :seon.agent.search/ok?
                error :seon.agent.search/error}]
              (is (false? ok?))
              (is (re-find #"REGEX" error))))
        (settle! done))))

(deftest graph-case-insensitive-flag
  (async done
    (-> (with-graph
          {:seon.agent.search/pattern "WIDGET"}
          (fn [cs]
            (is (= 0 (:seon.agent.search/match-count cs))
                "case-sensitive by default")))
        (.then (fn [_]
                 (with-graph
                   {:seon.agent.search/pattern "WIDGET"
                    :seon.agent.search/case-insensitive? true}
                   (fn [ci]
                     (is (true? (:seon.agent.search/ok? ci)))
                     (is (= 4 (:seon.agent.search/match-count ci)))))))
        (settle! done))))

(deftest graph-blank-pattern-envelope
  (async done
    (-> (with-graph
          {:seon.agent.search/pattern "  "}
          (fn [{ok?   :seon.agent.search/ok?
                error :seon.agent.search/error}]
              (is (false? ok?))
              (is (re-find #"pattern" error))))
        (settle! done))))
