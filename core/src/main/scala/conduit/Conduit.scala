package conduit

import zio.*
import zio.stream.ZStream

abstract class Conduit[M] private (private val stateRef: Ref[ConduitState[M]]):
  self =>
  import Conduit.get

  object unsafe:
    def apply(action: AppAction*): Unit =
      get(self.apply(action*))
    def zoom[U](lens: Lens[M, U]): U =
      get(self.zoom(lens))
    inline def zoomTo[U](inline path: M => U): U =
      get(self.zoomTo(path))
    inline def subscribe[S](inline path: M => S)(f: S => Unit): Listener[M, S] =
      get(self.subscribe(path)(f))
    def subscribe[S](lens: Lens[M, S])(f: S => Unit): Listener[M, S] =
      get(self.subscribe(lens)(f))
    def unsubscribe[S](listener: Listener[M, S]): Unit =
      get(self.unsubscribe(listener))
    def run(terminate: Boolean = true): Unit =
      get(self.run(terminate).orDie)
    def currentModel: M =
      get(self.currentModel)
  end unsafe

  def initialModel: M

  protected def handler: ActionHandler[M, ?]
  val runUntilDone: ZIO[Any, Throwable, Unit] = run(false)

  /** Run the forever or until [[Done]].
    *
    * @param terminate
    *   if true, will offer [[Done]] to the action queue so that the stream will terminate.
    * @return
    */
  def run(terminate: Boolean = true): ZIO[Any, Throwable, Unit] =
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
  def dispatch(action: AppAction): ZIO[Any, Throwable, AppAction] =
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
          _         <- ZIO.foreachDiscard(nextState.listeners)(_.notify(currentModel, nextState.model))
          _         <- ZIO.foreachDiscard(result.next)(dispatch)
        yield action

  def zoom[U](lens: Lens[M, U]): ZIO[Any, Nothing, U] =
    for m <- currentModel
    yield lens.get(m)

  inline def zoomTo[U](inline path: M => U): ZIO[Any, Nothing, U] =
    zoom(lensFor(path))

  /** Enqueue an action to be processed by the conduit.
    *
    * @param action
    * @return
    */
  def apply(action: AppAction*): UIO[Unit] =
    for
      state <- stateRef.get
      _ <- ZIO.foreachDiscard(action):
        state.actionQueue.offer
    yield ()

  inline def subscribe[S](inline path: M => S)(inline listener: S => Unit): ZIO[Any, Nothing, Listener[M, S]] =
    val l = Listener[M, S](lensFor(path), listener)
    stateRef
      .update: current =>
        current.copy(listeners = current.listeners + l)
      .as(l)

  def subscribe[S](lens: Lens[M, S])(listener: S => Unit): UIO[Listener[M, S]] =
    val l = Listener[M, S](lens, listener)
    stateRef
      .update: current =>
        current.copy(listeners = current.listeners + l)
      .as(l)

  def unsubscribe[S](listener: Listener[M, S]): UIO[Unit] =
    stateRef
      .update: current =>
        current.copy(listeners = current.listeners - listener)

  def currentModel: UIO[M] = stateRef.get.map(_.model)

end Conduit

object Conduit:
  def get[A](zio: UIO[A]): A = Unsafe.unsafe: u =>
    given Unsafe = u
    Runtime.default.unsafe.run(zio).getOrThrow()
  def make[M](
      init: M
  )(h: => ActionHandler[M, ?]): Conduit[M] =
    get(apply(init)(h))

  def apply[M](
      init: M
  )(h: => ActionHandler[M, ?]): ZIO[Any, Nothing, Conduit[M]] =
    for
      queue <- Queue.unbounded[AppAction]
      ref   <- Ref.make(ConduitState(init, Set.empty, queue))
    yield new Conduit[M](ref):
      override def initialModel: M = init
      override protected def handler: ActionHandler[M, ?] =
        h
end Conduit
