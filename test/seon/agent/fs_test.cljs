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
    [seon.agent.fs.internal :as fs-int]))

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
