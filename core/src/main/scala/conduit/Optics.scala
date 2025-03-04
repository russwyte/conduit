package conduit

trait Optics[Model <: Product]:
  inline def lens[Focus](inline path: Model => Focus): Lens[Model, Focus]  = lensFor(path)
  inline def apply[Focus](inline path: Model => Focus): Lens[Model, Focus] = lensFor(path)

object Optics:
  import scala.quoted.*
  inline def apply[Model <: Product: Optics as optics]: Optics[Model] = optics
  inline def derived[Model <: Product]: Optics[Model]                 = ${ deriveImpl[Model] }
  final private[conduit] class Derived[M <: Product] extends Optics[M]
  private def deriveImpl[Model <: Product: Type](using Quotes): Expr[Optics[Model]] =
    '{ new Derived[Model] }
end Optics
