package conduit

/** Bidirectional lens transformations that require explicit inverse functions. These provide more practical
  * map/flatMap-like operations for collections while maintaining lens laws.
  */
object BidirectionalLens:

  /** Isomorphism between two types - bidirectional transformation. */
  case class Iso[A, B](to: A => B, from: B => A):
    def reverse: Iso[B, A] = Iso(from, to)

  extension [M, V](lens: Lens[M, V])
    /** Transform a lens using an isomorphism. */
    def imap[W](iso: Iso[V, W]): Lens[M, W] =
      new Lens[M, W]:
        def get(m: M): W       = iso.to(lens.get(m))
        def set(m: M, w: W): M = lens.set(m, iso.from(w))

    /** Transform a lens with explicit to/from functions. */
    def xmap[W](to: V => W, from: W => V): Lens[M, W] =
      imap(Iso(to, from))
  end extension

  /** Collection-specific bidirectional transformations. */
  object Collections:

    /** Isomorphisms for common collection conversions. */
    object Isos:
      def listToVector[A]: Iso[List[A], Vector[A]] =
        Iso(_.toVector, _.toList)

      def vectorToList[A]: Iso[Vector[A], List[A]] =
        listToVector.reverse

      /** Set ↔ List. Note: Set→List→Set is lossy (drops order/duplicates). */
      def setToList[A]: Iso[Set[A], List[A]] =
        Iso(_.toList, _.toSet)

      def mapToList[K, V]: Iso[Map[K, V], List[(K, V)]] =
        Iso(_.toList, _.toMap)

      /** Option ↔ List (one-element). None ↔ Nil; multi-element lists collapse to head. */
      def optionToList[A]: Iso[Option[A], List[A]] =
        Iso(_.toList, _.headOption)
    end Isos

    extension [M, V](lens: Lens[M, List[V]])
      def mapListBi[W](to: V => W, from: W => V): Lens[M, List[W]] =
        lens.xmap(_.map(to), _.map(from))

      def asVector: Lens[M, Vector[V]] =
        lens.imap(Isos.listToVector)
    end extension

    extension [M, V](lens: Lens[M, Option[V]])
      def mapOptionBi[W](to: V => W, from: W => V): Lens[M, Option[W]] =
        lens.xmap(_.map(to), _.map(from))

      def asList: Lens[M, List[V]] =
        lens.imap(Isos.optionToList)

      /** Treat None as `default`. Setting `default` collapses to None. */
      def withDefault(default: V): Lens[M, V] =
        new Lens[M, V]:
          def get(m: M): V = lens.get(m).getOrElse(default)
          def set(m: M, v: V): M =
            if v == default then lens.set(m, None)
            else lens.set(m, Some(v))
    end extension

    extension [M, K, V](lens: Lens[M, Map[K, V]])
      def mapValuesBi[W](to: V => W, from: W => V): Lens[M, Map[K, W]] =
        lens.xmap(_.view.mapValues(to).toMap, _.view.mapValues(from).toMap)

      @annotation.targetName("mapAsList")
      def asList: Lens[M, List[(K, V)]] =
        lens.imap(Isos.mapToList)

      /** Focus the keyset. Setting drops entries whose keys aren't in the new set; never invents values. */
      def keySet: Lens[M, Set[K]] =
        new Lens[M, Set[K]]:
          def get(m: M): Set[K] = lens.get(m).keySet
          def set(m: M, keys: Set[K]): M =
            val currentMap = lens.get(m)
            lens.set(m, currentMap.filter((k, _) => keys.contains(k)))
    end extension
  end Collections

  /** Validation and filtering with lens laws. */
  object Validation:

    /** Lens that exposes the focused value only when it satisfies `predicate`; otherwise None. Setting Some(v) where
      * !predicate(v) is a no-op (preserves lens-set-set law on the success branch).
      */
    def validated[M, V](
        lens: Lens[M, V]
    )(predicate: V => Boolean): Lens[M, Option[V]] =
      new Lens[M, Option[V]]:
        def get(m: M): Option[V] =
          val value = lens.get(m)
          if predicate(value) then Some(value) else None
        def set(m: M, optV: Option[V]): M =
          optV match
            case Some(v) if predicate(v) => lens.set(m, v)
            case _                       => m

    /** Lens that exposes only elements satisfying `predicate`; setting filters incoming list as well. */
    def filteredList[M, V](
        lens: Lens[M, List[V]]
    )(predicate: V => Boolean): Lens[M, List[V]] =
      new Lens[M, List[V]]:
        def get(m: M): List[V] = lens.get(m).filter(predicate)
        def set(m: M, list: List[V]): M =
          lens.set(m, list.filter(predicate))
  end Validation

end BidirectionalLens
