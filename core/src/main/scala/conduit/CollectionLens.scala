package conduit

/** Lens primitives for collection-element access — the cases the [[Lens]] macro can't express because they index by
  * value rather than by case-class field name.
  *
  *   - [[at]] for `List`
  *   - [[atVector]] for `Vector`
  *   - [[key]] for `Map`
  *
  * All three focus `Option[V]` so absence is first-class. Semantics are uniform:
  *   - `Some(v)` at an in-range index updates; `Some(v)` at index == size appends; out-of-range / negative is a no-op.
  *   - `None` at in-range removes; out-of-range is a no-op.
  *
  * Lawful at every defined point: get-set, set-get, and set-set hold for any in-range index and for `Some(v)` at index
  * \== size (the append case). Out-of-range writes are no-ops by design — `set(m, Some(v))` then `get` returns the old
  * value, which is consistent with the get-set law (no observable change).
  */
object CollectionLens:

  extension [M, V](lens: Lens[M, List[V]])
    /** Focus element `index` in the focused list. */
    def at(index: Int): Lens[M, Option[V]] =
      new Lens[M, Option[V]]:
        def get(m: M): Option[V] = lens.get(m).lift(index)
        def set(m: M, optV: Option[V]): M =
          val xs = lens.get(m)
          optV match
            case Some(v) if index >= 0 && index < xs.length => lens.set(m, xs.updated(index, v))
            case Some(v) if index == xs.length              => lens.set(m, xs :+ v)
            case None if index >= 0 && index < xs.length    => lens.set(m, xs.patch(index, Nil, 1))
            case _                                          => m
  end extension

  extension [M, V](lens: Lens[M, Vector[V]])
    /** Focus element `index` in the focused vector. */
    def atVector(index: Int): Lens[M, Option[V]] =
      new Lens[M, Option[V]]:
        def get(m: M): Option[V] = lens.get(m).lift(index)
        def set(m: M, optV: Option[V]): M =
          val xs = lens.get(m)
          optV match
            case Some(v) if index >= 0 && index < xs.length => lens.set(m, xs.updated(index, v))
            case Some(v) if index == xs.length              => lens.set(m, xs :+ v)
            case None if index >= 0 && index < xs.length    => lens.set(m, xs.patch(index, Vector.empty, 1))
            case _                                          => m
  end extension

  extension [M, K, V](lens: Lens[M, Map[K, V]])
    /** Focus the value at `k` in the focused map. `Some(v)` adds or replaces; `None` removes (or is a no-op when the
      * key isn't present).
      */
    def key(k: K): Lens[M, Option[V]] =
      new Lens[M, Option[V]]:
        def get(m: M): Option[V] = lens.get(m).get(k)
        def set(m: M, optV: Option[V]): M =
          val mp = lens.get(m)
          optV match
            case Some(v)                => lens.set(m, mp + (k -> v))
            case None if mp.contains(k) => lens.set(m, mp - k)
            case None                   => m
  end extension

end CollectionLens
