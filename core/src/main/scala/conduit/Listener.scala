package conduit
import zio.*

private[conduit] case class Listener[M, S] private[conduit] (
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
