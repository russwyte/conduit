package conduit
import zio.*

final case class Listener[M, S] private[conduit] (
    cursor: Lens[M, S],
    listener: S => Unit,
):
  private var lastValue: Option[S] = None

  private[conduit] def notify(oldModel: M, newModel: M): UIO[Unit] =
    val newValue = cursor.get(newModel)
    ZIO.succeed {
      if !lastValue.exists(_ == newValue) then
        listener(newValue)
        lastValue = Some(newValue)
    }
end Listener
object Listener:
  def unit[M, S](cursor: Lens[M, S]): Listener[M, S] =
    Listener(cursor, _ => ())
