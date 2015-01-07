;A generic container for data necessary to manage a set of demanddata.
(ns marathon.demand.demandstore
  (use [spork.util.record :only [defrecord+ with-record]]
       [spork.util.tags :as t]))

(defrecord+ demandstore 
  [[name :DemandStore] 
   [demandmap  {}]
   [infeasible-demands {}] 
   unfilledq 
   [activations {}]
   [deactivations {}]
   [activedemands {}]
   [eligbledemands {}]
   [changed {}]
   demandtraffic  
   tryfill  
   loginfeasibles 
   [tags (t/add-tag t/empty-tags "Sinks")]
   fillables 
   verbose 
   [tlastdeactivation 0]])

(def empty-demandstore (make-demandstore)) 
