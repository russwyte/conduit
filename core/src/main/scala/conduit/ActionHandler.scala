package conduit

import zio.*

type ActionFunction[M, E]      = M => IO[E, ActionResult[M]]
type ActionSelector[M, E]      = PartialFunction[AppAction, ActionFunction[M, E]]
type SelectorFunction[M, V, E] = Lens[M, V] ?=> ActionSelector[M, E]
type HandlerFunction[M, V, E]  = SelectorFunction[M, V, E] => ActionHandler[M, V, E]

def update[M, V, E](using lens: Lens[M, V])(f: V => V): ActionFunction[M, E] = m =>
  ZIO.succeed(ActionResult(lens.set(m, f(lens.get(m)))))
def updated[M, V, E](newValue: V)(using lens: Lens[M, V]): ActionFunction[M, E] = m =>
  ZIO.succeed(ActionResult(lens.set(m, newValue)))
def noChange[M, E]: ActionFunction[M, E]                             = m => ZIO.succeed(ActionResult.clean(m))
def noChange[M, E](next: AppAction): ActionFunction[M, E]            = m => ZIO.succeed(ActionResult.clean(m, next))
def effectOnly[M, E](effect: M => IO[E, Unit]): ActionFunction[M, E] = m => effect(m).map(_ => ActionResult.clean(m))

def handle[M <: Product: Optics as model, E]: HandlerFunction[M, M, E] = handle(model)

def handle[M, V, E](l: Lens[M, V])(f: SelectorFunction[M, V, E]): ActionHandler[M, V, E] =
  new ActionHandler[M, V, E]:
    val lens: Lens[M, V]                 = l
    override private[conduit] def handle = f(using lens)

/** Base trait for all action handlers, defining core functionality.
  */
trait ActionHandler[M, V, E]:
  private[conduit] def lens: Lens[M, V]
  private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction, ActionFunction[M, E]]

  def orElse[E2 >: E](next: ActionHandler[M, ?, E2]): ActionHandler[M, ?, E2] =
    new ComposedActionHandler(this, next)
  def >>[E2 >: E](next: ActionHandler[M, ?, E2]): ActionHandler[M, ?, E2] = orElse(next)

  def fold[E2 >: E](next: ActionHandler[M, ?, E2]): ActionHandler[M, ?, E2] =
    new FoldedActionHandler(this, next)
  def ++[E2 >: E](next: ActionHandler[M, ?, E2]): ActionHandler[M, ?, E2] = fold(next)

  private[conduit] def process(action: AppAction, m: M): IO[E, ActionResult[M]] =
    handle(using lens).applyOrElse(
      action,
      (a: AppAction) =>
        (_: M) => ZIO.fail(IllegalArgumentException(s"unhandled action ${a.getClass().getName()}").asInstanceOf[E]),
    )(m)
end ActionHandler

/** Composed handler for orElse composition.
  */
private[conduit] class ComposedActionHandler[M, E](
    first: ActionHandler[M, ?, ? <: E],
    next: ActionHandler[M, ?, ? <: E],
) extends ActionHandler[M, M, E]:
  private[conduit] val lens: Lens[M, M] = null
  override private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction, ActionFunction[M, E]] =
    case a =>
      first.handle(using first.lens).lift(a).orElse(next.handle(using next.lens).lift(a)) match
        case Some(f) => (m: M) => f(m).mapError(identity)
        case None =>
          (_: M) => ZIO.fail(IllegalArgumentException(s"unhandled action ${a.getClass().getName()}").asInstanceOf[E])

end ComposedActionHandler

/** Folded handler for fold composition.
  */
private[conduit] class FoldedActionHandler[M, E](
    first: ActionHandler[M, ?, ? <: E],
    next: ActionHandler[M, ?, ? <: E],
) extends ActionHandler[M, M, E]:
  private[conduit] val lens: Lens[M, M] = null

  override private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction, ActionFunction[M, E]] =
    case a =>
      (first.handle.lift(a), next.handle.lift(a)) match
        case (Some(f), None) => (m: M) => f(m).mapError(identity)
        case (None, Some(n)) => (m: M) => n(m).mapError(identity)
        case (Some(f), Some(n)) =>
          (model: M) =>
            for
              r1 <- f(model).mapError(identity)
              r2 <- n(r1.newModel).mapError(identity)
            yield r2.copy(next = r1.next ++ r2.next)
        case _ =>
          (_: M) => ZIO.fail(IllegalArgumentException(s"unhandled action ${a.getClass().getName()}").asInstanceOf[E])
  end handle
end FoldedActionHandler
