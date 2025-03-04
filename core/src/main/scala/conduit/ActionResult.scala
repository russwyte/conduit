package conduit

case class ActionResult[M] private[conduit] (
    newModel: M,
    next: Vector[AppAction],
)

object ActionResult:
  def apply[M](model: M): ActionResult[M]                   = terminal(model)
  def apply[M](model: M, next: AppAction*): ActionResult[M] = ActionResult(model, next.toVector)
  def terminal[M](model: M): ActionResult[M]                = ActionResult(model, NoAction)
