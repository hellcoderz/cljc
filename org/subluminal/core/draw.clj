(in-ns 'org.subluminal.xproto)

;; graphics contexts

(bin/defbinary gc-valuemask
  [internal ::card32
    {:bitmask {:function 0 :plane-mask 1
               :foreground 2 :background 3
               :line-width 4 :line-style 5
               :cap-style 6 :join-style 7
               :fill-style 8 :fill-rule 9
               :tile 10 :stipple 11
               :tile-stipple-x-origin 12 :tile-stipple-y-origin 13
               :font 14 :subwindow-mode 15
               :graphics-exposures 16
               :clip-x-origin 17 :clip-y-origin 18
               :clip-mask 19 :dash-offset 20
               :dashes 21 :arc-mode 22}}])

(bin/defbinary [gc-values mask]
  (select mask
    :function
    (do [:function ::card8
          {:xenum {:clear 0 :and 1 :and-reverse 2
                   :copy 3 :and-inverted 4 :no-op 5
                   :xor 6 :or 7 :nor 8 :equiv 9
                   :invert 10 :or-reverse 11
                   :copy-inverted 12 :or-inverted 13
                   :nand 14 :set 15}}]
      (skip 3))
    :plane-mask [:plane-mask ::card32]
    :foreground [:foreground ::card32]
    :background [:background ::card32]
    :line-width (do [:line-width ::card16] (skip 2))
    :line-style
    (do [:line-style ::card8
          {:xenum {:solid 0 :on-off-dash 1 :double-dash 2}}]
      (skip 3))
    :cap-style
    (do [:cap-style ::card8
          {:xenum {:not-last 0 :butt 1 :round 2 :projecting 3}}]
      (skip 3))
    :join-style
    (do [:join-style ::card8
          {:xenum {:miter 0 :round 1 :bevel 2}}]
      (skip 3))
    :fill-style
    (do [:fill-style ::card8
          {:xenum {:solid 0 :tiled 1 :stippled 2 :opaque-stippled 3}}]
      (skip 3))
    :fill-rule
    (do [:fill-rule ::card8
          {:xenum {:even-odd 0 :winding 1}}]
      (skip 3))
    :tile [:tile ::pixmap]
    :stipple [:stipple ::pixmap]
    :tile-stipple-x-origin (do [:tile-stipple-x-origin ::bin/int16] (skip 2))
    :tile-stipple-y-origin (do [:tile-stipple-y-origin ::bin/int16] (skip 2))
    :font [:font ::font]
    :subwindow-mode
    (do [:subwindow-mode ::card8
          {:xenum {:clip-by-children 0 :include-inferiors 1}}]
      (skip 3))
    :graphics-exposures (do [:graphics-exposures ::card8] (skip 3))
    :clip-x-origin (do [:clip-x-origin ::bin/int16] (skip 2))
    :clip-y-origin (do [:clip-y-origin ::bin/int16] (skip 2))
    :clip-mask [:clip-mask ::pixmap]
    :dash-offset (do [:dash-offset ::card16] (skip 2))
    :dashes (do [:dashes ::card8] (skip 3))
    :arc-mode
    (do [:arc-mode ::card8
          {:xenum {:chord 0 :pie-slice 1}}]
      (skip 3))))

(define-core-op
  (::create-gc (+ 4 (count (:value-mask create-gc)))
    (skip 1)
    [:cid *alloc-resource*]
    [:drawable ::drawable]
    [:value-mask ::gc-valuemask]
    [:values ::gc-values]))

(define-core-op
  (::change-gc (+ 3 (count (:value-mask change-gc)))
    (skip 1)
    [:gc ::gcontext]
    [:value-mask ::gc-valuemask]
    [:values ::gc-values]))

;; Helper functions to manipulate gcs
(defn create-gc
  ([opts] (create-gc *display* (:root (get-screen)) opts))
  ([wnd opts] (create-gc *display* wnd opts))
  ([dpy wnd opts]
   (alloc-x dpy ::create-gc
            {:drawable wnd
             :value-mask (set (keys opts))
             :values opts)))

(defn change-gc
  ([gc opts] (change-gc *display* gc opts))
  ([dpy gc opts]
   (send-x dpy ::change-gc
           {:gc gc
            :value-mask (set (keys opts))
            :values opts})))

(define-core-op
  (::copy-gc 4
    (skip 1)
    [:source ::gcontext]
    [:dest   ::gcontext]
    [:value-mask ::gc-valuemask]))
