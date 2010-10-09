(ns org.subluminal.class-file
  (:require (org.subluminal [binfmt :as bin])
            (clojure string set))
  (:use (clojure.contrib monads))
  (:import (java.io ByteArrayInputStream DataInputStream InputStream
                    ByteArrayOutputStream DataOutputStream)
           (java.lang.reflect Field Method Constructor)
           (java.nio ByteBuffer)))

(declare disasm to-symbol class-name pool-string)

;;;; Parser for descriptor and signature strings

(def parser-m (state-t maybe-m))

(defn match-char
  "Parser that matches a specified char"
  [ch]
  (fn [^String input]
    (if (= (first input) ch)
      [ch (.substring input 1)]
      nil)))

(defn match-until
  "Parser that matches until next occurrence of a terminator char.
   Consumes the terminator."
  [ch]
  (fn [^String input]
    (let [idx (.indexOf input (int ch))]
      (if (== idx -1)
        nil
        [(.substring input 0 idx) (.substring input (inc idx))]))))

(defn peek-until
  "Parser that matches until next occurrence of one of a set of chars.
   Does not consume the terminator."
  [chs]
  (fn [^String input]
    (let [res (apply str (take-while (complement chs)
                                     input))]
      [res (.substring input (count res))])))

(defn match-case
  "Parser that matches the keys in a map returning the corresponding value"
  [map]
  (fn [^String input]
    (let [ch (first input)]
      (if (contains? map ch)
        [(map ch) (.substring input 1)]
        nil))))

(defn optional [parser]
  (with-monad parser-m
    (m-plus parser (m-result nil))))

(defn match-+
  "Match a sequence of one or more instances of p"
  [p]
  (domonad parser-m
    [head p
     tail (optional (match-+ p))]
    (cons head tail)))

(defn match-*
  "Match a sequence of zero or more instances of p"
  [p]
  (with-monad parser-m
    (m-plus (match-+ p) (m-result ()))))

(declare <segments>)

