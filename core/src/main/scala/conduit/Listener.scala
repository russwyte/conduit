package conduit
import zio.*

final case class Listener[M, E, S: FastEq as eq] private[conduit] (
    cursor: Lens[M, S],
    listener: S => IO[E, Unit],
    private val lastValueRef: Ref[Option[S]],
):
  /** Notify this listener of a new model.
    *
    * Atomically swaps in the new value, then invokes the user's listener effect only if the new value differs from the
    * previously observed value (per [[FastEq]]). The atomic swap means concurrent calls with the same value fire the
    * listener at most once.
    *
    * The lastValue advances even when the listener fails — this avoids tight-loop replay of the same failing value.
    */
  private[conduit] def notify(newModel: M): IO[E, Unit] =
    val newValue = cursor.get(newModel)
    for
      prev <- lastValueRef.modify(p => (p, Some(newValue)))
      _ <- prev match
        case Some(a) if eq.eqv(a, newValue) => ZIO.unit
        case _                              => listener(newValue)
    yield ()
  end notify
end Listener

object Listener:
  def apply[M, E, S: FastEq](cursor: Lens[M, S], listener: S => IO[E, Unit]): UIO[Listener[M, E, S]] =
    for lastValueRef <- Ref.make(Option.empty[S])
    yield new Listener(cursor, listener, lastValueRef)

  def unit[M, S: FastEq](cursor: Lens[M, S]): UIO[Listener[M, Nothing, S]] =
    apply(cursor, _ => ZIO.succeed(()))
