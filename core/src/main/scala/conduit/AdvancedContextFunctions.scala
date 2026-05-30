package conduit

/** Lens-derivation combinators that compose with an ambient `Lens[M, V]` (Scala 3 context functions). They let you
  * derive new lenses without threading the base through every call.
  *
  * ## Deriving a sub-lens To derive a `Lens[M, W]` from an `Lens[M, V]` and a path `V => W`, use the existing
  * `Lens.apply`:
  * {{{
  *   val userLens: Lens[Model, User] = Optics[Model](_.user)
  *   val nameLens: Lens[Model, String] = userLens(_.name)
  * }}}
  * That already does what a context-function `focus` would do, with cleaner inference.
  *
  * ## What this module adds
  *   - [[when]] — Option view that only reads when a predicate holds.
  *   - [[validated]] — Either view that reads via a partial transform.
  *   - [[multi]] — pair two independent lenses into one.
  *   - [[navigate]] — Option-with-default view, lossy on the default branch.
  *   - [[For]] — ergonomic handler builder for a model with many small handlers.
  */
object AdvancedContextFunctions:

  /** Lens that exposes the ambient value as Some(_) only when it satisfies `predicate`; None otherwise. Setting
    * Some(invalid) is a no-op (preserves on-success lens laws).
    */
  def when[M, V](predicate: V => Boolean): Lens[M, V] ?=> Lens[M, Option[V]] = lens ?=>
    new Lens[M, Option[V]]:
      def get(m: M): Option[V] =
        val v = lens.get(m)
        if predicate(v) then Some(v) else None
      def set(m: M, optV: Option[V]): M =
        optV match
          case Some(v) if predicate(v) => lens.set(m, v)
          case _                       => m

  /** Lens that exposes a validated view of the ambient value via `to`, with `from` as the inverse on the success
    * branch. `set Left(_)` is a no-op (no inverse for failure).
    */
  def validated[M, V, W, E](to: V => Either[E, W], from: W => V): Lens[M, V] ?=> Lens[M, Either[E, W]] = lens ?=>
    new Lens[M, Either[E, W]]:
      def get(m: M): Either[E, W] = to(lens.get(m))
      def set(m: M, either: Either[E, W]): M =
        either match
          case Right(w) => lens.set(m, from(w))
          case Left(_)  => m

  /** Combine two independent lenses on the same model into one focused on a tuple. Lens laws hold iff the two lenses
    * don't overlap (no field is reachable through both).
    */
  def multi[M, V1, V2](lens1: Lens[M, V1], lens2: Lens[M, V2]): Lens[M, (V1, V2)] =
    new Lens[M, (V1, V2)]:
      def get(m: M): (V1, V2) = (lens1.get(m), lens2.get(m))
      def set(m: M, t: (V1, V2)): M =
        val (v1, v2) = t
        lens2.set(lens1.set(m, v1), v2)

  /** Safe-navigation lens. `path` extracts an inner value (or returns `defaultValue` if absent); `inverse` writes a new
    * W back into V. Lawfulness depends on the caller's `inverse`:
    *   - set-get holds when `path(inverse(v, w)) == Some(w)`
    *   - get-set is intrinsically lossy on the default branch: you can't tell None from Some(default).
    */
  def navigate[M, V, W](
      path: V => Option[W],
      defaultValue: W,
      inverse: (V, W) => V,
  ): Lens[M, V] ?=> Lens[M, W] = baseLens ?=>
    new Lens[M, W]:
      def get(m: M): W       = path(baseLens.get(m)).getOrElse(defaultValue)
      def set(m: M, w: W): M = baseLens.set(m, inverse(baseLens.get(m), w))

  /** Builder for a family of handlers over the same model M, all with the same error type E. Saves repetition when a
    * module defines several small handlers:
    *
    * {{{
    *   val For = AdvancedContextFunctions.For[Model, MyErr]
    *   val nameH  = For.field(_.user.name)  { case Rename(n) => updated(n) }
    *   val itemsH = For.field(_.items)      { case Add(x)    => update(_ :+ x) }
    * }}}
    *
    * Equivalent to `handle(Optics[Model](path))(body)` — same lens, same handler, just less typing.
    */
  inline def For[M <: Product, E](using o: Optics[M]): ForBuilder[M, E] = new ForBuilder[M, E]

  final class ForBuilder[M <: Product, E](using o: Optics[M]):
    /** Build a handler focused on the field at `path`. */
    inline def field[V](inline path: M => V)(body: Lens[M, V] ?=> ActionSelector[M, E]): ActionHandler[M, V, E] =
      handle[M, V, E](o(path))(body)

    /** Build a handler focused on the whole model. */
    def model(body: Lens[M, M] ?=> ActionSelector[M, E]): ActionHandler[M, M, E] =
      handle[M, M, E](o)(body)
  end ForBuilder

end AdvancedContextFunctions
