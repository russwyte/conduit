package conduit

trait Optics[Model <: Product] extends Lens[Model, Model]:
  override def get(m: Model): Model           = m
  override def set(m: Model, v: Model): Model = v

object Optics:
  import scala.quoted.*
  inline def apply[Model <: Product: Optics as optics]: Optics[Model] = optics
  inline def derived[Model <: Product]: Optics[Model]                 = ${ deriveImpl[Model] }
  final private[conduit] class Derived[M <: Product] extends Optics[M]
  private def deriveImpl[Model <: Product: Type](using Quotes): Expr[Optics[Model]] =
    '{ new Derived[Model] }
end Optics
