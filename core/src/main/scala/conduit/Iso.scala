package conduit

/** Isomorphism between two types — a faithful bidirectional transformation.
  *
  * Lawfulness is the caller's contract: for `Iso(to, from)` to be a true iso, `from(to(a)) == a` and `to(from(b)) == b`
  * for all `a`, `b`. When the contract holds, lenses derived via [[Iso.imap]] / [[Iso.xmap]] preserve the lens laws of
  * the source lens.
  *
  * Use the extensions in this object's companion via `import conduit.Iso.*`:
  * {{{
  *   import conduit.Iso.*
  *   val intToStr: Iso[Int, String] = Iso(_.toString, _.toInt)
  *   val asStr: Lens[Model, String] = Optics[Model](_.count).imap(intToStr)
  * }}}
  */
final case class Iso[A, B](to: A => B, from: B => A):
  def reverse: Iso[B, A] = Iso(from, to)
end Iso

object Iso:
  /** The identity iso. */
  def id[A]: Iso[A, A] = Iso(identity, identity)

  extension [M, V](lens: Lens[M, V])
    /** Re-type the focus through an [[Iso]]. */
    def imap[W](iso: Iso[V, W]): Lens[M, W] =
      new Lens[M, W]:
        def get(m: M): W       = iso.to(lens.get(m))
        def set(m: M, w: W): M = lens.set(m, iso.from(w))

    /** Re-type the focus through explicit `to` / `from` functions. Equivalent to `imap(Iso(to, from))`. */
    def xmap[W](to: V => W, from: W => V): Lens[M, W] =
      imap(Iso(to, from))
  end extension
end Iso
