;;Possibly temporary namespace
;;to cordon off functions used to
;;assess "risk" of entities being
;;utilized for something.  This
;;typically shows up as our
;;red-amber-yellow-green
;;area plot or trails...
(ns marathon.assessment
  (:require [marathon.supply  [unitdata :as unit]]
            [marathon.visuals [styling :as styling]]))

;;The prototypical assessment criteria
;;really stemmed from ARFORGEN metrics.
;;We typically assess suitability (and
;;therefore color) based on a combination
;;of the unit's dwell-before-deployment
;;and its bog-time.

;;These dimensions provide us with a
;;mapping onto a 3d surface (color space)
;;that provides us with an assessment
;;criteria.

;;Typically, we have regions of the
;;surface that are red/amber/yellow/green
;;to correspond with risk criteria.

;;We'll typically re-use these assessments
;;for multiple trends.

;;A entity's assessment will change with
;;time and event....

;;For now, we're going to assess every
;;timestep.  It's fast enough.

;;Our typical output, the trails chart,
;;needs a function, assess, that maps
;;  dwell -> bog -> color
;;That's about it...
;;Alternately, if we allow a 2D
;;assessment, that is another surface,
;;we can map
;;  dwell -> non-bog -> color

;;Really, this is
;;  normalized-dwell -> bog -> color |
;;  normalized->dwell -> non-bog -> color

(def surface-coords 
  [[0 0 24 24]
   [9 11 15 7]
   [9 18 12 6]
   [12 12 12 12]])

(def surface-proportions 
  [[0.0 0.0 1.0 1.0]
   [0.375 0.4583333333333333 0.625 0.2916666666666667]
   [0.375 0.75 0.5 0.25]
   [0.5 0.5 0.5 0.5]])

(defn between?      [x l r]  (and (>= x l) (<= x r)))

;;Note: there is a difference between the color of the
;;unit's state, and its assessed risk of employment.

 ;;we could always color it based on a spectrum... 
;;hardwired for now...




;;a more parametric version
;;Really, this divides our space up into
;;3 regions, defined by overlapping
;;rectangles.  One way to look at it is,
;;we have four overlapping rectangles:
;;we check intersection for green, then
;;amber, then orange, then red.
;;We're basically doing hit tests in
;;a set order...
(defn ->assessor [red-x     red-y
                  orange-x  orange-y
                  amber-x   amber-y]
  (fn [x y]
    (cond
      (or (between? x 0 red-x)
          (> y red-y)) :red
      (and (between? x  orange-x amber-x)
           (between? y  1 orange-y)) :orange
      (and (between? x  orange-x amber-x)
           (between? y amber-y red-y)) :amber
      (and (> x red-x) (between? y amber-y red-y)) :amber
      :else :green)))

;;This approximates our assessment function for
;;ARFORGEN.
(let [f (->assessor 365        365
                    (+ 366 90) 270
                    (+ 366 90) 271)]                    
  (defn pos->color [x y]
    (cond ;(zero? y) nil
      (or  (between? x 0 365)
           (> y 365)) :red
           (and (between? x 366 (+ 366 90))  (between? y 1 270))   :orange
           (and (between? x 366 (+ 366 90))  (between? y 271 365)) :amber
           (and (> x 366)  (between? y 271 365)) :amber
           :else :green)))

;; (picc/render!
;;  (picc/->cartesian [(picc/->filled-rect :red 0 0 1095 400)                  
;;                     (picc/->filled-rect :amber  (+ 366 90)   0    (- 1095 90 366) 365)
;;                     (picc/->filled-rect :orange (+ 366 90)  0 (- 1095 366 90) 270)
;;                     (picc/->filled-rect :green  730  0 (- 1095 730) 270)]))



;;Note: we can optimize this later
;;by applying a dwell-velocity
;;to units, and a bog-velocity
(defn arforgen-assessment [ent]
  (let [dwell (unit/get-dwell ent)
        bog   (unit/get-bog   ent)]
    (pos->color dwell bog)))

;;No idea how to assess SRM at the moment...
;;we could just have the color's mirror
;;the entity's state color...
;;This will leave us with potentially
;;wierd assessments.....dunno.  Make the sponsor
;;define a better way to assess.
(defn srm-assessment [ent]
  (if-let [clr (get  styling/default-palette
                      (:positionpolicy ent))]
    clr
    (throw (Exception. (str "No known color for "
                            (:positionpolicy ent))))))

;;the interesting bit is....which assessor do we
;;use?  Does it depend on policy or behavior?

;;for now, we'll just default to assessing using
;;arforgen...
