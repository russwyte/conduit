package conduit

/** A typeclass for fast equality checking.
  *
  * This allows library users to provide optimized equality implementations for their model types, enabling performance
  * optimizations when checking if state changes should trigger listener notifications.
  *
  * Common optimization strategies:
  *   - Reference equality checks first
  *   - Dirty flags or version numbers
  *   - Checksums or hashes
  *   - Field-level granular checks
  *   - Structural sharing awareness
  *
  * @tparam A
  *   the type for which to provide fast equality
  */
trait FastEq[-A]:
  /** Check if two values are equal.
    *
    * Implementations should be:
    *   - Reflexive: eqv(a, a) == true
    *   - Symmetric: eqv(a, b) == eqv(b, a)
    *   - Transitive: if eqv(a, b) && eqv(b, c) then eqv(a, c)
    *   - Consistent with standard equality for correctness
    *
    * @param lhs
    *   left-hand side value
    * @param rhs
    *   right-hand side value
    * @return
    *   true if the values are considered equal
    */
  def eqv(lhs: A, rhs: A): Boolean
end FastEq

object FastEq:
  /** Summon a FastEq instance for type A */
  def apply[A](using eq: FastEq[A]): FastEq[A] = eq

  /** Get a FastEq instance for type A, falling back to standard equality if none exists */
  def get[A](using eq: FastEq[A] = fromEquals[A]): FastEq[A] = eq

  /** Create a FastEq instance from a function */
  def instance[A](f: (A, A) => Boolean): FastEq[A] =
    new FastEq[A]:
      def eqv(lhs: A, rhs: A): Boolean = f(lhs, rhs)

  /** Default FastEq that delegates to standard equality */
  def fromEquals[A]: FastEq[A] =
    instance(_ == _)

  /** FastEq that uses reference equality first, then delegates to another FastEq */
  def withReferenceEquality[A <: AnyRef](fallback: FastEq[A]): FastEq[A] =
    instance { (lhs, rhs) =>
      (lhs eq rhs) || fallback.eqv(lhs, rhs)
    }

  /** FastEq for types with version/revision tracking */
  def fromVersion[A](version: A => Long): FastEq[A] =
    instance { (lhs, rhs) =>
      version(lhs) == version(rhs)
    }

  /** FastEq for types with hash-based equality */
  def fromHash[A](hash: A => Int): FastEq[A] =
    instance { (lhs, rhs) =>
      hash(lhs) == hash(rhs)
    }

  /** FastEq that checks a dirty flag first */
  def withDirtyFlag[A](isDirty: A => Boolean, fallback: FastEq[A]): FastEq[A] =
    instance { (lhs, rhs) =>
      val lhsDirty = isDirty(lhs)
      val rhsDirty = isDirty(rhs)

      // If both are clean, they haven't changed
      if !lhsDirty && !rhsDirty then true
      // If dirty flags differ, they're different
      else if lhsDirty != rhsDirty then false
      // Both dirty, need to check actual equality
      else fallback.eqv(lhs, rhs)
    }

  // Provide default instances for common types
  given FastEq[String]  = fromEquals[String]
  given FastEq[Int]     = fromEquals[Int]
  given FastEq[Long]    = fromEquals[Long]
  given FastEq[Double]  = fromEquals[Double]
  given FastEq[Boolean] = fromEquals[Boolean]

  given [A](using FastEq[A]): FastEq[Option[A]] =
    instance {
      case (None, None)       => true
      case (Some(a), Some(b)) => FastEq[A].eqv(a, b)
      case _                  => false
    }

  given [A](using FastEq[A]): FastEq[List[A]] =
    instance { (lhs, rhs) =>
      // Fast length check first
      if lhs.length != rhs.length then false
      else lhs.zip(rhs).forall((a, b) => FastEq[A].eqv(a, b))
    }

  given [A](using FastEq[A]): FastEq[Vector[A]] =
    instance { (lhs, rhs) =>
      // Fast length check first
      if lhs.length != rhs.length then false
      else lhs.zip(rhs).forall((a, b) => FastEq[A].eqv(a, b))
    }

  given [K, V](using eqK: FastEq[K], eqV: FastEq[V]): FastEq[Map[K, V]] =
    instance { (lhs, rhs) =>
      // Fast size check first
      if lhs.size != rhs.size then false
      else
        lhs.forall { (k, v) =>
          rhs.keys.exists(k2 => eqK.eqv(k, k2)) &&
          rhs.get(k).exists(v2 => eqV.eqv(v, v2))
        }
    }

  /** Derive FastEq for case classes using standard equality (can be overridden) */
  inline def derived[A]: FastEq[A] = fromEquals[A]

  // Extension methods for convenient usage
  extension [A](lhs: A)
    def ===(rhs: A)(using FastEq[A]): Boolean = FastEq[A].eqv(lhs, rhs)
    def !==(rhs: A)(using FastEq[A]): Boolean = !FastEq[A].eqv(lhs, rhs)
end FastEq
