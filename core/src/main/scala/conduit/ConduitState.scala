package conduit
import zio.*

case class ConduitState[M, E](
    model: M,
    listeners: Set[Listener[M, ?, E]],
    actionQueue: Queue[AppAction],
)
