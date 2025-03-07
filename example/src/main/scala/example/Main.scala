package example
import conduit.*

import zio.*

case class Pet(name: String, age: Int)
object Pet:
  case class ChangeName(name: String) extends AppAction
  case class ChangeAge(age: Int)      extends AppAction
  def handler[M](pet: Lens[M, Pet]) =
    val ageHandler = handle(pet(_.age)):
      case ChangeAge(age) => updated(age)
    val nameHandler = handle(pet(_.name)):
      case ChangeName(name) => updated(name)
    ageHandler >> nameHandler // Combine the two handlers using orElse
end Pet

case class Model(counter: Int, pet: Pet = Pet("Fido", 2)) derives Optics
object Model:
  val model = Optics[Model]

  enum Action extends AppAction:
    case Increment extends Action
    case Decrement extends Action

  val counterHandler =
    handle(model(_.counter)):
      case Action.Increment => update(_ + 1)
      case Action.Decrement => update(_ - 1)

  val logger = handle[Model]:
    // log the current model state for every action
    case _ => m => Console.printLine(s"Foo: $m").as(ActionResult.terminal(m))

  val handler =
    (counterHandler >> Pet.handler(model(_.pet))) ++
      logger
end Model

object MyApp extends ZIOAppDefault:
  import Model.Action as model
  import Pet as pet
  val run =
    for
      c <- Conduit(Model(0))(Model.handler)
      _ = c.unsafe(model.Increment)
      _ <- c(
        model.Increment,
        model.Increment,
        model.Increment,
        model.Decrement,
        pet.ChangeName("Rex"),
        pet.ChangeAge(3),
      )
      _   <- c(model.Increment)
      res <- c.run()
    yield res
end MyApp
