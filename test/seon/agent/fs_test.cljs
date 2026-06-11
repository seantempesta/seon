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
       up in grants as :seon.agent.fs/locked? (aria ask 8 — an agent
       NARROWED its own grant and locked itself out for the session)
     - read-file paging returns the requested line window + honest
       totals (aria ask 10b — partial reads must never look complete)

   The pod is live and `!config` is a shared atom — every test saves
   and restores it (and the SEON_FS_LOCK env var alongside).

   Run interactively via MCP eval:
     (require 'seon.agent.fs-test :reload)
     (cljs.test/run-tests 'seon.agent.fs-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing use-fixtures]]
    [seon.agent.fs :as fs]))

(def ^:private !saved (atom nil))
(def ^:private !saved-lock (atom nil))

(defn- set-lock-env! [v]
  (if (some? v)
    (aset (.-env js/process) "SEON_FS_LOCK" v)
    (js-delete (.-env js/process) "SEON_FS_LOCK")))

(use-fixtures :each
  {:before (fn []
             (reset! !saved @fs/!config)
             (reset! !saved-lock (.. js/process -env -SEON_FS_LOCK))
             ;; tests assume an unlocked baseline; lock tests opt in
             (set-lock-env! nil))
   :after  (fn []
             (reset! fs/!config @!saved)
             (set-lock-env! @!saved-lock))})

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
  (reset! fs/!config {})
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
;; SEON_FS_LOCK — host-immutable grant (aria ask 8)
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
;; read-file paging — honest section reads (aria ask 10b)
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
