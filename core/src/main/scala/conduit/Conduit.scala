package conduit

import zio.*
import zio.stream.ZStream

/** Unidirectional state container.
  *
  * Dispatch is sequential: actions are pulled from the queue one at a time and processed by the handler. Listeners are
  * notified in `Set` iteration order; a listener that fails its effect will abort the dispatch loop (fail-fast — the
  * ZIO idiom is to handle recoverable failures inside the listener's effect).
  *
  * Two kinds of values flow through the queue:
  *   - [[AppAction]] — user verbs against the model, processed by the handler.
  *   - [[ConduitOp]] — control-plane operations ([[NoAction]], [[Done]], [[Subscribe]], [[Unsubscribe]]) handled
  *     directly by the dispatch loop without touching the handler.
  */
abstract class Conduit[M, E] private (private val stateRef: Ref[ConduitState[M, E]]):
  self =>

  val fastEq = FastEq.get[M]

  /** Synchronous façade that runs each call against [[Runtime.default]]. Useful for UI threads or interop with code
    * that can't `await` a ZIO effect. The type is the nested [[Conduit.Unsafe]] class — NOT a path-dependent singleton
    * — which keeps it usable inside zio-test smart assertions and other macro-based tools.
    */
  val unsafe: Conduit.Unsafe[M, E] = new Conduit.Unsafe[M, E](self)

  def initialModel: M

  protected def handler: ActionHandler[M, ?, E]
  val runUntilDone: IO[E, Unit] = run(false)

  /** Run the conduit's dispatch loop.
    *
    * @param terminate
    *   if `true`, drains all currently-queued actions plus any follow-ups they produce, then returns when the queue is
    *   empty. If `false`, runs until a [[Done]] is dispatched (typically by user code).
    */
  def run(terminate: Boolean = true): IO[E, Unit] =
    for
      state <- stateRef.get
      _ <-
        if terminate then
          // Drain to fixpoint: take + dispatch while items remain (including handler-emitted follow-ups).
          def loop: IO[E, Unit] =
            state.actionQueue.poll.flatMap:
              case Some(a) => dispatch(a) *> loop
              case None    => ZIO.unit
          loop
        else
          // Run until external Done arrives.
          ZStream
            .fromQueue(state.actionQueue)
            .takeUntil(_ == Done)
            .foreach(dispatch)
    yield ()

  /** Dispatch a single [[Dispatchable]]. */
  def dispatch(action: Dispatchable[M, E]): IO[E, Dispatchable[M, E]] =
    action match
      case NoAction => ZIO.succeed(NoAction)
      case Done     => ZIO.succeed(Done)
      case Subscribe(l) =>
        stateRef.update(c => c.copy(listeners = c.listeners + l.asInstanceOf[Listener[M, E, ?]])).as(action)
      case Unsubscribe(l) =>
        stateRef.update(c => c.copy(listeners = c.listeners - l.asInstanceOf[Listener[M, E, ?]])).as(action)
      case _: ConduitOp[?, ?] =>
        // Forward-compat: unknown ConduitOp values are no-ops in this version of the library.
        ZIO.succeed(action)
      case a: AppAction[M, E] @unchecked =>
        for
          currentState <- stateRef.get
          currentModel = currentState.model
          result <- handler.process(a, currentModel)

          modelChanged = result.dirty && !fastEq.eqv(currentModel, result.newModel)

          // Always update state (preserves action processing semantics)
          _ <- stateRef.update(_.copy(model = result.newModel))

          // Only notify listeners if the model actually changed (performance optimization).
          nextState <- stateRef.get
          _ <-
            if modelChanged then ZIO.foreachDiscard(nextState.listeners)(_.notify(nextState.model))
            else ZIO.unit

          _ <- ZIO.foreachDiscard(result.next)(dispatch)
        yield action

  def zoom[U](lens: Lens[M, U]): IO[Nothing, U] =
    for m <- currentModel
    yield lens.get(m)

  inline def zoomTo[U](inline path: M => U): IO[Nothing, U] =
    zoom(lensFor(path))

  /** Enqueue actions or ops to be processed. */
  def apply(action: Dispatchable[M, E]*): IO[Nothing, Unit] =
    for
      state <- stateRef.get
      _     <- ZIO.foreachDiscard(action)(state.actionQueue.offer)
    yield ()

  inline def subscribe[S](inline path: M => S)(inline listener: S => IO[E, Unit]): IO[Nothing, Listener[M, E, S]] =
    for
      l <- Listener(lensFor(path), listener)
      _ <- stateRef.update: current =>
        current.copy(listeners = current.listeners + l)
    yield l

  def subscribe[S](lens: Lens[M, S])(listener: S => IO[E, Unit]): IO[Nothing, Listener[M, E, S]] =
    for
      l <- Listener(lens, listener)
      _ <- stateRef.update: current =>
        current.copy(listeners = current.listeners + l)
    yield l

  def unsubscribe[S](listener: Listener[M, E, S]): UIO[Unit] =
    stateRef.update: current =>
      current.copy(listeners = current.listeners - listener)

  def currentModel: IO[Nothing, M] = stateRef.get.map(_.model)

end Conduit

object Conduit:
  def get[A](z: UIO[A]): A = zio.Unsafe.unsafe: u =>
    given zio.Unsafe = u
    Runtime.default.unsafe.run(z).getOrThrow()

  /** Synchronous façade for a [[Conduit]]; see [[Conduit.unsafe]]. */
  final class Unsafe[M, E](private val c: Conduit[M, E]):
    def apply(action: Dispatchable[M, E]*): Unit =
      get(c.apply(action*))
    def zoom[U](lens: Lens[M, U]): U =
      get(c.zoom(lens))
    inline def zoomTo[U](inline path: M => U): U =
      get(c.zoom(lensFor(path)))
    inline def subscribe[S](inline path: M => S)(f: S => Unit): Listener[M, E, S] =
      get(c.subscribe(lensFor(path))((s: S) => zio.ZIO.succeed(f(s))))
    def subscribe[S](lens: Lens[M, S])(f: S => Unit): Listener[M, E, S] =
      get(c.subscribe(lens)((s: S) => zio.ZIO.succeed(f(s))))
    def unsubscribe[S](listener: Listener[M, E, S]): Unit =
      get(c.unsubscribe(listener))
    def run(terminate: Boolean = true): Unit =
      get(c.run(terminate).catchAll(e => zio.ZIO.die(RuntimeException(s"Conduit error: $e"))))
    def currentModel: M =
      get(c.currentModel)
  end Unsafe

  def make[M, E](init: M)(h: => ActionHandler[M, ?, E]): Conduit[M, E] =
    get(apply(init)(h))

  def apply[M, E](init: M)(h: => ActionHandler[M, ?, E]): IO[Nothing, Conduit[M, E]] =
    for
      queue <- Queue.unbounded[Dispatchable[M, E]]
      ref   <- Ref.make(ConduitState[M, E](init, Set.empty, queue))
    yield new Conduit[M, E](ref):
      override def initialModel: M                           = init
      override protected def handler: ActionHandler[M, ?, E] = h
end Conduit
