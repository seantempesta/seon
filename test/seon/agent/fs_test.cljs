(ns seon.agent.fs-test
  "Tests for seon.agent.fs's grant introspection (agent-robustness
   unit, 2026-06-11).

   Observed live: an agent INFERRED its fs grant from a CWD listing
   and got it wrong (claimed grant = CWD when the allowed root was an
   ancestor). [[seon.agent.fs/grants]] is the read API returning the
   CONFIGURED truth. Pins:

     - grants returns exactly what configure! installed (roots + flag)
     - default-deny reads back as empty roots, not an error
     - the enforcement agrees with the read-back: a path under a
       granted root resolves, a path outside it is denied
     - SEON_FS_LOCK makes configure! a legible no-op error and shows
       up in grants as :seon.agent.fs/locked? (consumer ask 8 — an agent
       NARROWED its own grant and locked itself out for the session)
     - read-file paging returns the requested line window + honest
       totals (consumer ask 10b — partial reads must never look complete)

   The pod is live and `!config` is a shared atom — every test saves
   and restores it (and the SEON_FS_LOCK env var alongside).

   Run interactively via MCP eval:
     (require 'seon.agent.fs-test :reload)
     (cljs.test/run-tests 'seon.agent.fs-test)"
  (:require
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing use-fixtures]]
    [seon.agent.fs :as fs]
    [seon.agent.fs.internal :as fs-int]
    [seon.code :as code]))

(def ^:private edit-dir
  (.resolve npath (str "tmp/fs-edit-test-" (.-pid js/process))))

(def ^:private !saved (atom nil))
(def ^:private !saved-lock (atom nil))

(defn- set-lock-env! [v]
  (if (some? v)
    (aset (.-env js/process) "SEON_FS_LOCK" v)
    (js-delete (.-env js/process) "SEON_FS_LOCK")))

