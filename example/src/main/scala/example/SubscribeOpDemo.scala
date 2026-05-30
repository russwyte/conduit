package example

import java.io.IOException

import conduit.*

import zio.*

/** Demonstrates [[Subscribe]] / [[Unsubscribe]] [[ConduitOp]]s.
  *
  * Two patterns:
  *   1. Enqueueing `Subscribe(listener)` at the same level as a domain action — useful when external code wants the
  *      subscription installed at a specific point in the action stream. 2. A handler returning `Subscribe` as a
  *      follow-up in [[ActionResult.next]] — useful when a state transition should wire up a new listener as a
  *      consequence (e.g. "after login, watch the user's profile slice").
  */
object SubscribeOpDemo extends ZIOAppDefault:

  case class User(name: String, loggedIn: Boolean) derives Optics
  case class App(user: User, count: Int) derives Optics

  enum AppAction extends Action:
    case Login(name: String)
    case Logout
    case Inc

  val app  = Optics[App]
  val user = app(_.user)

  // Handler that — when the user logs in — returns a Subscribe(listener) follow-up so that count
  // changes start being logged. Logout dispatches an Unsubscribe to tear it down.
  // The listener captures a Ref so the demo can observe it firing.
  def appHandler(observed: Ref[List[Int]]): ActionHandler[App, ?, IOException] =
    val countLens = app(_.count)
    val userHandler: ActionHandler[App, User, IOException] =
      handle[App, User, IOException](user):
        case AppAction.Login(name) =>
          m =>
            for countListener <- Listener[App, IOException, Int](
                countLens,
                c =>
                  observed.update(c :: _) *>
                    Console.printLine(s"  [count-listener] count=$c").orDie,
              )
            yield ActionResult(
              user.set(m, User(name, loggedIn = true)),
              Subscribe(countListener),
            )
        case AppAction.Logout => updated(User("", loggedIn = false))

    val countHandler: ActionHandler[App, Int, IOException] =
      handle[App, Int, IOException](countLens):
        case AppAction.Inc => update(_ + 1)

    userHandler >> countHandler
  end appHandler

  val run: ZIO[Any, IOException, Unit] =
    for
      _        <- Console.printLine("=== Subscribe / Unsubscribe ConduitOp Demo ===")
      observed <- Ref.make(List.empty[Int])
      c        <- Conduit(App(User("", loggedIn = false), 0))(appHandler(observed))

      _ <- Console.printLine("\n1. Inc BEFORE login — no listener installed yet:")
      _ <- c(AppAction.Inc, AppAction.Inc)

      _ <- Console.printLine("\n2. Login — handler returns Subscribe(...) as a follow-up:")
      _ <- c(AppAction.Login("Alice"))

      _ <- Console.printLine("\n3. Inc AFTER login — listener fires for each change:")
      _ <- c(AppAction.Inc, AppAction.Inc, AppAction.Inc)

      _    <- c.run()
      seen <- observed.get
      _    <- Console.printLine(s"\nObserved count values (newest first): ${seen.mkString(", ")}")
      _    <- Console.printLine("=== Demo Complete ===")
    yield ()
end SubscribeOpDemo
