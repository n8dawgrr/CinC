(ns clojure.java.compiler
  (:refer-clojure :exclude [eval load munge *ns* type])
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:use [clojure
          [analyzer :only [analyze namespaces *ns*]]
          [walk :only [walk]]
          [reflect :only [type-reflect]]
          [set :only [select]]
          [pprint]]) ; pprint is for debugging
  (:import [org.objectweb.asm Type Opcodes ClassReader ClassWriter]
           [org.objectweb.asm.commons Method GeneratorAdapter]
           [org.objectweb.asm.util CheckClassAdapter TraceClassVisitor]
           [clojure.lang DynamicClassLoader RT Util]))

(clojure.core/load "compiler/analysis")

(def max-positional-arity 20)

(def ^:private char-map
  {\- "_",
   \: "_COLON_",
   \+ "_PLUS_",
   \> "_GT_",
   \< "_LT_",
   \= "_EQ_",
   \~ "_TILDE_",
   \! "_BANG_",
   \@ "_CIRCA_",
   \# "_SHARP_",
   \' "_SINGLEQUOTE_",
   \" "_DOUBLEQUOTE_",
   \% "_PERCENT_",
   \^ "_CARET_",
   \& "_AMPERSAND_",
   \* "_STAR_",
   \| "_BAR_",
   \{ "_LBRACE_",
   \} "_RBRACE_",
   \[ "_LBRACK_",
   \] "_RBRACK_",
   \/ "_SLASH_",
   \\ "_BSLASH_",
   \? "_QMARK_",
   \. "_DOT_"})

