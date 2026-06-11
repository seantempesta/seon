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

   The pod is live and `!config` is a shared atom — every test saves
   and restores it.

   Run interactively via MCP eval:
     (require 'seon.agent.fs-test :reload)
     (cljs.test/run-tests 'seon.agent.fs-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing use-fixtures]]
    [seon.agent.fs :as fs]))

(def ^:private !saved (atom nil))

(use-fixtures :each
  {:before (fn [] (reset! !saved @fs/!config))
   :after  (fn [] (reset! fs/!config @!saved))})

(deftest grants-returns-the-configured-truth
  (fs/configure! {:seon.agent.fs/allowed-roots ["/Users/grantee/work"
                                                "/Users/grantee/notes"]
                  :seon.agent.fs/read-only?    true})
  (is (= {:seon.agent.fs/allowed-roots ["/Users/grantee/work"
                                        "/Users/grantee/notes"]
          :seon.agent.fs/read-only?    true}
         (fs/grants))
      "grants reads back the CONFIGURED roots + flag — never an inference"))

(deftest grants-default-deny-is-empty-roots
  (reset! fs/!config {})
  (is (= {:seon.agent.fs/allowed-roots []
          :seon.agent.fs/read-only?    false}
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
