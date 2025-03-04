package conduit
import zio.*

case class Listener[M, S] private[conduit] (
    cursor: Lens[M, S],
    listener: S => Unit,
):
  private var lastValue: Option[S] = None

  def notify(oldModel: M, newModel: M): UIO[Unit] =
    val newValue = cursor.get(newModel)
    ZIO.succeed {
      if !lastValue.exists(_ == newValue) then
        listener(newValue)
        lastValue = Some(newValue)
    }
end Listener
