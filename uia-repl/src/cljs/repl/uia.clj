(ns cljs.repl.uia
  (:refer-clojure :exclude [loaded-libs])
  (:require [uia-repl.utils :as utils]
            [cljs.compiler :as comp]
            [cljs.analyzer :as ana]
            [clojure.java.io :as io]
            [cljs.repl :as repl])
  (:import cljs.repl.IJavaScriptEnv))

;; adapted from @bodil https://github.com/bodil/cljs-noderepl/blob/master/cljs-noderepl/src/cljs/repl/node.clj
(def loaded-libs (atom
                  #{"goog.base" "goog.deps" "cljs.core"
                    "goog.string" "goog.string.Unicode"
                    "goog.debug.Error"
                    "goog.asserts" "goog.asserts.AssertionError"
                    "goog.string.StringBuffer"
                    "goog.array" "goog.array.ArrayLike"
                    "goog.object"
                    }))

(def cmd-count (atom 0))

(defn command-path
  [env]
  (str (:command-path env) java.io.File/separator "repl-cmd.txt"))

(defn uia-setup [repl-env]
  (let [env (ana/empty-env)]
    (repl/evaluate-form repl-env env "<cljs repl>"
                        '(ns cljs.user (:require [calabash-script.core :as c])))
    (repl/evaluate-form repl-env env "<cljs repl>"
                        '(set! cljs.core/*print-fn* (fn [& args] (.output js/Log (apply str args)))))))

(defn uia-eval
  [env file line js]
  (let [{:keys [input output]} env]
    (spit (command-path env) (str @cmd-count ":" js))
    (swap! cmd-count inc)
    (let [res (.readLine input)]
      (println res)
      (read-string res))))

(defn load-javascript-files [repl-env ns url]
  (println "Loading files for " ns)
  (let [code (slurp url)]
    (if (<= (count code) 12000)
      (uia-eval repl-env ns 1 code)
      (let [splits (utils/split-at-seq (str url) code)
            lines (line-seq (io/reader (java.io.StringReader. code)))
            line-count (count lines)
            mid (quot line-count 2)
            [line chr] (->> splits
                            (filter (fn [[lineno _]] (<= lineno mid)))
                            (last))
            [before after] (split-at (dec line) lines)
            before-file (utils/create-temp-file ".js" (clojure.string/join "\n" before))
            after-file (utils/create-temp-file ".js" (clojure.string/join "\n" after))]
        (load-javascript-files repl-env (.toString before-file) before-file)
        (load-javascript-files repl-env (.toString after-file) after-file)))))

(defn load-javascript [repl-env ns url]
  (let [missing (remove #(contains? @loaded-libs %) ns)]
    (when (seq missing)
      (prn ns)
      (swap! loaded-libs (partial apply conj) missing)
      (let [code (slurp url)]
        (if (> (count code) 12000)
          (load-javascript-files repl-env ns url)
          (uia-eval repl-env (.toString url) 1 (slurp url)))))))

(defn load-resource
  "Load a JS file from the classpath into the REPL environment."
  [env filename]
  (let [resource (io/resource filename)]
    (assert resource (str "Can't find " filename " in classpath"))
    (uia-eval env filename 1 (str "(" (slurp resource) ")"))))

(defn uia-tear-down [repl-env]
  (let [process (:process repl-env)]
    (doto process
      (.destroy)
      (.waitFor))))

(defrecord UIAEnv []
  repl/IJavaScriptEnv
  (-setup [this]
    (spit (command-path this) "")
    (uia-setup this))
  (-evaluate [this filename line js]
    (uia-eval this filename line js))
  (-load [this ns url]
    (prn  "load " url)
    (load-javascript this ns url))
  (-tear-down [this]
    (uia-tear-down this)))


(defn repl-env
  "Create a UIA-based REPL environment."
  [& kwargs]
  (let [opts (apply hash-map kwargs)
        process (apply utils/launch-instruments kwargs)
        new-repl-env (merge (UIAEnv.)
                            (merge process
                                   {:optimizations :simple}))]
    new-repl-env))

(defn run-uia-repl [& kwargs]
  (let [env (apply repl-env kwargs)]
    (repl/repl env)
    env))

;:app "~/Library/Developer/Xcode/DerivedData/LPSimpleExample-dmfxejwasmrjgzgkdqccfrlwwgbm/Build/Products/Debug-iphonesimulator/LPSimpleExample-cal.app"
(defn run
  []
  (run-uia-repl
   :udid "2c2db4e68a9243523673fc026de1bf7193593ebd"
   :app "com.lesspainful.example.LPSimpleExample5"))

(defn run-sim
  []
  (run-uia-repl :app "/Users/krukow/Library/Developer/Xcode/DerivedData/LPSimpleExample-dmfxejwasmrjgzgkdqccfrlwwgbm/Build/Products/Debug-iphonesimulator/LPSimpleExample-cal.app"))
