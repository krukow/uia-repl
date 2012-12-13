(ns uia-repl.utils
  (:require [clojure.string :as string]
            [clojure.java.io :as io])
  (:import java.io.PipedReader
           java.io.PipedWriter
           (com.google.javascript.jscomp.parsing ParserRunner Config)
           com.google.javascript.jscomp.JSSourceFile
           com.google.javascript.rhino.Node))


;; adapted from @bodil https://github.com/bodil/cljs-noderepl/blob/master/cljs-noderepl/src/cljs/repl/node.clj
(defn load-as-tempfile
  "Copy a file from the classpath into a temporary file.
  Return the path to the temporary file."
  [filename]
  (let [tempfile (java.io.File/createTempFile "uia-repl" "")
        resource (io/resource filename)]
    (.deleteOnExit tempfile)
    (assert resource (str "Can't find " filename " in classpath"))
    (with-open [in (io/input-stream resource)
                out (io/output-stream tempfile)]
      (io/copy in out))
    (.getAbsolutePath tempfile)))

(defn parse-line
  [line]
  (let [line (clojure.string/replace line "*" "")
        idx (.indexOf line "{:status")]
    (if (> idx -1)
      {:output (.substring line idx)}
      {:message line})))

(defn output-filter
  "Take a reader and wrap a filter around it which swallows and
  acts on output events from the subprocess. Keep the filter
  thread running until alive-func returns false."
  [reader alive-func]
  (let [pipe (PipedWriter.)]
    (future
      (while (alive-func)
        (let [line (.readLine reader)
              data (parse-line line)]
          (if-let [msg (:message data)]
            (println (str "MESSAGE: " msg))
            (doto pipe
              (.write (str (:output data) "\n"))
              (.flush)))
          )))
    (io/reader (PipedReader. pipe))))

(defn process-alive?
  "Test if a process is still running."
  [^Process process]
  (try (.exitValue process) false
       (catch IllegalThreadStateException e true)))

(def trace-template "/Applications/Xcode.app/Contents/Applications/Instruments.app/Contents/PlugIns/AutomationInstrument.bundle/Contents/Resources/Automation.tracetemplate")


(defn split-points
  [node]
  (let [children (seq (.children node))]
    (map (fn [node]
           [(.getLineno node)
            (.getCharno node)])
         children)))

(defn split-at-seq
  [^String name ^String code]
  (let [sf (JSSourceFile/fromCode name code)
        closure-compiler (com.google.javascript.jscomp.Compiler.)
        node (.parse closure-compiler sf)
        points (split-points node)]
    points))

(defn create-temp-file
  [ext contents]
  (let [tempfile (java.io.File/createTempFile "uia-repl" ext)]
    (.deleteOnExit tempfile)
    (spit tempfile contents)
    (.getAbsolutePath tempfile)))

(defn command-path
  [opts]
  (or (:command-path opts)
      (let [f (java.io.File/createTempFile "command-path" (str (rand-int 256)))]
        (when-not (.delete f) (throw (java.io.IOException. "Unable to create temp dir")))
        (when-not (.mkdir f) (throw (java.io.IOException. "Unable to create temp dir")))
        (spit (str f java.io.File/separator "repl-cmd.txt") "")
        f)))


(defn launch-instruments
  "Launch the UIA subprocess."
  [& kwargs]
  (let [options (apply hash-map kwargs)
        udid (:udid options)
        app-id-or-path (:app options)
        command-path (command-path options)
        base (slurp (io/resource "calabash_script/base.js"))
        sexp (slurp (io/resource "cljs/repl/sexp.js"))

        uia-repl-script (clojure.string/replace
                         (slurp (io/resource "cljs/repl/uia_repl.js"))
                         "$PATH"
                         (.getAbsolutePath command-path))
        launch-script (create-temp-file ".js"
                                        (str base sexp uia-repl-script))

        instruments-udid (if udid ["-w" udid] [])
        instruments-cmd (apply conj
                               ["instruments"]
                               (conj instruments-udid
                                     "-t" trace-template
                                     app-id-or-path
                                     "-D" "run/trace"
                                     "-e" "UIARESULTSPATH" "run"
                                     "-e" "UIASCRIPT"
                                     launch-script))]

    (println instruments-cmd)
    (let [process (.start (ProcessBuilder. instruments-cmd))]
      {:input (output-filter (io/reader (.getInputStream process)) #(process-alive? process))
       :output (io/writer (.getOutputStream process))
       :command-path command-path
       :process process})))
