package conduit
import zio.*

case class ConduitState[M](
    model: M,
    listeners: Set[Listener[M, ?]],
    actionQueue: Queue[AppAction],
)
