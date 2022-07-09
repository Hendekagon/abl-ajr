(ns abl.ajr.core
  "

  "
  (:require
    [clojure.string :as string :refer [starts-with?]]
    [clojure.math :refer [pow sqrt signum]]
    [clojure.math.combinatorics :as x]
    [clojure.walk :as w]))

(def b& bit-and)
(def b| bit-or)
(def b< bit-shift-left)
(def b> bit-shift-right)
(def b>> unsigned-bit-shift-right)
(def b¬ bit-not)
(def b⊻ bit-xor)

(defrecord Blade [bitmap scale grade])

; grade is the number of factors, or elements
; which have been wedged together - also the
; dimensionality of the subspace represented
; by the k-blade/vector
(defn grade [bits]
  (Long/bitCount bits))

; make a blade. 'G' is like a circle with an arrow
; which is like the circular bivector visualization
; in GA4CS, and 'G' is also for Geometric Number
(defn G
  ([basis scale]
    (G {} basis scale))
  ([ga basis scale]
    (let [b (or (:bitmap basis) basis)]
      (assoc (Blade. b scale (grade b))
        :basis
        (or (get-in ga [:basis-by-bitmap b])
          (:basis basis))))))

(defn multivector [{basis :basis :as ga} elements]
  (mapv
    (fn [[co bb]]
      (G (get basis bb bb) co))
    (partition 2 elements)))

