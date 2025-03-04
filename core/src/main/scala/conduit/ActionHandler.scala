package conduit

import zio.*

type ActionFunction[M]      = M => Task[ActionResult[M]]
type ActionSelector[M]      = PartialFunction[AppAction, ActionFunction[M]]
type SelectorFunction[M, V] = Lens[M, V] ?=> ActionSelector[M]
type HandlerFunction[M, V]  = SelectorFunction[M, V] => ActionHandler[M, V]

def update[M, V](using lens: Lens[M, V])(f: V => V): ActionFunction[M] = m =>
  ZIO.succeed(ActionResult(lens.set(m, f(lens.get(m)))))
def updated[M, V](newValue: V)(using lens: Lens[M, V]): ActionFunction[M] = m =>
  ZIO.succeed(ActionResult(lens.set(m, newValue)))
def noChange[M](using lens: Lens[M, M]): ActionFunction[M]                  = m => ZIO.succeed(ActionResult.terminal(m))
def noChange[M](next: AppAction)(using lens: Lens[M, M]): ActionFunction[M] = m => ZIO.succeed(ActionResult(m, next))
def effectOnly[M](effect: M => Task[Unit])(using lens: Lens[M, M]): ActionFunction[M] = m =>
  effect(m).map(_ => ActionResult(m))

def handle[M]: HandlerFunction[M, M] = handle(Lens.identity)

def handle[M, V](l: Lens[M, V])(f: SelectorFunction[M, V]): ActionHandler[M, V] =
  new ActionHandler[M, V]:
    val lens: Lens[M, V]                 = l
    override private[conduit] def handle = f(using lens)

/** Base trait for all action handlers, defining core functionality.
  */
trait ActionHandler[M, V]:
  private[conduit] def lens: Lens[M, V]
  private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction, ActionFunction[M]]

  def orElse(next: ActionHandler[M, ?]): ActionHandler[M, ?] =
    new ComposedActionHandler(this, next)
  def >>(next: ActionHandler[M, ?]): ActionHandler[M, ?] = orElse(next)

  def fold(next: ActionHandler[M, ?]): ActionHandler[M, ?] =
    new FoldedActionHandler(this, next)
  def ++(next: ActionHandler[M, ?]): ActionHandler[M, ?] = fold(next)

  private[conduit] def process(action: AppAction, m: M): Task[ActionResult[M]] =
    handle(using lens).applyOrElse(
      action,
      (a: AppAction) => _ => ZIO.fail(IllegalArgumentException(s"unhandled action ${a.getClass().getName()}")),
    )(m)
end ActionHandler

/** Composed handler for orElse composition.
  */
private[conduit] class ComposedActionHandler[M](
    first: ActionHandler[M, ?],
    next: ActionHandler[M, ?],
) extends ActionHandler[M, M]:
  private[conduit] val lens: Lens[M, M] = null
  override private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction, ActionFunction[M]] =
    first.handle(using first.lens) orElse next.handle(using next.lens)

end ComposedActionHandler

/** Folded handler for fold composition.
  */
private[conduit] class FoldedActionHandler[M](
    first: ActionHandler[M, ?],
    next: ActionHandler[M, ?],
) extends ActionHandler[M, M]:
  private[conduit] val lens: Lens[M, M] = null

  override private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction, ActionFunction[M]] =
    case a =>
      (first.handle.lift(a), next.handle.lift(a)) match
        case (Some(f), None) => f
        case (None, Some(n)) => n
        case (Some(f), Some(n)) =>
          model =>
            for
              r1 <- f(model)
              r2 <- n(r1.newModel)
            yield r2.copy(next = r1.next ++ r2.next)
        case _ =>
          throw IllegalArgumentException(s"unhandled action ${a.getClass().getName()}")
  end handle
end FoldedActionHandler
