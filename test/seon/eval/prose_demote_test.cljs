(ns seon.eval.prose-demote-test
  "Pure classification of parenthesized prose before eval recording."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.eval :as eval]
   [seon.repl :as repl]
   [seon.test.async :refer [settle!]]))

(def ^:private prose-parenthetical? (deref #'eval/prose-paren?))

(deftest prose-parenthetical-predicate-is-conservative
  (async done
    (settle!
     (-> (repl/ensure-bootstrap!)
         (.then
          (fn [_]
            (let [compile-state @repl/!compile-state
                  prose? #(prose-parenthetical? compile-state 'cljs.user %)]
              (testing "undefined bare-word parentheticals are prose"
                (is (true? (prose? "(Abk and fvV both look correct)")))
                (is (true? (prose? "(June 3 before June 14)")))
                (is (true? (prose? "(results look fine)")))
                (is (true? (prose? "(numbers and strings \"ok\" :kw allowed here too)"))))
              (testing "resolving heads and qualified symbols remain code"
                (is (false? (prose? "(+ 1 2)")))
                (is (false? (prose? "(str hello world)")))
                (is (false? (prose? "(seon.db/query foo bar)")))
                (is (false? (prose? "(see seon.db/query result here)"))))
              (testing "special forms and plausible typo calls remain code"
                (is (false? (prose? "(when foo bar)")))
                (is (false? (prose? "(-> data foo bar)")))
                (is (false? (prose? "(undefined-fn 1 2)")))
                (is (false? (prose? "(frobnicate '[:find ?x])"))))
              (testing "non-list and multi-form input remains code"
                (is (false? (prose? "42")))
                (is (false? (prose? "[a b c]")))
                (is (false? (prose? "(foo) (bar baz qux)"))))))))
     done)))
