package conduit

import zio.*
import zio.stream.ZStream

abstract class Conduit[M, E] private (private val stateRef: Ref[ConduitState[M, E]]):
  self =>
  import Conduit.get

  object unsafe:
    def apply(action: AppAction*): Unit =
      get(self.apply(action*))
    def zoom[U](lens: Lens[M, U]): U =
      get(self.zoom(lens))
    inline def zoomTo[U](inline path: M => U): U =
      get(self.zoomTo(path))
    inline def subscribe[S](inline path: M => S)(f: S => Unit): Listener[M, S, E] =
      get(self.subscribe(path)(s => ZIO.succeed(f(s))))
    def subscribe[S](lens: Lens[M, S])(f: S => Unit): Listener[M, S, E] =
      get(self.subscribe(lens)(s => ZIO.succeed(f(s))))
    def unsubscribe[S](listener: Listener[M, S, E]): Unit =
      get(self.unsubscribe(listener))
    def run(terminate: Boolean = true): Unit =
      try get(self.run(terminate).catchAll(e => ZIO.die(RuntimeException(s"Conduit error: $e"))))
      catch case t: Throwable => throw t
    def currentModel: M =
      get(self.currentModel)
  end unsafe

  def initialModel: M

  protected def handler: ActionHandler[M, ?, E]
  val runUntilDone: IO[E, Unit] = run(false)

  /** Run the forever or until [[Done]].
    *
    * @param terminate
    *   if true, will offer [[Done]] to the action queue so that the stream will terminate.
    * @return
    */
  def run(terminate: Boolean = true): IO[E, Unit] =
    for
      state <- stateRef.get
      _ <-
        if terminate then state.actionQueue.offer(Done)
        else ZIO.unit
      _ <- ZStream
        .fromQueue(state.actionQueue)
        .takeUntil(_ == Done) // Sometimes we want to run until done, sometimes we don't :)
        .foreach(dispatch)
    yield ()

  /** Dispatch an action on the conduit with the current state.
    *
    * @param action
    * @return
    */
  def dispatch(action: AppAction): IO[E, AppAction] =
    action match
      case NoAction => ZIO.succeed(NoAction)
      case Done     => ZIO.succeed(Done)
      case _ =>
        for
          currentState <- stateRef.get
          currentModel = currentState.model
          result    <- handler.process(action, currentModel)
          _         <- stateRef.update(_.copy(model = result.newModel))
          nextState <- stateRef.get
          _         <- ZIO.foreachDiscard(nextState.listeners)(_.notify(nextState.model))
          _         <- ZIO.foreachDiscard(result.next)(dispatch)
        yield action

  def zoom[U](lens: Lens[M, U]): IO[Nothing, U] =
    for m <- currentModel
    yield lens.get(m)

  inline def zoomTo[U](inline path: M => U): IO[Nothing, U] =
    zoom(lensFor(path))

  /** Enqueue an action to be processed by the conduit.
    *
    * @param action
    * @return
    */
  def apply(action: AppAction*): IO[Nothing, Unit] =
    for
      state <- stateRef.get
      _ <- ZIO.foreachDiscard(action):
        state.actionQueue.offer
    yield ()

  inline def subscribe[S](inline path: M => S)(inline listener: S => IO[E, Unit]): UIO[Listener[M, S, E]] =
    val l = Listener[M, S, E](lensFor(path), listener)
    stateRef
      .update: current =>
        current.copy(listeners = current.listeners + l)
      .as(l)

  def subscribe[S](lens: Lens[M, S])(listener: S => IO[E, Unit]): UIO[Listener[M, S, E]] =
    val l = Listener[M, S, E](lens, listener)
    stateRef
      .update: current =>
        current.copy(listeners = current.listeners + l)
      .as(l)

  def unsubscribe[S](listener: Listener[M, S, E]): UIO[Unit] =
    stateRef
      .update: current =>
        current.copy(listeners = current.listeners - listener)

  def currentModel: IO[Nothing, M] = stateRef.get.map(_.model)

end Conduit

object Conduit:
  def get[A](zio: UIO[A]): A = Unsafe.unsafe: u =>
    given Unsafe = u
    Runtime.default.unsafe.run(zio).getOrThrow()
  def make[M, E](
      init: M
  )(h: => ActionHandler[M, ?, E]): Conduit[M, E] =
    get(apply(init)(h))

  def apply[M, E](
      init: M
  )(h: => ActionHandler[M, ?, E]): IO[Nothing, Conduit[M, E]] =
    for
      queue <- Queue.unbounded[AppAction]
      ref   <- Ref.make(ConduitState[M, E](init, Set.empty, queue))
    yield new Conduit[M, E](ref):
      override def initialModel: M = init
      override protected def handler: ActionHandler[M, ?, E] =
        h
end Conduit
