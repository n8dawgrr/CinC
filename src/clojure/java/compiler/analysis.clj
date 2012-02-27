(in-ns 'clojure.java.compiler)

(def ^:private prims
  {"byte" Byte/TYPE "bool" Boolean/TYPE "char" Character/TYPE "int" Integer/TYPE "long" Long/TYPE "float" Float/TYPE "double" Double/TYPE})

(defmulti maybe-class class)
(defmethod maybe-class java.lang.Class [c] c)
(defmethod maybe-class java.lang.String [s]
  (if-let [ret (prims s)]
    ret
    (if-let [ret (maybe-class (symbol s))]
      ret
      (try
        (RT/classForName s)
        (catch Exception e nil)))))
(defmethod maybe-class clojure.lang.Symbol [sym]
  (when-not (namespace sym)
    ; TODO: I have no idea what this used to do
    ;    (if(Util.equals(sym,COMPILE_STUB_SYM.get()))
    ;    return (Class) COMPILE_STUB_CLASS.get();
    (let [ret (resolve sym)]
      (when (class? ret)
        ret))))

(defn- primitive? [o]
  (let [c (maybe-class o)]
    (and
      (not (or (nil? c) (= c Void/TYPE)))
      (.isPrimitive c))))

(defn- asm-type [s]
  (when s
    (let [class (maybe-class s)]
      (if class
        (Type/getType class)
        (Type/getType s)))))

(defn- asm-method [nm return-type & args]
  (Method. (str nm) (asm-type return-type) (into-array Type (map asm-type args))))

(defn- var! [sym]
  (RT/var (namespace sym) (name sym)))

(defn dynamic? [v]
  (or (:dynamic (meta v))
      (when-let [var (cond
                       (symbol? v) (resolve v)
                       (var? v) v)]
        (.isDynamic var))))

(defmulti expression-type
  "Returns the type of the ast node provided, or Object if unknown. Respects :tag metadata"
  :op )

(defmethod expression-type :default [{tag :tag}]
  (if tag tag java.lang.Object))

(defmethod expression-type :constant [ast]
  [ast]
  (let [class (-> ast :form class)
        unboxed (:unboxed ast)]
    (condp = class
             java.lang.Integer (if unboxed Long/TYPE Long)
             java.lang.Long (if unboxed Long/TYPE Long)
             java.lang.Float (if unboxed Double/TYPE Double)
             java.lang.Double (if unboxed Double/TYPE Double)
             clojure.lang.Keyword clojure.lang.Keyword
             nil nil
             java.lang.Object)))


;; ---


(defn- rprintln [args]
  (println "---" args)
  args)

(defn- node? [form] (:op form))

(defn- walk-node [f form]
  (let [walk-child
        (fn [child]
          (if (node? child) (f child) child))
        walk-children
        (fn [[key child]]
          (when-let [new-child (if-let [s (and (sequential? child) (seq child))]
            (into (empty child) (map walk-child s))
            (walk-child child))]
            [key new-child]))]
    (into {} (map walk-children (seq form)))))


(defn- map-children [f form]
  (let [walk-children
          (fn [child]
            (if-let [s (and (sequential? child) (seq child))]
              (into [] (map f s))
              [(f child)]))]
    (reduce into [] (map walk-children (vals form)))))

(defn ast-processor
  [pres posts]
  (let [pre  (apply comp pres)
        post (apply comp posts)]
    (fn this [form]
             (let [form (pre form)
                   form (walk-node this form)]
               (post form)))))

(defmulti set-unbox :op)

(defmethod set-unbox :default
  [{:as form :keys [unbox op]}]
  (walk-node #(assoc % :unbox (or unbox (= op :let))) form))

(defmulti exported (fn [attribute form] (:op form)))

(defmethod exported :default
  [attribute form]
  (attribute form))

(defmethod exported :fn
  [_ _]
  #{})

(defn- collect
  [attribute form]
  (->> form
    (map-children (partial exported attribute))
    (reduce into #{})
    (assoc form attribute)))

(defmulti collect-constants :op)
(defmethod collect-constants :default
  [form]
  (collect :constants form))

(defmethod collect-constants :constant
  [form]
  (assoc form :constants #{{:value (:form form) :type (expression-type form)}}))


(defmulti collect-callsites :op)
(defmethod collect-callsites :default
  [form]
  (collect :callsites form))

(defmethod collect-callsites :invoke
  [form]
  (let [s (-> form :f :info :name )]
    (if (-> s var! meta :protocol )
      (assoc form :callsites #{s})
      form)))

(defmulti collect-vars :op)
(defmethod collect-vars :default
  [form]
  (collect :vars form))

(defmethod collect-vars :var
  [{:as form :keys [info env]}]
  (let [sym (:name info)
        lb (-> env :locals sym)
        v (clojure.analyzer/resolve-var env sym)]
    (when-not (:name v)
      (throw (Util/runtimeException (str "No such var: " sym))))
    (if-not lb
      (assoc form :vars #{sym})
      form)))

(defmethod collect-vars :def
  [form]
  (assoc form :vars #{(:name form)}))


(def process-frames (ast-processor [set-unbox] [collect-constants collect-vars collect-callsites]))
