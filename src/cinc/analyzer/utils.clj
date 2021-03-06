(ns cinc.analyzer.utils
  (:import (clojure.lang IRecord IType IObj
                         IReference Var)
           java.util.regex.Pattern))

(defmacro update! [target f & args]
  (list 'set! target (list* f target args)))

(defn ctx
  "Returns a copy of the passe environment with :context set to ctx"
  [env ctx]
  (assoc env :context ctx))

(defn record? [x]
  (instance? IRecord x))
(defn type? [x]
  (instance? IType x))
(defn obj? [x]
  (instance? IObj x))
(defn reference? [x]
  (instance? IReference x))
(defn regex? [x]
  (instance? Pattern x))
(defn boolean? [x]
  (or (true? x) (false? x)))

(defn classify
  "Returns a keyword describing the form type"
  [form]
  (cond
   (nil? form)     :nil
   (boolean? form) :bool
   (keyword? form) :keyword
   (symbol? form)  :symbol
   (string? form)  :string
   (number? form)  :number
   (type? form)    :type
   (record? form)  :record
   (map? form)     :map
   (vector? form)  :vector
   (set? form)     :set
   (seq? form)     :seq
   (char? form)    :char
   (regex? form)   :regex
   (class? form)   :class
   (var? form)     :var
   :else           :unknown))

(defn private? [var]
  (:private (meta var)))
(defn macro? [var]
  (:macro (meta var)))
(defn constant? [var]
  (:const (meta var)))
(defn dynamic? [var]
  (or (:dynamic (meta var))
      (.isDynamic ^Var var)))
(defn protocol-node? [var]
  (boolean (:protocol (meta var))))

(defn resolve-ns [ns]
  (when ns
    (or (find-ns ns)
        ((ns-aliases *ns*) ns))))

(defn resolve-var
  ([sym] (resolve-var sym false))
  ([sym allow-macro?]
     (let [name (-> sym name symbol)
           ns (when-let [ns (namespace sym)]
                (symbol ns))
           full-ns (resolve-ns ns)]
       (when (or (not ns)
                 (and ns full-ns))
         (if-let [var (if full-ns
                        ((ns-interns full-ns) name)
                        ((ns-map *ns*) name))]
           (when (var? var)
             (let [var-ns (.ns ^Var var)]
               #_(when (and (not= *ns* var-ns)
                            (private? var))
                   (throw (ex-info (str "var: " sym " is not public") {:var sym})))
               (if (and (macro? var) (not allow-macro?))
                 (throw (ex-info (str "can't take value of a macro: " var) {:var var}))
                 var)))
           (when full-ns
             (throw (ex-info (str "no such var: " sym) {:var sym}))))))))

(defn maybe-var [sym]
  (try (resolve-var sym true)
       (catch Exception _)))

;; should also use :variadic? and :max-fixed-arity
(defn arglist-for-arity [fn argc]
  (let [arglists (->> fn :arglists (sort-by count)) ;; :init :arglists when vars won't map to Vars
        arglist (->> arglists (filter #(= argc (count %))) first)
        last-arglist (last arglists)]
    (or arglist
        (when (and (seq (filter '#{&} last-arglist))
                   (>= argc (- (count last-arglist) 2)))
          last-arglist))))
