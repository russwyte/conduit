package conduit

import zio.*

type ActionFunction[M, E]      = M => IO[E, ActionResult[M, E]]
type ActionSelector[M, E]      = PartialFunction[AppAction[M, E], ActionFunction[M, E]]
type SelectorFunction[M, V, E] = Lens[M, V] ?=> ActionSelector[M, E]
type HandlerFunction[M, V, E]  = SelectorFunction[M, V, E] => ActionHandler[M, V, E]

def update[M, V, E](using lens: Lens[M, V])(f: V => V): ActionFunction[M, E] = m =>
  ZIO.succeed(ActionResult(lens.set(m, f(lens.get(m)))))
def updated[M, V, E](newValue: V)(using lens: Lens[M, V]): ActionFunction[M, E] = m =>
  ZIO.succeed(ActionResult(lens.set(m, newValue)))

/** Re-bind the ambient `Lens[M, V]` to a sub-focus `Lens[M, W]` for `body`, deriving the inner lens via the existing
  * `Lens.apply` macro. Lets a single `handle(lens) { ... }` block act on different sub-fields per case without
  * splitting into multiple handlers.
  *
  * {{{
  *   handle(Optics[Model]) {
  *     case Rename(n)  => focus(_.user.name)(updated(n))
  *     case Inc        => focus(_.count)(update(_ + 1))
  *     case AddItem(x) => focus(_.items)(update(_ :+ x))
  *   }
  * }}}
  */
inline def focus[M, V, W, E](using outer: Lens[M, V])(inline path: V => W)(
    body: Lens[M, W] ?=> ActionFunction[M, E]
): ActionFunction[M, E] =
  body(using outer(path))
def noChange[M, E]: ActionFunction[M, E] = m => ZIO.succeed(ActionResult.clean(m))
def noChange[M, E](next: Dispatchable[M, E]): ActionFunction[M, E] =
  m => ZIO.succeed(ActionResult.clean(m, next))
def effectOnly[M, E](effect: M => IO[E, Unit]): ActionFunction[M, E] = m => effect(m).map(_ => ActionResult.clean(m))

def handle[M <: Product: Optics as model, E]: HandlerFunction[M, M, E] = handle(model)

def handle[M, V, E](l: Lens[M, V])(f: SelectorFunction[M, V, E]): ActionHandler[M, V, E] =
  new ActionHandler[M, V, E]:
    val lens: Lens[M, V]                 = l
    override private[conduit] def handle = f(using lens)

/** Default unhandled mapping: produces a defect (untyped failure). Used when the user has not supplied an `onUnhandled`
  * mapping; appropriate for handlers whose `E = Nothing` (no typed failures expected).
  */
private[conduit] def defaultUnhandled[M, E](action: AppAction[M, E]): E =
  throw RuntimeException(s"Unhandled action ${action.getClass.getName}; supply onUnhandled to map to a typed error.")

/** Base trait for all action handlers, defining core functionality.
  */
trait ActionHandler[M, V, E]:
  self =>
  private[conduit] def lens: Lens[M, V]
  private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction[M, E], ActionFunction[M, E]]

  /** How an unhandled action is mapped to the error channel. Defaults to a defect; override with [[onUnhandled]]. */
  private[conduit] def unhandled: AppAction[M, E] => E = defaultUnhandled[M, E]

  /** Provide a typed error for actions this handler doesn't match. */
  def onUnhandled(f: AppAction[M, E] => E): ActionHandler[M, V, E] =
    new ActionHandler[M, V, E]:
      private[conduit] val lens: Lens[M, V]   = self.lens
      override private[conduit] def handle    = self.handle
      override private[conduit] val unhandled = f

  /** Widen the error channel to a supertype. */
  def widen[E2 >: E]: ActionHandler[M, V, E2] = this.asInstanceOf[ActionHandler[M, V, E2]]

  def orElse[E2 >: E](next: ActionHandler[M, ?, E2]): ActionHandler[M, ?, E2] =
    new ComposedActionHandler(this, next)
  def >>[E2 >: E](next: ActionHandler[M, ?, E2]): ActionHandler[M, ?, E2] = orElse(next)

  def fold[E2 >: E](next: ActionHandler[M, ?, E2]): ActionHandler[M, ?, E2] =
    new FoldedActionHandler(this, next)
  def ++[E2 >: E](next: ActionHandler[M, ?, E2]): ActionHandler[M, ?, E2] = fold(next)

  private[conduit] def process(action: AppAction[M, E], m: M): IO[E, ActionResult[M, E]] =
    handle(using lens).applyOrElse(
      action,
      (a: AppAction[M, E]) => (_: M) => ZIO.fail(unhandled(a)),
    )(m)
end ActionHandler

/** Composed handler for orElse composition. */
private[conduit] class ComposedActionHandler[M, E](
    first: ActionHandler[M, ?, ? <: E],
    next: ActionHandler[M, ?, ? <: E],
) extends ActionHandler[M, M, E]:
  private[conduit] val lens: Lens[M, M] = null
  override private[conduit] val unhandled: AppAction[M, E] => E =
    a => first.unhandled.asInstanceOf[AppAction[M, E] => E](a)
  private val firstHandle =
    first.handle(using first.lens).asInstanceOf[PartialFunction[AppAction[M, E], ActionFunction[M, E]]]
  private val nextHandle =
    next.handle(using next.lens).asInstanceOf[PartialFunction[AppAction[M, E], ActionFunction[M, E]]]
  override private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction[M, E], ActionFunction[M, E]] =
    firstHandle.orElse(nextHandle)
end ComposedActionHandler

/** Folded handler for fold composition. */
private[conduit] class FoldedActionHandler[M, E](
    first: ActionHandler[M, ?, ? <: E],
    next: ActionHandler[M, ?, ? <: E],
) extends ActionHandler[M, M, E]:
  private[conduit] val lens: Lens[M, M] = null
  override private[conduit] val unhandled: AppAction[M, E] => E =
    a => first.unhandled.asInstanceOf[AppAction[M, E] => E](a)
  private val firstHandle =
    first.handle(using first.lens).asInstanceOf[PartialFunction[AppAction[M, E], ActionFunction[M, E]]]
  private val nextHandle =
    next.handle(using next.lens).asInstanceOf[PartialFunction[AppAction[M, E], ActionFunction[M, E]]]
  override private[conduit] def handle: Lens[M, ?] ?=> PartialFunction[AppAction[M, E], ActionFunction[M, E]] =
    new PartialFunction[AppAction[M, E], ActionFunction[M, E]]:
      def isDefinedAt(a: AppAction[M, E]): Boolean = firstHandle.isDefinedAt(a) || nextHandle.isDefinedAt(a)
      def apply(a: AppAction[M, E]): ActionFunction[M, E] =
        (firstHandle.lift(a), nextHandle.lift(a)) match
          case (Some(f), None) => f
          case (None, Some(n)) => n
          case (Some(f), Some(n)) =>
            (model: M) =>
              for
                r1 <- f(model)
                r2 <- n(r1.newModel)
              yield r2.copy(next = r1.next ++ r2.next)
          case _ => sys.error("unreachable: guarded by isDefinedAt")
end FoldedActionHandler
