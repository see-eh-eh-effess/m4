;Data required to constitute a simulation environment.
(ns marathon.data.simstate
  (:use [spork.util.record :only [defrecord+]]
        [marathon.supply.supplystore]
        [marathon.demand.demandstore] 
        [marathon.policy.policystore]
        [marathon.output.outputstore]
        [marathon.events.eventstore]
        [marathon.fill.fillstore])
  (:require  [spork.sim [simcontext :as sim]]))


;;Pending Modifications:
;;=====================

;;It would be "really" nice to wrap the functions 
;;in spork.sim.simcontext in a protocol, and 
;;have simstate implement said protocol.  That'd 
;;make it much cleaner to operate on simstate...
;;The other, probably easier option, is to embed 
;;the simulation state into a simcontext, as 
;;we have already defined operations that work 
;;seamlessly on simcontext records.  All we 
;;do then is strip out the :context from the 
;;simstate, and just leave the state as pure data.
;;Then we modify function signatures (slightly) 
;;to unpack the state inside the simcontext.

;;Description
;;===========
;simstate is a consolidation of all the simulation state required for Marathon 
;to do its thing.  Each of these bits used to be part of a hierarchical object 
;model with parent-child relationships, where each child manager could get at 
;the state for another child via its parent, the simulation Engine.  This worked
;okay in the beginning, and fit with some naive object oriented programming 
;notions, but ended up leading to coupling and some other phenomena. As a result
;I've re-organized the code base to conform to a more functional-programming 
;paradigm: specifically, data is maintained separate from functionality, rather 
;than classic OOP where data is encapsulated and bundled with 
;methods/properties.  The result is a simplification of the data model, as well
;as functions that can produce and consume bits of data necessary for the 
;simulation.  This simstate object really just gathers all the data in one 
;place, so that functions that need to access multiple components of data
;simultaneously CAN.  Since it's a record, and most of the elements are also 
;records, we get the benefit of using clojure's associative map functions and 
;sequence libraries to access and modify our state, rather than having to deal
;with a special set of one-off functions.


(defrecord+ simstate 
  [[supplystore empty-supplystore];Chunk of state for unit entity data.
   [demandstore empty-demandstore];Chunk of state for demand entity data.
   [policystore empty-policystore];Chunk of state for rotational policy, and policy periods.
   [outputstore empty-outputstore];Chunk of state for output streams, file I/O.
   [parameters  {}];Chunk of state for simulation parameters, as key/val pairs.
   behaviormanager ;Possibly deprecated.  Repository for unit behaviors.
   [fillstore empty-fillstore];Chunk of state for managing Fill Rules, Fill Graph,
             ;Fill Functions, etc.
  ; [context sim/empty-context];Bread and butter for any simulation.  Contains even stream, 
           ;scheduler, and update manager. Analagous to a message-dispatch, 
           ;scheduler, and thread-manager.
   ;imported parameters from timestep_engine, to be deprecated.   
   pause  ;indicates if the simulation is currently paused, suspending simulation.
   time-start ;Wall-clock Start time of the simulation.  
   time-finish ;Wall-clock Stop time of the simulation.
   [maximum-traffic true] ;Flag to enable a debugging mode, with maximum event traffic.  
                   ;Slower and lots of I/O.
   interactive ;Indicate the presence of a linked GUI form.  
               ;Cedes control to the Form.  Maybe deprecated.
   no-io ;Forces simulation to try to suppress its I/O, particularly in output 
         ;metrics and event history.
   moderate-io ;only record the lightweight stuff, ala summaries, cyclerecords, deployments
               ;sandtrends are dumped out to csv.
   no-supply-warning ;Ignore lack of supply warnings when set.  
                     ;Necessary for requirements analysis.
   no-demand-warning ;Ignore lack of demand warnings when set.  
                     ;Necessary for supply-only analysis.
   [earlyscoping true] ;Flag that tells the preprocessor to try to eliminate 
                       ;unusable data early on.
   truncate-time     ;used for requirements analysis.  Allows us to tell 
                     ;Marathon to stop the simulation AS SOON as the last demand 
                     ;has ended.  If there are no pending demand activations
                     ;we assume that we can stop.  This assumption holds for 
                     ;requirements analysis only....    
   found-truncation]) 

;For maximum flexibility, I am going to embed the simulation state inside of 
;an entity store, which is basically a fancy database with some special 
;properties.  This hasn't happened yet, but the change to a component entity 
;system is on the horizon.

;An alternate view....instead of a record, we can view the sim state as an 
;entity store, with components, which lets us define a specification to build
;one...
(comment 
	(defentity simstate [id name]
	  [:name name 
	   :supplystore {} ;Chunk of state for unit entity data.
	   :demandstore {} ;Chunk of state for demand entity data.
	   :policystore {} ;Chunk of state for rotational policy, and policy periods.
	   :outputstore {} ;Chunk of state for output streams, file I/O.
	   :parameters  {} ;Chunk of state for simulation parameters, as key/val pairs.
	   :behaviormanager {} ;Possibly deprecated.  Repository for unit behaviors.
	   :fillstore {} ;Chunk of state for managing Fill Rules, Fill Graph,
	             ;Fill Functions, etc.
	  ; :context {} ;Bread and butter for any simulation.  Contains even stream, 
	           ;scheduler, and update manager. Analagous to a message-dispatch, 
	           ;scheduler, and thread-manager.
	   ;imported parameters from timestep_engine, to be deprecated.   
	   :pause  false;indicates if the simulation is currently paused, suspending simulation.
	   :time-start 0;Wall-clock Start time of the simulation.  
	   :time-finish 0;Wall-clock Stop time of the simulation.
	   :maximum-traffic true ;Flag to enable a debugging mode, with maximum event traffic.  
	                   ;Slower and lots of I/O.
	   :interactive ;Indicate the presence of a linked GUI form.  
	               ;Cedes control to the Form.  Maybe deprecated.
	   :no-io false ;Forces simulation to try to suppress its I/O, particularly in output 
	         ;metrics and event history.
	   :moderate-io false;only record the lightweight stuff, ala summaries, cyclerecords, deployments
	               ;sandtrends are dumped out to csv.
	   :no-supply-warning false ;Ignore lack of supply warnings when set.  
	                     ;Necessary for requirements analysis.
	   :no-demand-warning false;Ignore lack of demand warnings when set.  
	                     ;Necessary for supply-only analysis.
	   :earlyscoping true      ;Flag that tells the preprocessor to try to eliminate 
	                     ;unusable data early on.
	   :truncate-time false    ;used for requirements analysis.  Allows us to tell 
	                     ;Marathon to stop the simulation AS SOON as the last demand 
	                     ;has ended.  If there are no pending demand activations
	                     ;we assume that we can stop.  This assumption holds for 
	                     ;requirements analysis only....    
	   :found-truncation false]) ;flag the indicates a reason to truncate.
 )



