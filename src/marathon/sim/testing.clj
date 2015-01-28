(ns marathon.sim.testing
  (:require [marathon.sim.missing] 
            [marathon.sim [engine :refer :all]]
            [marathon.sim.fill.demand   :as fill]
            [marathon.sim [core :as core]
                          [supply :as supply]
                          [demand :as demand]
                          [unit :as unit]
                          [policy :as policy]
                          [policyio :as policyio]
                          [sampledata :as sd]
                          [entityfactory :as ent]
                          [setup :as setup]
                          [engine :as engine]
                          [query :as query]]                        
            [marathon.data [simstate :as simstate]
                           [protocols :as generic]]
            [spork.sim     [simcontext :as sim]]
            [spork.util.reducers]
            [spork.sketch :as sketch]
            [clojure.core [reducers :as r]]
            [clojure.test :as test :refer :all]))

;;Testing for the core Engine Infrastructure
;;==========================================

;;There is no guard against negative times....we may want to enforce 
;;that.
(def ^:constant +end-time+ 1000)

;;note, even the empty sim has time 0 on the clock.  Perhaps we should
;;alter that....
(def primed-sim
  (->> (initialize-sim core/emptysim +end-time+)
       (sim/add-times [44 100 203 55])))

;;#Tests for basic simulation engine invariants.
(deftest basic-engine-testing
  (is (keep-simulating?  core/emptysim)
      "We should be able to simulate, since there is time, 
       and thus events on the clock now.")
  (is (not (can-simulate? core/emptysim))     
      "No supply or demand should indicate as false for now.")
  (is (zero?(sim/current-time core/emptysim)) 
      "empty simulations have no time")
  (is (not  (sim/has-time-remaining? (sim/advance-time core/emptysim))) 
      "nothing scheduled, should be no more work.")
)


;;#Tests for minimal simulation context invariants.
(deftest primed-engine-testing 
  (is (keep-simulating? primed-sim)
      "We should be able to simulate, since there is time, 
       and thus events on the clock now.")
  (is (= (sim/get-next-time primed-sim) 1)
      "Initialized simulation should have a start time of one....")
  (is (= (sim/get-final-time primed-sim) +end-time+ ) "upper bound should be the final time.")
  (is (sim/has-time-remaining? primed-sim) "we have time scheduled")
)


;;#Event propogation tests.
(defn push-message! [ctx edata name]
  (let [s    (:state ctx)
        msgs (get-in s [:state :messages] [])]
    (->> (assoc-in s [:state :messages] (conj msgs [name edata]))
        (assoc ctx :state))))

(def listener-ctx (assoc core/emptysim :propogator 
                     (:propogator (sim/make-debug-context 
                                   :debug-handler push-message!))))

(deftest event-propogation-testing
  (is (= (:messages (sim/get-state (sim/trigger-event :hello :dee :dumb "test!" nil listener-ctx)))
         [[:debugger #spork.sim.simcontext.packet{:t 0, :type :hello, :from :dee, :to :dumb, :msg "test!", :data nil}]])
      "Should have one message logged."))
 
;;Mocking up a sample run....
;;When we go to "run" marathon, we're really loading data from a
;;project.  The easiest way to do that is to provide marathon an API
;;for instantiating a project from tables.  Since we have canonical
;;references for project data, it's pretty easy to do this...  
(def testctx  (assoc-in core/emptysim [:state :parameters :SRCs-In-Scope] {"SRC1" true "SRC2" true "SRC3" true}))
(def debugctx (assoc-in core/debugsim [:state :parameters :SRCs-In-Scope] {"SRC1" true "SRC2" true "SRC3" true}))

(def demand-records    (sd/get-sample-records :DemandRecords))
(def ds       (ent/demands-from-records demand-records testctx))
(def first-demand (first ds))
(def tstart   (:startday first-demand))
(def tfinal   (+ tstart (:duration first-demand)))
(def res      (demand/register-demand  first-demand testctx))
(def dstore   (core/get-demandstore res))
(deftest scheduled-demand-correctly 
  (is (= ((juxt  :startday :duration) first-demand)
         [ 901 1080])
      "Sampledata should not change.  Naming should be deterministic.")
  (is (= (first (demand/get-activations dstore tstart))
         (:name first-demand))
      "Demand should register as an activation on startday.")
  (is (= (first (demand/get-deactivations dstore tfinal)) (:name first-demand)) 
        "Demand should be scheduled for deactivation")
  (is (zero? (sim/get-time res)) "Simulation time should still be at zero.")
  (is (== (sim/get-next-time res) tstart) "Next event should be demand activation")
  (is (== (sim/get-next-time (sim/advance-time res)) tfinal) "Next event should be demand activation"))
(def earliest (reduce min (map :startday ds)))
(def latest   (reduce max (map #(+ (:startday %) (:duration %)) ds)))

(def multiple-demands (demand/register-demands! ds testctx))

(def m-dstore (core/get-demandstore multiple-demands))
(def times    (map sim/get-time (take-while spork.sim.agenda/still-time? (iterate sim/advance-time multiple-demands))))
(def known-events   (core/events multiple-demands))
(def expected-events (list {:time 0, :type :time} {:time 1, :type :time} {:time 91, :type :time} {:time 181, :type :time} 
            {:time 271, :type :time} {:time 361, :type :time} {:time 451, :type :time} {:time 467, :type :time} 
            {:time 481, :type :time} {:time 523, :type :time} {:time 541, :type :time} {:time 554, :type :time} 
            {:time 563, :type :time} {:time 595, :type :time} {:time 618, :type :time} {:time 631, :type :time} 
            {:time 666, :type :time} {:time 721, :type :time} {:time 778, :type :time} {:time 811, :type :time} 
            {:time 901, :type :time} {:time 963, :type :time} {:time 991, :type :time} {:time 1048, :type :time} 
            {:time 1051, :type :time} {:time 1081, :type :time} {:time 1261, :type :time} {:time 1330, :type :time} 
            {:time 1351, :type :time} {:time 1441, :type :time} {:time 1531, :type :time} {:time 1621, :type :time} 
            {:time 1711, :type :time} {:time 1801, :type :time} {:time 1981, :type :time} {:time 2071, :type :time} 
            {:time 2095, :type :time} {:time 2341, :type :time} {:time 2521, :type :time}))

(def activations481 (demand/get-activations m-dstore 481))

;;TODO# modify test clause to work around structural equality problem.
;;I had to put this dude in, for some reason the structural equality
;;checks are not working inside the test clauses.  This does...
(defn same? [& colls]
  (loop [cs colls
         acc true]
    (if (every? seq cs)
      (let [x (ffirst cs)]
        (if (every? #(= (first %) x)  cs)
          (recur (filter identity (map rest cs))
                 acc)
          false))
      (if (some seq cs)
        false
        acc))))
;  (every? identity (map = colls)))

(deftest scheduled-demands-correctly 
  (is (= times
         '(0 1 91 181 271 361 451 467 481 523 541 554 563 595 618 631 666 721 
             778 811 901 963 991 1048 1051 1081 1261 1330 1351 1441 1531 1621 1711 1801 1981 2071 2095 2341 2521))
      "Scheduled times from sampledata should be consistent, in sorted order.")
  (is (= known-events expected-events)           
      "The only events scheduled should be time changes.")
  (is (same? (take 2 activations481)
             ["1_R29_SRC3[481...554]" "1_A11_SRC2[481...554]"])
      "Should have actives on 481...")
  (is (re-find #"1_Vig-ANON-.*[481...554]" (nth activations481 2))
      "The third active on 481 should be an anonymously named vignette with a  number in the name.")
  (is (some (fn [d] (= d (:name first-demand))) (demand/get-activations m-dstore tstart))
      "Demand should register as an activation on startday.")
  (is (zero? (sim/get-time multiple-demands)) "Simulation time should still be at zero.")
  (is (== (sim/get-next-time multiple-demands) earliest) "Next event should be demand activation")
  (is (= (last times) (:tlastdeactivation m-dstore))
      "Last event should be a deactivation time.")) 


;;we can't build supply without policy....initializing supply with
;;an understanding of policy...
(def pstore            (setup/default-policystore))

;;our canonical test data...
(def test-dstore m-dstore)
(def loadedctx (core/merge-updates {:policystore  pstore :demandstore test-dstore} testctx))

;;#unit processing#
;;build a supply store...
(def supply-records    (sd/get-sample-records :SupplyRecords))
(def sstore            (core/get-supplystore loadedctx))
(def us                (ent/units-from-records supply-records sstore pstore))
  
;;processing units, adding stuff.
;;Note, this is taking about a second for processing 30000 units.
;;Most of that bottleneck is due to not using transients and doing
;;bulk updates where possible.  
(def loadedctx        (ent/process-units us loadedctx))


;;TODO# add tests for mutable version of process-units!

(def test-fillstore   (setup/default-fillstore))
(def loadedctx        (core/set-fillstore loadedctx test-fillstore))


;;#Testing on Entire Default Context

;;An entire context loaded from the default project.
;;Includes scoping information, supply, demand, policy, etc.  This
;;will be the new hub for regression testing.
(def defaultctx       (setup/default-simstate core/debugsim))

(defn nonzero-srcs [tbl]
  (into #{} (r/map :SRC (r/filter #(and (:Enabled %)(pos? (:Quantity %))) tbl ))))

(def nonzero-supply-srcs  (nonzero-srcs (setup/get-table :SupplyRecords)))
(def nonzero-demand-srcs  (nonzero-srcs (setup/get-table :DemandRecords)))
(def expected-srcs        (clojure.set/union nonzero-supply-srcs nonzero-demand-srcs))

(deftest correct-context
  (is (= nonzero-supply-srcs
         (set (map :src (vals (core/units defaultctx)))))
      "Should have all the supply loaded.")
  (is (= nonzero-demand-srcs
         (set (map :src (vals (core/demands defaultctx)))))
      "Should have all the demand loaded."))

;;can we run a demand simulation?
(deftest demand-activations
  (is (empty? (keys  (:activedemands (core/get-demandstore (demand/activate-demands 0 defaultctx)))))
      "Should have no demands active at t 0")
  (is (same? '("2_R1_SRC3[1...91]" "2_R2_SRC3[1...2521]" "1_R3_SRC3[1...2521]" "1_R25_SRC3[1...541]")
             (keys  (:activedemands (core/get-demandstore (demand/activate-demands 1 defaultctx)))))
      "Should have four demands active at t 1"))

(defn demand-step
  "Stripped down demand simulation."
  [day ctx]
  (->> ctx 
    (engine/begin-day day)         ;Trigger beginning-of-day logic and notifications.
;    (manage-supply day)     ;Update unit positions and policies.
;    (manage-policies day)   ;Apply policy changes, possibly affecting supply.
    (demand/manage-demands day)    ;Activate/DeActiveate demands, handle affected units.      
;    (fill-demands day)      ;Try to fill unfilled demands in priority order. 
;    (manage-followons day)  ;Resets unused units from follow-on status. 
    (engine/end-day day)           ;End of day logic and notifications.
    (demand/manage-changed-demands day)));Clear set of changed demands
                                        ;in demandstore.

(defn ->simreducer [stepf init]  
  (r/take-while identity (r/iterate (fn [ctx] 
                                      (when  (engine/keep-simulating? ctx)
                                        (sim/advance-time
                                         (stepf (sim/get-time ctx) ctx))))
                                    init)))
;;A wrapper for an abstract simulation.  Can produce a sequence of
;;simulation states; reducible.
(defn ->simulator [stepf seed]
  (let [simred (->simreducer stepf seed)]
    (reify     
      clojure.lang.Seqable 
      (seq [this]  
        (take-while identity (iterate (fn [ctx] 
                                        (when  (engine/keep-simulating? ctx)
                                          (sim/advance-time
                                           (stepf (sim/get-time ctx) ctx))))
                                      seed)))
      clojure.core.protocols/CollReduce
      (coll-reduce [this f1]   (reduce f1 simred))
      (coll-reduce [_ f1 init] (reduce f1 init simred)))))

(def demand-sim (->simulator demand-step defaultctx))

(defn actives [rctx]
  (into [] (r/map (fn [ctx] [(sim/get-time ctx) (:activedemands (core/get-demandstore ctx))])  rctx)))

(def demandctx         (demand-step 1 defaultctx))
(def unfilled-ds  (keys (val (first (:unfilledq (core/get-demandstore demandctx))))))

(deftest unfilled-demands
  (is  (same? unfilled-ds
              '(["1_R25_SRC3[1...541]" 1] ["1_R3_SRC3[1...2521]" 1] ["2_R1_SRC3[1...91]" 2] ["2_R2_SRC3[1...2521]" 2]))
      "Should have the same order and set of demands unfilled on day 1."))


(def deployables  (filter unit/in-deployable-window? (vals  (core/units defaultctx))))
(defn deployable-units [ctx] (filter unit/can-deploy?(vals  (core/units defaultctx))))
(def deploynames  (map :name deployables))


;;fill queries...
(def fillrules (map marathon.sim.fill/derive-supply-rule (vals (core/demands defaultctx)) (core/get-fillstore defaultctx)))

;; ([:fillrule "SRC3"] [:fillrule "SRC3"] [:fillrule "SRC2"] [:fillrule "SRC1"] [:fillrule "SRC3"] [:fillrule "SRC3"] [:fillrule "SRC1"] [:fillrule "SRC3"] [:fillrule "SRC2"])

;;using features from sim.query, we build some supply queries:
(def odd-units (->> defaultctx
                    (query/find-supply {:where #(odd? (:cycletime %)) 
                                        :order-by query/uniform 
                                        :collect-by [:name :cycletime]})))

(def even-units (->> defaultctx 
                     (query/find-supply {:where #(even? (:cycletime %)) 
                                         :order-by query/uniform 
                                         :collect-by [:name :cycletime]})))

;;Can we define more general supply orderings?...
(deftest unit-queries 
  (is (same? deploynames 
             '("29_SRC3_NG" "36_SRC3_AC" "23_SRC3_NG" "11_SRC2_AC" "2_SRC1_NG" "24_SRC3_NG" "22_SRC3_NG" "40_SRC3_AC"
               "34_SRC3_AC" "37_SRC3_AC" "8_SRC2_NG" "35_SRC3_AC"))
      "Should have 5 units deployable")
  (is (same? odd-units)
      '(["24_SRC3_NG" 1601] ["38_SRC3_AC" 923] ["8_SRC2_NG" 1825] ["23_SRC3_NG" 1399] ["29_SRC3_NG" 1385] ["22_SRC3_NG" 1217] 
          ["28_SRC3_NG" 929] ["10_SRC2_AC" 365] ["40_SRC3_AC" 365] ["4_SRC1_AC" 365] ["17_SRC3_NG" 509] ["16_SRC3_NG" 395] 
            ["14_SRC3_NG" 187] ["33_SRC3_AC" 109] ["13_SRC3_NG" 91]))
  (is (same? even-units)
      '(["5_SRC1_AC" 912] ["11_SRC2_AC" 912] ["2_SRC1_NG" 1520] ["41_SRC3_AC" 912] ["37_SRC3_AC" 704] ["21_SRC3_NG" 1052] 
        ["31_SRC3_NG" 912] ["20_SRC3_NG" 900] ["36_SRC3_AC" 522] ["19_SRC3_NG" 760] ["18_SRC3_NG" 630] ["35_SRC3_AC" 366] 
        ["7_SRC2_NG" 730] ["1_SRC1_NG" 608] ["27_SRC3_NG" 564] ["34_SRC3_AC" 230] ["15_SRC3_NG" 288] ["26_SRC3_NG" 260] 
        ["32_SRC3_AC" 0] ["9_SRC2_AC" 0] ["0_SRC1_NG" 0] ["3_SRC1_AC" 0] ["12_SRC3_NG" 0] ["6_SRC2_NG" 0] ["30_SRC3_NG" 0]
        ["39_SRC3_AC" 0] ["25_SRC3_NG" 0])))

;;How can we fill a demand with the category '[:fillrule "SRC3"] ? 
;;Actually, for primitive fillrules, like "SRC3"....


;;we have a couple of different rules we could match..

;;"SRC3" -> match any supply that corresponds to src3.
  ;;[(where-category :any) (where-src "SRC3")]

;;["SomeCategory" "SRC3"] -> match any supply the correponds to src3,
;;from a specific category of supply.
  ;;[(where-category "SomeCategory") (where-src "SRC3")]

;;{:tags #{"Special"} :category "SomeCategory" :src "SRC3"} -> 
  ;;[(where-tag "Special") (where-category "SomeCategory") (where-src "SRC3")]

;;another idea here is to allow where to be a function...
;;(where symb               val) 
;;(where symb)         === (where symb true) 
;;(where-not symb)     === (not (where symb false))
;;(where-not symb val) === (not (where symb val))

;;rather than (where-category ....), a specific function, 
;;we use where as a dispatch mechanism...
;;(where category ...)  -> lookup the function associated with
;;category, and use that.
;;=== (where #'category ...) 

;;same thing with order-by
;;(order-by symb)...
;;(max symb ...)
;;(min symb ...)



;;can we interpret a demand as a rule? 
;;Currently, we go [category -> srcpref -> pref1 -> pref2 ...]
;;Like,            [category -> src1 -> ac -> has-tag :blue]

;;What if we want to alter the order?  
;;[ac -> has-tag -> src1 -> category] ? 

;;Note that "where" clauses, or filters, are pretty universal..
;;We typically filter by category, not sort...although we should be
;;able to do both.

;;We can implement that alteration, but it happens after the fact, as

;;What if demands have a category preference? That adds a second
;;dimension of ordering we can use....

(defmacro exception [& body-expr]
  `(throw (Exception. (str ~@body-expr))))

(defn demand->rule    [d ] (marathon.sim.fill/derive-supply-rule d)) ;;.e.g {:src "SRC3" :category :any}
;;what am I doing here...
(defn rule->criteria  [rule] 
  (cond (string? rule)  {:src rule}
        :else (exception "Not sophisticated enough to process ")))

(defn criteria->query 
  ([crit features]  (fn [ctx] (query/find-supply (assoc crit :collect features) ctx)))
  ([crit] (criteria->query crit [:name])))

;;(match-supply (demand->rule d) ctx) ;--desireable


;;Probably a good idea....
;;Do we define protocols for rules? i.e.
;;(defprotocol IRule
;;  (as-rule [obj]))

;;#TODO - we can incorporate quantity into the rules too...


;;match-supply could actually incorporate some sophisticated pattern
;;matching logic from core.match, fyi.  Maybe later....We can always extend...
(defn match-supply 
 ([rule features ctx]  
    (query/find-supply (-> rule (rule->criteria) (assoc :collect-by features)) ctx))
 ([rule ctx] (query/find-supply (rule->criteria rule) ctx)))




