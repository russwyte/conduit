package conduit

/** Common parent for everything the Conduit dispatch loop processes.
  *
  * Two kinds of values flow through the queue:
  *   - [[AppAction]] — user-defined verbs against the model (the application's "language").
  *   - [[ConduitOp]] — runtime control-plane operations on the conduit itself (subscribe, terminate, etc.).
  */
sealed trait Dispatchable[-M, +E]

/** A user-defined verb against the model.
  *
  * `AppAction` represents the language of your application — `Increment`, `AddTodo`, `Login`, etc. Handlers pattern
  * match on these to produce state transitions. Most user actions don't need either type parameter, so prefer
  * [[Action]] (parameter-free) or [[ActionE]] (error-typed only) at the use site.
  */
trait AppAction[-M, +E] extends Dispatchable[M, E]

/** Parameter-free `AppAction` alias — the common case. */
type Action = AppAction[Any, Nothing]

/** Error-typed but model-agnostic `AppAction` alias. */
type ActionE[+E] = AppAction[Any, E]

/** A runtime control-plane operation on the conduit itself.
  *
  * Distinct from [[AppAction]]: ops change the conduit's wiring (listeners, lifecycle), not the user's model.
  *
  *   - [[NoAction]] / [[Done]] are control-flow sentinels.
  *   - [[Subscribe]] / [[Unsubscribe]] add or remove listeners.
  *
  * Ops can be enqueued externally via `conduit(...)` or returned from a handler as follow-ups in [[ActionResult.next]].
  */
trait ConduitOp[-M, +E] extends Dispatchable[M, E]

case object NoAction extends ConduitOp[Any, Nothing]
case object Done     extends ConduitOp[Any, Nothing]

case class Subscribe[M, E, S](listener: Listener[M, E, S])   extends ConduitOp[M, E]
case class Unsubscribe[M, E, S](listener: Listener[M, E, S]) extends ConduitOp[M, E]
