(require 'clojure.edn)
(def aot-order (let [f (clojure.java.io/file "order.edn")]
                 (if (.exists f)
                   (clojure.edn/read-string (slurp "order.edn"))
                   '[marathon.main])))
(def version "4.1.1")
(def capsule-name "marathon")
(def capsule-jar (str version capsule-name ".jar"))
(def artifact (str version "-SNAPSHOT"))

(defproject marathon ~artifact
  :description "An Integrated Suite of Rotational Analysis Tools."
  :dependencies [[org.clojure/clojure "1.8.0"]
;                 [org.clojure.contrib/standalone "1.3.0-alpha4"]
                 [spork "0.2.0.6-SNAPSHOT"]
                 [proc  "0.2.3-SNAPSHOT"] ;;post processing.
                 ;;external libs
;                 [com.taoensso/nippy "2.11.0-RC1"] ;temporarily added to tes serialization.
                 ;;temporarily added to explore possible uses of inference...
                 [datascript "0.15.0"] 
                 [org.clojure/core.logic "0.8.10"]
                 [joinr/nightclub "0.0.1-SNAPSHOT"]
                 [alembic "0.3.2"]
                 ]
  :jvm-opts ^:replace ["-Xmx4g" #_"-Xmx1000m" "-XX:NewSize=200m"]
  :source-paths ["src" "../spork/src" "../nightclub/src" "../proc/src"]
  :profiles {:uberjar {:aot  [marathon.main]
                       :main  marathon.main
                       :jvm-opts ^:replace ["-Xmx1000m" "-XX:NewSize=200m" "-server"]
                       }
             :aot {:main  marathon.main
                   :aot ~aot-order}}
  :plugins [[lein-capsule "0.2.1"]]
  ;;; Capsule plugin configuration section, optional
  :capsule {:application {:name    ~capsule-name
                          :version ~version} 
            :types {:fat {:name   ~capsule-jar}}
            :execution {:runtime {:jvm-args ["-Xmx4g"]}
                        :boot    {:main-class  "marathon.main"}}}
  )
