package conduit

import zio.*

/** Collection-aware action helpers. Each function takes an ambient `Lens[M, C]` focused on a collection-shaped slice of
  * the model and returns an [[ActionFunction]] that updates that focus.
  *
  * Pattern:
  * {{{
  *   given Lens[Model, List[Item]] = Optics[Model](_.items)
  *   val handler = handle(Optics[Model](_.items)):
  *     case AddItem(x) => appendToList(x)
  *     case Drop(p)    => filterList(p)
  * }}}
  *
  * No-op cases (out-of-range indexes, missing keys, predicate failures on `whenCondition` / `safeTransform`) return
  * [[ActionResult.clean]] so the conduit knows no state change occurred.
  */
object ActionHandlerCollections:

  // ── Option ────────────────────────────────────────────────────────────────

  def updateOption[M, V, E](f: V => V)(using lens: Lens[M, Option[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).map(f))))

  def setSome[M, V, E](value: V)(using lens: Lens[M, Option[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, Some(value))))

  def setNone[M, V, E](using lens: Lens[M, Option[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, None)))

  /** Drop the focused Option's value if it doesn't satisfy `predicate`. */
  def filterOptionAction[M, V, E](predicate: V => Boolean)(using lens: Lens[M, Option[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).filter(predicate))))

  /** If the focus is None, write `default`; otherwise leave the value alone. */
  def orElseOption[M, V, E](default: Option[V])(using lens: Lens[M, Option[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).orElse(default))))

  // ── List ──────────────────────────────────────────────────────────────────

  def updateList[M, V, E](f: V => V)(using lens: Lens[M, List[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).map(f))))

  def filterListAction[M, V, E](predicate: V => Boolean)(using lens: Lens[M, List[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).filter(predicate))))

  def appendToList[M, V, E](items: V*)(using lens: Lens[M, List[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m) ++ items)))

  def prependToList[M, V, E](items: V*)(using lens: Lens[M, List[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, items.toList ++ lens.get(m))))

  def removeFromList[M, V, E](item: V)(using lens: Lens[M, List[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).filterNot(_ == item))))

  /** Update the element at `index` to `value`. Out-of-range or negative indexes leave the model untouched (returns
    * clean).
    */
  def updateAtListIndex[M, V, E](index: Int, value: V)(using lens: Lens[M, List[V]]): ActionFunction[M, E] =
    m =>
      val xs = lens.get(m)
      if index >= 0 && index < xs.length then ZIO.succeed(ActionResult(lens.set(m, xs.updated(index, value))))
      else ZIO.succeed(ActionResult.clean(m))

  // ── Vector ────────────────────────────────────────────────────────────────

  def updateVector[M, V, E](f: V => V)(using lens: Lens[M, Vector[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).map(f))))

  def filterVectorAction[M, V, E](predicate: V => Boolean)(using lens: Lens[M, Vector[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).filter(predicate))))

  def appendToVector[M, V, E](items: V*)(using lens: Lens[M, Vector[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m) ++ items)))

  def prependToVector[M, V, E](items: V*)(using lens: Lens[M, Vector[V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, items.toVector ++ lens.get(m))))

  /** Update the element at `index`. Out-of-range or negative indexes leave the model untouched (returns clean). */
  def updateAtVectorIndex[M, V, E](index: Int, value: V)(using lens: Lens[M, Vector[V]]): ActionFunction[M, E] =
    m =>
      val v = lens.get(m)
      if index >= 0 && index < v.length then ZIO.succeed(ActionResult(lens.set(m, v.updated(index, value))))
      else ZIO.succeed(ActionResult.clean(m))

  // ── Map ───────────────────────────────────────────────────────────────────

  def updateMapValues[M, K, V, E](f: V => V)(using lens: Lens[M, Map[K, V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).view.mapValues(f).toMap)))

  def filterMapValues[M, K, V, E](predicate: V => Boolean)(using lens: Lens[M, Map[K, V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m).filter((_, v) => predicate(v)))))

  def putInMap[M, K, V, E](key: K, value: V)(using lens: Lens[M, Map[K, V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m) + (key -> value))))

  def removeFromMap[M, K, V, E](key: K)(using lens: Lens[M, Map[K, V]]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, lens.get(m) - key)))

  /** Apply `f` to the value at `key`. If the key is absent, the model is untouched (returns clean). */
  def updateAtMapKey[M, K, V, E](key: K, f: V => V)(using lens: Lens[M, Map[K, V]]): ActionFunction[M, E] =
    m =>
      val mp = lens.get(m)
      mp.get(key) match
        case Some(v) => ZIO.succeed(ActionResult(lens.set(m, mp + (key -> f(v)))))
        case None    => ZIO.succeed(ActionResult.clean(m))

  // ── Generic context-function patterns ─────────────────────────────────────

  /** Apply `op` only when the focus satisfies `predicate`. Otherwise no-op (clean). */
  def whenCondition[M, V, E](predicate: V => Boolean)(op: V => V)(using lens: Lens[M, V]): ActionFunction[M, E] =
    m =>
      val v = lens.get(m)
      if predicate(v) then ZIO.succeed(ActionResult(lens.set(m, op(v))))
      else ZIO.succeed(ActionResult.clean(m))

  /** Apply `transform`; on `Left` the model is untouched (returns clean). The error value is discarded — use a real
    * typed error in your handler if you need to surface it.
    */
  def safeTransform[M, V, X, E](transform: V => Either[X, V])(using lens: Lens[M, V]): ActionFunction[M, E] =
    m =>
      transform(lens.get(m)) match
        case Right(v) => ZIO.succeed(ActionResult(lens.set(m, v)))
        case Left(_)  => ZIO.succeed(ActionResult.clean(m))

  /** Apply each operation in sequence to the focused value. Empty list is a write-through identity (still marks dirty
    * because it ran a set). For a true no-op use [[whenCondition]].
    */
  def sequenceOps[M, V, E](ops: List[V => V])(using lens: Lens[M, V]): ActionFunction[M, E] =
    m => ZIO.succeed(ActionResult(lens.set(m, ops.foldLeft(lens.get(m))((a, op) => op(a)))))

  /** Find the first element matching `finder` in the focused list and replace it with `updater(found)`. If no match,
    * no-op (clean). All occurrences of the matched value are replaced.
    */
  def findAndUpdate[M, V, E](finder: List[V] => Option[V])(updater: V => V)(using
      lens: Lens[M, List[V]]
  ): ActionFunction[M, E] =
    m =>
      val xs = lens.get(m)
      finder(xs) match
        case Some(found) => ZIO.succeed(ActionResult(lens.set(m, xs.map(v => if v == found then updater(v) else v))))
        case None        => ZIO.succeed(ActionResult.clean(m))

  /** Apply `op` to the focused list iff `cond(list)` holds. Otherwise no-op (clean). */
  def batchOperation[M, V, E](cond: List[V] => Boolean)(op: List[V] => List[V])(using
      lens: Lens[M, List[V]]
  ): ActionFunction[M, E] =
    m =>
      val xs = lens.get(m)
      if cond(xs) then ZIO.succeed(ActionResult(lens.set(m, op(xs))))
      else ZIO.succeed(ActionResult.clean(m))

end ActionHandlerCollections
