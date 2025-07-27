package conduit
import zio.*

final case class Listener[M, S, E] private[conduit] (
    cursor: Lens[M, S],
    listener: S => IO[E, Unit],
):
  private var lastValue: Option[S] = Option.empty[S]

  private[conduit] def notify(newModel: M): IO[E, Unit] =
    val newValue = cursor.get(newModel)
    val res = lastValue match
      case Some(a) if a == newValue => ZIO.unit
      case _                        => listener(newValue)
    lastValue = Some(newValue)
    res
end Listener
object Listener:
  def unit[M, S](cursor: Lens[M, S]): Listener[M, S, Nothing] =
    Listener(cursor, _ => ZIO.succeed(()))
