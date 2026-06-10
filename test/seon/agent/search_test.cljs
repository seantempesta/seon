(ns seon.agent.search-test
  "Envelope-contract tests for `seon.agent.search/grep` (the exemplar
   npm-package wrapper).

   The contract under test:

   1. `grep` NEVER rejects — every outcome RESOLVES to a
      `:seon.agent.search/grep-response` envelope (same contract as
      seon.db/transact!, model: test/seon/db/envelope_test.cljs).
   2. Matches carry path + line-number + line-text the agent can feed
      straight into seon.agent.fs/read-file (search → read).
   3. No matches is SUCCESS (rg exit 1): ok? true, empty matches.
   4. The seon.agent.fs allowlist gates search roots — an out-of-scope path
      resolves to the guiding denied envelope; no roots configured =
      default-deny envelope.
   5. max-results clips with :seon.agent.search/truncated? true.
   6. Bad regex (rg exit 2) → guiding message + raw stderr preserved.
   7. :seon.agent.search/glob filters filenames.

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
  (reset! !saved-fs-config @fs/!config)
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
                     matches :seon.agent.search/matches
                     n       :seon.agent.search/match-count
                     trunc?  :seon.agent.search/truncated?}]
                 (is (true? ok?))
                 (is (= 1 n))
                 (is (false? trunc?))
                 (let [{p :seon.agent.search/path
                        l :seon.agent.search/line-number
                        t :seon.agent.search/line-text} (first matches)]
                   (is (= alpha-path p) "absolute, allowlisted path")
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
                     matches :seon.agent.search/matches
                     n       :seon.agent.search/match-count
                     trunc?  :seon.agent.search/truncated?}]
                 (is (true? ok?) "no matches is ok? true")
                 (is (= [] matches))
                 (is (= 0 n))
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
;; 4. max-results truncation.
;; ---------------------------------------------------------------------------

(deftest max-results-clips-and-flags-truncated
  (async done
    (-> (resolves! (search/grep {:seon.agent.search/pattern     "dup-needle"
                                 :seon.agent.search/max-results 5}))
        (.then (fn [{ok?     :seon.agent.search/ok?
                     matches :seon.agent.search/matches
                     n       :seon.agent.search/match-count
                     trunc?  :seon.agent.search/truncated?}]
                 (is (true? ok?))
                 (is (= 5 n))
                 (is (= 5 (count matches)))
                 (is (true? trunc?) "clip is reported")))
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
                     matches :seon.agent.search/matches
                     n       :seon.agent.search/match-count}]
                 (is (true? ok?))
                 (is (= 1 n))
                 (is (every? #(str/ends-with? (:seon.agent.search/path %) ".md")
                             matches))))
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
                       matches :seon.agent.search/matches}]
                   (is (true? ok?))
                   (is (= [beta-path]
                          (mapv :seon.agent.search/path matches)))))
          (.then done)))))
