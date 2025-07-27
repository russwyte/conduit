package conduit

// Placeholder traits for actions, assumed to be part of the application
trait AppAction

case object NoAction                                         extends AppAction
case object Done                                             extends AppAction
case class Subscribe[M, S, E](listener: Listener[M, S, E])   extends AppAction
case class Unsubscribe[M, S, E](listener: Listener[M, S, E]) extends AppAction
