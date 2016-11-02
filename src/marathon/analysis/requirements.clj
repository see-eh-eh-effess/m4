;;Requirements Analysis implementation.
(ns marathon.analysis.requirements
  (:require [spork.util [record :as r] [table :as tbl]]
            [marathon.ces [core :as core]]
            [marathon [analysis :as a]]))

;;Utility functions
;;=================
(defmacro get-or [m k & else]
  `(if-let [res# (get ~m ~k)]
     res#
     ~@else))

;;this isn't a huge deal; multiplying by ratios returns bigints...
(defn distribute-rationally [n xs] (mapv (fn [r] (* n r)) xs))
(defn sums-to-one?   [xs] (== 1.0 (double (reduce + xs))))
;;useful utility function...
(defn sum-by [f init xs]
  (transduce (map f) (completing +) init xs))

;;useful function for spork.util.table todo: move there...
(defn apply-schema
  "Given schema s and table t, attempts to coerce the table entries into 
   the appropriate data format for all fields defined in s.  Coerces 
   field entries to strings prior to conversion, so there may be a 
   penalty.  Typically most useful working with string-ified data, 
   such as a literal table.  Fieldnames must match exactly, i.e.  
   string fieldnames will not match keyword schema names."
  [s t]
  (let [parse-field (fn [fld col]
                      (if-let [f (spork.util.parsing/parse-defaults  (get-or s fld))]
                        [fld (mapv (comp f str) col)]
                        [fld col]))]
    (spork.util.table/order-fields-by (spork.util.table/table-fields t)
        (->  (map parse-field
                  (spork.util.table/table-fields  t)
                  (spork.util.table/table-columns t))           
             (spork.util.table/conj-fields spork.util.table/empty-table)))))

;;Data munging and records
;;========================

;;If we use records, we get ordered fields automatically.  Better strategy.
;;generic supply record.  Should probably tie these to marathon.schemas
(defrecord srecord [Type Enabled Quantity SRC Component OITitle
                    Name Behavior CycleTime Policy Tags Spawntime Location Position Original])
;;outstreaam/mystream is output....canonically RequirementsGeneratedSupply.csv
;;may not use this guy...we'll see.
(r/defrecord+ outrecord [[Iteration 0]
                         [SRC ""]
                         [Component ""]
                         [Quantity   0]])

;;generate a new supplyrecord.
(defn ->supply-record
  ([src component count]
   (->srecord "SupplyRecord" true count src component (str "Generated_"  src)
              "Auto" "Auto" 0 "Auto" "Auto" 0 "Auto" "Auto" false))
  ([src component] (->srecord src component 0)))


;;Distributors
;;============
;;distributors need to be implemented....
;;default is binned, provides deterministic
;;round-robin growth.
;;Note: We can also have deterministic growth
;;using a seeded prng...

;;kind of lame at the moment
;;should return a map of [compo val]
(defprotocol IDistributor
  (distribute- [obj n]))  

(defn distribute-by [f n]
  (let [tf (type f)]
    (cond (extends? IDistributor tf)
            (distribute- f n)
          (extends? clojure.core.protocols/IKVReduce tf)
            (reduce-kv (fn [acc k prop]
                         (assoc acc k (* prop n))) {} f)
          (fn? f) (f n)
          :else
          (throw (Exception. (str "unknown distributor!"))))))

;;Basic Algorithm
;;===============
;;Requirements analysis is the process of calculating some required additional
;;supply in the face of a given demand signal.  Technically, requirements
;;analysis is indifferent to the existence of supply.  We can calculate a
;;requirement regardless of pre-existing supply.  In fact, we can grow a
;;requirement.

;;Traditional requirements analysis consisted of a fixed-point function:
;;- RequiredSupply(SupplyInitial) = SupplyInitial + Generated(SupplyInitial, Demand)
;;  - Do until SupplyNext = SupplyInitial
;;    - SupplyInitial <- SupplyNext
;;    - SupplyNext <- Distribute(RequiredSupply(SupplyInitial), SupplyInitial)

;;Estimating Bounds
;;=================
;;We will use DemandAnalysis and Supply Analysis to help the convergence go
;;much faster. As a pre-process step, we utilize Static Demand Analysis to
;;determine the peak demands and the maximum deployment acceleration
;;experienced during the run.
;;- This provides definite constraints on our minimum required supply.
;;  - ANY supply must have a theoretical capacity >= the peak demand.

;;We then utilize Static Supply Analysis to determine the theoretical
;;rotational capacity for the initial supply.  (If we have no supply,
;;capacity is nil). Prior to starting the convergence algorithm, we
;;distribute this intial gap in supply.

;;Then run until convergence.
    ;;Should take less time.

;;Note -> Requirements analysis does not utilize substitutions.
;;- This is a pure 1:1 demand to supply.
;;- This makes the problem essentially much easier (and faster).

;;It also makes calculating requirements even faster and more effecient,
;;because we can parallelize the entire analysis into a set of n
;;independent simulations, which only considers the supply and demand of
;;a single type.


;;Changes from VBA
;;What we'll do instead of having file i/o driving this, is we'll
;;derive any necessary i/o from the sequence we compute as we converge.
;;Basically, using reductions, we'll derive the supply table each iteration.

;;oh, we need to be able to generate supply...
;;Well, we can delegate that to RA....
;;Instead of creating "supply generators"
;;that are complicated, we can add additional
;;supply after-the-fact, and then fill again with
;;the next context.  So, basically, add-ghosts if
;;there are missed demands.

(comment ;testing
  (def root "C:/Users/tspoon/Documents/srm/tst/notionalv2/reqbase.xlsx")
  (require '[marathon.analysis [dummydata :as data]])
  (def dummy-table
    (apply-schema (marathon.schemas/get-schema :SupplyRecords)
                  (tbl/keywordize-field-names (tbl/records->table  data/dummy-supply-records))))
  (defn zero-field [t k]
    (let [n (tbl/count-rows t)]
      (tbl/conj-field  [k (vec (repeat n 0))] t)))
  (defn zero-supply [t]
    (zero-field t :Quantity))
)

;;Growing Supply
;;==============
;;note:
;;the act of "creating supply" from a supply record
;;transforms a serialized form into a sequence of instructions
;;that create entities and schedule initial behavior, like spawning.
;;So, one way to view it is that the record maps to a sequence of
;;instructions, which yield entities.

;;We're basically altering the initial supply via supply records....
;;The reason we do so is because the system is setup to initialize
;;the context from raw tables, so changing the data creates
;;new sim contexts automagically.

;;As we look at how to vary supply programmatically, rather than
;;"just" varying the table, we could look into varying the
;;context directly....

;;reads distributions from a table of [src ac rc ng]
;;probably a better way to do this...legacy implementation
;;We can also provide a way to compute this empirically right?
(defn compute-aggregate-supply
  "Given a table of supply-records, computes a sequence of 
   records by src, compo, summing by the Quantity field."
  [xs]
  (let [quantities (fn quantities [xs]
                     (sum-by :Quantity 0 xs))]
    (->> (for [[src src-groups] (group-by :SRC       xs)]
           (let [compo-xs       (group-by :Component src-groups)]
             {:Type "GhostProportionsAggregateRecord"
              :Enabled true :SRC src :AC (quantities  (get compo-xs "AC"))
              :NG (quantities (get compo-xs "NG"))
              :RC (quantities (get compo-xs "RC"))})))))

(defn aggregate-proportions
  "Computes proportional values for each record in xs, by component, 
   where the associated component value is a proportion of the sum of 
   component values.  Retains rational precision by default."
  [xs & {:keys [float?]}]
  (let [divide (if float? (fn [l r] (double (/ l r)))
               / )]
    (into [] (map (fn [{:keys [AC NG RC] :as r}]
                    (let [sum (+ AC NG RC)]
                      (merge r {:AC (divide AC sum)
                                :NG (divide NG sum)
                                :RC (divide RC sum)}))))
        xs)))
                   
(defn aggregate-distributions
  "Given a map of tables, computes the aggregate supply proportions  
   from the input.  User may specify alternate tables to use for the 
   proportions.  If no table is found, will compute the proportions 
   from the input supply."
  [tbls & {:keys [distribution-table dtype float?]
           :or   {distribution-table :GhostProportionsAggregate
                  dtype :bin}}]
  (let [make-distributor (fn [n] (fn [k] (* k n)))] 
    (->> (get-or tbls distribution-table
                 (->> (:SupplyRecords tbls)                                           
                      (tbl/table-records)
                      (filter :Enabled)
                      (compute-aggregate-supply)
                      (aggregate-proportions)))       
         (reduce (fn [acc r]
                   (let [{:keys [SRC AC RC NG]} r]
                     (assoc acc SRC {"AC" AC "RC" RC "NG" NG})))
                 {}))))

;;fill this in...
;;probably need some state.
;;TOM Change 19 April 2012
;;For an src, use our local distributor to compute how x ghosts should be converted into
;;[X,Y,Z..] units of supply, by component
;;Note: renamed 'ns to 'steps.  Embeding stuff in a requirements state
;;map.
;;we have the supply records in reqstate/supply
;;goal is to update the quantities incrementally.
(defn increment-supply [reqstate src compo n]
  (update-in reqstate [:supply src compo]
             (fn [r] (update r :Quantity
                         (fn [q] (+ q n))))))

(defn compute-amounts [reqstate src n]
  (distribute-by (get reqstate src) n))

;;Replacement method for an earlier hack.  We now separate the process of calculating and applying
;;distributions.  Given a set of distributions, by component, apply them (whatever that means)
;;to the src.
(defn apply-amounts [reqstate src amounts]
  (reduce (fn [acc [compo n]]
            (if n
              (let [adjusted-count (double n)]
                (if (pos? adjusted-count)
                  (increment-supply acc src compo adjusted-count)
                  acc))
              acc)) reqstate amounts))

;;So, each time we add supply, we conceptually take a growth step.
(defn distribute [reqstate src count]
  (let [amounts (compute-amounts reqstate src count)
        steps   (or (:steps reqstate) [])
        total   (if (empty? steps) 0
                    (:total-ghosts (last steps)))]
    (-> reqstate
        (apply-amounts src amounts) 
        (assoc  :steps ;;record the step we took.
           (conj steps {:src   src
                        :count count
                        :total-ghosts (+ total count)
                        :added amounts
                        :total  (throw (Exception. "copysupply"))})
           ))))

;;I think we'll have this as part of the reqstate...
;;rather than a separate output. Redo this...
(defn write-record
  [reqstate iteration src component quantity]
  (update-in reqstate  [:iterations src] conj
      (->outrecord iteration src component quantity)))

;;This is an auxillary function to handle each run of the requirements analysis.
;;Given a simulation that is already primed and loaded, and possibly an initial supply of units, calculate the
;;units needed (the requirement), as represented by the amount of ghosts created by SRC.  The requirement is then
;;applied to the distributor, which transforms the homogeneous supply of ghosts into a set of units that are to be added
;;to the final result. The return is a dictionary of (SRC|Component, count) pairs.  This allows us to trivially update
;;the supplytable, by incrementing.

;;The idea here is to just layer on another fill step,
;;if there are unmet demands, we allow ghosts to be created and
;;used to fill.  This is different than the scheme in vba, where
;;we had "supply generators" that were a little squirrely.  We'll
;;just move to a multipass fill, and use a ghost-fill function as
;;the last stage.  Easy peasy.
(defn unconstrained-ghost-step [ctx]
  (throw (Exception. (str "placeholder for stepping with ghost-fills.")))
  )

;;probably want to stick this in marathon.analysis...
;;Given a history, compute the maximum amount of ghosts
;;(high-water mark) over time.  We should be able to
;;determine this easily by selecting entities with a
;;"Ghost" component at the end of the simulation.
(defn history->ghosts [h]
  (throw (Exception. (str "placeholder for computing ghosts"))))

;;I think we'll prefer to work with the history, so probably using a marathon-stream
;;instead of this approach...
;;TODO: Replace event-step-marathon with the appropriate simreducer or whatnot.
;;Returns the next requirement state, if we actually have a requirement.
;;Otherwise nil.
(defn calculate-requirements [{:keys [ctx src ns] :as reqstate}
                              & {:keys [step-function distance]
                                 :or   {step-function unconstrained-ghost-step
                                        distance history->ghosts}}]
  (let [dist (-> ctx (step-function) (distance))]
    (when (pos? distance)
      (do (println "Generated ghosts on iteration")
          (distribute reqstate src dist)))))


;;Compute a sequence of "empty" supply records
;;from the proportions indicated 
(defn proportion-record->supply-records [r]
  (let [src (:SRC r)
        compos [:AC :RC :NG]]
    (for [c compos
          :let [n (get r c)]
          :when (pos? n)]
      (->supply-record src c n))))
  

;;do we actually need a ghost watcher?
;;We have ghost-specific events, like ghost spawn and the
;;like.  May not need to scrape them..
;;returns a map of {src src-max}, typically
;;looks for a ghost-watcher observer...
(defn get-ghost-counts [ctx] )

;;The iterative convergence function is a fixed-point function that implements the algorithm described in the declarations section.
;;During iterative convergence, we don;;t care about intermediate results, only the final fixed-point calculation.
;;After we determine the fixed-point, we can perform a final dynamic analysis (capacity analysis) on the output.
;;The concrete implementation follows:
    ;;Assuming we have a simulation object, we can call its FromExcel method to load all demand and supply from Excel.
    ;;Future iterations will avoid re-parsing demand and supply, but for now, we;;ll just reload the whole thing everytime.
        ;;Possibly use a "limited-reload? or hot-load" method.
    ;;Each time the simulation runs, it will load supply from the SupplyRecords input (which is a worksheet).
    ;;Our goal is to effectively generate supply records.
    ;;In the extreme case, we start with no supply, thus no supply records.
    ;;We must have ghost relation rules in effect (i.e. ghostables for all the SRCs), and a set of demands to simulate.
    ;;Prior to the first iteration, we attach a special observer to the simulation;;s event pump.
        ;;This observer, the GhostWatcher, will maintain statistics for all the ghosts spawned, by SRC, etc. during the simulation.
        ;;The GhostWatcher will serve as the GhostRecord generator that we will need in our distribution function.
    
    ;;On the first iteration, the simulation is initialized "fromexcel", which pulls in all supply, demand, policy, etc.
        ;;Note, again, there may be no supply.
    
    ;;We then pass the primed simulation, a valid distribution function, and the ghostwatcher, into the CalculateRequirement function.
        ;;The simulation is run in a non-interactive mode, with no supply, which triggers the generation of ghosts.
            ;;We want each run to be as fast as possible, thus we run the sim in its most effecient state, avoiding log files
            ;;and other detritus.
            
            ;;The externally-attached Ghost Watcher observes all of these ghost spawning events, noting the SRC count of ghosts
            ;;generated during the run.
            
        ;;CalculateRequirement;;s return value is simply a function of the application of the distributor to the observerd quantities of ghosts, by SRC,
        ;;in the ghostwatcher.  This should be a number of units, by SRC, by component.
    
    ;;Given a set of new units, we simply update the supply records data (worksheet), possibly recording the amount of supply added during each
        ;;iteration.
    
    ;;Given a set of no new units (i.e. zero ghosts generated), iterative convergence returns the reported supply.  The data is already on-hand for
    ;;additional analysis (namely capacity analyis), if desired.

;;creates a "lightweight" context
;;for requirements analysis, so we can
;;go faster.  Basically, drop any observers that
;;aren't useful.
(defn requirements-ctx [tbls]
  ;;we'll basically do the same thing we normally do.
  ;;For now at least...
  ;;use init-context here, but 
  )

;;Note: we need a higher-order function that wraps
;;performing RA for multiple srcs...
;;I think the chain of causality is...
;;Given an SRC, some tables,
;;  Filter the supply,demand,policy records...
;;  Compute requirements (however, typically iterative convergence)
;;    ;;Create a simcontext from filtered tables.
;;This gives us the mapping of src->requirements
;;really, src->supply-records

(defn initial-supply [compo-distros]
  (throw (Exception. (str "not implemented"))))

;;we'll explicitly pass in SRC as a filter for now.
;;It'd be really nice to share the context once we've
;;created it....although policies and stuff might change...
;;there's a bit of overhead in the policy stuff, so it's
;;easier to move from ctx->ctx than otherwise...
;;For now, we'll just suck it up. And redo every time,
;;see how expensive it ends up being.
(defn ->requirements-state
  "Given a map of tables (a marathon project), 
   creates a map that contains all of the state 
   we'll need for our requirements analysis."
  [tables src compo-distros]
  (let [ctx0 (requirements-ctx tables src)
        s    (initial-supply compo-distros)]
    {:ctx ctx0 ;initial simulation context.
     :supply s  ;initial set of supply records.
     :distributions compo-distros
     :steps []
     :iteration 0}))

(defn calculate-requirement [reqstate]
  (throw (Exception. (str "not implemented"))))

(defn interative-convergence
  "Given a requirements-state, searches the force structure 
   space by varying the supply of the requirements, until 
   it converges on a minimum feasible force structure.
   At the low end, we'll just be performing multiple 
   capacity analyses..."
  [{:keys [ctx supply distributions] :as reqstate}]
  ;;we need afucntion to reset the engine.
  ;;Since we're using immutable data, it's easier...
  ;;We don't have to reset.
  (loop [iteration 0
         reqs      reqstate]
    (if-let [res (calculate-requirement reqs)] ;;naive growth.
      (recur (inc iteration)
             res)
      reqs)))

;;Given a set of demand data, the theoretical proportion of supply by component, and policy schedules,
;;calculate the supply required by SRC.
(defn compute-requirements [ctx init-supply-table proportions]
  (let [supply-table init-supply-table
        distributions {}]
    ))


;; Do
;;     Set ghosts = sim.outputmanager.observers("Ghosts")
;;     Iteration = Iteration + 1
    
;;     If Not CalculateRequirement(sim, ghosts, , ns) Then
;;         Debug.Print "No More ghosts to generate!"
;;         Exit Do
;;     Else
;;         tstrt = Timer() - tstrt
        
;;         If noio Then 'don't bother writing to the sheet
;;             sim.Reset_Engine_FromExcel True, supplyTable 'this will reset marathon, using the GeneratedSupply Worksheet to pull in initial supply.
;;         Else
;;             updateGeneratedSupply
;;             sim.Reset_Engine_FromExcel True
;;         End If
        
;;         If logevents Then
;;             Set logger = Nothing
;;             Set logger = New TimeStep_ObserverLogFile
;;             logger.init "ReqEvents" & Iteration, sim.EventManager.evtstream
;;         End If
;;     End If
;; Loop


;; If squeeze Then 'Make sure we've found the optimal using bisection
;;     If ns.count > 1 Then 'need to handle this corner case.
;;         Bisect sim, ns, ns(ns.count - 1), ns(ns.count), Iteration
;;     ElseIf ns.count = 1 Then
;;         Bisect sim, ns, zeroSupply(ns), ns(ns.count), Iteration
;;     End If
;; End If

;; If noio Then finalIO sim

;; updateGeneratedSupply

;; Set logger = Nothing
;; Set sim = Nothing

;; End Sub



;; Private Function zeroSupply(ns As Collection) As Dictionary
;; Set zeroSupply = copyDict(ns(1))
;; End Function

(defn requirements-search [reqs]
  (throw (Exception. (str "not implemented"))))

(defn tables->requirements
  "Given a database of distributions, and the required tables for a marathon 
   project, computes a sequence of [src {compo requirement}] for each src."
  [tbls & {:keys [dtype search] :or {search requirements-search
                                     dtype :proportional}}]
  (let [;;note: we can also derive aggd based on supplyrecords, we look for a table for now.
        distros (aggregate-distributions tbls :dtype dtype) 
        srcs    (keys distros)]
    (for [[src compo->distros] srcs]
      (let [_ (println [:computing-requirements src])
            src-filter (a/filter-srcs [src])
            src-tables (src-filter tbls) ;alters SupplyRecords, DemandRecords
            reqstate  (->requirements-state src-tables ;create the searchstate.
                                             src compo->distros)
            ]
        [src (search reqstate)]))))

;; 'TOM Change 3 August -> implemented a bracketing algorithm not unlike binary search.
;; 'This is meant to be performed on a single SRC, i.e. a single independent requirement.
;; 'Bisection requires an src as the arguement.
;; Public Sub Bisect(sim As TimeStep_Engine, ns As Collection, left As Dictionary, right As Dictionary, Iteration As Long)

;; Dim searchstate As Collection
;; Dim middle As Long
;; Dim lower As Long
;; Dim upper As Long
;; Dim src As String
;; Dim lowest As Long
;; Dim uppermoved As Boolean
;; Dim idx As Long, bin As Long
;; Dim binstate As Dictionary

;; Dim ghosts As TimeStep_ObserverGhostWatch

;; Set searchstate = New Collection

;; lower = left.item("totalghosts")
;; lowest = lower
;; upper = right.item("totalghosts")
;; middle = lower + (upper - lower) \ 2

;; src = left("src")
   
;; 'determine what the next step should be

;; While upper - lower > 1
;;     Iteration = Iteration + 1
;;     Debug.Print "Iteration " & Iteration & ", Bracketing solution between n = [" & lower & ", " & upper & "] ghosts."

;;     Set supplyTable = copysupply(left("total")) 'starting from our last step
;;     Distribute src, middle - lowest, , searchstate  'add our ghosts.
    
;;     If noio Then 'don't bother writing to the sheet
;;         sim.Reset_Engine_FromExcel True, supplyTable 'this will reset marathon, using the GeneratedSupply Worksheet to pull in initial supply.
;;     Else
;;         updateGeneratedSupply
;;         sim.Reset_Engine_FromExcel True
;;     End If

;;     Set ghosts = sim.outputmanager.observers("Ghosts")


;;     'test sufficiency with new supply
;;     If Not CalculateRequirement(sim, ghosts, , searchstate) Then
;;         'did not generate ghosts.....
;;         'this means our middle value is now our right, upperbound.
;;         upper = middle 'move the bracket <<<<<<-
;;         middle = lower + (upper - lower) \ 2
;;         'no need to redistribute.
;;         uppermoved = True
;;     Else
;;         'we added ghosts, which means middle is insufficient.
;;         lower = middle 'move the bracket ->>>>>>>
;;         middle = lower + (upper - lower) \ 2
;;         'Set left = searchstate(searchstate.count)
;;         uppermoved = False
;;     End If
;; Wend

;; If upper - lower = 1 Then 'ubound is the answer
;;     lower = upper
;;     Set supplyTable = copysupply(left("total")) 'starting from our last step
;;     Distribute src, upper - lowest, , searchstate  'add our ghosts.
;; ElseIf upper - lower = 2 Then 'middle is the answer
;;      If uppermoved Then
;;         lower = lower + 1
;;      Else
;;         lower = upper
;;      End If
;;      Distribute src, upper - lowest, , searchstate
;; Else
;;     Err.Raise 101, , "convergence is off"
;; End If

;; Debug.Print "No More ghosts to generate.  Binary search converged on " & lower & " Ghosts for src " & src

;; If noio Then finalIO sim

;; updateGeneratedSupply

;; Set sim = Nothing

;; End Sub





;; Private Sub makeghost()

;; Dim grecord As GenericRecord
;; Set grecord = New GenericRecord

;; With grecord
;;     .AddField "Type", "SupplyRecord"
;;     .AddField "Enabled", True
;;     .AddField "Quantity", 1
;;     .AddField "SRC", "Ghost"
;;     .AddField "Component", "Ghost"
;;     .AddField "OITitle", "Anything"
;;     .AddField "Name", "Auto"
;;     .AddField "Behavior", "Ghost365_45"
;;     .AddField "CycleTime", 0
;;     .AddField "Policy", "Ghost365_45"
;;     .AddField "Tags", "Auto"
;;     .AddField "SpawnTime", 0
;;     .AddField "Location", "Auto"
;;     .AddField "Position", "Auto"
;;     .AddField "Original", False
;; End With

;; supplyTable.add "Ghost", grecord

;; Debug.Print "Asked to do requirements analysis without a ghost," & _
;;             " added Default ghost record to generated supply table."
;; End Sub


;;GeneratedSupply.csv  is out default output, apparently.
;;Don't think we really need these...

;;Distributors
;;============
;;I think we're going to skip this and just define a
;;distribute function..
  
;; Private Function addDistributor(src As String, compoDistributions As Dictionary, Optional dtype As DistributorType)
;; Dim distributor As Dynamic_Distributors
;; Dim compo

;; For Each compo In compoDistributions
;;     If compoDistributions(compo) = 0 Then
;;         compoDistributions.Remove (compo)
;;     End If
;; Next compo

;; Set distributor = New Dynamic_Distributors
;; Select Case dtype
;;     Case binned
;;         distributor.initBinned compoDistributions, src
;;         SRCdistributors.add src, distributor
;;     Case continuous1418
;;         distributor.initContinuous1418 compoDistributions
;;         SRCdistributors.add src, distributor
;;     Case rounding1418
;;         distributor.initRounding1418 compoDistributions
;;         SRCdistributors.add src, distributor
;; End Select

;; End Function

;;note: we need to filter only positive compodistriutions.

;;multimethod for constructing different distributors.
(defmulti distributor identity)
(defmethod distributor :binned [n] nil)
(defmethod distributor :continuous1418 [kw] nil)
(defmethod distributor :rounding1418 [kw] nil)



;; ''TOM Change 3 August -> implemented a bracketing algorithm not unlike binary search.
;; ''This is meant to be performed on a single SRC, i.e. a single independent requirement.
;; ''Bisection requires an src as the arguement.
;; Public Sub BisectionConvergence(Optional logevents As Boolean, Optional addcapacity As Boolean)

;; Dim tstrt As Single
;; Dim logger As TimeStep_ObserverLogFile
;; Dim ghosts As TimeStep_ObserverGhostWatch
;; Dim max As Long, min As Long
;; Dim nextX As Long
;; Dim generatedGhosts As Boolean
;; Dim fX As Long
;; Dim bracketed As Boolean
;; Dim src As String
;; Dim ky

;; Iteration = 0
;; importSupplyRecords
;; importAggregateDistributions
;; Err.Raise 101, , "Needs updating!"

;; If sim Is Nothing Then
;;     Set sim = New TimeStep_Engine
;;     sim.noio = noio
;;     sim.Initialize_Engine_FromExcel New TimeStep_SimState, True 'this will cause overhead....
;; Else
;;     sim.noio = noio
;;     sim.Reset_Engine_FromExcel
;; End If


;; If logevents Then
;;     Set logger = New TimeStep_ObserverLogFile
;;     logger.init "ReqEvents" & Iteration, sim.EventManager.evtstream
;; End If

;; For Each ky In sim.DemandManager.demandmap
;;     src = sim.DemandManager.demandmap.item(ky).src
;;     If src <> "Ghost" Then Exit For
;; Next ky

;; If src = "Ghost" Then Err.Raise 101, , "Only registered ghost srcs"


;; tstrt = Timer()

;; max = 10
;; min = 0
;; fX = max
;; nextX = max
;; bracketed = False

;; While max <> min
;;     Set ghosts = sim.outputmanager.observers("Ghosts")
;;     Iteration = Iteration + 1
;;     'determine what the next step should be
;;     Debug.Print "Searching for solution using " & nextX & " ghosts."
;;     fX = search(src, nextX, sim, ghosts)

;;     If fX = 0 Then
;;         bracketed = True
;;         max = nextX
;;         nextX = (max - min) \ 2 + min
;;     ElseIf fX > 0 Then
;;         If bracketed Then
;;             min = max
;;             max = 2 * max
;;             nextX = (max - min) \ 2
;;         Else
;;             If fX > max Then max = max + fX
;;             'ElseIf fX < max Then
;;                 'max = fX
;;             'End If
;;             min = max
;;             max = 2 * max
;;             nextX = max
;;         End If
;;     End If

;;     tstrt = Timer() - tstrt

;;     If noio Then 'don't bother writing to the sheet
;;         sim.Reset_Engine_FromExcel True, supplyTable 'this will reset marathon, using the GeneratedSupply Worksheet to pull in initial supply.
;;     Else
;;         updateGeneratedSupply
;;         sim.Reset_Engine_FromExcel True
;;     End If

;;     If logevents Then
;;         Set logger = Nothing
;;         Set logger = New TimeStep_ObserverLogFile
;;         logger.init "ReqEvents" & Iteration, sim.EventManager.evtstream
;;     End If
;; Wend

;; Debug.Print "No More ghosts to generate.  Binary search converged on " & max & " Ghosts for src " & src

;; If noio Then finalIO sim

;; updateGeneratedSupply

;; Set logger = Nothing
;; Set sim = Nothing

;; End Sub

;; 'write out the final, summary report, specifically the final supply, the number of ghosts, etc.
;; Private Sub finalIO(sim As TimeStep_Engine, Optional addcapacity As Boolean)

;; updateGeneratedSupply
;; If addcapacity Then
;;     sim.noio = False
;;     sim.Reset_Engine_FromExcel True
;;     sim.EventStepMarathon
;; End If


;; End Sub

;; Public Function search(src As String, ghostcount As Long, sim As TimeStep_Engine, ghostwatcher As TimeStep_ObserverGhostWatch) As Long
;; Static counts As Dictionary
;; search = 0
;; Distribute src, ghostcount, True 'update the ghost solution
;; sim.EventStepMarathon 'execute the simulation, may produce ghosts.
;; Set counts = getGhostCounts(ghostwatcher)
;; If counts.count > 0 Then
;;     Debug.Print "Generated ghosts on iteration " & Iteration
;;     search = counts(src)
;; End If
;; End Function





(comment ;;possibly obe
  ;;we add a ghost record though
  (defn make-ghost []
    (supply-record  "SupplyRecord"  true 1 "Ghost" "Ghost"
                    "Anything" "Auto"  "Ghost365_45" ;default behavior...
                    0 "Ghost365_45" "Auto"  0 "Auto" "Auto" false))

  ;;import the original provided supply records (currently from excel) into the supplytable
  (defn import-supply-records [ctx]) ;;may be uncessary.
  (defn clear-supply
    "Eliminate all the unit entities from the context."
    [ctx]
    )

  #_(defn reload-supply [ctx]
      (default-supply))

  ;;allows a nice handle on 
  #_(defn load-variable-supply-context [tbls]
      (fn [supply-records]))
)