(use-fixtures :each
  {:before (fn []
             (reset! !saved @fs-int/!config)
             (reset! !saved-lock (.. js/process -env -SEON_FS_LOCK))
             ;; tests assume an unlocked baseline; lock tests opt in
             (set-lock-env! nil))
   :after  (fn []
             (reset! fs-int/!config @!saved)
             (set-lock-env! @!saved-lock)
             (.rmSync nfs edit-dir #js {:recursive true :force true}))})

(deftest grants-returns-the-configured-truth
  (fs/configure! {:seon.agent.fs/allowed-roots ["/Users/grantee/work"
                                                "/Users/grantee/notes"]
                  :seon.agent.fs/read-only?    true})
  (is (= {:seon.agent.fs/allowed-roots ["/Users/grantee/work"
                                        "/Users/grantee/notes"]
          :seon.agent.fs/read-only?    true
          :seon.agent.fs/locked?       false}
         (fs/grants))
      "grants reads back the CONFIGURED roots + flag — never an inference"))

(deftest grants-default-deny-is-empty-roots
  (reset! fs-int/!config {})
  (is (= {:seon.agent.fs/allowed-roots []
          :seon.agent.fs/read-only?    false
          :seon.agent.fs/locked?       false}
         (fs/grants))
      "no grant configured → empty allowed-roots (default-deny), writable flag false"))

(deftest grants-agrees-with-enforcement
  (testing "the read-back boundary IS the enforced boundary"
    ;; grant an ANCESTOR (the live defect shape: agent stood in
    ;; <root>/sub and claimed <root>/sub was the grant)
    (fs/configure! {:seon.agent.fs/allowed-roots ["/definitely/not/a/real/root"]
                    :seon.agent.fs/read-only?    false})
    (let [in-scope  (fs/stat {:seon.agent.fs/path "/definitely/not/a/real/root/sub/f.txt"})
          out-scope (fs/stat {:seon.agent.fs/path "/somewhere/else/f.txt"})]
      (is (not (str/includes? (str (:seon.agent.fs/error in-scope))
                              "allowed-roots"))
          "under the granted root → past the allowlist (fails ENOENT, not denied)")
      (is (false? (:seon.agent.fs/ok? out-scope)))
      (is (str/includes? (:seon.agent.fs/error out-scope) "allowed-roots")
          "outside the granted root → the allowlist denial"))))

(deftest stat-exposes-file-state-without-a-raw-size
  (let [root (.cwd js/process)
        path (.resolve npath root "deps.edn")]
    (fs/configure! {:seon.agent.fs/allowed-roots [root]
                    :seon.agent.fs/read-only?    true})
    (let [result (fs/stat {:seon.agent.fs/path path})]
      (is (true? (:seon.agent.fs/ok? result)))
      (is (= #{:seon.agent.fs/ok?
               :seon.agent.fs/path
               :seon.agent.fs/dir?
               :seon.agent.fs/file?
               :seon.agent.fs/mtime}
             (set (keys result)))
          "stat returns path, time, and file-state facts only")
      (is (true? (:seon.agent.fs/file? result)))
      (is (false? (:seon.agent.fs/dir? result))))))

;; ============================================================
;; SEON_FS_LOCK — host-immutable grant (consumer ask 8)
;; ============================================================

(deftest seon-fs-lock-makes-configure!-a-legible-no-op
  ;; the live incident: env grant installed by the host, then the
  ;; agent configure!s itself to a NARROWER root → locked out
  (fs/configure! {:seon.agent.fs/allowed-roots ["/host/granted"]
                  :seon.agent.fs/read-only?    true})
  (set-lock-env! "1")
  (let [r (fs/configure! {:seon.agent.fs/allowed-roots ["/host/granted/narrower"]})]
    (is (false? (:seon.agent.fs/ok? r)) "configure! refused under the lock")
    (is (true? (:seon.agent.fs/locked? r)))
    (is (str/includes? (:seon.agent.fs/error r) "SEON_FS_LOCK")
        "the error names the knob")
    (is (str/includes? (:seon.agent.fs/error r) "seon.agent.fs/grants")
        "…and points at the read API"))
  (let [g (fs/grants)]
    (is (true? (:seon.agent.fs/locked? g))
        "grants surfaces WHY configure! refuses")
    (is (= ["/host/granted"] (:seon.agent.fs/allowed-roots g))
        "the host grant is INTACT — the narrowing never landed")))

(deftest seon-fs-lock-unset-or-zero-stays-mutable
  (doseq [v [nil "0" ""]]
    (set-lock-env! v)
    (let [r (fs/configure! {:seon.agent.fs/allowed-roots ["/a"]
                            :seon.agent.fs/read-only?    true})]
      (is (true? (:seon.agent.fs/ok? r))
          (str "SEON_FS_LOCK=" (pr-str v) " → configure! works as before"))
      (is (= ["/a"] (:seon.agent.fs/allowed-roots (fs/grants)))))))

;; ============================================================
;; read-file paging — honest section reads (consumer ask 10b)
;; ============================================================

(deftest read-file-paging-returns-range-plus-honest-totals
  (let [root (.cwd js/process)
        path (str root "/deps.edn")]
    (fs/configure! {:seon.agent.fs/allowed-roots [root]
                    :seon.agent.fs/read-only?    true})
    (let [full  (fs/read-file {:seon.agent.fs/path path})
          lines (let [ls (str/split (:seon.agent.fs/content full) #"\n" -1)]
                  (if (and (seq ls) (= "" (peek ls))) (pop ls) ls))
          page  (fs/read-file {:seon.agent.fs/path path
                               :seon.agent.fs/from-line 2
                               :seon.agent.fs/max-lines 3})]
      (is (true? (:seon.agent.fs/ok? full)) "fixture file readable")
      (is (nil? (:seon.agent.fs/total-lines full))
          "unpaged read keeps its original shape")
      (is (true? (:seon.agent.fs/ok? page)))
      (is (= 2 (:seon.agent.fs/from-line page)))
      (is (= 3 (:seon.agent.fs/lines-returned page)))
      (is (= (count lines) (:seon.agent.fs/total-lines page))
          "total-lines = the WHOLE file's line count, not the page's")
      (is (= (str/join "\n" (subvec lines 1 4))
             (:seon.agent.fs/content page))
          "content is exactly lines 2..4 (1-based, inclusive window)"))))

(deftest read-file-paging-off-the-end-is-honest-not-fake
  (let [root (.cwd js/process)
        path (str root "/deps.edn")]
    (fs/configure! {:seon.agent.fs/allowed-roots [root]
                    :seon.agent.fs/read-only?    true})
    (let [page (fs/read-file {:seon.agent.fs/path path
                              :seon.agent.fs/from-line 1000000
                              :seon.agent.fs/max-lines 10})]
      (is (true? (:seon.agent.fs/ok? page)) "off-the-end is not an error")
      (is (= 0 (:seon.agent.fs/lines-returned page))
          "…but it returns ZERO lines, loudly")
      (is (= "" (:seon.agent.fs/content page)))
      (is (pos? (:seon.agent.fs/total-lines page))
          "total-lines still reports the real file size"))))

;; ============================================================
;; edit-file — in-place line-range + unique exact-match edits.
;; Hermetic: pid-scoped tmp dir, granted writable per test.
;; ============================================================

(defn- edit-fixture!
  "Fresh pid-scoped tmp file with `content`; grants the dir writable.
   Returns the file's absolute path."
  [content]
  (.rmSync nfs edit-dir #js {:recursive true :force true})
  (.mkdirSync nfs edit-dir #js {:recursive true})
  (let [path (.join npath edit-dir "target.txt")]
    (.writeFileSync nfs path content "utf-8")
    (fs/configure! {:seon.agent.fs/allowed-roots [edit-dir]
                    :seon.agent.fs/read-only?    false})
    path))

(defn- file-content [path]
  (.readFileSync nfs path "utf-8"))

(deftest edit-file-line-range-lands-exactly-with-context
  (let [path (edit-fixture! "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\n")
        r    (fs/edit-file {:seon.agent.fs/path path
                            :seon.agent.fs/from-line 4
                            :seon.agent.fs/to-line   5
                            :seon.agent.fs/content   "NEW-A\nNEW-B\nNEW-C"})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "l1\nl2\nl3\nNEW-A\nNEW-B\nNEW-C\nl6\nl7\nl8\nl9\nl10\n"
           (file-content path))
        "1-based INCLUSIVE range [4 5] replaced; trailing newline preserved")
    (is (= 4  (:seon.agent.fs/from-line r)))
    (is (= 2  (:seon.agent.fs/lines-replaced r)))
    (is (= 3  (:seon.agent.fs/lines-inserted r)))
    (is (= 11 (:seon.agent.fs/total-lines r)))
    (is (= 1 (:seon.agent.fs/context-from-line r))
        "context window starts 3 lines above the edit (clamped to 1)")
    (is (= "l1\nl2\nl3\nNEW-A\nNEW-B\nNEW-C\nl6\nl7\nl8"
           (:seon.agent.fs/context r))
        "the context SHOWS the landed edit — new lines + 3 lines each side")
    (is (false? (:seon.agent.fs/truncated? r)))))

(deftest edit-file-line-range-out-of-range-is-an-error-value
  (let [path (edit-fixture! "a\nb\nc\n")
        r    (fs/edit-file {:seon.agent.fs/path path
                            :seon.agent.fs/from-line 2
                            :seon.agent.fs/to-line   9
                            :seon.agent.fs/content   "x"})]
    (is (false? (:seon.agent.fs/ok? r)))
    (is (str/includes? (:seon.agent.fs/error r) "3 lines")
        "the error names the file's REAL line count")
    (is (= "a\nb\nc\n" (file-content path)) "no write on error")))

(deftest edit-file-exact-match-replaces-the-unique-occurrence
  (let [path (edit-fixture! "(def x 1)\n(def y 2)\n(def z 3)\n")
        r    (fs/edit-file {:seon.agent.fs/path path
                            :seon.agent.fs/old-string "(def y 2)"
                            :seon.agent.fs/new-string "(def y 42)"})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "(def x 1)\n(def y 42)\n(def z 3)\n" (file-content path)))
    (is (= 2 (:seon.agent.fs/from-line r)) "edit landed on line 2")
    (is (str/includes? (:seon.agent.fs/context r) "(def y 42)")
        "context shows the NEW text")))

(deftest edit-file-zero-and-ambiguous-matches-are-distinct-errors
  (testing "0 matches"
    (let [path (edit-fixture! "alpha\nbeta\n")
          r    (fs/edit-file {:seon.agent.fs/path path
                              :seon.agent.fs/old-string "gamma"
                              :seon.agent.fs/new-string "delta"})]
      (is (false? (:seon.agent.fs/ok? r)))
      (is (str/includes? (:seon.agent.fs/error r) "not found"))
      (is (= "alpha\nbeta\n" (file-content path)) "no write")))
  (testing ">1 matches"
    (let [path (edit-fixture! "same\nother\nsame\n")
          r    (fs/edit-file {:seon.agent.fs/path path
                              :seon.agent.fs/old-string "same"
                              :seon.agent.fs/new-string "diff"})]
      (is (false? (:seon.agent.fs/ok? r)))
      (is (str/includes? (:seon.agent.fs/error r) "AMBIGUOUS"))
      (is (str/includes? (:seon.agent.fs/error r) "2 matches")
          "the error counts the matches")
      (is (= "same\nother\nsame\n" (file-content path)) "no write"))))

(deftest edit-file-respects-the-write-gate
  (testing "ungranted path → allowlist denial"
    (edit-fixture! "a\n")
    (let [r (fs/edit-file {:seon.agent.fs/path "/somewhere/else/f.txt"
                           :seon.agent.fs/old-string "a"
                           :seon.agent.fs/new-string "b"})]
      (is (false? (:seon.agent.fs/ok? r)))
      (is (str/includes? (:seon.agent.fs/error r) "allowed-roots"))))
  (testing "read-only grant refuses, same as write-file"
    (let [path (edit-fixture! "a\n")]
      (fs/configure! {:seon.agent.fs/allowed-roots [edit-dir]
                      :seon.agent.fs/read-only?    true})
      (let [r (fs/edit-file {:seon.agent.fs/path path
                             :seon.agent.fs/old-string "a"
                             :seon.agent.fs/new-string "b"})]
        (is (false? (:seon.agent.fs/ok? r)))
        (is (str/includes? (:seon.agent.fs/error r) "read-only"))
        (is (= "a\n" (file-content path)) "no write")))))

(deftest edit-file-mode-selection-errors-guide-the-caller
  (let [path (edit-fixture! "a\nb\n")]
    (testing "both modes at once"
      (let [r (fs/edit-file {:seon.agent.fs/path path
                             :seon.agent.fs/from-line 1
                             :seon.agent.fs/to-line 1
                             :seon.agent.fs/content "x"
                             :seon.agent.fs/old-string "a"
                             :seon.agent.fs/new-string "x"})]
        (is (false? (:seon.agent.fs/ok? r)))
        (is (str/includes? (:seon.agent.fs/error r) "ONE mode"))))
    (testing "neither mode"
      (let [r (fs/edit-file {:seon.agent.fs/path path})]
        (is (false? (:seon.agent.fs/ok? r)))
        (is (str/includes? (:seon.agent.fs/error r) "no edit given"))))
    (testing "incomplete line mode"
      (let [r (fs/edit-file {:seon.agent.fs/path path
                             :seon.agent.fs/from-line 1})]
        (is (false? (:seon.agent.fs/ok? r)))
        (is (str/includes? (:seon.agent.fs/error r) "line-range mode"))))
    (is (= "a\nb\n" (file-content path)) "no write on any mode error")))

(deftest edit-file-empty-content-deletes-the-range
  (let [path (edit-fixture! "keep1\ndrop1\ndrop2\nkeep2\n")
        r    (fs/edit-file {:seon.agent.fs/path path
                            :seon.agent.fs/from-line 2
                            :seon.agent.fs/to-line   3
                            :seon.agent.fs/content   ""})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "keep1\nkeep2\n" (file-content path)))
    (is (= 2 (:seon.agent.fs/lines-replaced r)))
    (is (= 0 (:seon.agent.fs/lines-inserted r)))
    (is (= 2 (:seon.agent.fs/total-lines r)))))

;; ============================================================
;; view — line-numbered, bounded, sha-stamped read (A3).
;; ============================================================

(deftest view-numbers-lines-and-stamps-a-sha
  (let [path (edit-fixture! "one\ntwo\nthree\n")
        r    (fs/view {:seon.agent.fs/path path})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "1\tone\n2\ttwo\n3\tthree" (:seon.agent.fs/content r))
        "content carries 1-based N<tab> line numbers")
    (is (= 3 (:seon.agent.fs/total-lines r)))
    (is (= 3 (:seon.agent.fs/lines-returned r)))
    (is (re-matches #"[0-9a-f]{64}" (:seon.agent.fs/file-sha r))
        "…and the file's content SHA, to pass to replace!")))

(deftest view-pages-with-honest-totals
  (let [path (edit-fixture! "a\nb\nc\nd\ne\n")
        r    (fs/view {:seon.agent.fs/path path
                       :seon.agent.fs/from-line 2
                       :seon.agent.fs/max-lines 2})]
    (is (= "2\tb\n3\tc" (:seon.agent.fs/content r)) "window is lines 2..3, numbered")
    (is (= 2 (:seon.agent.fs/from-line r)))
    (is (= 2 (:seon.agent.fs/lines-returned r)))
    (is (= 5 (:seon.agent.fs/total-lines r)) "total is the WHOLE file"))
  (testing "off the end is honest, not fake"
    (let [path (edit-fixture! "a\nb\n")
          r    (fs/view {:seon.agent.fs/path path :seon.agent.fs/from-line 99})]
      (is (= "" (:seon.agent.fs/content r)))
      (is (= 0 (:seon.agent.fs/lines-returned r)))
      (is (= 2 (:seon.agent.fs/total-lines r))))))

;; ============================================================
;; replace! — anchored, deterministic-only, sha-fenced (A2).
;; ============================================================

(deftest replace!-unique-match-writes-and-returns-the-landing
  (let [path (edit-fixture! "(def x 1)\n(def y 2)\n(def z 3)\n")
        r    (fs/replace! {:seon.agent.fs/path path
                           :seon.agent.fs/find "(def y 2)"
                           :seon.agent.fs/replace "(def y 42)"})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "(def x 1)\n(def y 42)\n(def z 3)\n" (file-content path)))
    (is (= [2 2] (:seon.agent.fs/range-after r)))
    (is (= 1 (:seon.agent.fs/lines-added r)))
    (is (= 1 (:seon.agent.fs/lines-removed r)))
    (is (str/includes? (:seon.agent.fs/excerpt r) "2\t(def y 42)")
        "the excerpt line-numbers the landed result")
    (is (re-matches #"[0-9a-f]{64}" (:seon.agent.fs/file-sha r))
        "returns the NEW file sha for a follow-up edit")))

(deftest replace!-ambiguous-refuses-with-candidates-and-no-write
  (let [path (edit-fixture! "log(1)\nlog(2)\nlog(3)\n")
        r    (fs/replace! {:seon.agent.fs/path path
                           :seon.agent.fs/find "log"
                           :seon.agent.fs/replace "trace"})]
    (is (false? (:seon.agent.fs/ok? r)))
    (is (string? (:seon.error/message r)) "guiding message present")
    (is (= :seon.agent.fs.match/ambiguous
           (:seon.agent.fs.match/reason (:seon.error/data r))))
    (is (= 3 (count (:seon.agent.fs.match/candidates (:seon.error/data r))))
        "every occurrence offered as a candidate")
    (is (= "log(1)\nlog(2)\nlog(3)\n" (file-content path)) "NOTHING mutated")))

(deftest replace!-near-window-disambiguates
  (let [path (edit-fixture! "hit\na\nhit\nb\nhit\n")
        r    (fs/replace! {:seon.agent.fs/path path
                           :seon.agent.fs/find "hit"
                           :seon.agent.fs/replace "DONE"
                           :seon.agent.fs/near [3 3]})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "hit\na\nDONE\nb\nhit\n" (file-content path)))
    (is (= [3 3] (:seon.agent.fs/range-after r)))))

(deftest replace!-expected-count-changes-all
  (let [path (edit-fixture! "hit\nx\nhit\n")
        r    (fs/replace! {:seon.agent.fs/path path
                           :seon.agent.fs/find "hit"
                           :seon.agent.fs/replace "DONE"
                           :seon.agent.fs/expected-count 2})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "DONE\nx\nDONE\n" (file-content path)))))

(deftest replace!-all?-changes-every-occurrence-without-a-count
  (let [path (edit-fixture! "log(1)\nx\nlog(2)\nlog(3)\n")
        r    (fs/replace! {:seon.agent.fs/path path
                           :seon.agent.fs/find "log"
                           :seon.agent.fs/replace "trace"
                           :seon.agent.fs/all? true})]
    (is (true? (:seon.agent.fs/ok? r)) "::all? applies to every occurrence, no ambiguity")
    (is (= "trace(1)\nx\ntrace(2)\ntrace(3)\n" (file-content path)))
    (is (= 3 (:seon.agent.fs/lines-added r)) "all three lines rewritten")))

;; ============================================================
;; walk-dir — recursive glob filter + mtime sort (A6 item 4).
;; ============================================================

(defn- walk-fixture!
  "A pid-scoped tree: a.py, sub/c.py, note.md — grants the dir writable.
   Files are written with staggered mtimes (note.md newest). Returns the dir."
  []
  (.rmSync nfs edit-dir #js {:recursive true :force true})
  (.mkdirSync nfs (.join npath edit-dir "sub") #js {:recursive true})
  (.writeFileSync nfs (.join npath edit-dir "a.py") "x = 1\n" "utf-8")
  (.writeFileSync nfs (.join npath edit-dir "sub" "c.py") "z = 2\n" "utf-8")
  (.writeFileSync nfs (.join npath edit-dir "note.md") "text\n" "utf-8")
  ;; bump note.md's mtime so :mtime sort is deterministic (newest first)
  (let [future (js/Date. (+ (.now js/Date) 100000))]
    (.utimesSync nfs (.join npath edit-dir "note.md") future future))
  (fs/configure! {:seon.agent.fs/allowed-roots [edit-dir]
                  :seon.agent.fs/read-only?    true})
  edit-dir)

(deftest walk-dir-glob-filters-recursively
  (let [dir (walk-fixture!)
        r   (fs/walk-dir {:seon.agent.fs/path dir :seon.agent.fs/glob "*.py"})
        rel (fn [p] (subs p (inc (count dir))))]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= #{"a.py" "sub/c.py"} (set (map rel (:seon.agent.fs/entries r))))
        "a slash-free glob matches .py at any depth; note.md excluded")
    (is (= 2 (:seon.agent.fs/total-found r)))))

(deftest walk-dir-glob-with-path-segment
  (let [dir (walk-fixture!)
        r   (fs/walk-dir {:seon.agent.fs/path dir :seon.agent.fs/glob "sub/**/*.py"})
        rel (fn [p] (subs p (inc (count dir))))]
    (is (= ["sub/c.py"] (mapv rel (:seon.agent.fs/entries r)))
        "a glob with a / matches the root-relative path only")))

(deftest walk-dir-mtime-sort-newest-first
  (let [dir (walk-fixture!)
        r   (fs/walk-dir {:seon.agent.fs/path dir :seon.agent.fs/sort :mtime})
        rel (fn [p] (subs p (inc (count dir))))]
    (is (= "note.md" (rel (first (:seon.agent.fs/entries r))))
        "note.md's bumped mtime sorts it newest-first")))

(deftest walk-dir-truncation-hints-the-cap
  (let [dir (walk-fixture!)
        r   (fs/walk-dir {:seon.agent.fs/path dir :seon.agent.fs/max-results 1})]
    (is (true? (:seon.agent.fs/truncated? r)) "cap of 1 clips the 3-file tree")
    (is (= 1 (:seon.agent.fs/total-found r)))
    (is (string? (:seon.agent.fs/hint r)) "a hint names the cap knob")
    (is (re-find #"max-results" (:seon.agent.fs/hint r)))))

(deftest replace!-sha-guard-fences-a-stale-edit
  (let [path (edit-fixture! "line-a\nline-b\n")
        good (:seon.agent.fs/file-sha (fs/view {:seon.agent.fs/path path}))]
    (testing "matching sha proceeds"
      (let [r (fs/replace! {:seon.agent.fs/path path
                            :seon.agent.fs/find "line-a" :seon.agent.fs/replace "LINE-A"
                            :seon.agent.fs/file-sha good})]
        (is (true? (:seon.agent.fs/ok? r)))))
    (testing "a STALE sha refuses and reports the actual"
      (let [r (fs/replace! {:seon.agent.fs/path path
                            :seon.agent.fs/find "LINE-A" :seon.agent.fs/replace "x"
                            :seon.agent.fs/file-sha "deadbeef"})]
        (is (false? (:seon.agent.fs/ok? r)))
        (is (str/includes? (:seon.error/message r) "changed since"))
        (is (re-matches #"[0-9a-f]{64}" (:seon.agent.fs/file-sha r))
            "the ACTUAL on-disk sha is handed back")
        (is (str/includes? (file-content path) "LINE-A") "no second write")))))

(deftest replace!-accepts-a-code-heredoc-value
  (let [path (edit-fixture! "print('hi')\n")
        blk  {:seon.code/lang :python :seon.code/text "print('hi')"}
        r    (fs/replace! {:seon.agent.fs/path path
                           :seon.agent.fs/find blk
                           :seon.agent.fs/replace {:seon.code/lang :python
                                                   :seon.code/text "print('bye')"}})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "print('bye')\n" (file-content path))
        "#code values flow to disk with no escaping (seon.code/text extracts)")))

(deftest replace!-indentation-mismatch-refuses
  ;; file is TAB-indented, find is SPACE-indented — not a substring and the
  ;; normalizer never touches leading whitespace, so it must refuse.
  (let [path (edit-fixture! "def f():\n\treturn 1\n")
        r    (fs/replace! {:seon.agent.fs/path path
                           :seon.agent.fs/find "    return 1"    ; spaces vs a tab
                           :seon.agent.fs/replace "    return 2"})]
    (is (false? (:seon.agent.fs/ok? r)))
    (is (= "def f():\n\treturn 1\n" (file-content path))
        "leading indentation is never normalized away — no write")))

(deftest replace!-respects-the-write-gate
  (testing "read-only grant refuses"
    (let [path (edit-fixture! "a\n")]
      (fs/configure! {:seon.agent.fs/allowed-roots [edit-dir]
                      :seon.agent.fs/read-only?    true})
      (let [r (fs/replace! {:seon.agent.fs/path path
                            :seon.agent.fs/find "a" :seon.agent.fs/replace "b"})]
        (is (false? (:seon.agent.fs/ok? r)))
        (is (str/includes? (:seon.error/message r) "read-only")))))
  (testing "out-of-scope path denied"
    (edit-fixture! "a\n")
    (let [r (fs/replace! {:seon.agent.fs/path "/somewhere/else/f.txt"
                          :seon.agent.fs/find "a" :seon.agent.fs/replace "b"})]
      (is (false? (:seon.agent.fs/ok? r)))
      (is (str/includes? (:seon.error/message r) "allowed-roots")))))

;; ============================================================
;; insert! — exactly-one line anchor.
;; ============================================================

(deftest insert!-after-line-splices-in
  (let [path (edit-fixture! "a\nb\nc\n")
        r    (fs/insert! {:seon.agent.fs/path path
                          :seon.agent.fs/after-line 2
                          :seon.agent.fs/content "NEW1\nNEW2"})]
    (is (true? (:seon.agent.fs/ok? r)))
    (is (= "a\nb\nNEW1\nNEW2\nc\n" (file-content path)))
    (is (= [3 4] (:seon.agent.fs/range-after r)))
    (is (= 2 (:seon.agent.fs/lines-added r)))
    (is (= 0 (:seon.agent.fs/lines-removed r)))))

(deftest insert!-before-line-and-boundaries
  (testing "before-line 1 prepends"
    (let [path (edit-fixture! "a\nb\n")
          r    (fs/insert! {:seon.agent.fs/path path
                            :seon.agent.fs/before-line 1 :seon.agent.fs/content "TOP"})]
      (is (true? (:seon.agent.fs/ok? r)))
      (is (= "TOP\na\nb\n" (file-content path)))))
  (testing "after-line 0 also prepends"
    (let [path (edit-fixture! "a\nb\n")
          r    (fs/insert! {:seon.agent.fs/path path
                            :seon.agent.fs/after-line 0 :seon.agent.fs/content "TOP"})]
      (is (= "TOP\na\nb\n" (file-content path)))))
  (testing "after-line = total appends"
    (let [path (edit-fixture! "a\nb\n")
          r    (fs/insert! {:seon.agent.fs/path path
                            :seon.agent.fs/after-line 2 :seon.agent.fs/content "END"})]
      (is (= "a\nb\nEND\n" (file-content path))))))

(deftest insert!-out-of-range-fails-with-total-lines
  (let [path (edit-fixture! "a\nb\n")
        r    (fs/insert! {:seon.agent.fs/path path
                          :seon.agent.fs/after-line 9 :seon.agent.fs/content "x"})]
    (is (false? (:seon.agent.fs/ok? r)))
    (is (= 2 (:seon.agent.fs/total-lines (:seon.error/data r)))
        "the error names the file's real line count")
    (is (= "a\nb\n" (file-content path)) "no write")))

(deftest insert!-requires-exactly-one-anchor
  (let [path (edit-fixture! "a\n")]
    (testing "both anchors refused"
      (let [r (fs/insert! {:seon.agent.fs/path path :seon.agent.fs/content "x"
                           :seon.agent.fs/after-line 1 :seon.agent.fs/before-line 1})]
        (is (false? (:seon.agent.fs/ok? r)))
        (is (str/includes? (:seon.error/message r) "EXACTLY ONE"))))
    (testing "neither anchor refused"
      (let [r (fs/insert! {:seon.agent.fs/path path :seon.agent.fs/content "x"})]
        (is (false? (:seon.agent.fs/ok? r)))
        (is (str/includes? (:seon.error/message r) "EXACTLY ONE"))))
    (is (= "a\n" (file-content path)) "no write on any anchor error")))
