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

trait FastEqLowPriority:
  /** Used when no more-specific `given FastEq[A]` is in scope. */
  given fallback[A]: FastEq[A] = FastEq.fromEquals[A]

object FastEq extends FastEqLowPriority:
  /** Summon a FastEq instance for type A */
  def apply[A: FastEq as eq]: FastEq[A] = eq

  /** Get a FastEq instance for type A. Prefers a `given`; otherwise [[fallback]]. */
  def get[A: FastEq as eq]: FastEq[A] = eq

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

  given [A: FastEq as eq]: FastEq[Option[A]] =
    instance {
      case (None, None)       => true
      case (Some(a), Some(b)) => eq.eqv(a, b)
      case _                  => false
    }

  given [A: FastEq as eq]: FastEq[List[A]] =
    instance { (lhs, rhs) =>
      if lhs.length != rhs.length then false
      else lhs.zip(rhs).forall((a, b) => eq.eqv(a, b))
    }

  given [A: FastEq as eq]: FastEq[Vector[A]] =
    instance { (lhs, rhs) =>
      if lhs.length != rhs.length then false
      else lhs.zip(rhs).forall((a, b) => eq.eqv(a, b))
    }

  /** FastEq for Map.
    *
    * Uses `eqK` consistently for key matching (rather than mixing it with the underlying Map's `==` lookup, which would
    * give wrong answers when `eqK` is coarser than `==`). O(n²) worst case when keys are not `==`-equal.
    */
  given [K: FastEq as eqK, V: FastEq as eqV]: FastEq[Map[K, V]] =
    instance { (lhs, rhs) =>
      if lhs.size != rhs.size then false
      else
        lhs.forall { (k, v) =>
          rhs.exists((k2, v2) => eqK.eqv(k, k2) && eqV.eqv(v, v2))
        }
    }

  /** `derives FastEq` defaults to standard `==` equality. Provide an explicit `given FastEq[A]` (e.g. via
    * [[fromVersion]], [[fromHash]], [[withReferenceEquality]], or [[instance]]) to opt into a faster strategy.
    */
  inline def derived[A]: FastEq[A] = fromEquals[A]

  // Extension methods for convenient usage
  extension [A: FastEq as eq](lhs: A)
    def ===(rhs: A): Boolean = eq.eqv(lhs, rhs)
    def !==(rhs: A): Boolean = !eq.eqv(lhs, rhs)
end FastEq