(defn edalb [{:keys [scale grade] :as blade}]
  (let [n (* 1/2 grade (dec grade))
        r (if (== 0 n) 1N (last (take n (cycle [-1N 1N]))))]
    (G blade (*' scale r))))

(defn <- [multivector]
  (mapv edalb multivector))

(defn involute [{:keys [scale grade] :as blade}]
  (G blade (* scale (pow -1 grade))))

(defn <_
  ([ga mv] (<_ mv))
  ([multivector]
    (mapv involute multivector)))

(defn negate [{:keys [scale grade] :as blade}]
  (G blade (* scale -1N)))

; also called conjugation
(defn negate-mv
  ([ga mv] (negate-mv mv))
  ([mv] (mapv negate mv)))

(defn inverse [{{:syms [• *]} :ops {S 'S} :specials :as ga} mv]
  (let [r (<- mv)
        [{s :scale}] (• r r)]
     (if s
       (* r [(G S (/ 1N s))])
       (throw (ex-info (str "non-invertable multivector ["
                         (string/join " " (map (fn [{:keys [scale basis]}] (str scale basis)) mv)) "]") {:non-invertable mv})))))

(defn rsqrt
  ([x i n]
    (if (== i n)
      x
      (/ (- x 1)
         (+ 2 (rsqrt x (inc i) n)))))
  ([x n]
   (if (== x 1)
     1
     (- (/ (- x 1) (rsqrt x 0 n)) 1)))
  ([x] (rsqrt x 16)))

(defn length
  ([{{:syms [•]} :ops :as ga} mv]
    (length ga mv 16))
  ([{{:syms [•]} :ops :as ga} mv n]
    (if (seq mv)
       (let [[{l :scale}] (• mv mv)] (if l (rsqrt l n) 0))
       0)))

(defn normalize [{{:syms [•]} :ops :as ga} mv]
  (if (seq mv)
    (let [d (/ 1 (length ga mv))]
      (mapv (fn [e] (update e :scale * d)) mv))
     mv))

(defn scale [ga mv s]
  (mapv (fn [b] (update b :scale * s)) mv))

; todo check that the bitmaps made by xoring here are < count bases
; also this is only good for small numbers of dimensions
; soon need to work out how to manage large spaces
(defn bases-of
   ([n] (bases-of "e" 0 n))
   ([prefix n] (bases-of prefix 0 n))
   ([prefix start n]
     (reduce
       (fn [r components]
         (let [n (symbol (str prefix (string/join "" (map (fn [[i]] (+ start i)) components))))]
          (assoc r n
            (assoc (G (reduce b⊻ (map (comp :bitmap last) components)) 1N)
              :basis n))))
       {(symbol (str prefix "_")) (assoc (G 0 1N) :basis (symbol (str prefix "_")))}
       (let [b (map (fn [i] [i (symbol (str prefix i)) (G (b< 1 i) 1N)]) (range n))]
          (mapcat (fn [k] (x/combinations b k))
            (range 1 (inc n)))))))

(defn bit-flips [a b]
  (reduce +
    (map (fn [x] (Long/bitCount (b& x b)))
      (take-while (fn [x] (> x 0))
        (iterate (fn [x] (b> x 1)) (b> a 1))))))

; page 514 GA4CS
(defn canonical-order [a b]
  (if (== 0 (b& (bit-flips a b) 1)) +1N -1N))

; sadly depending on the order of numbers in that reduce
; annihilation isn't ensured
; leading to, for example,
; tiny bivectors after multiplying odd numbers of planes in PGA
(defn consolidate-blades [ga]
  (comp
    (partition-by :bitmap)
    (map (fn [[fb :as blades]] (G ga fb (reduce + (map :scale blades)))))))

(def int-xf (filter (fn [[{ag :grade} {bg :grade} {pg :grade}]] (== pg (- bg ag)))))
(def ext-xf (filter (fn [[{ag :grade} {bg :grade} {pg :grade}]] (== pg (+ ag bg)))))

(def remove-scalars-xf (remove (comp zero? :grade)))

(defn consolidate&remove0s [ga]
  (comp
    (consolidate-blades ga)
    (remove (comp zero? :scale))))

(defn simplify- [xf blades]
  (into [] xf (sort-by :bitmap blades)))

(defn simplify
  ([ga blades]
    (simplify- (consolidate&remove0s ga) blades)))

(defn simplify0 [ga blades]
  (simplify- (consolidate-blades ga) blades))

(defn <>
  "return the multivector by grade"
  [mv]
  (into {} (map (juxt :grade identity) mv)))

(defn <>r
  "return the grade r part of mv"
  [r mv]
  ((<> mv) r))

(defn **
  {:doc ""}
  [{{:syms [*]} :ops [e_] :basis-by-grade :as ga} mva mvb]
  (for [a mva b mvb]
    [a b (* a b)]))

(defn ⌋
  {:doc "Left contraction" :ref "§2.2.4 eq 2.6 IPOGA"}
  [{{:syms [*]} :ops [e_] :basis-by-grade :as ga} mva mvb]
  (simplify-
    (comp
      (filter (fn [[{ag :grade} {bg :grade} {g :grade}]] (== g (- bg ag))))
      (map peek)
      (consolidate&remove0s ga))
    (** ga mva mvb)))

(defn ⌊
  {:doc "Right contraction" :ref "§2.2.4 eq 2.7 IPOGA"}
  [{{:syms [*]} :ops [e_] :basis-by-grade :as ga} mva mvb]
  (simplify-
    (comp
      (filter (fn [[{ag :grade} {bg :grade} {g :grade}]] (== g (- ag bg))))
      (map peek)
      (consolidate&remove0s ga))
    (** ga mva mvb)))

; left contraction or inner product §3.5.3
(defn ⌋• [{{:syms [*]} :ops [e_] :basis-by-grade :as ga} mva mvb]
  (simplify ga
    (map
       (fn [[{ag :grade :as a} {bg :grade :as b} {g :grade :as p}]]
         (if (== g (abs (- bg ag))) p (G e_ 0)))
       (for [{ag :grade :as a} mva {bg :grade :as b} mvb :when (and (> ag 0) (> bg 0))]
         [a b (* a b)]))))

(defn ⌋' [{{:syms [*]} :ops [e_] :basis-by-grade :as ga} mva mvb]
  (mapv peek
    (filter
       (fn [[{ag :grade :as a} {bg :grade :as b} {g :grade :as p}]]
         (== g (- bg ag)))
       (for [{ag :grade :as a} mva {bg :grade :as b} mvb :when (not= ag bg)]
         [a b (* a b)]))))

(defn basis-range
  "select blades having basis in range f t "
  [mv f t]
  (vec (filter
         (fn [{b :bitmap}]
           ((into (hash-set) (map (fn [i] (int (pow 2 i))) (range f t))) b)) mv)))

(defn imv [mvs]
  (mapv (fn [i mv] (mapv (fn [j e] (G e (if (== i j) 1 0))) (range) mv)) (range) mvs))

(defn qr
  "a GA implementation of QR decomposition by Householder reflection"
  ([{{:syms [+ - ⁻ * *- *0 • ∧ V ∼ • ⍣ ⧄]} :ops
    [e_ e0 :as bg] :basis-by-grade :as ga} [fmv :as mvs]]
   (loop [n (count fmv) d 0 r mvs q identity qs []]
     (if (< d (dec n))
       (let [
              vd  (mapv (fn [b i] (if (< i d) (assoc b :scale 0) b)) (r d) (range)) ; dth basis vector, zeroed out up to d
              ed  [(update (vd d) :scale (fn [x] (let [sn (signum x)] (* -1.0 (if (zero? sn) 1.0 sn)))))]
              bi' (+ (⧄ vd) ed)                         ; bisector of unit v and ei
              bi  (if (seq bi') bi' ed)                 ; if v is ei then bisector will be empty
              hy  (∼ bi)                                ; reflection hyperplane
              qd  (fn [x] (* (- hy) x (⁻ hy)))
              qs' (into (vec (repeat d identity)) (repeat (clojure.core/- n d) qd))
            ]
         (recur n (inc d)
           (mapv (fn [f x] (f x)) qs' r)
           (comp q qd) ; todo use associativity of sandwich product to compose these
           qs'))
       {:q (mapv (fn [v] (basis-range (q v) 0 n)) (imv mvs))
        :qfn (fn [mvs] (mapv (fn [v] (basis-range (q v) 0 n)) mvs))
        :r (mapv (fn [v] (basis-range v 0 n)) r)}))))

(defn eigendecompose
  ([{mm :metric-mvs mmga :mmga :as ga}]
    (eigendecompose mmga mm))
  ([ga mvs]
    (eigendecompose ga (qr ga mvs) mvs))
  ([ga {:keys [q r]} mvs]
   {
    :eigenvectors q
    :eigenvalues (vec (map-indexed (fn [i mv] (mv i)) r))
    }))

; this will use the metric of the given GA so that
; will need to be identity
(defn ->basis
  "Change of basis. Based on the GA4CS reference impl"
  ([{basis :eigenvectors mmga :mmga :as ga} blade]
    (->basis mmga basis blade))
  ([{[e_] :basis-by-grade bbb :basis-in-order
    {* '* ∧ '∧ *- '*- •∧ '•∧} :ops :as ga}
    metric-mvs {:keys [bitmap scale] :as blade}]
   (loop [r [(G e_ scale)] i 0 b bitmap]
     (if (== b 0)
       r
       (if (even? b)
         (recur r (inc i) (b>> b 1))
         (recur
           (reduce
             (fn [rr [mv j]]
               (let [s (:scale (mv i))]
                 (if (== s 0)
                   rr
                   (reduce
                     (fn [rrr x]
                       (into rrr (∧ [x] [(G (bbb (b< 1 j)) s)])))
                     rr r))))
             [] (map vector metric-mvs (range)))
           (inc i) (b>> b 1)))))))


(defn op-error
  ([op {help :help :as ga} a b]
   (throw (ex-info (str op " (" (help op) ") can't take " (or (type a) "nil") a " & " (or (type b) "nil") b) {:op op :args [a b]})))
  ([op {help :help :as ga} a]
   (throw (ex-info (str op " (" (help op) ") can't take " (or (type a) "nil") a) {:op op :arg a}))))

(defn compare-G
  ([op]
    (fn dispatch
      ([{ops :ops :as m} a]
        ((ops (compare-G op m a) (partial op-error op)) (assoc m :op op) a))
      ([{ops :ops :as m} a b]
        ((ops (compare-G op m a b) (partial op-error op)) (assoc m :op op) a b))
      ([{ops :ops :as ga} a b & more]
       (cond
         (ops [op :multivectors]) ((ops [op :multivectors]) ga (cons a (cons b more)))
         :default (reduce (partial (ops (compare-G op ga a b) (partial op-error op)) (assoc ga :op op)) (cons a (cons b more)))))))
  ([op ga a b]
    (let [meet (if (and (:bitmap a) (:bitmap b)) (b& (:bitmap a) (:bitmap b)) nil)
          dependency (if (and meet (zero? meet)) :independent :dependent)
          ag (if (vector? a) :grades (min 1 (or (:grade a) 0)))
          bg (if (vector? b) :grades (min 1 (or (:grade b) 0)))
          ta (cond (number? a) :number (:bitmap a) :blade (vector? a) :multivector)
          tb (cond (number? b) :number (:bitmap b) :blade (vector? b) :multivector)
          ]
       [op dependency ta tb ag bg]))
   ([op ga a]
    (cond
      (seq? a) [op :multivectors]
      (vector? a) [op :multivector]
      :else [op (type a)])))

(defn ga-ops []
  {
   [:no-such-op :independent :blade :blade 0 0]
   (fn g* [ga a b]
     (update a :scale *' (:scale b)))

   ['* :dependent :number :number 0 0]
   (fn g* [ga a b]
     (*' a b))

   ['* :independent :blade :blade 0 0]
   (fn g* [ga a b]
     (update a :scale *' (:scale b)))

   ['* :independent :blade :blade 0 1]
   (fn g* [ga a b]
     (update b :scale *' (:scale a)))

   ['* :independent :blade :blade 1 0]
   (fn g* [ga a b]
     (update a :scale *' (:scale b)))

   ['* :independent :blade :blade 1 1]
   (fn g* [ga {bma :bitmap va :scale :as a} {bmb :bitmap vb :scale :as b}]
     (G ga (b⊻ bma bmb)
       (*' (canonical-order bma bmb) va vb)))

   ['* :dependent :blade :blade 1 1]
   (fn g* [{metric :metric :as ga} {bma :bitmap va :scale :as a} {bmb :bitmap vb :scale :as b}]
     (G ga (b⊻ bma bmb)
       (loop [i 0 m (b& (:bitmap a) (:bitmap b))
              s (*' (canonical-order bma bmb) va vb)]
         ;(println ">>" i (metric i))
         (if (== 0 m)
           s
           (recur (inc i) (b> m 1)
             (if (== 1 (b& m 1)) (*' s (metric i)) s))))))

   ^{:doc "raw Geometric product"}
   ['*'' :dependent :multivector :multivector :grades :grades]
   (fn g*'' [{{* '*} :ops :as ga} mva mvb]
     (for [a mva b mvb] [a b (* a b)]))

   ; see 22.3.1 for future optimizations
   ^{:doc "Geometric product" :e.g. '(* [1 v1 2 v2] [3 v0 4 v2])}
   ['* :dependent :multivector :multivector :grades :grades]
   (fn g* [{{* '*} :ops :as ga} a b]
     (simplify ga (for [a a b b] (* ga a b))))

   ^{:doc "unsimplified Geometric product"}
   ['*- :dependent :multivector :multivector :grades :grades]
   (fn g*- [{{* '*} :ops :as ga} a b]
     (vec (for [a a b b] (* a b))))

   ^{:doc "simplified Geometric product keep zeros"}
   ['*0 :dependent :multivector :multivector :grades :grades]
   (fn g*0 [{{* '*} :ops :as ga} a b]
     (simplify0 ga (for [a a b b] (* a b))))

   ^{:doc "Interior and exterior products"}
   ['•∧ :dependent :multivector :multivector :grades :grades]
   (fn ip [{{:syms [*'']} :ops :as ga} mva mvb]
     (let [gp (sort-by (comp :bitmap peek) (*'' mva mvb))]
       {:• (simplify- (comp int-xf (map peek) (consolidate&remove0s ga)) gp)
        :∧ (simplify- (comp ext-xf (map peek) (consolidate&remove0s ga)) gp)}))

   ^{:doc "Interior product ·"}
   ['•' :dependent :multivector :multivector :grades :grades]
   (fn ip [{{:syms [•∧]} :ops :as ga} a b]
     (:• (•∧ a b)))

   ^{:doc "Interior product"}
   ['• :dependent :multivector :multivector :grades :grades]
   (fn ip [{{:syms [*]} :ops :as ga} a b]
     (⌋• ga a b))

   ^{:doc "Exterior product or meet, largest common subspace, intersection"}
   ['∧ :dependent :multivector :multivector :grades :grades]
   (fn ∧ [{{:syms [•∧]} :ops :as ga} a b]
     (:∧ (•∧ a b)))

   ^{:doc "Regressive product or join, smallest common superspace, union"
     :note "Gunn arXiv:1501.06511v8 §3.1"}
   ['∨' :dependent :multivector :multivector :grades :grades]
   (fn ∨' [{{:syms [* • ∼]} :ops :as ga} a b]
     (∼ (* (∼ a) (∼ b))))

   ^{:doc "Regressive product or join, smallest common superspace, union"
     :note "Gunn arXiv:1501.06511v8 §3.1"}
   ['∨ :dependent :multivector :multivector :grades :grades]
   (fn ∨ [{{:syms [∧ ∼]} :ops {:keys [I I-]} :specials :as ga} a b]
     (simplify ga (∼ (∧ (∼ b) (∼ a)))))

   ; I'll think of a more elegant way to handle this later
   ^{:doc "Regressive product or join, smallest common superspace, union"
     :note "Gunn arXiv:1501.06511v8 §3.1"}
   ['∨ :multivectors]
   (fn ∨ [{{:syms [∧ ∼]} :ops {:keys [I I-]} :specials :as ga} mvs]
     (let [r (simplify ga (∼ (reduce ∧ (map ∼ mvs))))]
       (if (odd? (count mvs))
         r
         (mapv (fn [b] (update b :scale * -1)) r))))

   ^{:doc ""
     :note ""}
   ['h∨ :dependent :multivector :multivector :grades :grades]
   (fn h∨ [{{:syms [* • ∼]} :ops :as ga} a b]
     ; Hestenes (13) defines ∨ as (• (∼ a) b) which doesn't give the same result
     (• (∼ a) b))

   ^{:doc "Sum, bisect 2 planes, bisect 2 normalized lines"
     :ascii '+ :short 'sum :verbose 'sum :gs '+}
   ['+ :dependent :multivector :multivector :grades :grades]
   (fn g+ [ga a b]
     (simplify ga (into a b)))

   ^{:doc "Sum, bisect 2 planes, bisect 2 normalized lines"
     :ascii '+ :short 'sum :verbose 'sum :gs '+}
   ['+ :multivector]
   (fn g+ [ga a] a)

   ^{:doc "Exponential"
     :ascii 'e :short 'exp :verbose 'exponential}
   ['𝑒 :multivector]
   (fn exp [{{:syms [• *]} :ops
             basis :basis [G_] :basis-in-order :as ga} a]
     (let [[{max :scale}] (• a (<- a))
           scale (loop [s (if (> max 1) (b< 1 1) 1) m max]
                   (if (> m 1) (recur (b< s 1) (/ m 2)) s))
           scaled (* a [(G (basis G_) (/ 1 scale))])
           r (simplify ga (reduce
                            (fn exp1 [r i]
                              (into r (* [(peek r)] (* scaled [(G (basis G_) (/ 1 i))]))))
                            [(basis G_)] (range 1 16)))
           ]
       (loop [r r s scale]
         (if (> s 1)
           (recur (* r r) (b>> s 1))
           (simplify ga r)))))

   ^{:doc "Sandwich product"}
   ['⍣ :dependent :multivector :multivector :grades :grades]
   (fn |*| [{{* '*} :ops :as ga} r mv]
     (reduce * [(<- r) mv r]))

   ^{:doc "Inverse"}
   ['⁻ :multivector]
   inverse

   ^{:doc "Negate"}
   ['- :multivector]
   negate-mv

   ^{:doc "Involution"}
   ['_ :multivector]
   <_

   ^{:doc "Dual"}
   ['∼ :multivector]
   (fn dual [{{• '•} :ops duals :duals ds :duals- :as ga} mv]
     ; (⌋ ga a [I-])
     (mapv (fn [{bm :bitmap s :scale :as a}]
             (assoc (duals (G a 1N)) :scale (* (ds (G a 1N)) s))) mv))

   ^{:doc "Dual"}
   ['∼ Blade]
   (fn dual [{{⌋ '⌋ • '•} :ops duals :duals ds :duals- :as ga} {bm :bitmap s :scale :as a}]
     (assoc (duals (G a 1N)) :scale (* (ds (G a 1N)) s)))

   ^{:doc "Hodge dual ★"}
   ['★ :multivector]
   (fn hodge [{{* '*} :ops {I 'I} :specials :as ga} mv]
     (* (<- mv) [I]))

   ^{:doc "Hodge dual ★"}
   ['★ Blade]
   (fn hodge [{{* '*} :ops {I 'I} :specials :as ga} x]
     (* (edalb x) I))

   ^{:doc "Normalize"}
   ['⧄ :multivector]
   normalize
   })

; p.80 the inverse of the pseudoscalar is simply the reverse
(defn with-specials [{b :basis-by-grade bio :basis-in-order md :metric :as ga}]
  (let [
         I (peek b)
         I- (edalb I)
         S (first b)
       ]
    (assoc ga :specials
      (reduce
        (fn [r [j [i m]]]
          (if (== 0 m)
            (assoc r (symbol (str "z" j)) (b (inc i)))
            r))
        {'I I 'I- I- 'S S} (map-indexed vector (filter (comp zero? peek) (map-indexed vector md)))))))

; the sign of the dual such that (= I (* x (∼ x)))
(defn with-duals [{bbg :basis-by-grade duals :duals {:syms [*]} :ops :as ga}]
  (assoc ga :duals-
    (into {} (map (fn [[a b]] [a (:scale (* ga a b))]) duals))))

(defn compare-blades [{ag :grade ab :bitmap :as a} {bg :grade bb :bitmap :as b}]
  (if (== ag bg)
    (compare ab bb)
    (compare ag bg)))

(defn with-help [{ops :ops :as a-ga}]
  (-> a-ga
    (assoc :help
       (into {} (map (juxt first (comp :doc meta)) (filter meta (keys ops)))))
    (assoc :examples
       (into {} (map (juxt first (comp :e.g. meta)) (filter meta (keys ops)))))))

(defn with-eigendecomposition [{mm :metric-mvs :as ga}]
  (if mm
    (let [{:keys [eigenvalues eigenvectors]} (eigendecompose ga)]
       (assoc ga :eigenvalues eigenvalues :eigenvectors eigenvectors))
     ga))

(defn ga-
  "Create a new GA from the given params:
   md - metric diagonal
   Metrics:

   Euclidean - all diagonal elements are 1
   Diagonal - metric factors only along diagonal
   Othonormal

   use :pqr to permute pqr e.g. [:r :q :p] so e0 is 0^2
  "
  ([{:keys [prefix base p q r pm qm rm md pqr]
     :or {prefix "e" base 0 pm 1N qm -1N rm 0N pqr [:p :q :r]}}]
    (let [md (or md (vec (apply concat (map {:p (repeat p pm) :q (repeat q qm) :r (repeat r rm)} pqr))))
          p (or p (count (filter (partial == 1) md)))
          q (or q (count (filter (partial == -1) md)))
          r (or r (count (filter (partial == 0) md)))
          d (+ p q r)
          bases (bases-of prefix base d)
          bio (reduce (fn [r [n b]] (assoc r (:bitmap b) b)) (vec (repeat (count bases) 0)) bases)
          bbg (vec (sort compare-blades (vals bases)))
          zv (vec (repeat (count bases) 0))
          m {
             :p p
             :q q
             :r r
             :metric md
             :basis bases
             :size (pow 2 d)
             :zv zv
             :basis-by-bitmap (reduce (fn [r [n b]] (assoc r (:bitmap b) n)) zv bases)
             :duals (zipmap bbg (rseq bbg))
             :basis-by-grade bbg
             :basis-in-order bio
             :ops (ga-ops)
             }
          ; note ops must be in order of dependence because of the partial later
          ops '[+ * 𝑒 ⍣ - _ *'' *- *0 •∧ • •' ⁻ ∧ ∼ ∨ ∨' h∨ ★ ⧄ op]
          ]
      (ga- m ops)))
  ([m ops]
    (ga- m
      (reduce
        (fn [r op] (assoc-in r [:ops op] (compare-G op))) m ops) ops))
  ([m g ops]
   (reduce
     (fn [r op]
       (update-in r [:ops op]
         (fn [g] (partial g r))))
     (-> g with-duals with-specials with-help) ops)))

(defn ga
  ([p q r]
    (ga "e" p q r))
  ([prefix p q r]
    (ga {:prefix prefix :p p :q q :r r}))
  ([prefix base p q r]
    (ga {:prefix prefix :base base :p p :q q :r r}))
  ([{:keys [prefix base p q r pm qm rm md mm mmga]
     :or {prefix "e" base 0 pm 1N qm -1N rm 0N} :as params}]
    (let [
           ; if creating a GA from a metric-multivectors, precompute their eigendecomposition
           ; now for later changes of basis. Such a change of basis needs a GA itself,
           ; so either use the one supplied or create one
           {:keys [eigenvalues eigenvectors]} (if mm (eigendecompose mmga mm) nil)
           g (ga- (if mm (assoc params :md (mapv :scale eigenvalues)) params))
           g' (assoc g :eigenvectors eigenvectors :eigenvalues eigenvalues
                       :metric-mvs mm :mmga (or mmga (ga- {:prefix 'b :p (count mm) :q 0 :r 0})))
         ]
      g')))

(defn multivector? [f]
  (and
    (or
       (and (list? f) (every? (fn [[c b]] (and (number? c) (and (symbol? b) (nil? (namespace b))))) (partition 2 f)))
       (and (vector? f) (every? (fn [[c b]] (and (or (number? c) (list? c)) (symbol? b))) (partition 2 f))))
    (== 0 (mod (count f) 2))))

(defn bladelike [x]
  (if (symbol? x)
    (let [basis (namespace x) n (name x)]
      (try {:basis (symbol basis) :scale (parse-double n)}
        (catch Exception e nil)))
    nil))

(defn multivector1? [f]
  (and (list? f)
    (every? (fn [x] (bladelike x)) f)))

(defmacro in-ga
  ([prefix p q r body]
    `(in-ga {:prefix ~prefix :base 0 :p ~p :q ~q :r ~r} ~body))
  ([prefix base p q r body]
    `(in-ga {:prefix ~prefix :base ~base :p ~p :q ~q :r ~r} ~body))
  ([p q r body]
    `(in-ga {:prefix e :base 0 :p ~p :q ~q :r ~r} ~body))
  ([{:keys [prefix base p q r mm pqr] :or {base 0 prefix 'e pqr [:p :q :r]}} body]
    (let [
          prefix (name prefix)
          ops '[+ * 𝑒 ⍣ -  _ *'' *- *0 •∧ • •' ⁻ ∧ ∼ ∨ ∨' h∨ ★ ⧄ op]
          specials '[I I- S]
           opz (into #{} ops)
           o (complement opz)
           s (filter symbol? (tree-seq seqable? seq body))
           e (vec (cons (symbol (str prefix "_")) (filter (fn [i] (or (starts-with? (name i) prefix)
                                                                    (and (namespace i) (starts-with? (namespace i) prefix)))) s)))
           e (mapv (fn [x] (if-let [{b :basis} (bladelike x)] b x)) e)
           ]
       `(let [{{:syms ~e :as ~'basis} :basis
               {:syms ~ops} :ops
               ~'basis-by-grade :basis-by-grade
               ~'basis-by-bitmap :basis-by-bitmap
               ~'basis-in-order :basis-in-order
               ~'basis :basis
               ~'duals :duals
               ~'duals- :duals-
               {:syms ~specials} :specials :as ~'ga} (ga {:prefix ~prefix :base ~base :p ~p :q ~q :r ~r :mm ~mm :pqr ~pqr})]
          ~(w/postwalk
             (fn [f]
               (cond
                 (and (multivector? f) (o (first f)))
                   (mapv (fn [[c b]] `(G ~b ~c)) (partition 2 f))
                 (and (multivector1? f) (o (first f)))
                   (mapv (fn [x] (let [{b :basis c :scale}
                                       (if (number? x)
                                         `{:basis ~(symbol (str prefix "_")) :scale ~x}
                                         (bladelike x))]
                                   `(G ~b ~c))) f)
                 :else f))
               body)))))

(defmethod print-method Blade [{:keys [basis scale]} writer]
  (doto writer
    (.write (str scale))
    (.write " ")
    (.write (str basis))))