package conduit

// Placeholder traits for actions, assumed to be part of the application
trait AppAction

case object NoAction                                         extends AppAction
case object Done                                             extends AppAction
case class Subscribe[M, E, S](listener: Listener[M, E, S])   extends AppAction
case class Unsubscribe[M, E, S](listener: Listener[M, E, S]) extends AppAction
