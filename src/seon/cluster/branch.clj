(ns seon.cluster.branch
  "The cluster → branch name derivation, a leaf any owner can require.

  `seon.fault` records a core fault's copy on its cluster branch, and
  `seon.cluster.store` requires `seon.fault`, so the derivation cannot live
  behind the registry (which requires the store).")

(defn cluster-branch
  "The ONE branch name for a cluster: `:cluster-<name>`.
  One derivation — no other code builds this keyword, so a cluster's
  name and its branch can never disagree."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :seon.store/branch]}
  [cluster-name]
  (keyword (str "cluster-" cluster-name)))
