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
    [seon.agent.search :as search]))

;; ---------------------------------------------------------------------------
;; Fixture — tmp/search-test/ with known content + scoped fs allowlist.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  (.resolve npath "tmp/search-test"))

(def ^:private alpha-path (.join npath fixture-dir "alpha.md"))
(def ^:private beta-path  (.join npath fixture-dir "beta.cljs"))
(def ^:private many-path  (.join npath fixture-dir "many.txt"))

(defonce ^:private !saved-fs-config (atom nil))

(defn- setup! []
  (.mkdirSync nfs fixture-dir #js {:recursive true})
  (.writeFileSync nfs alpha-path "# Title\n\nthe needle-alpha is here\n")
  (.writeFileSync nfs beta-path "(ns beta)\n\n(defn hello [] :needle-beta)\n")
  (.writeFileSync nfs many-path
                  (str/join "\n" (map #(str "dup-needle line " %) (range 20))))
  ;; Save the live config, then scope the allowlist to the fixture dir.
  (reset! !saved-fs-config @fs-int/!config)
  (fs/configure! {:seon.agent.fs/allowed-roots [fixture-dir]
                  :seon.agent.fs/read-only?    true}))

(defn- teardown! []
  ;; Restore the exact saved config (configure! merges both keys).
  (fs/configure! @!saved-fs-config))

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
        (.then done))))

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
        (.then done))))

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
          (.then done)))))

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
        (.then done))))

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
        (.then done))))

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
        (.then done))))

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
        (.then done))))

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
        (.then done))))

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
        (.then done))))

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
        (.then done))))

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
        (.then done))))

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
          (.then done)))))
