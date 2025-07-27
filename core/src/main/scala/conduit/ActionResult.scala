package conduit

case class ActionResult[M] private[conduit] (
    newModel: M,
    next: Vector[AppAction],
    dirty: Boolean = true, // true potentially dirty false definitely clean (no state change)
)

object ActionResult:
  private[conduit] def apply[M](model: M): ActionResult[M]                   = terminal(model)
  private[conduit] def apply[M](model: M, next: AppAction*): ActionResult[M] = ActionResult(model, next.toVector)
  private[conduit] def clean[M](model: M): ActionResult[M]                   = terminal(model, false)
  private[conduit] def clean[M](model: M, next: AppAction*): ActionResult[M] =
    ActionResult(model, next.toVector, dirty = false)
  private[conduit] def terminal[M](model: M, dirty: Boolean = true): ActionResult[M] =
    new ActionResult(model, Vector(NoAction), dirty)
