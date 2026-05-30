package conduit

/** Collection-aware lens extensions: focus on elements, keys, and slices of `Option`, `List`, `Map`, `Vector`.
  *
  * All lenses here obey lens laws — `set` is a real write-back. For read-only transformations whose forward function
  * isn't invertible (e.g. `map`, `flatMap` over a collection), use [[BidirectionalLens]] which requires the caller to
  * supply an inverse.
  */
object CollectionLens:

  extension [M, V](lens: Lens[M, Option[V]])
    /** Filter: get returns Some only when the value satisfies `predicate`. set passes through only valid values; an
      * invalid Some is rejected, None clears the slot.
      */
    def filterOption(predicate: V => Boolean): Lens[M, Option[V]] =
      new Lens[M, Option[V]]:
        def get(m: M): Option[V] = lens.get(m).filter(predicate)
        def set(m: M, optV: Option[V]): M =
          optV match
            case Some(v) if predicate(v) => lens.set(m, optV)
            case None                    => lens.set(m, None)
            case _                       => m

    /** View as `V` with `default` for None; setting writes through as `Some(_)`. */
    def getOrElse(default: V): Lens[M, V] =
      new Lens[M, V]:
        def get(m: M): V       = lens.get(m).getOrElse(default)
        def set(m: M, v: V): M = lens.set(m, Some(v))
  end extension

  extension [M, V](lens: Lens[M, List[V]])
    /** Filter list. `set` retains only elements satisfying `predicate`. */
    def filterList(predicate: V => Boolean): Lens[M, List[V]] =
      new Lens[M, List[V]]:
        def get(m: M): List[V]           = lens.get(m).filter(predicate)
        def set(m: M, listV: List[V]): M = lens.set(m, listV.filter(predicate))

    /** Focus element at `index`. `Some(v)` writes (or appends iff index == size); `None` removes; out-of-bounds is a
      * no-op.
      */
    def at(index: Int): Lens[M, Option[V]] =
      new Lens[M, Option[V]]:
        def get(m: M): Option[V] = lens.get(m).lift(index)
        def set(m: M, optV: Option[V]): M =
          val currentList = lens.get(m)
          optV match
            case Some(v) if index >= 0 && index < currentList.length =>
              lens.set(m, currentList.updated(index, v))
            case Some(v) if index == currentList.length =>
              lens.set(m, currentList :+ v)
            case None if index >= 0 && index < currentList.length =>
              lens.set(m, currentList.patch(index, Nil, 1))
            case _ => m
        end set

    def head: Lens[M, Option[V]] = at(0)

    /** Focus all elements after the head. Setting prepends the existing head onto the new tail; if the list was empty,
      * setting yields the new tail as the whole list.
      */
    def tail: Lens[M, List[V]] =
      new Lens[M, List[V]]:
        def get(m: M): List[V] = lens.get(m).drop(1)
        def set(m: M, listV: List[V]): M =
          val currentList = lens.get(m)
          currentList.headOption match
            case Some(h) => lens.set(m, h :: listV)
            case None    => lens.set(m, listV)
  end extension

  extension [M, K, V](lens: Lens[M, Map[K, V]])
    /** Filter map by `(K,V)` predicate. `set` writes the supplied map verbatim (caller decides what's valid). */
    def filterMap(predicate: (K, V) => Boolean): Lens[M, Map[K, V]] =
      new Lens[M, Map[K, V]]:
        def get(m: M): Map[K, V]          = lens.get(m).filter(predicate.tupled)
        def set(m: M, mapV: Map[K, V]): M = lens.set(m, mapV)

    /** Focus on a specific key. `Some(v)` adds/updates; `None` removes. */
    def key(k: K): Lens[M, Option[V]] =
      new Lens[M, Option[V]]:
        def get(m: M): Option[V] = lens.get(m).get(k)
        def set(m: M, optV: Option[V]): M =
          val currentMap = lens.get(m)
          optV match
            case Some(v) => lens.set(m, currentMap + (k -> v))
            case None    => lens.set(m, currentMap - k)

    /** Focus the keyset. Setting drops entries whose keys aren't in the new set. */
    def keys: Lens[M, Set[K]] =
      new Lens[M, Set[K]]:
        def get(m: M): Set[K] = lens.get(m).keySet
        def set(m: M, ks: Set[K]): M =
          val currentMap = lens.get(m)
          lens.set(m, currentMap.filter((k, _) => ks.contains(k)))
  end extension

  extension [M, V](lens: Lens[M, Vector[V]])
    /** Focus element at `index` in a Vector — same semantics as List `at`. */
    def atVector(index: Int): Lens[M, Option[V]] =
      new Lens[M, Option[V]]:
        def get(m: M): Option[V] = lens.get(m).lift(index)
        def set(m: M, optV: Option[V]): M =
          val cur = lens.get(m)
          optV match
            case Some(v) if index >= 0 && index < cur.length => lens.set(m, cur.updated(index, v))
            case Some(v) if index == cur.length              => lens.set(m, cur :+ v)
            case None if index >= 0 && index < cur.length    => lens.set(m, cur.patch(index, Vector.empty, 1))
            case _                                           => m
  end extension

  /** Effectful traversals — apply an `Either`-returning function across a focused collection, short-circuit on first
    * failure. These don't return lenses; they return `M => Either[E, _]`.
    */
  object Traverse:
    def traverseList[M, V, W, E](lens: Lens[M, List[V]])(f: V => Either[E, W]): M => Either[E, List[W]] =
      (m: M) =>
        lens.get(m).foldLeft(Right(List.empty[W]): Either[E, List[W]]) { (acc, v) =>
          for
            xs <- acc
            w  <- f(v)
          yield xs :+ w
        }

    def traverseOption[M, V, W, E](lens: Lens[M, Option[V]])(f: V => Either[E, W]): M => Either[E, Option[W]] =
      (m: M) =>
        lens.get(m) match
          case Some(v) => f(v).map(Some(_))
          case None    => Right(None)
  end Traverse

  /** Prism-like focus on a sum-type case. `get` returns Some only for the matching case; `set Some(w)` injects; `set
    * None` is a no-op (no way to invent a different case).
    */
  object Prism:
    def partial[M, V, W](
        lens: Lens[M, V]
    )(extract: V => Option[W], inject: W => V): Lens[M, Option[W]] =
      new Lens[M, Option[W]]:
        def get(m: M): Option[W] = extract(lens.get(m))
        def set(m: M, optW: Option[W]): M =
          optW match
            case Some(w) => lens.set(m, inject(w))
            case None    => m
  end Prism

end CollectionLens