(defn munge [s]
  (symbol (apply str (map #(char-map % %) (str s)))))

(defn- notsup [& args]
  (throw (Util/runtimeException (apply str "Unsupported: " args))))

(def ^:dynamic ^:private *frame*) ; Contains per-class information
(defn- new-frame [] (atom {}))

(def ^:dynamic ^:private ^GeneratorAdapter *gen* nil) ; Current GeneratorAdapter to emit to

(def ^:dynamic ^:private ^DynamicClassLoader *loader* (DynamicClassLoader.))

(def ^:private object-type (asm-type java.lang.Object))
(def ^:private class-type (asm-type java.lang.Class))

(def ^:private ifn-type (asm-type clojure.lang.IFn))
(def ^:private afn-type (asm-type clojure.lang.AFn))
(def ^:private var-type (asm-type clojure.lang.Var))
(def ^:private symbol-type (asm-type clojure.lang.Symbol))
(def ^:private keyword-type (asm-type clojure.lang.Keyword))
(def ^:private rt-type (asm-type clojure.lang.RT))
(def ^:private util-type (asm-type clojure.lang.Util))

(def ^:private arg-types (into-array (for [i (range max-positional-arity)]
                                       (into-array Type (repeat i object-type)))))

(def ^:private constructor-method (Method/getMethod "void <init> ()"))
(def ^:private var-get-method (Method/getMethod "Object get()"))
(def ^:private var-get-raw-method (Method/getMethod "Object getRawRoot()"))

(def ^:dynamic *trace-bytecode* false)
(def ^:dynamic *check-bytecode* false)

(defmulti ^:private emit-boxed :op )
(defmulti ^:private emit-unboxed :op )

(defn ^:private emit [ast]
  (if false #_(:unboxed ast)
    (emit-unboxed ast)
    (emit-boxed ast)))

(defn load-class [name bytecode form]
  (let [binary-name (.replace name \/ \.)]
    (when (or *trace-bytecode* *check-bytecode*)
      (let [cr (ClassReader. bytecode)
            w (java.io.PrintWriter. (if *trace-bytecode* *out* (java.io.StringWriter.)))
            v (TraceClassVisitor. w)
            v (if *check-bytecode* (CheckClassAdapter. v) v)]
        (.accept cr v 0)))
    (.defineClass *loader* binary-name bytecode form)))

(declare emit-class emit-statement)

(defn eval
  ([form & {:keys [trace check]}]
   (binding [*trace-bytecode* trace
             *check-bytecode* check]
     (eval form)))
  ([form]
   (binding [*loader* (DynamicClassLoader.)]
     (let [env {:ns (@namespaces *ns*) :context :statement :locals {}}
           ast (analyze env form)
           ast (process-frames ast)
           internal-name (str "repl/Temp" (RT/nextID))
           cw (emit-class internal-name (assoc ast :super "clojure/lang/AFn") emit-statement)]
       (let [bytecode (.toByteArray cw)
             class (load-class internal-name bytecode form)
             instance (.newInstance class)]
         (instance))))))

(defn load [f]
  (let [res (or (io/resource f) (io/as-url (io/as-file f)))]
    (assert res (str "Can't find " f " in classpath"))
    (binding [*file* (.getPath res)]
      (with-open [r (io/reader res)]
        (let [env {:ns (@namespaces *ns*) :context :statement :locals {}}
              pbr (clojure.lang.LineNumberingPushbackReader. r)
              eof (java.lang.Object.)]
          (loop [r (read pbr false eof false)]
            (let [env (assoc env :ns (@namespaces *ns*))]
              (when-not (identical? eof r)
                (eval r)
                (recur
                  (read pbr false eof false))))))))))

(defn- emit-vars [cv {:keys [vars env]}]
  (doseq [v vars]
    (let [name (str (munge v))
          var (var! v)]
      (.visitField cv
        (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_STATIC) name (.getDescriptor var-type) nil nil)
      (swap! *frame* assoc-in [:fields var] {:name name :type clojure.lang.Var :value v}))))

(defn- emit-constants [cv {constants :constants}]
  (dorun
    (map-indexed
      (fn [i v]
        (when-not (nil? (:value v))
          (let [name (str "CONSTANT" i)]
            (.visitField cv (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_STATIC)
              name
              (-> v :type asm-type .getDescriptor)
              nil
              nil)
            (swap! *frame* assoc-in [:fields (:value v)] (assoc v :name name)))))
      constants)))

(defn- emit-proto-callsites [cv {:keys [callsites env]}]
  (dorun (map-indexed
           (fn [i site]
             (let [cached-class (str "__cached_class__" i)
                   cached-proto-fn (str "__cached_proto_fn__" i)
                   cached-proto-impl (str "__cached_proto_impl__" i)]
               (.visitField cv (+ Opcodes/ACC_PRIVATE) cached-class (.getDescriptor class-type) nil nil)
               (.visitField cv (+ Opcodes/ACC_PRIVATE) cached-proto-fn (.getDescriptor afn-type) nil nil)
               (.visitField cv (+ Opcodes/ACC_PRIVATE) cached-proto-impl (.getDescriptor ifn-type) nil nil)
               (swap! *frame* assoc-in [:protos site]
                              {:cached-class cached-class :cached-proto-fn cached-proto-fn :cached-proto-impl cached-proto-impl})))
           callsites)))

(defmulti ^:private emit-value :type)

(defmethod emit-value java.lang.Long [{v :value}]
  (.push *gen* (long v))
  (.box *gen* (asm-type Long/TYPE)))

(defmethod emit-value Long/TYPE [{v :value}]
  (.push *gen* (long v)))

(defmethod emit-value clojure.lang.Symbol [{v :value}]
  (.push *gen* (namespace v))
  (.push *gen* (name v))
  (.invokeStatic *gen* symbol-type (Method/getMethod "clojure.lang.Symbol intern(String,String)")))

(defmethod emit-value clojure.lang.Var [{v :value}]
  (.push *gen* (namespace v))
  (.push *gen* (name v))
  (.invokeStatic *gen* rt-type (Method/getMethod "clojure.lang.Var var(String,String)")))

(defmethod emit-value clojure.lang.Keyword [{v :value}]
  (.push *gen* ^String (namespace v))
  (.push *gen* (name v))
  (.invokeStatic *gen* rt-type (Method/getMethod "clojure.lang.Keyword keyword(String,String)")))

(defn- emit-list-as-array [list]
  (.push *gen* (int (count list)))
  (.newArray *gen* object-type)
  (dorun
    (map-indexed
      (fn [i item]
        (.dup *gen*)
        (.push *gen* (int i))
        (emit-value {:value item :type (class item)})
        (.arrayStore *gen* object-type))
      list)))

(defmethod emit-value clojure.lang.IPersistentMap [{v :value}]
  (emit-list-as-array (reduce into [] v))
  (.invokeStatic *gen* rt-type (Method/getMethod "clojure.lang.IPersistentMap map(Object[])")))

(defmethod emit-value :default [ast]
  (notsup "Don't know how to emit value: " ast))

(defn- emit-constructors [cv ast]
  (let [ctor (GeneratorAdapter. Opcodes/ACC_PUBLIC constructor-method nil nil cv)]
    (.loadThis ctor)
    (.invokeConstructor ctor (asm-type (:super ast)) constructor-method)
    (.returnValue ctor)
    (.endMethod ctor))
  (let [m (Method/getMethod "void <clinit> ()")
        line (-> ast :env :line )
        {:keys [class fields]} @*frame*]
    (binding [*gen* (GeneratorAdapter. Opcodes/ACC_PUBLIC m nil nil cv)]
      (.visitCode *gen*)
      (when line
        (.visitLineNumber *gen* line (.mark *gen*)))
      (doseq [[v field] fields]
        (emit-value field)
;        (.checkCast *gen* (:type field))
        (.putStatic *gen* class (:name field) (asm-type (:type field))))
      (.returnValue *gen*)
      (.endMethod *gen*))))

(defn- debug-info [name source-path line]
  (str "SMAP\n"
    name ".java\n"
    "Clojure\n"
    "*S Clojure\n"
    "*F\n"
    "+ 1 " name "\n"
    source-path "\n"
    "*L\n"
    line
    "*E"))

(defn- emit-class [internal-name ast emit-methods]
  (binding [*frame* (new-frame)]
    (let [cw (ClassWriter. ClassWriter/COMPUTE_MAXS)]
      (swap! *frame* merge ast {:class (Type/getType internal-name)})
      (doto cw
        (.visit Opcodes/V1_5 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER) internal-name nil (:super ast) nil)
        ;                (.visitSource internal-name (debug-info internal-name "NO_SOURCE_PATH" (-> ast :env :line)))
        (.visitSource internal-name nil)
        (emit-vars ast)
        (emit-constants ast)
        (emit-proto-callsites ast)
        (emit-constructors ast)
        (emit-methods ast)
        .visitEnd)
      cw)))

(defn- emit-statement [cv ast]
  (binding [*gen* (GeneratorAdapter. Opcodes/ACC_PUBLIC (Method/getMethod "Object invoke()") nil nil cv)]
    (.visitCode *gen*)
    (emit ast)
    (.returnValue *gen*)
    (.endMethod *gen*)))

(defn- emit-args-and-call [args first-to-emit]
  ; TODO: Handle (> (count args) max-positional-arity)
  (doseq [arg (nthrest args first-to-emit)]
    (emit arg))
  ; Java clojure calls method.emitClearLocals here, but it does nothing?
  (.invokeInterface *gen* ifn-type (Method. "invoke" object-type (get arg-types (count args)))))

(defmethod expression-type :default [{tag :tag}]
  (if tag tag java.lang.Object))

(defmulti convertible? (fn [t1 t2] [t1 t2]))
(defmethod convertible? :default [t1 t2]
  (if (= t1 t2) true (println "Conversion not implemented: " [t1 t2])))

(defmulti emit-convert (fn [t e] [t (class e)]))
(defmethod emit-convert :default [t e]
  (if (= t (class e)) (emit e) (println "Conversion not implemented")))

(defn- emit-unchecked-cast [t p]
  (let [m
        (cond
          (= t Integer/TYPE) (Method/getMethod "int uncheckedIntCast(Object)")
          (= t Float/TYPE) (Method/getMethod "float uncheckedFloatCast(Object)")
          (= t Double/TYPE) (Method/getMethod "double uncheckedDoubleCast(Object)")
          (= t Long/TYPE) (Method/getMethod "long uncheckedLongCast(Object)")
          (= t Byte/TYPE) (Method/getMethod "byte uncheckedByteCast(Object)")
          (= t Short/TYPE) (Method/getMethod "short uncheckedShortCast(Object)"))]
    (.invokeStatic *gen* rt-type m)))

(defn- emit-checked-cast [t p]
  (let [m
        (cond
          (= t Integer/TYPE) (Method/getMethod "int intCast(Object)")
          (= t Float/TYPE) (Method/getMethod "float floatCast(Object)")
          (= t Double/TYPE) (Method/getMethod "double doubleCast(Object)")
          (= t Long/TYPE) (Method/getMethod "long longCast(Object)")
          (= t Byte/TYPE) (Method/getMethod "byte byteCast(Object)")
          (= t Short/TYPE) (Method/getMethod "short shortCast(Object)"))]
    (.invokeStatic *gen* rt-type m)))

(defn- emit-cast-arg [t p]
  (if (primitive? t)
    (cond
      (= t Boolean/TYPE)
      (do
        (.checkCast *gen* Type/BOOLEAN_TYPE)
        (.invokeVirtual *gen* Type/BOOLEAN_TYPE (Method/getMethod "boolean booleanValue()")))

      (= t Character/TYPE)
      (do
        (.checkCast *gen* Type/CHAR_TYPE)
        (.invokeVirtual *gen* Type/CHAR_TYPE (Method/getMethod "char charValue()")))

      :else
      (do
        (.checkCast *gen* (asm-type java.lang.Number))
        (if *unchecked-math*
          (emit-unchecked-cast t p)
          (emit-checked-cast t p))))))

(defn- emit-typed-arg [param-type arg]
  (cond
    (= param-type (expression-type arg))
    (emit arg)

    (convertible? (expression-type arg) param-type)
    (emit-convert param-type arg)

    :else
    (do
      (emit arg)
      (emit-cast-arg param-type arg))))

(defn- emit-typed-args [param-types args]
  (doall (map emit-typed-arg param-types args)))

(defn- emit-box-return [type]
  (when (primitive? type)
    (.box *gen* (asm-type type))))

(defn- emit-invoke-proto [{:keys [f args]}]
  (let [{:keys [class fields protos]} @*frame*
        on-label (.newLabel *gen*)
        call-label (.newLabel *gen*)
        end-label (.newLabel *gen*)
        fsym (-> f :info :name )
        fvar (var! fsym)
        proto @(-> fvar meta :protocol)
        e (get args 0)
        protocol-on (maybe-class (:on proto))]
    ; load the first arg, so we can see its type
    (emit e)
    (.dup *gen*)
    (.invokeStatic *gen* (asm-type clojure.lang.Util) (Method/getMethod "Class classOf(Object)"))
    (.loadThis *gen*)
    (.getField *gen* class (-> protos fsym :cached-class ) class-type) ; target,class,cached-class
    ; Check if first arg type is same as cached
    (.visitJumpInsn *gen* Opcodes/IF_ACMPEQ call-label) ; target
    (when protocol-on
      (.dup *gen*) ; target, target
      (.instanceOf *gen* (asm-type protocol-on))
      (.ifZCmp *gen* GeneratorAdapter/NE on-label))
    (.dup *gen*) ; target, target
    (.invokeStatic *gen* (asm-type clojure.lang.Util) (Method/getMethod "Class classOf(Object)")) ; target, class
    (.loadThis *gen*)
    (.swap *gen*)
    (.putField *gen* class (-> protos fsym :cached-class) class-type) ; target

    ; Slow path through proto-fn
    (.mark *gen* call-label) ; target
    (.getStatic *gen* class (-> fvar fields :name) var-type)
    (.invokeVirtual *gen* var-type var-get-raw-method) ; target, proto-fn
    (.swap *gen*)
    (emit-args-and-call args 1)
    (.goTo *gen* end-label)

    ; Fast path through interface
    (.mark *gen* on-label)
    (when protocol-on
      (let [mmap (:method-map proto)
            key (or (-> fsym name keyword mmap)
                    (throw (IllegalArgumentException.
                      (str "No method of interface: " protocol-on
                        " found for function: " fsym " of protocol: " (-> fvar meta :protocol )
                        " (The protocol method may have been defined before and removed.)"))))
            meth-name (-> key name munge)
            members (-> protocol-on type-reflect :members )
            methods (select #(and (= (:name %) meth-name) (= (dec (count args)) (-> % :parameter-types count))) members)
            _ (when-not (= (count methods) 1)
                (throw (IllegalArgumentException.
                  (str "No single method: " meth-name " of interface: " protocol-on
                    " found for function: " fsym " of protocol: " (-> fvar meta :protocol )))))
            meth (first methods)]
        (emit-typed-args (:parameter-types meth) (rest args))
        (when (= (-> f :info :env :context ) :return )
          ; emit-clear-locals
          (println "Clear locals")
          )
        (let [r (:return-type meth)
              m (apply asm-method (:name meth) r (:parameter-types meth))]
          (.invokeInterface *gen* (asm-type protocol-on) m)
          (emit-box-return r))
        (.mark *gen* end-label)))))

(defn- emit-invoke-fn [{:keys [f args env]}]
  (emit f)
  (.checkCast *gen* ifn-type)
  (emit-args-and-call args 0))

(defmethod emit-boxed :invoke [ast]
  (.visitLineNumber *gen* (-> ast :env :line ) (.mark *gen*))
  (if (:protocol (meta (-> ast :f :info :name var!)))
    (emit-invoke-proto ast)
    (emit-invoke-fn ast)))

(defn- emit-var [v]
  (let [var (var! v)
        {:keys [class fields]} @*frame*]
    (.getStatic *gen* class (:name (fields var)) var-type)
    (.invokeVirtual *gen* var-type (if (dynamic? var) var-get-method var-get-raw-method))))

(defn- emit-local [v]
  (let [lb (-> @*frame* :locals v)]
    (.loadLocal *gen* (:index lb))))

(defmethod emit-boxed :var [{:keys [info env]}]
  (let [v (:name info)]
    (if (namespace v)
      (emit-var v)
      (emit-local v))))

(defmulti emit-test (fn emit-test-dispatch [ast null-label false-label] (:op ast)))

(defmethod emit-test :default [ast null-label false-label]
  (emit ast)
  (doto *gen*
    .dup
    (.ifNull null-label)
    (.getStatic (asm-type java.lang.Boolean) "FALSE" (asm-type java.lang.Boolean))
    (.visitJumpInsn Opcodes/IF_ACMPEQ false-label)))

(defmethod emit-boxed :if
  [{:keys [test then else env]}]
  (let [line (:line env)
        null-label (.newLabel *gen*)
        false-label (.newLabel *gen*)
        end-label (.newLabel *gen*)]
    (.visitLineNumber *gen* line (.mark *gen*))
    (emit-test test null-label false-label)
    (emit then)
    (.goTo *gen* end-label)

    (.mark *gen* null-label)
    (.pop *gen*)

    (.mark *gen* false-label)
    (emit else)

    (.mark *gen* end-label)))

(defmethod emit-boxed :def [{:keys [name form init env doc export] :as args}]
  (let [sym (second form)
        var (var! name)
        {:keys [class fields]} @*frame*]
    (.getStatic *gen* class (:name (fields var)) var-type)
    (when (dynamic? name)
      (.push *gen* true)
      (.invokeVirtual *gen* var-type (Method/getMethod "clojure.lang.Var setDynamic(boolean)")))
    (when-let [meta (meta sym)]
      (.dup *gen*)
      (emit-value {:value meta :type clojure.lang.IPersistentMap})
      (.checkCast *gen* (asm-type clojure.lang.IPersistentMap))
      (.invokeVirtual *gen* var-type (Method/getMethod "void setMeta(clojure.lang.IPersistentMap)")))
    (when init
      (.dup *gen*)
      (emit init)
      (.invokeVirtual *gen* var-type (Method/getMethod "void bindRoot(Object)")))))

(defn- emit-constant [t v]
  (if (nil? v)
    (.visitInsn *gen* Opcodes/ACONST_NULL)
    (let [{:keys [class fields]} @*frame*
        {:keys [name type]} (fields v)
        type (asm-type type)]
      (.getStatic *gen* class name type)
      (.box *gen* type))))

(defmethod emit-boxed :constant [{:keys [form env]}]
  (emit-constant java.lang.Object form))

(defmethod emit-unboxed :constant [{:keys [form env]}]
  (emit-constant (expression-type form) form))

(defn emit-fn-method [cv {:keys [name params statements ret env recurs] :as ast}]
  (binding [*gen* (GeneratorAdapter. Opcodes/ACC_PUBLIC (apply asm-method "invoke" "Object" (map expression-type params)) nil nil cv)]
    (.visitCode *gen*)
    (when recurs (notsup "recurs"))
    (when statements
      (dorun (map emit statements)))
    (emit ret)
    (.returnValue *gen*)
    (.endMethod *gen*)))

(defn- emit-fns [cv {:keys [name env methods max-fixed-arity variadic recur-frames] :as ast}]
  (let [loop-locals (seq (mapcat :names (filter #(and % @(:flag %)) recur-frames)))]
    (when loop-locals
      (notsup "loop-locals"))
    (doseq [meth methods]
      (if (:variadic meth)
        (notsup '(emit-variadic-fn-method meth))
        (emit-fn-method cv meth)))
    (when loop-locals
      (notsup "loop-locals"))))

(defmethod emit-boxed :fn [ast]
  (let [name (str (or (:name ast) (gensym)))
        cw (emit-class name (assoc ast :super "clojure/lang/AFn") emit-fns)
        bytecode (.toByteArray cw)
        class (load-class name bytecode ast)
        type (asm-type class)]
    (.newInstance *gen* type)
    (.dup *gen*)
    (.invokeConstructor *gen* type constructor-method)))

(defmethod emit-boxed :let [{:keys [bindings statements ret env loop]}]
  (let [bs
        (into {}
          (map
            (fn [{:keys [name init]}]
              (emit init)
              (let [type (expression-type init)
                    i (.newLocal *gen* (asm-type type))]
                (.storeLocal *gen* i)
                [name {:index i :type type :label (.mark *gen*)}]))
            bindings))]
  (swap! *frame* assoc :locals bs)
  (when statements
    (dorun (map emit statements)))
  (emit ret)
  (let [end-label (.mark *gen*)]
    (doseq [[name binding] bs]
      (.visitLocalVariable *gen* (str name) (-> binding :type asm-type .getDescriptor) nil (:label binding) end-label (:index binding))))))

(defn- emit-field
  [env target field box-result]
  (let [class (expression-type target)
        members (-> class type-reflect :members)
        field-info (select #(= (:name %) field) members)
        type (:type field-info)]
  (if type
    (do
      (.getField *gen* (asm-type class) field (asm-type type))
      (when box-result (emit-box-return type)))
    (do
      (when *warn-on-reflection*
        (.format (RT/errPrintWriter)
          "Reflection warning, %s:%d - reference to field %s can't be resolved.\n"
          *file* (make-array (-> target :env :line) field)))
      (.push *gen* (name field))
      (.invokeStatic *gen* (asm-type clojure.lang.Reflector)
                           (Method/getMethod "Object invokeNoArgInstanceMember(Object,String)"))))))

(defn- match [name args]
  (fn match-method [method]
    (and (= name (:name method))
         (= (count args) (-> method :parameter-types count))
         (every? true? (map convertible? args (:parameter-types method))))))

(defn- emit-method-call [target name args]
  (let [class (expression-type target)
        members (-> class type-reflect :members )
        methods (select (match name args) members)
        _ (when-not (= (count methods) 1)
            (throw (IllegalArgumentException.
              (str "No single method: " name " of class: " class " founds with args: " args))))
        meth (first methods)]
  (emit-typed-args (:parameter-types meth) (rest args))
  (let [r (:return-type meth)
        m (apply asm-method (:name meth) r (:parameter-types meth))]
    (.invokeVirtual *gen* (asm-type class) m)
    (emit-box-return r))))

(defmethod emit-boxed :dot
  [{:keys [target field method args env]}]
  (emit target)
  (if field
    (emit-field env target field true)
    (emit-method-call target method args)))


(defmethod emit-boxed :default [args] (throw (Util/runtimeException (str "Unknown operator: " (:op args) "\nForm: " args))))