package conduit
import zio.*

final case class Listener[M, E, S] private[conduit] (
    cursor: Lens[M, S],
    listener: S => IO[E, Unit],
    private val lastValueRef: Ref[Option[S]],
):
  private[conduit] def notify(newModel: M): IO[E, Unit] =
    val newValue = cursor.get(newModel)
    val fastEq   = FastEq.get[S] // Get FastEq with automatic fallback
    for
      lastValue <- lastValueRef.get
      res <- lastValue match
        case Some(a) if fastEq.eqv(a, newValue) => ZIO.unit
        case _                                  => listener(newValue)
      _ <- lastValueRef.set(Some(newValue))
    yield res
  end notify
end Listener

object Listener:
  def apply[M, E, S](cursor: Lens[M, S], listener: S => IO[E, Unit]): UIO[Listener[M, E, S]] =
    for lastValueRef <- Ref.make(Option.empty[S])
    yield new Listener(cursor, listener, lastValueRef)

  def unit[M, S](cursor: Lens[M, S]): UIO[Listener[M, Nothing, S]] =
    apply(cursor, _ => ZIO.succeed(()))
