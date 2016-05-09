;;Deploying entities requires operating on multiple systems.  This namespace
;;builds on the services provided by supply, demand, and policy, to coordinate 
;;changes to the simulation context necessary to physically allocate supply to 
;;demand - or to execute deployments.  
;;Primarily used by __marathon.sim.fill__ .
(ns marathon.ces.deployment
  (:require [marathon.demand [demanddata :as d]]
            [marathon.supply [unitdata :as udata]]
            [marathon.ces    [core :as core] [demand :as dem] 
                             [policy :as policy] [supply :as supply] 
                             [unit :as u]]
            [marathon.data   [protocols :as protocols]]
            [spork.sim       [simcontext :as sim]]
            [spork.util      [tags :as tag]]))

;;#Functions for Deploying Supply
(defn get-max-bog [unit policystore]
  (let [bog-remaining (udata/unit-bog-budget unit)
        p             (policy/get-policy    (-> unit :policy :name) policystore)]
    (- bog-remaining  (protocols/overlap p))))

;;These seem like lower level concerns.....
;;Can we push this down to the unit entity behavior?
;;Let that hold more of the complexity?  The unit can be responsible
;;for the bulk of the implementation detail of what a
;;deployment entails...
;;Since units have access to the simulation context, like
;;every other system, they could apply all the updating necessary.
;;Now, we move the updates into a behavior function, where
;;we can more efficiently handle the state updates...
;;For instance, we can perform bulk updates with the same
;;or a simular behavior context.....this is more appealing.
(defn deploy!  [followon? unit demand t ctx]
  (let [supply (core/get-supplystore ctx)]
      (if followon?
          (->> ctx
               (supply/record-followon supply unit demand)
               (u/re-deploy-unit unit t (or (:deployment-index unit) 0)))
          (u/deploy-unit unit t (or (:deployment-index unit) 0) ctx))))

(defn check-first-deployer!   [store unitname ctx]
  (let [unit (supply/get-unit store unitname)]  
    (if (supply/first-deployment? unit store)
      (->> (core/set-supplystore ctx (supply/tag-as-deployed unit store))
           (supply/first-deployment! store unit)
           (supply/adjust-max-utilization! store unit)))))

;;TODO# fix bog arg here, we may not need it.  Also drop the followon?
;;arg, at least make it non-variadic..
(defn deploy-unit
  "Deploys a unit entity, registered in supply, at time t, to demand.  The 
   expected length of stay, bog, will determine when the next update is 
   scheduled for the unit.  Propogates logging information about the context 
   of the deployment."
  ([ctx unit t demand   followon?]
    (assert  (u/valid-deployer? unit) 
             "Unit is not a valid deployer! Must have bogbudget > 0, 
     cycletime in deployable window, or be eligible or a followon  deployment")
    (core/with-simstate [[supplystore parameters policystore demandstore fillstore] ctx]
      (let [
            fillcount     (count (:fills fillstore))
            bog           (get-max-bog unit policystore) 
            unitname      (:name unit)
            demandname    (:name demand)
            from-location (:locationname unit) ;may be extraneous
            from-position (:position-policy unit);
            to-location   demandname           
            to-position   :deployed
            unit-delta    {:position-policy to-position
                           :dwell-time-when-deployed (udata/get-dwell unit)}
            unit          (merge unit ;MOVE THIS TO A SEPARATE FUNCTION? 
                                     unit-delta)
            demand        (d/assign demand unit) ;need to update this in ctx..          
            supplystore   (assoc supplystore :tags  (supply/drop-fence (:tags supplystore)
                                                                       (:name unit)))
            ]  
        (->> (sim/merge-entity {unitname unit-delta
                                :DemandStore (dem/add-demand demandstore demand)
                                ;:SupplyStore (supply/add-unit supplystore unit)
                                } ctx)
             (u/change-location unit (:name demand)) 
             (deploy! followon?  unit demand t)  ;;apply state changes.             
             ))))  
  ([ctx unit t demand ]
    (deploy-unit  ctx unit t demand  (core/followon? unit))))

(defn deploy-units [ctx us d]
  (let [t (core/get-time ctx)
        ]
    (reduce (fn [acc u]
                (deploy-unit acc u t d)) ctx us)))
(comment   
           
           
           (u/deploy-unit unit t (supply/get-next-deploymentid supplystore))
           (check-first-deployer! supplystore unitname) ;THIS MAY BE OBVIATED.
           (supply/update-deployability unit false false) 
           (supply/log-deployment! t from-location demand unit fillcount filldata 
                                   deploydate (policy/find-period t policystore))
           (supply/supply-update! supplystore unit nil))
