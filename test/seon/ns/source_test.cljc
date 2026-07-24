(ns seon.ns.source-test
  (:require
   #?(:clj [clojure.test :refer [deftest is]]
      :cljs [cljs.test :refer [deftest is]])
   [malli.core :as m]
   [seon.ns.source :as ns.source]))

(deftest source-require-edges-match-the-structural-edge-contract
  (let [edges (ns.source/require-edges-from-source
               "(ns my.probe (:require [seon.db :as db]
                                       [seon.agent.lifecycle :refer [wait complete]]
                                       [my.types :as-alias types]
                                       [legacy.all :refer :all]
                                       plain.ns))")]
    (is (m/validate :seon.ns.source/require-edges edges))
    (is (= #{{:seon.ns.require/target 'seon.db
              :seon.ns.require/alias 'db}
             {:seon.ns.require/target 'seon.agent.lifecycle
              :seon.ns.require/refers #{'wait 'complete}}
             {:seon.ns.require/target 'my.types
              :seon.ns.require/alias 'types
              :seon.ns.require/as-alias? true}
             {:seon.ns.require/target 'legacy.all
              :seon.ns.require/refer-all? true}
             {:seon.ns.require/target 'plain.ns}}
           edges))))

(deftest namespace-source-fails-soft
  (is (= #{} (ns.source/require-edges-from-source "not an ns form")))
  (is (= #{} (ns.source/require-edges-from-source "(ns broken")))
  (is (= {:seon.ns/require-edges #{}}
         (ns.source/namespace-info-from-source "(ns broken"))))

(deftest namespace-info-derives-documentation-and-edges
  (let [source "(ns my.probe\n  \"Owns probe behavior.\\n\\nMore detail.\"\n  (:require [seon.db :as db]))"
        info (ns.source/namespace-info-from-source source)]
    (is (= "Owns probe behavior.\n\nMore detail." (:seon.ns/doc info)))
    (is (= "Owns probe behavior." (:seon.ns/summary info)))
    (is (= #{{:seon.ns.require/target 'seon.db
              :seon.ns.require/alias 'db}}
           (:seon.ns/require-edges info)))
    (is (m/validate :seon.ns.source/namespace-info info))))

(deftest namespace-info-selects-the-current-platform-branch
  (let [source
        (str "(ns my.portable\n"
             "  \"Portable namespace documentation.\"\n"
             "  #?(:clj (:require [clojure.string :as str])\n"
             "     :cljs (:require [cljs.string :as str])))")
        info (ns.source/namespace-info-from-source source)]
    (is (= "Portable namespace documentation." (:seon.ns/doc info)))
    (is (= #{{:seon.ns.require/target
              #?(:clj 'clojure.string :cljs 'cljs.string)
              :seon.ns.require/alias 'str}}
           (:seon.ns/require-edges info)))))
