package conduit

case class ActionResult[M, E] private[conduit] (
    newModel: M,
    next: Vector[Dispatchable[M, E]],
    dirty: Boolean = true, // true: potentially dirty; false: definitely clean (no state change)
)

object ActionResult:
  /** Build an [[ActionResult]] with optional follow-up [[Dispatchable]]s. */
  def apply[M, E](model: M, next: Dispatchable[M, E]*): ActionResult[M, E] =
    new ActionResult(model, next.toVector)

  /** Build a clean (no state change) [[ActionResult]] with optional follow-ups. */
  def clean[M, E](model: M, next: Dispatchable[M, E]*): ActionResult[M, E] =
    new ActionResult(model, next.toVector, dirty = false)
