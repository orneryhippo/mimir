(ns mimir.match
  (:use [clojure.set :only (intersection map-invert rename-keys difference union join)]
        [clojure.tools.logging :only (debug info warn error spy)]
        [clojure.walk :only (postwalk prewalk walk postwalk-replace)])
  (:import [java.util.regex Pattern]))

(defn filter-walk
  [pred coll]
  (let [acc (transient [])]
    (postwalk #(when (pred %) (conj! acc %)) coll)
    (distinct (persistent! acc))))

(def ^:dynamic *match-var?* symbol?)

(def ^:dynamic *var-symbol* symbol)

(defn bind-vars [x pattern acc]
  (if-let [var (if (*match-var?* pattern)
                 pattern
                 (-> pattern meta :tag))]
    (assoc acc var x)
    acc))

(defn preserve-meta [form meta]
  (list 'with-meta form (list 'quote meta)))

(defn meta-walk [form]
  (if-let [m (meta form)]
    (preserve-meta (walk meta-walk identity form) m)
    (if (*match-var?* form)
      (list 'quote form)
      (walk meta-walk identity form))))

(defn bound-vars [x]
  (let [vars (transient [])
        var-walk (fn this [form]
                   (when-let [v (-> form meta :tag)]
                     (when (*match-var?* v)
                       (conj! vars v)))
                   form)]
    (prewalk var-walk x)
    (distinct (persistent! vars))))

(defn regex-vars [x]
  (let [vars (transient [])
        regex-walk (fn this [form]
                     (when (instance? Pattern form)
                       (reduce conj! vars
                               (map (comp symbol second)
                                    (re-seq #"\(\?<(.+?)>.*?\)" (str form)))))
                     form)]
    (postwalk regex-walk x)
    (distinct (persistent! vars))))

(defn match*
  ([x pattern] (match* x pattern {}))
  ([x pattern acc]
     (condp some [pattern]
       *match-var?* (assoc acc pattern x)
       (partial
        instance?
        Pattern) (let [re (re-matcher pattern (str x))
                       groups (regex-vars pattern)]
                   (when (.matches re)
                     (reduce #(assoc % (*var-symbol* %2)
                                     (.group re (str %2)))
                             acc groups)))
        (partial
         instance?
         Class) (when (instance? pattern x)
                  acc)
         fn? (when (pattern x)
               (bind-vars x pattern acc))
         set? (loop [[k & ks] (seq pattern)
                     acc acc]
                (when k
                  (if-let [acc (match* x k acc)]
                    (bind-vars x pattern acc)
                    (recur ks acc))))
         map? (when (map? x)
                (loop [[k & ks] (keys pattern)
                       acc acc]
                  (if-not k
                    (bind-vars x pattern acc)
                    (when-let [acc (match* (x k) (pattern k) acc)]
                      (recur ks (bind-vars (x k) (pattern k) acc))))))
         sequential? (when (sequential? x)
                       (loop [[p & ps] pattern
                              [y & ys] x
                              acc acc]
                         (if-not p
                           (bind-vars x pattern acc)
                           (if (= '& p)
                             (when-let [rst (when y (vec (cons y ys)))]
                               (when-let [acc (match* rst (repeat (count rst)
                                                                  (first ps)) acc)]
                                 (bind-vars rst (first ps) acc)))
                             (when-let [acc (match* y p acc)]
                               (recur ps ys (bind-vars y p acc)))))))
         #{x} acc
         nil)))

(defn prepare-matcher [m]
  (postwalk-replace
   {'_ identity '& (list 'quote '&)}
   (preserve-meta (walk identity meta-walk m) (meta m))))

(defmacro match [x m]
  `(match* ~x ~(prepare-matcher m)))

(defn all-vars [lhs]
  (vec (concat (filter-walk *match-var?* lhs)
               (bound-vars lhs)
               (map *var-symbol* (regex-vars lhs)))))

(defmacro condm* [match-var [lhs rhs & ms]]
  `(if-let [{:syms ~(all-vars lhs)}
            (mimir.match/match ~match-var ~lhs)]
     ~rhs
     ~(when ms
        `(condm* ~match-var ~ms))))

(defmacro condm [x & ms]
  (let [match-var (if-let [v (-> x meta :tag)] v '*match*)]
    `(let [~match-var ~(with-meta x {})]
       (condm* ~match-var ~ms))))

(defn single-arg? [ms]
  (not-any? coll? (take-nth 2 ms)))

(defmacro fm [& ms]
  `(fn ~'this [& ~'args]
     (condm (if ~(single-arg? ms) (first ~'args) ~'args) ~@ms)))

(defmacro defm [name & ms]
  (let [[doc ms] (split-with string? ms)]
    `(do
       (defn ~name [& ~'args]
         (condm (if ~(single-arg? ms) (first ~'args) ~'args) ~@ms))
       (when '~doc
         (alter-meta! (var ~name) merge {:doc (apply str '~doc)}))
       ~name)))