(def <segments>
  (domonad parser-m
    [seg1 (peek-until #{\/ \; \< \.})
     sep (match-char \/)
     more (match-* #'<segments>)]
    (cons seg1 (first more))))

;; Parse descriptors

(declare <field-descriptor>)

(def <primitive>
  (match-case
    {\B :byte, \C :char, \D :double,
     \F :float, \I :int, \J :long,
     \S :short, \V :void, \Z :boolean}))

(def <class>
  (domonad parser-m
    [ch (match-char \L)
     name (match-until \;)]
    (to-symbol name)))

(def <array>
  (domonad parser-m
    [ch (match-char \[)
     f #'<field-descriptor>]
    (list 'array f)))

(def <field-descriptor>
  (with-monad parser-m
    (m-plus <primitive> <array> <class>)))

(def <method-descriptor>
  (domonad parser-m
    [lp (match-char \()
     args (match-* <field-descriptor>)
     rp (match-char \))
     ret <field-descriptor>]
    (list 'method ret args)))

(defn class-type-descriptor [^String cname]
  (str "L" (.replace cname \. \/) ";"))

(defn field-descriptor-string [desc]
  (cond
    (keyword? desc)
    (case desc
      :byte "B", :char "C",
      :double "D", :float "F",
      :int "I", :long "J"
      :short "S", :void "V",
      :boolean "Z")

    (symbol? desc)
    (class-type-descriptor (name desc))

    (class? desc)
    (class-type-descriptor (.getName desc))

    :else
    (let [[atag component] desc]
      (case atag :array (str "[" (field-descriptor-string component))))))

(defn method-descriptor-string [desc]
  (let [[ret args] desc]
    (str "(" (apply str (map field-descriptor-string args)) ")"
         (field-descriptor-string ret))))

(defn descriptor-string [desc]
  (if (= (first desc) :method)
    (method-descriptor-string (rest desc))
    (field-descriptor-string desc)))

;; Parse signatures

(declare <field-type-signature>
         <class-type-signature>
         <type-signature>)

(def <iface-bound>
  (domonad parser-m
    [colon (match-char \:)
     val #'<field-type-signature>]
    val))

(def <type-param>
  (domonad parser-m
    [id (match-until \:)
     class-bound (optional #'<field-type-signature>)
     iface-bound (match-* <iface-bound>)]
    (vector (to-symbol id)
            class-bound
            iface-bound)))

(def <type-params>
  (domonad parser-m
    [lp (match-char \<)
     val (match-+ <type-param>)
     rp (match-char \>)]
    val))

(def <class-signature>
  (domonad parser-m
    [type-params (optional <type-params>)
     super <class-type-signature>
     ifaces (match-* #'<class-type-signature>)]
    (list 'class
          type-params
          (list 'extends super)
          (list 'implements ifaces))))

(def <type-variable-signature>
  (domonad parser-m
    [ch (match-char \T)
     id (match-until \;)]
    (list 'var (to-symbol id))))

(def <array-type-signature>
  (domonad parser-m
    [ch (match-char \[)
     sig #'<type-signature>]
    (list 'array sig)))

(def <type-argument>
  (with-monad parser-m
    (m-plus (match-case {\* '*})
      (domonad parser-m
        [indicator (optional (match-case {\+ '+ \- '-}))
         bound #'<field-type-signature>]
        (if indicator
          (vector indicator bound)
          bound)))))

(def <type-arguments>
  (domonad parser-m
    [lp (match-char \<)
     args (match-+ <type-argument>)
     rp (match-char \>)]
    args))

(def <simple-class-type-sig>
  (domonad parser-m
    [id (peek-until #{\< \; \.})
     args (optional <type-arguments>)]
    (if args (cons (to-symbol id) args) (to-symbol id))))

(def <suffix>
  (domonad parser-m
    [ch (match-char \.)
     val <simple-class-type-sig>]
    val))

(def <class-type-signature>
  (domonad parser-m
    [ch (match-char \L)
     pkg <segments>
     top <simple-class-type-sig>
     suff (match-* <suffix>)
     endch (match-char \;)]
    (cons
      (to-symbol (clojure.string/join "/" pkg))
      (cons top suff))))

(def <field-type-signature>
  (with-monad parser-m
    (m-plus <class-type-signature>
            <array-type-signature>
            <type-variable-signature>)))

(def <type-signature>
  (with-monad parser-m
    (m-plus <field-type-signature>
            <primitive>)))

(def <throws-signature>
  (with-monad parser-m
    (domonad parser-m
      [caret (match-char \^)
       sig (m-plus <class-type-signature> <type-variable-signature>)]
      sig)))

(def <method-type-signature>
  (with-monad parser-m
    (domonad parser-m
      [types (optional <type-params>)
       lp (match-char \()
       args (match-* <type-signature>)
       rp (match-char \))
       ret <type-signature>
       throws (match-* <throws-signature>)]
      (list 'method
            types
            ret
            args
            (cons 'throws throws)))))

(def <any-signature>
  (with-monad parser-m
    (m-plus <method-type-signature> <class-signature> <field-type-signature>)))

(defn full-parse
  "Parse input string with the parser. Input must be fully consumed."
  [parser input]
  (let [[res remain :as whole] (parser input)]
    (if (or (nil? whole)
            (not (empty? remain)))
      (throw (IllegalArgumentException. (str "Cannot parse " input)))
      res)))

;;;; Class file format
;; Compare "The Java(TM) Virtual Machine Specification", chapter 4

(bin/defbinary ClassFile
  [:magic ::bin/uint32 {:constraint #(= % 0xCAFEBABE)
                        :aux 0xCAFEBABE}]
  [:minor-version ::bin/uint16]
  [:major-version ::bin/uint16 {:constraint #(<= % 50)}]
  [:constant-pool-count ::bin/uint16 {:aux (count (:pool symtab))}]
  [:constant-pool [::cp-info constant-pool-count] {:aux (:pool symtab)}]
  [:symtab ::null {:transient {:pool []}}] ; (split-pool constant-pool)
  [:flags ::bin/uint16
     {:bitmask {:public 0 :final 4 :super 5 :interface 9 :abstract 10
                :synthetic 12 :annotation 13 :enum 14}}]
  [:this-class ::bin/uint16 {:constraint #(< 0 % constant-pool-count)}]
  [:name ::null
    {:transient (to-symbol (class-name constant-pool this-class))}]

  ;; super-class is zero for java.lang.Object
  [:super-class ::bin/uint16 {:constraint #(< % constant-pool-count)}]
  [:extends ::null
    {:transient (when-not (zero? super-class)
                  (to-symbol (class-name constant-pool super-class)))}]

  [:interfaces-count ::bin/uint16 {:aux (count implements)}]
  [:interfaces ::bin/uint16 {:times interfaces-count
                             :constraint #(< 0 % constant-pool-count)}]
  [:implements ::null
    {:transient (vec (map #(to-symbol (class-name constant-pool %))
                          interfaces))}]
  [:fields-count ::bin/uint16 {:aux (count fields)}]
  [:fields [::field-info constant-pool] {:times fields-count}]
  [:methods-count ::bin/uint16 {:aux (count methods)}]
  [:methods [::method-info constant-pool] {:times methods-count}]
  [:attributes-count ::bin/uint16 {:aux (count attributes)}]
  [:attributes [::attribute-info constant-pool] {:times attributes-count}])

(bin/defbinary [field-info pool]
  [:flags ::bin/uint16
    {:bitmask {:public 0 :private 1 :protected 2
               :static 3 :final 4 :volatile 6 :transient 7
               :synthctic 12 :enum 14}}]
  [:name-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
  [:name ::null {:transient (symbol (pool-string pool name-index))}]
  [:descriptor-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
  [:descriptor ::null {:transient (->> descriptor-index (pool-string pool)
                                       (full-parse <field-descriptor>))}]
  [:attributes-count ::bin/uint16 {:aux (count attributes)}]
  [:attributes [::attribute-info pool] {:times attributes-count}])

(bin/defbinary [method-info pool]
  [:flags ::bin/uint16
    {:bitmask {:public 0 :private 1 :protected 2
               :static 3 :final 4 :synchronized 5
               :bridge 6 :varargs 7 :native 8
               :abstract 10 :strict 11 :synthetic 12}}]
  [:name-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
  [:name ::null {:transient (symbol (pool-string pool name-index))}]
  [:descriptor-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
  [:descriptor ::null {:transient (->> descriptor-index (pool-string pool)
                                       (full-parse <method-descriptor>))}]
  [:attributes-count ::bin/uint16 {:aux (count attributes)}]
  [:attributes [::attribute-info pool] {:times attributes-count}])

;;;; Constant pool

(defn class-name
  "Look up the name corresponding to a Class_info constant"
  [pool ix]
  (let [name-ix (:name-index (pool ix))]
    (when name-ix
      (:val (pool name-ix)))))

(defn pool-string
  "Look up an Utf8_info constant"
  [pool ix]
  (:val (pool ix)))

(defn to-symbol
  "Returns a symbol naming a class, method or field"
  [^String txt]
  (symbol (.replace txt \/ \.)))

;; long and double constants use 2 pool indices
(bin/defprimitive cp-info [buf obj size]
  (loop [src (cons [nil] (repeatedly (fn [] (bin/read-binary ::cp-info1 buf))))
         acc []]
    (if (= (count acc) size)
      acc
      (let [{tag :tag :as obj} (first src)
            seg (case tag
                  (:long :double) [obj nil]
                  [obj])]
        (recur (rest src) (into acc seg)))))

  (doseq [inf obj]
    (when inf
      (bin/write-binary ::cp-info1 inf))))

(bin/defprimitive utf8 [buf ^String obj]
  (let [size (bin/read-binary ::bin/uint16 buf)]
    (.position buf (- (.position buf) 2))
    (let [bytes (bin/read-binary ::bin/byte-array buf (+ size 2))
          dis (DataInputStream. (ByteArrayInputStream. bytes))]
      (.readUTF dis)))

  ;; Size is accurate only for the ASCII subset
  (let [baos (ByteArrayOutputStream. (+ (count obj) 2))
        dos (DataOutputStream. baos)]
    (.writeUTF dos obj)
    (.put buf (.toByteArray baos))))

(bin/defbinary cp-info1
  [:tag ::bin/uint8 {:xenum {:class 7 :field 9 :method 10
                             :interface-method 11 :string 8
                             :integer 3 :float 4 :long 5 :double 6
                             :name-and-type 12 :utf8 1}}]
  (case tag
    :class [:name-index ::bin/uint16]
    :field (do [:class-index ::bin/uint16]
               [:name-type-index ::bin/uint16])
    :method (do [:class-index ::bin/uint16]
                [:name-type-index ::bin/uint16])
    :interface-method
    (do [:class-index ::bin/uint16]
        [:name-type-index ::bin/uint16])
    :string [:string-index ::bin/uint16]
    :integer [:val ::bin/int32]
    :float [:val ::bin/single-float]
    :long [:val ::bin/int64]
    :double [:val ::bin/double-float]
    :name-and-type
    (do [:name-index ::bin/uint16]
        [:descriptor-index ::bin/uint16])
    :utf8 [:val ::utf8]))

(defn lookup-field
  "Lookup symbolic reprepentation of field name and type
   from a Fieldref_info constant"
  [pool idx]
  (let [fld (pool idx)
        nti (:name-type-index fld)
        cli (:class-index fld)
        cls (to-symbol (class-name pool cli))
        nt (pool nti)
        name (to-symbol (:val (pool (:name-index nt))))
        descr (full-parse <field-descriptor>
                          (:val (pool (:descriptor-index nt))))]
    [cls name descr]))

(defn lookup-method
  "Lookup symbolic representation of method name and type
   from a Methodref_info or InterfaceMethodref_info constant"
  [pool idx]
  (let [meth (pool idx)
        nti (:name-type-index meth)
        cli (:class-index meth)
        cls (to-symbol (class-name pool cli))
        nt (pool nti)
        name (to-symbol (:val (pool (:name-index nt))))
        descr (full-parse <method-descriptor>
                          (:val (pool (:descriptor-index nt))))]
    [cls name descr]))

;;;; Attributes

(bin/defbinary [attribute-info pool]
  [:name-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
  [:name ::null {:transient (pool-string pool name-index)}]
  [:length ::bin/uint32 {:aux 0}]
  (cond
    (= name "SourceFile")
    (do [:file-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
        [:file-name ::null {:transient (pool-string pool file-index)}])

    (= name "ConstantValue")
    (do [:value-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
        [:value ::null {:transient (pool value-index) :constraint identity}])

    (= name "Code")
    (do [:max-stack ::bin/uint16]
        [:max-locals ::bin/uint16]
        [:code-length ::bin/uint32 {:aux (alength code)}]
        [:code [::bin/byte-array code-length]]
        [:disasm ::null
           {:transient (try (disasm code pool)
                         (catch Exception e (.printStackTrace e)))}]
        [:extab-length ::bin/uint16 {:aux (count exception-table)}]
        [:exception-table [::exception-table pool] {:times extab-length}]
        [:attributes-count ::bin/uint16 {:aux (count attributes)}]
        [:attributes [::attribute-info pool] {:times attributes-count}])

    (= name "Exceptions")
    (do [:num-exceptions ::bin/uint16 {:aux (count exceptions)}]
        [:index-table ::bin/uint16 {:times num-exceptions}]
        [:exceptions ::null
          {:transient (vec (map #(to-symbol (class-name pool %))
                                index-table))}])

    (= name "LineNumberTable")
    (do [:table-length ::bin/uint16 {:aux (count table)}]
        [:table ::line-number-info {:times table-length}])

    (= name "LocalVariableTable")
    (do [:table-length ::bin/uint16 {:aux (count table)}]
        [:table [::local-var-info pool] {:times table-length}])

    (= name "LocalVariableTypeTable")
    (do [:table-length ::bin/uint16 {:aux (count table)}]
        [:table [::local-var-type-info pool]
                {:times table-length}])

    (= name "InnerClasses")
    (do [:num-classes ::bin/uint16 {:aux (count classes)}]
        [:classes [::inner-class pool] {:times num-classes}])

    (= name "EnclosingMethod")
    (do [:class-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
        [:class ::null {:transient (class-name pool class-index)}]
        [:method-index ::bin/uint16 {:constraint #(< 0 % (count pool))}])

    (= name "Signature")
    (do [:signature-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
        [:sig ::null {:transient (full-parse <any-signature>
                                   (pool-string pool signature-index))}])

    #_(= name "SourceDebugExtension")
    #_[:debug-extension ::utf8]

    (= name "Deprecated")
    (do)

    (= name "Synthetic")
    (do)

    (= name "RuntimeVisibleAnnotations")
    (do [:num-annotations ::bin/uint16 {:aux (count annotations)}]
        [:annotations [::annotation pool] {:times num-annotations}])
    
    (= name "RuntimeInvisibleAnnotations")
    (do [:num-annotations ::bin/uint16 {:aux (count annotations)}]
        [:annotations [::annotation pool] {:times num-annotations}])

    (= name "RuntimeVisibleParameterAnnotations")
    (do [:num-parameters ::bin/uint16 {:aux (count parameters)}]
        [:parameters [::parameter-annotations pool] {:times num-parameters}])

    (= name "RuntimeInvisibleParameterAnnotations")
    (do [:num-parameters ::bin/uint16 {:aux (count parameters)}]
        [:parameters [::parameter-annotations pool] {:times num-parameters}])

    (= name "AnnotationDefault")
    [:default-value [::element-value pool]]

    (= name "StackMapTable")
    (do [:num-entries [::bin/uint16] {:aux (count entries)}]
        [:entries [::stack-map-entry pool] {:times num-entries}])

    :else
    [:info [::bin/byte-array length]]))

(defn frame-tag [frame-type]
  (cond (<= 0 frame-type 63) :same
        (<= 64 frame-type 127) :same-locals-1-stack-item
        (= frame-type 247) :same-locals-1-stack-item-extended
        (<= 248 frame-type 250) :chop
        (= frame-type 251) :same-extended
        (<= 252 frame-type 254) :append
        (= frame-type 255) :full))

(bin/defbinary [stack-map-entry pool]
  [:frame-type ::bin/uint8]
  [:tag ::null {:transient (frame-tag frame-type)}]
  (cond
    (<= 0 frame-type 63) ; same-frame
    (do [:offset-delta ::null {:transient frame-type}])
    (<= 64 frame-type 127) ; same-locals-1-stack-item-frame
    (do
      [:stack [::verification-type pool] {:times 1}]
      [:offset-delta ::null {:transient (- frame-type 64)}])

    (= frame-type 247) ; same-locals-1-stack-item-frame-extended
    (do [:offset-delta ::bin/uint16]
        [:stack [::verification-type pool] {:times 1}])

    (<= 248 frame-type 250) ; chop-frame
    (do [:locals-chopped ::null {:transient (- 251 frame-type)}]
        [:offset-delta ::bin/uint16])

    (= frame-type 251) ; same-frame-extended
    [:offset-delta ::bin/uint16]

    (<= 252 frame-type 254) ; append-frame
    (do [:offset-delta ::bin/uint16]
        [:locals [::verification-type pool] {:times (- frame-type 251)}])

    (= frame-type 255) ; full-frame
    (do [:offset-delta ::bin/uint16]
        [:num-locals ::bin/uint16 {:aux (count locals)}]
        [:locals [::verification-type pool] {:times num-locals}]
        [:num-stack ::bin/uint16 {:aux (count stack)}]
        [:stack [::verification-type pool] {:times num-stack}])))

(bin/defbinary [verification-type pool]
  [:tag ::bin/uint8 {:enum {:top 0 :int 1 :float 2 :long 4
                            :double 3 :null 5 :uninitialized-this 6
                            :object 7 :uninitialized 8}}]
  (cond
    (= tag :object)
    (do [:class-index ::bin/uint16 {:aux 0}]
        [:class ::null {:transient (to-symbol (class-name pool class-index))}])
    (= tag :uninitialized)
    [:offset ::bin/uint16]
    :default
    (do)))

(bin/defbinary [parameter-annotations pool]
  [:num-annotations ::bin/uint16 {:aux (count parameter-annotations)}]
  [internal [::annotation pool] {:times num-annotations}])

(bin/defbinary [element-value pool]
  [:itag ::bin/uint8 {:aux (int tag)}]
  [:tag ::null {:transient (char itag)}]
  (cond
    ;; base type
    (#{\B\C\D\F\I\J\S\Z\s} tag)
    (do [:value-index ::bin/uint16 {:constraint #(< 0 % (count pool)) :aux 0}]
        [:value ::null {:transient (pool value-index) :constraint identity}])

    (= tag \e)
    (do [:enum-type-index ::bin/uint16 {:constraint #(< 0 % (count pool))
                                        :aux 0}]
        [:enum-type ::null {:transient (full-parse <field-descriptor>
                                         (pool-string pool enum-type-index))}]
        [:enum-val-index ::bin/uint16 {:constraint #(< 0 % (count pool))}]
        [:enum-val ::null {:transient (to-symbol
                                        (pool-string pool enum-val-index))}])

    (= tag \c)
    (do [:class-index ::bin/uint16 {:constraint #(< 0 % (count pool)) :aux 0}]
        [:class ::null {:transient (full-parse <field-descriptor>
                                     (pool-string pool class-index))}])

    (= tag \@)
    [:annotation-value ::annotation]

    (= tag \[)
    (do [:length ::bin/uint16 {:aux (count values)}]
        [:values ::element-value {:times length}])))

(bin/defbinary [attribute-property pool]
  [:name-index ::bin/uint16 {:constraint #(< 0 % (count pool)) :aux 0}]
  [:name ::null {:transient (to-symbol (pool-string pool name-index))}]
  [:value [::element-value pool]])

(bin/defbinary [annotation pool]
  [:type-index ::bin/uint16 {:constraint #(< 0 % (count pool)) :aux 0}]
  [:type ::null {:transient (full-parse <field-descriptor>
                                        (:val (pool type-index)))}]
  [:num-value-pairs ::bin/uint16 {:aux (count value-pairs)}]
  [:value-pairs [::attribute-property pool] {:times num-value-pairs}])

(bin/defbinary [inner-class pool]
  [:inner-class-info-index ::bin/uint16 {:constraint #(< 0 % (count pool))
                                         :aux 0}]
  [:inner ::null {:transient (to-symbol
                               (class-name pool inner-class-info-index))}]
  [:outer-class-info-index ::bin/uint16 {:constraint #(< % (count pool))}]
  (if (not (zero? outer-class-info-index))
    [:outer ::null {:transient (to-symbol
                                 (class-name pool outer-class-info-index))}])
  [:inner-name-index ::bin/uint16 {:constraint #(< % (count pool))}]
  (if (not (zero? inner-name-index))
    [:inner-name ::null {:transient (pool-string pool inner-name-index)}])
  [:inner-class-access-flags ::bin/uint16
     {:bitmask {:public 0 :private 1 :protected 2
                :static 3 :final 4 :interface 9 :abstract 10
                :synthetic 12 :annotation 13 :enum 14}}])

(bin/defbinary [local-var-info pool]
  [:start-pc ::bin/uint16]
  [:length ::bin/uint16]
  [:name-index ::bin/uint16 {:constraint #(< 0 % (count pool)) :aux 0}]
  [:name ::null {:transient (to-symbol (pool-string pool name-index))}]
  [:descriptor-index ::bin/uint16 {:constraint #(< 0 % (count pool)) :aux 0}]
  [:descriptor ::null
     {:transient (full-parse <field-descriptor>
                             (pool-string pool descriptor-index))}]
  [:index ::bin/uint16])

(bin/defbinary [local-var-type-info pool]
  [:start-pc ::bin/uint16]
  [:length ::bin/uint16]
  [:name-index ::bin/uint16 {:constraint #(< 0 % (count pool)) :aux 0}]
  [:name ::null {:transient (pool-string pool name-index)}]
  [:signature-index ::bin/uint16 {:constraint #(< 0 % (count pool)) :aux 0}]
  [:sig ::null {:transient (full-parse <field-type-signature>
                             (pool-string pool signature-index))}]
  [:index ::bin/uint16])

(bin/defbinary line-number-info
  [:start-pc ::bin/uint16]
  [:line-number ::bin/uint16])

(bin/defbinary [exception-table pool]
  [:start-pc ::bin/uint16]
  [:end-pc ::bin/uint16]
  [:handler-pc ::bin/uint16]
  [:catch-type ::bin/uint16 {:constraint #(< % (count pool)) :aux 0}]
  [:catch ::null {:transient (if (zero? catch-type)
                               '* ; finally
                               (to-symbol (class-name pool catch-type)))}])

;;;; Disassembler

(bin/alias-tag! ::bin/uint8 ::local-var)
(bin/alias-tag! ::bin/uint16 ::pool-class)
(bin/alias-tag! ::bin/uint16 ::pool-field)
(bin/alias-tag! ::bin/uint16 ::pool-method)
(bin/alias-tag! ::bin/uint16 ::pool-iface-method)
(bin/alias-tag! ::bin/uint16 ::pool-name-type)
(bin/alias-tag! ::bin/uint8  ::pool-constant8)
(bin/alias-tag! ::bin/uint16 ::pool-constant16)

(bin/defprimitive zero [buf obj]
  (.get buf)
  (.put buf (byte 0)))

;; Padding for :tableswitch and :lookupswitch
(bin/defprimitive pad4 [buf obj]
  (.position buf
    (bin/pad4 (.position buf)))
  (.position buf
    (bin/pad4 (.position buf))))

;; Type codes for :newarray
(bin/defprimitive primitive-tag [buf obj]
  ({4 :boolean, 5 :char, 6 :float, 7 :double,
    8 :byte, 9 :short, 10 :int, 11 :long}
    (.get buf))
  (.put buf
    (byte ({:boolean 4, :char 5, :float 6, :double 7,
            :byte 8, :short 9, :int 10, :long 11} obj))))

(def
  ^{:doc "Opcodes and argument types for JVM instructions"}
  +opcodes+
  {:aaload      [0x32]
   :aastore     [0x53]
   :aconst-null [0x01]
   :aload       [0x19 ::local-var]
   :aload-0     [0x2a]
   :aload-1     [0x2b]
   :aload-2     [0x2c]
   :aload-3     [0x2d]
   :anewarray   [0xbd ::pool-class]
   :areturn     [0xb0]
   :arraylength [0xbe]
   :astore      [0x3a ::local-var]
   :astore-0    [0x4b]
   :astore-1    [0x4c]
   :astore-2    [0x4d]
   :astore-3    [0x4e]
   :athrow      [0xbf]
   :baload      [0x33]
   :bastore     [0x54]
   :bipush      [0x10 ::bin/int8]
   :caload      [0x34]
   :castore     [0x55]
   :checkcast   [0xc0 ::pool-class]
   :d2f         [0x90]
   :d2i         [0x8e]
   :d2l         [0x8f]
   :dadd        [0x63]
   :daload      [0x31]
   :dastore     [0x52]
   :dcmpg       [0x98]
   :dcmpl       [0x97]
   :dconst-0    [0x0e]
   :dconst-1    [0x0f]
   :ddiv        [0x6f]
   :dload       [0x18 ::local-var]
   :dload-0     [0x26]
   :dload-1     [0x27]
   :dload-2     [0x28]
   :dload-3     [0x29]
   :dmul        [0x6b]
   :dneg        [0x77]
   :drem        [0x73]
   :dreturn     [0xaf]
   :dstore      [0x39 ::local-var]
   :dstore-0    [0x47]
   :dstore-1    [0x48]
   :dstore-2    [0x49]
   :dstore-3    [0x4a]
   :dsub        [0x67]
   :dup         [0x59]
   :dup-x1      [0x5a]
   :dup-x2      [0x5b]
   :dup2        [0x5c]
   :dup2-x1     [0x5d]
   :dup2-x2     [0x5e]
   :f2d         [0x8d]
   :f2i         [0x8b]
   :f2l         [0x8c]
   :fadd        [0x62]
   :faload      [0x30]
   :fastore     [0x51]
   :fcmpg       [0x96]
   :fcmpl       [0x95]
   :fconst-0    [0x0b]
   :fconst-1    [0x0c]
   :fconst-2    [0x0d]
   :fdiv        [0x6e]
   :fload       [0x17 ::local-var]
   :fload-0     [0x22]
   :fload-1     [0x23]
   :fload-2     [0x24]
   :fload-3     [0x25]
   :fmul        [0x6a]
   :fneg        [0x76]
   :frem        [0x72]
   :freturn     [0xae]
   :fstore      [0x38 ::local-var]
   :fstore-0    [0x43]
   :fstore-1    [0x44]
   :fstore-2    [0x45]
   :fstore-3    [0x46]
   :fsub        [0x66]
   :getfield    [0xb4 ::pool-field]
   :getstatic   [0xb2 ::pool-field]
   :goto        [0xa7 ::bin/int16]
   :goto-w      [0xc8 ::bin/int32]
   :i2b         [0x91]
   :i2c         [0x92]
   :i2d         [0x87]
   :i2f         [0x86]
   :i2l         [0x85]
   :i2s         [0x93]
   :iadd        [0x60]
   :iaload      [0x2e]
   :iand        [0x7e]
   :iastore     [0x4f]
   :iconst-m1   [0x02]
   :iconst-0    [0x03]
   :iconst-1    [0x04]
   :iconst-2    [0x05]
   :iconst-3    [0x06]
   :iconst-4    [0x07]
   :iconst-5    [0x08]
   :idiv        [0x6c]
   :if-acmpeq   [0xa5 ::bin/int16]
   :if-acmpne   [0xa6 ::bin/int16]
   :if-icmpeq   [0x9f ::bin/int16]
   :if-icmpne   [0xa0 ::bin/int16]
   :if-icmplt   [0xa1 ::bin/int16]
   :if-icmpge   [0xa2 ::bin/int16]
   :if-icmpgt   [0xa3 ::bin/int16]
   :if-icmple   [0xa4 ::bin/int16]
   :ifeq        [0x99 ::bin/int16]
   :ifne        [0x9a ::bin/int16]
   :iflt        [0x9b ::bin/int16]
   :ifge        [0x9c ::bin/int16]
   :ifgt        [0x9d ::bin/int16]
   :ifle        [0x9e ::bin/int16]
   :ifnonnull   [0xc7 ::bin/int16]
   :ifnull      [0xc6 ::bin/int16]
   :iinc        [0x84 ::local-var ::bin/int8]
   :iload       [0x15 ::local-var]
   :iload-0     [0x1a]
   :iload-1     [0x1b]
   :iload-2     [0x1c]
   :iload-3     [0x1d]
   :imul        [0x68]
   :ineg        [0x74]
   :instanceof  [0xc1 ::pool-class]
   :invokedynamic   [0xba ::pool-name-type]
   :invokeinterface [0xb9 ::pool-iface-method ::bin/uint8 ::zero]
   :invokespecial   [0xb7 ::pool-method]
   :invokestatic    [0xb8 ::pool-method]
   :invokevirtual   [0xb6 ::pool-method]
   :ior         [0x80]
   :irem        [0x70]
   :ireturn     [0xac]
   :ishl        [0x78]
   :ishr        [0x7a]
   :istore      [0x36 ::local-var]
   :istore-0    [0x3b]
   :istore-1    [0x3c]
   :istore-2    [0x3d]
   :istore-3    [0x3e]
   :isub        [0x64]
   :iushr       [0x7c]
   :ixor        [0x82]
   :jsr         [0xa8 ::bin/int16]
   :jsr-w       [0xc9 ::bin/int32]
   :l2d         [0x8a]
   :l2f         [0x89]
   :l2i         [0x88]
   :ladd        [0x61]
   :laload      [0x2f]
   :land        [0x7f]
   :lastore     [0x50]
   :lcmp        [0x94]
   :lconst-0    [0x09]
   :lconst-1    [0x0a]
   :ldc         [0x12 ::pool-constant8]
   :ldc-w       [0x13 ::pool-constant16]
   :ldc2-w      [0x14 ::pool-constant16]
   :ldiv        [0x6d]
   :lload       [0x16 ::local-var]
   :lload-0     [0x1e]
   :lload-1     [0x1f]
   :lload-2     [0x20]
   :lload-3     [0x21]
   :lmul        [0x69]
   :lneg        [0x75]
   :lookupswitch [0xab ::pad4 ::bin/int32 ::bin/int32]
   :lor         [0x81]
   :lrem        [0x71]
   :lreturn     [0xad]
   :lshl        [0x79]
   :lshr        [0x7b]
   :lstore      [0x37 ::local-var]
   :lstore-0    [0x3f]
   :lstore-1    [0x40]
   :lstore-2    [0x41]
   :lstore-3    [0x42]
   :lsub        [0x65]
   :lushr       [0x7d]
   :lxor        [0x83]
   :monitorenter [0xc2]
   :monitorexit  [0xc3]
   :multianewarray [0xc5 ::pool-class ::bin/uint8]
   :new         [0xbb ::pool-class]
   :newarray    [0xbc ::primitive-tag]
   :nop         [0x00]
   :pop         [0x57]
   :pop2        [0x58]
   :putfield    [0xb5 ::pool-field]
   :putstatic   [0xb3 ::pool-field]
   :ret         [0xa9 ::local-var]
   :return      [0xb1]
   :saload      [0x35]
   :sastore     [0x56]
   :sipush      [0x11 ::bin/int16]
   :swap        [0x5f]
   :tableswitch [0xaa ::pad4 ::bin/int32 ::bin/int32 ::bin/int32]
   :wide        [0xc4]})

(def 
  ^{:doc "Argument types for instructions modified by the :wide instruction"}
  +wide-ops+
  {:iload [::bin/uint16]
   :fload [::bin/uint16]
   :aload [::bin/uint16]
   :lload [::bin/uint16]
   :dload [::bin/uint16]
   :istore [::bin/uint16]
   :fstore [::bin/uint16]
   :astore [::bin/uint16]
   :lstore [::bin/uint16]
   :dstore [::bin/uint16]
   :ret  [::bin/uint16]
   :iinc [::bin/uint16 ::bin/int16]})

(def +lookup-ops+
  (into {}
    (map (fn [[sym [op & args]]]
           [op (vec (cons sym args))])
         +opcodes+)))

(def +lookup-wide+
  (into {}
    (map (fn [[sym [& args]]]
           (let [[op] (+opcodes+ sym)]
             [op (vec (cons sym args))]))
         +wide-ops+)))

(defn- lookup-pool
  "Lookup symbolic representation of an instruction field
   from the constant pool"
  [pool typ raw]
  (case typ
    ::pool-class (to-symbol (class-name pool raw))
    ::pool-method (lookup-method pool raw)
    ::pool-iface-method (lookup-method pool raw)
    ::pool-field (lookup-field pool raw)
    (::pool-constant8 ::pool-constant16)
    (let [const (pool raw)]
      (case (:tag const)
        :string (pool-string pool (:string-index const))
        :class (to-symbol (class-name pool raw))
        const))
    raw))

(defn- dis1
  "Disassemble one bytecode instruction"
  [^ByteBuffer buf pool]
  (let [pc (.position buf)
        [op & atyps] (+lookup-ops+ (bin/read-binary ::bin/uint8 buf))
        raw-args (doall (map #(lookup-pool pool %
                                (bin/read-binary % buf))
                             atyps))]
    (case op
      :invokeinterface
      (let [[mspec _ _] raw-args]
        (vector pc op mspec))
      
      :tableswitch
      (let [[_ default low high] raw-args
            offsets (repeatedly (inc (- high low))
                      (fn [] (bin/read-binary ::bin/int32 buf)))]
        (apply vector pc op default low high offsets))

      :lookupswitch
      (let [[_ default npairs] raw-args
            pairs (repeatedly npairs
                    (fn [] [(bin/read-binary ::bin/int32 buf)
                            (bin/read-binary ::bin/int32 buf)]))]
        (apply vector pc op default npairs pairs))

      :wide
      (let [[wop & watyps]
            (+lookup-wide+ (bin/read-binary ::bin/uint8 buf))
            wargs (doall (map #(lookup-pool %
                                 (bin/read-binary % buf))
                              watyps))]
        (apply vector pc op wop wargs))

      ; default
      (apply vector pc op raw-args))))

(defn disasm
  "Disassemble the bytecode array of a method"
  [^"[B" bytecode pool]
  (let [^ByteBuffer buf (bin/buffer-wrap bytecode)]
    (loop [res []]
      (if (zero? (.remaining buf))
        res
        (recur (conj res (dis1 buf pool)))))))

;; Helper for parsing classes from jars and the filesystem

(defn bin-slurp
  "Read an InputStream into a byte array"
  [^InputStream input]
  (let [out (ByteArrayOutputStream.)
        buf (byte-array 4096)]
    (loop []
      (let [siz (.read input buf)]
        (if (< siz 0)
          (bin/buffer-wrap (.toByteArray out))
          (do
            (.write out buf 0 siz)
            (recur)))))))

(defn parse-class
  "Parses the .class file representation of a class object
   into clojure data structures. May not work for dynamically
   loaded classes since the .class file must exist on the
   classpath."
  [^Class cls]
  (let [name (str "/" (.replace (.getName cls) \. \/) ".class")]
    (with-open [input (.getResourceAsStream cls name)]
      (bin/read-binary ::ClassFile (bin-slurp input)))))

;;;; Assembler

(def *assembling* {})

(def empty-symtab {:pool [nil]
                   :classes {}
                   :strings {}
                   :utf {}
                   :ints {}
                   :longs {}
                   :floats {}
                   :doubles {}
                   :fields {}
                   :methods {}
                   :imethods {}
                   :descriptors {}})

(def empty-class {:symtab empty-symtab
                  :major-version 49
                  :minor-version 0
                  :extends Object
                  :fields []
                  :methods []
                  :attributes []})

;; Monadic functions that find the index of a value from the constant pool,
;; adding it if necessary

(defn int-to-pool [val]
  (fn [tab]
    (if-let [idx (get (:ints tab) (int val))]
      [idx tab]
      (let [idx (count (:pool tab))
            ntab (assoc tab :pool (conj (:pool tab) {:tag :integer :val val})
                            :ints (assoc (:ints tab) val idx))]
        [idx ntab]))))

(defn long-to-pool [val]
  (fn [tab]
    (if-let [idx (get (:longs tab) (long val))]
      [idx tab]
      (let [idx (count (:pool tab))
            ntab (assoc tab :pool (conj (:pool tab) {:tag :long :val val} nil)
                            :longs (assoc (:longs tab) val idx))]
        [idx ntab]))))

(defn float-to-pool [val]
  (fn [tab]
    (if-let [idx (get (:floats tab) (float val))]
      [idx tab]
      (let [idx (count (:pool tab))
            ntab (assoc tab :pool (conj (:pool tab) {:tag :float :val val})
                            :floats (assoc (:floats tab) val idx))]
        [idx ntab]))))

(defn double-to-pool [val]
  (fn [tab]
    (if-let [idx (get (:doubles tab) (double val))]
      [idx tab]
      (let [idx (count (:pool tab))
            ntab (assoc tab :pool (conj (:pool tab) {:tag :double :val val} nil)
                            :doubles (assoc (:doubles tab) val idx))]
        [idx ntab]))))

(defn utf-to-pool [s]
  (fn [tab]
    (if-let [idx (get (:utf tab) s)]
      [idx tab]
      (let [idx (count (:pool tab))
            ntab (assoc tab :pool (conj (:pool tab) {:tag :utf8 :val s})
                            :utf (assoc (:utf tab) s idx))]
        [idx ntab]))))

(defn string-to-pool [s]
  (fn [tab]
    (if-let [idx (get (:strings tab) s)]
      [idx tab]
      ((domonad state-m
         [stri (utf-to-pool s)
          pool (fetch-val :pool)
          _ (update-val :pool #(conj % {:tag :string :string-index stri}))
          _ (update-val :strings #(assoc % s (count pool)))]
         (count pool)) tab))))

(defn desc-to-pool [d]
  (fn [tab]
    (if-let [idx (get (:descriptors tab) d)]
      [idx tab]
      (let [[name desc] d]
        ((domonad state-m
           [namei (utf-to-pool name)
            desci (utf-to-pool (descriptor-string desc))
            pool (fetch-val :pool)
            _ (update-val :pool #(conj % {:tag :name-and-type
                                          :name-index namei
                                          :descriptor-index desci}))
            _ (update-val :descriptors #(assoc % d (count pool)))]
           (count pool)) tab)))))

(defn class-to-pool [cls]
  (let [clsym (if (symbol? cls) cls (symbol (.getName cls)))]
    (fn [tab]
      (if-let [idx (get (:classes tab) clsym)]
        [idx tab]
        ((domonad state-m
           [stri (utf-to-pool (name clsym))
            pool (fetch-val :pool)
            _ (update-val :pool #(conj % {:tag :class :name-index stri}))
            _ (update-val :classes #(assoc % clsym (count pool)))]
           (count pool)) tab)))))

(def +primitive-descriptors+
     {Byte/TYPE :byte Short/TYPE :short Integer/TYPE :int
      Long/TYPE :long Float/TYPE :float Double/TYPE :double
      Character/TYPE :char Void/TYPE :void Boolean/TYPE :boolean})

(defn class-to-descriptor [^Class cls]
  (cond
    (.isPrimitive cls)
    (+primitive-descriptors+ cls)
    (.isArray cls)
    [:array (class-to-descriptor (.getComponentType cls))]
    :else
    cls))

;; class Foo { X bar; }
;; --> [Foo 'bar X]
(defn field-to-pool [fld]
  (let [fname (if (instance? fld Field)
                (symbol (.getName fld))
                (second fld))
        fclass (if (instance? fld Field)
                 (symbol (.getName (.getClass fld)))
                 (first fld))
        fdesc (if (instance? fld Field)
                (class-to-descriptor (.getType fld))
                (nth fld 2))]
    (fn [tab]
      (if-let [idx (get (:fields tab) [fname fclass fdesc])]
        [idx tab]
        ((domonad state-m
           [cli (class-to-pool fclass)
            desci (desc-to-pool [fname fdesc])
            pool (fetch-val :pool)
            _ (update-val :pool #(conj % {:tag :field
                                          :class-index cli
                                          :name-type-index desci}))
            _ (update-val :fields #(assoc % [fname fclass fdesc]
                                            (count pool)))]
           (count pool)) tab)))))

(defn method-args [mdesc]
  (if (instance? mdesc Method)
    (vec (map class-to-descriptor
              (seq (.getParameterTypes ^Method mdesc))))
    (let [[_ _ [_ _ args]] mdesc]
      args)))

;; class Foo { X bar(Y) {...} }
;; --> [Foo 'bar [:method X [Y]]]
(defn method-to-pool [meth key]
  (let [mname (if (instance? meth Method)
                (symbol (.getName meth))
                (second meth))
        mclass (if (instance? meth Method)
                 (symbol (.getName (.getClass meth)))
                 (first meth))
        mdesc (if (instance? meth Method)
                [:method (class-to-descriptor (.getReturnType meth))
                         (method-args meth)]
                (nth meth 2))]
    (fn [tab]
      (if-let [idx (get (key tab) [mname mclass mdesc])]
        [idx tab]
        ((domonad state-m
           [cli (class-to-pool mclass)
            desci (desc-to-pool [mname mdesc])
            pool (fetch-val :pool)
            _ (update-val :pool #(conj % {:tag (if (= key :methods)
                                                 :method
                                                 :interface-method)
                                          :class-index cli
                                          :name-type-index desci}))
            _ (update-val key #(assoc % [mname mclass mdesc]
                                        (count pool)))]
           (count pool)) tab)))))

;; need class name strings for "[I" etc.?
(defmulti const-to-pool class)

(defmethod const-to-pool Class [c] (class-to-pool c))
(defmethod const-to-pool Integer [c] (int-to-pool c))
(defmethod const-to-pool Long [c] (long-to-pool c))
(defmethod const-to-pool Float [c] (float-to-pool c))
(defmethod const-to-pool Double [c] (double-to-pool c))
(defmethod const-to-pool String [c] (string-to-pool c))

(defn init-class [cls]
  (let [[res syms]
        ((domonad state-m
           [this-class (class-to-pool (:name cls))
            super-class (if-let [ext (:extends cls)]
                          (class-to-pool ext)
                          (m-result 0))
            ifaces (m-seq (map class-to-pool (:implements cls)))]
           (assoc cls
                  :this-class this-class
                  :super-class super-class
                  :interfaces (vec ifaces)))
          (:symtab cls))]
    (assoc res :symtab syms)))

(defn add-field [cref {:keys [name descriptor flags] :as fld}]
  (dosync
    (let [[[namei desci] tab]
          ((domonad state-m
             [n (utf-to-pool (str name))
              d (utf-to-pool (field-descriptor-string descriptor))]
             [n d])
           (:symtab @cref))
          fref (ref (assoc fld :name-index namei
                               :descriptor-index desci))]
      (alter cref #(-> % (update-in [:fields] conj fref)
                         (assoc :symtab tab)))
      fref)))

(defn add-attribute [cref xref {:keys [name] :as attr}]
  (dosync
    (let [[namei tab] ((utf-to-pool name) (:symtab @cref))
          attr (assoc attr :name-index namei)]
      (alter xref update-in [:attributes] conj attr)
      (alter cref assoc :symtab tab)
      attr)))

(defn add-method [cref {:keys [name descriptor flags] :as meth}]
  (dosync
    (let [[[namei desci] tab]
          ((domonad state-m
             [n (utf-to-pool (str name))
              d (utf-to-pool (method-descriptor-string (next descriptor)))]
             [n d])
           (:symtab @cref))
          mref (ref (assoc meth :name-index namei
                                :attributes []
                                :descriptor-index desci))]
      (alter cref #(-> % (update-in [:methods] conj mref)
                         (assoc :symtab tab)))
      (when-not (some #{:abstract :native} flags)
        (add-attribute cref mref {:name "Code"
                                  :labels {}
                                  :pc [0 0]
                                  :max-stack 0
                                  :max-locals 0
                                  :code-length 0
                                  :disasm []
                                  :exception-table []
                                  :attributes []}))
      mref)))

(defn advance [[min max]]
  (fn [code]
    (let [[l r] (:pc code)]
      [nil (assoc code :pc [(+ l min) (+ r max)])])))

(defn bound-interval? [[s1 s2] [d1 d2] [low hi]]
  (and (<= low (- d1 s1) hi)
       (<= low (- d2 s1) hi)
       (<= low (- d1 s2) hi)
       (<= low (- d2 s2) hi)))

(def +simple-instructions+
   #{:aaload :aastore :aconst-null :aload-0 :aload-1 :aload-2 :aload-3
     :areturn :arraylength :astore-0 :astore-1 :astore-2 :astore-3
     :athrow :baload :bastore :caload :castore :d2f :d2i :d2l :dadd
     :daload :dastore :dcmpg :dcmpl :dconst-0 :dconst-1 :ddiv
     :dload-0 :dload-1 :dload-2 :dload-3 :dmul :dneg :drem :dreturn
     :dstore-0 :dstore-1 :dstore-2 :dstore-3 :dsub :dup :dup-x1 :dup-x2
     :dup2 :dup2-x1 :dup2-x2 :f2d :f2i :f2l :fadd :faload :fastore
     :fcmpg :fcmpl :fconst-0 :fconst-1 :fconst-2 :fdiv :fload-0 :fload-1
     :fload-2 :fload-3 :fmul :fneg :frem :freturn :fstore-0 :fstore-1
     :fstore-2 :fstore-3 :fsub :i2b :i2c :i2d :i2f :i2l :i2s :iadd
     :iaload :iand :iastore :iconst-m1 :iconst-0 :iconst-1 :iconst-2
     :iconst-3 :iconst-4 :iconst-5 :idiv :iload-0 :iload-1 :iload-2
     :iload-3 :imul :ineg :ior :irem :ireturn :ishl :ishr :istore-0
     :istore-1 :istore-2 :istore-3 :isub :iushr :ixor :l2d :l2f :l2i
     :ladd :laload :land :lastore :lcmp :lconst-0 :lconst-1 :ldiv
     :lload-0 :lload-1 :lload-2 :lload-3 :lmul :lneg :lor :lrem
     :lreturn :lshl :lshr :lstore-0 :lstore-1 :lstore-2 :lstore-3 :lsub
     :lushr :lxor :monitorenter :monitorexit :nop :pop :pop2
     :return :saload :sastore :swap})

(defn exact-size
  [[op & args :as ins] pc]
  (if (contains? +simple-instructions+ op)
    1
    (case op
      (:aload :astore :bipush :dload :dstore :fload :fstore :iload :istore
       :ldc :lload :lstore :newarray :ret)
      2
      (:anewarray :checkcast :getfield :getstatic :iinc :instanceof
       :invokedynamic :invokespecial :invokestatic :invokevirtual
       :ldc-w :ldc2-w :new :putfield :putstatic :sipush
       :goto :jsr)
      3
      (:multinewarray)
      4
      (:invokeinterface :jsr-w :goto-w)
      5

      (:if-acmpeq :if-acmpne :if-icmpeq :if-icmpne :if-icmplt
       :if-icmpge :if-icmpgt :if-icmple :ifeq :ifne :iflt :ifge
       :ifgt :ifle :ifnonnull :ifnull)
      3

      :lookupswitch
      (let [[_ default count] args 
            fix (+ 9 (* 8 count))
            pad (bin/padd4 (inc pc))]
        (+ fix pad))

      :tableswitch
      (let [[_ default low high] args
            fix (+ 13 (* 4 (inc (- high low))))
            pad (bin/padd4 (inc pc))]
        (+ fix pad))

      :wide
      (let [[_ op] ins]
        (if (= op :iinc) 6 4)))))

(defn ins-size
  "Estimate size of one instruction"
  [pc [op target :as ins] labels]
  (if (contains? +simple-instructions+ op)
    [1 1]
    (case op
      (:aload :astore :bipush :dload :dstore :fload :fstore :iload :istore
       :ldc :lload :lstore :newarray :ret)
      [2 2]
      (:anewarray :checkcast :getfield :getstatic :iinc :instanceof
       :invokedynamic :invokespecial :invokestatic :invokevirtual
       :ldc-w :ldc2-w :new :putfield :putstatic :sipush)
      [3 3]
      (:multinewarray)
      [4 4]
      (:invokeinterface :jsr-w)
      [5 5]
      (:goto :jsr :goto-w)
      (if (integer? target)
        (if (<= -0x8000 target 0x7FFF)
          [3 3]
          [5 5])

        (if-let [lbl (get labels target)]
          (if (bound-interval? pc lbl [-0x8000 0x7FFF])
            [3 3]
            [3 5])
          [3 5]))

      (:if-acmpeq :if-acmpne :if-icmpeq :if-icmpne :if-icmplt
       :if-icmpge :if-icmpgt :if-icmple :ifeq :ifne :iflt :ifge
       :ifgt :ifle :ifnonnull :ifnull)
      (if (integer? target)
        [3 3]
        (if-let [lbl (get labels target)]
          (if (bound-interval? pc lbl [-0x8000 0x7FFF])
            [3 3]
            [3 8])
          [3 8]))

      :lookupswitch
      (let [[_ _ default count] ins
            fix (+ 9 (* 8 count))]
        [fix (+ fix 3)])

      :tableswitch
      (let [[_ _ default low high] ins
            fix (+ 13 (* 4 (inc (- high low))))]
        [fix (+ fix 3)])

      :wide
      (let [[_ op] ins]
        (if (= op :iinc)
          [6 6]
          [4 4])))))

(defn add-label [lbl]
  (fn [code]
    (let [pc (:pc code)]
      [pc (assoc-in [code :labels lbl] pc)])))

(defn bump-locals [k]
  (fn [code]
    (let [prev (:max-locals code)]
      (if (> k prev)
        [nil (assoc code :max-locals k)]
        [nil code]))))

;; Does not convert labels to offsets (offset16, 32)
(defn lookup-instr-arg [atyp asym locals code pool]
;  (with-monad state-m
    (case atyp
      ::local-var (with-monad state-m (m-result (locals asym)))
      ::pool-class (class-to-pool asym)
      ::pool-field (field-to-pool asym)
      ::pool-method (method-to-pool asym :methods)
      ::pool-iface-method (method-to-pool asym :imethods)
      ::pool-name-type (desc-to-pool asym)

      (::pool-constant8 ::pool-constant16)
      (const-to-pool asym)

      ::zero (with-monad state-m (m-result 0))
      ::pad4 (with-monad state-m (m-result nil))

      (::primitive-tag ::bin/int8 ::bin/uint8 ::bin/int32 ::bin/int16
       ::offset16 ::offset32)
      (with-monad state-m (m-result asym))))

;; [:astore 0] --> :astore-0, etc.
(defn load-store-op [op n]
  (keyword (str (name op) "-" n)))

(defn sizeof-desc [desc]
  (cond
    (= desc :long) 2
    (= desc :double) 2
    :else 1))

;; Convert Xload n <--> Xload_n, etc.
;; Do NOT convert bipush -> sipush
;; Do NOT convert goto <-> goto-w (see #'refine)
;; invokeinterface, wide, *switch need the original (symbolic) arguments
(defn emit-modify [[op & args :as ins] orig]
  (if (contains? +simple-instructions+ op)
    ins
    (case op
      (:aload :astore :iload :istore :lload :lstore
       :fload :fstore :dload :dstore)
      (let [[idx] args]
        (cond
          (<= idx 3) [(load-store-op op idx)]
          (<= idx 0xFF) ins
          :else [:wide op idx]))

      (:ldc :ldc-w)
      (let [[idx] args]
        (if (<= idx 0xFF)
          [:ldc idx]
          [:ldc-w idx]))

      :ret
      (let [[idx] args]
        (if (<= idx 0xFF)
          [:ret idx]
          [:wide :ret idx]))

      :iinc
      (let [[idx const] args]
        (if (and (<= idx 0xFF) (<= -0x80 const 0x7F))
          ins
          [:wide op idx const]))

      ; calculate argument count including 'this'
      :invokeinterface
      (let [[idx] args
            iface-args (method-args orig)]
        [op idx (->> iface-args
                     (map sizeof-desc)
                     (reduce + 1))
            0])

      :lookupswitch
      (let [[_ default n & pairs] orig]
        (apply vector op nil default n pairs))

      :tableswitch
      (let [[_ default low high & tab] orig]
        (apply vector op nil default low high tab))

      :wide
      ins ; TODO: narrow

      (:anewarray :bipush :checkcast :getfield :getstatic
       :goto :goto-w :if-acmpeq :if-acmpne :if-icmpeq :if-icmpne
       :if-icmplt :if-icmpge :if-icmpgt :if-icmple
       :ifeq :ifne :iflt :ifge :ifgt :ifle
       :ifnonnull :ifnull :instanceof
       :invokedynamic :invokespecial :invokevirtual
       :jsr :jsr-w :ldc2-w :multianewarray :new :newarray
       :putfield :putstatic :sipush)
      ins
      )))

(defn emit1
  "Add one instruction to the code buffer converting symbolic
  constants to constant pool indices"
  [cref mref instr ctx]
  (dosync
    (let [code (get-in @mref [:attributes 0])
          pool (:symtab @cref)
          currpc (:pc code)
          [op & args] instr
          {:keys [locals]} ctx
          arg-m (with-monad state-m
                  (m-map (fn [[t a]]
                           (lookup-instr-arg t a locals code pool))
                         (map vector
                              (next (+opcodes+ op))
                              args)))]
      (let [[argidx ntab] (arg-m pool)
            ninstr (emit-modify (apply vector op argidx) args)
            [nextpc ncode] ((domonad state-m
                              [nextpc (advance
                                        (ins-size currpc instr
                                                  (:labels code)))
                               _ (update-val :disasm
                                   #(conj % ninstr))]
                              nextpc) code)]
        (alter mref assoc-in [:attributes 0] ncode)
        (alter cref assoc :symtab ntab)))))

(defn label? [ins]
  (= (first ins) 'label))

(defn block? [ins]
  (= (first ins) 'block))

(defn emit
  "Add an instruction, block, or label into the instruction stream"
  ([cref mref item] (emit cref mref item {}))
  ([cref mref item ctx]
   (cond
     (label? item)
     (let [[_ lbl] item]
        (dosync
         (alter mref update-in [:attributes 0]
                (comp second
                  (domonad state-m
                    [_ (add-label lbl)
                     _ (update-val :disasm #(conj % item))]
                    nil)))))

     (block? item)
     (let [[_ lbl vars & items] item]
       (dosync
         (when lbl (emit cref mref ['label lbl]))
         (let [subctx (apply assoc ctx vars)]
           (doseq [i items]
             (emit cref mref subctx)))))

     :else
     (emit1 cref mref item ctx))))

(def +negate-op+
  (let [pairs {:if-acmpeq :if-acmpne,
               :if-icmpeq :if-icmpne,
               :if-icmplt :if-icmpge,
               :if-icmpgt :if-icmple,
               :ifeq :ifne, :iflt :ifge, :ifgt :ifle,
               :ifnonnull :ifnull}]
    (into pairs (bin/invert-map pairs)))) ; symmetrize

;; Transform if* --> ifnot* goto-w,
;;           goto --> goto-w
;; if necessary
(defn refine-split [[op & args :as ins] pc labels]
  (case op
    (:if-acmpeq :if-acmpne :if-icmpeq :if-icmpne :if-icmplt
     :if-icmpge :if-icmpgt :if-icmple :ifeq :ifne :iflt :ifge
     :ifgt :ifle :ifnonnull :ifnull)
    (let [[target] args]
      (if (integer? target)
        [ins]
        (if-let [target-label (labels target)]
          (if (bound-interval? [pc pc] target-label [-0x8000 0x7FFF])
            [ins]
            [[(get +negate-op+ op) 8]
             [:goto-w target]])
          (throw (IllegalArgumentException.
                   (str "Undefined label " target))))))
    
    (:goto :goto-w)
    (let [[target] args]
      (if (integer? target)
        [ins]
        (if-let [target-label (labels target)]
          (if (bound-interval? [pc pc] target-label [-0x8000 0x7FFF])
            [:goto target]
            [:goto-w target])
          (throw (IllegalArgumentException.
                   (str "Undefined label " target))))))

    ; default
    [ins]))

(defn refine1
  "Calculate exact offset and size for one label or instruction"
  [ins]
  (if (label? ins)
    ;; Remove the label from instruction stream
    (let [[_ lbl] ins]
      (fn [{:keys [labels pc] :as code}]
        [nil (assoc-in code [:labels lbl] [pc pc])]))
    (fn [{:keys [labels pc] :as code}]
      (let [split (refine-split ins pc labels)]
        (loop [target (:disasm code) pc pc remain split]
          (if (seq remain)
            (let [ins (first remain)
                  siz (exact-size ins pc)]
              (recur (assoc target pc ins)
                     (+ pc siz)
                     (next remain)))
            [nil (-> code (assoc-in [:pc] pc)
                          (assoc-in [:disasm] target))]))))))

(defn refine
  "Calculate exact offsets for all instructions and labels in a method"
  [meth]
  (dosync
    (alter meth
           update-in [:attributes 0]
           (comp second
             (domonad state-m
               [_   (set-val :pc 0)
                ins (set-val :disasm (sorted-map))
                _   (m-map refine1 ins)
                siz (fetch-val :pc)
                _   (set-val :code-length siz)]
               nil)))))

;;;; StackMapTable generation

(def *java-hierarchy*
  (-> (make-hierarchy)
      (derive :one-word :top)
      (derive :two-word :top)
      (derive :reference :one-word)
      (derive Object :reference)
      (derive :uninitialized :reference)
      (derive :uninitialized-this :uninitialized)
      (derive :null :reference)
      (derive :long :two-word)
      (derive :double :two-word)
      (derive :int :one-word)
      (derive :float :one-word)))

(defn array-class [^Class c]
  (class (make-array c 0)))

;; brute force
(defn ancestors+ [x]
  (let [anc (ancestors *java-hierarchy* x)]
    (if (and (class? x)
             (.isArray ^Class x))
      (->> (.getComponentType ^Class x) ancestors+
           (filter class?) (map array-class)
           (into anc))
      anc)))

;; handles array class covariance
(defn j-isa? [x y]
  (or (isa? *java-hierarchy* x y)
      (and (isa? *java-hierarchy* y :reference)
           (= x :null))
      (and (class? x) (class? y)
           (let [^Class x x ^Class y y]
             (.isAssignableFrom y x)))))

;             (and (.isArray x) (.isArray y)
;                  (j-isa? (.getComponentType x)
;                          (.getComponentType y)))))))

(defn unify1 [l r]
  (cond
    (and (set? l) (set? r))
    (clojure.set/intersection l r)

    (set? r)
    (if (contains? r l) l
      (clojure.set/intersection
        (ancestors+ l) r))

    (set? l)
    (if (contains? l r) r
      (clojure.set/intersection
        (ancestors+ r) l))

    (j-isa? l r) r
    (j-isa? r l) l

    :else
    (clojure.set/intersection
      (ancestors+ l)
      (ancestors+ r))))

(defn unify-frame [pc [loc stak :as curr]]
  (fn [code]
    (let [[ploc pstak :as prev] (get (:frames code) pc)]
      (if prev
        [nil (assoc-in [code :frames pc]
                       [(map unify1 loc ploc) (map unify1 stak pstak)])]
        [nil (assoc-in [code :frames pc] curr)]))))

(defn target-offset [target pc labels]
  (if (integer? target)
    (+ pc target)
    (first (get labels target))))

(defn basic-blocks [code labels]
  (loop [blocks (sorted-map) ins (seq code) start? true]
    (if-not ins
      blocks
      (let [[pc [op target :as whole]] (first ins)
            blocks (if start? (assoc blocks pc {}) blocks)]
        (case op
          (:areturn :athrow :dreturn :freturn :ireturn :lreturn :return)
          (recur blocks (next ins) true)

          (:goto :goto-w :if-acmpeq :if-acmpne
           :if-icmpeq :if-icmpne :if-icmplt :if-icmpge
           :if-icmpgt :if-icmple :ifeq :ifne :iflt :ifge
           :ifgt :ifle :ifnonnull :ifnull)
          (recur (assoc blocks (target-offset target pc labels) {})
                 (next ins) true)

          :lookupswitch
          (let [[_ _ default count & pairs] whole]
            (recur (apply assoc blocks
                          (target-offset default pc labels) {}
                          (interleave
                            (map #(target-offset % pc labels)
                                 (take-nth 2 (rest pairs)))
                            (repeat {})))
                   (next ins) true))

          :tableswitch
          (let [[_ _ default low high & tab] whole]
            (recur (apply assoc blocks
                          (target-offset default pc labels) {}
                          (interleave
                            (map #(target-offset % pc labels) tab)
                            (repeat {})))
                   (next ins) true))

          ; (:jsr jsr-w :ret) -- unimplemented

          ; default
          (recur blocks (next ins) false))))))

(defn block-graph [ins blocks]
  (loop [ins ins, blocks (seq blocks),
         neighbors (into {} (map (fn [x] [x #{}]) blocks))
         curr (first blocks), prev nil]
    (let [[pc op target :as whole] (first ins)
          neighbors (if (== pc (first blocks))
                      (case prev
                        (:goto :goto-w) neighbors
                        ; default
                        (if prev
                          (update-in [neighbors curr] conj pc)
                          neighbors)))
          neighbors (case op
                      ; branch
                      (update-in [neighbors pc] conj target)
                      ; default
                      neighbors)]
      (if (== pc (first blocks))
        (recur (next ins) (next blocks) neighbors pc op)
        (recur (next ins) blocks neighbors curr op)))))

;;;; Top-level interface

;; refine, compute block graph, generate stack map, emit bytecode
(defn assemble-method [mref]
  (dosync
    (when-not (some #{:abstract :native} (:flags @mref))
      (refine mref))))

(defn assemble-class [cref]
  (dosync
    (let [cls @cref]
      (assoc cls
             :methods (map deref (:methods cls))
             :fields (map deref (:fields cls))))))

(defmacro assembling [bindings & body]
  (let [syms (take-nth 2 bindings)
        vals (take-nth 2 (rest bindings))
        refs (map (fn [expr]
                    `(ref (init-class (merge empty-class ~expr))))
                  vals)
        names (map :name vals)]
    `(let ~(vec (interleave syms refs))
       (binding [*assembling* (assoc *assembling*
                                     ~@(interleave names syms))]
         ~@body))))

;; Walk the class description trees
;; calling add-field, add-method, emit, assemble-method, assemble-class
(defn assemble [cs]
  cs)

;;;; Debug: inspect classes being generated in assembling

(defn inspect [cref]
  (clojure.inspector/inspect-tree
    (assemble-class cref)))
