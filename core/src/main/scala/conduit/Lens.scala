package conduit

import scala.annotation.tailrec
import scala.quoted.*

trait Lens[M, V]:
  self =>
  def get(m: M): V
  def set(m: M, v: V): M
  def >>[W](lens: Lens[V, W]): Lens[M, W] = compose(lens)
  def compose[W](lens: Lens[V, W]): Lens[M, W] =
    new Compose[W](lens)
  private[conduit] class Compose[W](lens: Lens[V, W]) extends Lens[M, W]:
    def get(m: M): W       = lens.get(self.get(m))
    def set(m: M, w: W): M = self.set(m, lens.set(self.get(m), w))
  inline def apply[W](inline path: V => W): Lens[M, W] = new Compose[W](lensFor[V, W](path))
end Lens

private[conduit] inline def lensFor[M, V](inline path: M => V): Lens[M, V] = ${ lensForImpl('path) }

private def lensForImpl[M: Type, V: Type](path: Expr[M => V])(using Quotes): Expr[Lens[M, V]] =
  import quotes.reflect.*

  /** Helper working with case classes
    *
    * @param obj
    */
  case class CaseClass(cc: Term):

    val typeSymbol = cc.tpe.typeSymbol
    val fields     = typeSymbol.caseFields

    def memberMethod(name: String): Symbol =
      typeSymbol
        .methodMember(name)
        .headOption
        .getOrElse(
          report.errorAndAbort(s"Expected a case class ${typeSymbol.fullName} to have '$name' member.")
        )

    def applyCopy(fieldName: String, fieldValue: Term): Apply =
      val args = fields.zipWithIndex.map { (field, idx) =>
        if field.name == fieldName then NamedArg(fieldName, fieldValue)
        else Select(cc, memberMethod("copy$default$" + (idx + 1)))
      }
      Apply(Select(cc, memberMethod("copy")), args)

    def selectField(fieldName: String): Select =
      fields
        .find(_.name == fieldName)
        .fold(report.errorAndAbort(s"Field $fieldName not found in ${typeSymbol.fullName}")): field =>
          Select(cc, field)
  end CaseClass

  def selectChain(term: Term): List[String] =
    @tailrec
    def loop(term: Term, acc: List[String]): List[String] =
      term match
        case Select(qual, name) => loop(qual, name :: acc)
        case Ident(name)        => acc
        case _                  => report.errorAndAbort(s"Expected a chain of field selections, got ${term.show}")
    loop(term, Nil)
  end selectChain

  @annotation.tailrec
  def copyLoop(obj: Term, fieldName: String, tail: List[String], value: Term, acc: Term => Term = identity): Term =
    val caseClass = CaseClass(obj)

    val fieldValue =
      if tail.isEmpty then value
      else caseClass.selectField(fieldName)

    if tail.isEmpty then acc(caseClass.applyCopy(fieldName, fieldValue))
    else
      copyLoop(
        fieldValue,
        tail.head,
        tail.tail,
        value,
        term => acc(caseClass.applyCopy(fieldName, term)),
      )
    end if
  end copyLoop

  def setExpr(fieldChain: Term): Expr[(M, V) => M] =
    val fields =
      val list =
        selectChain(fieldChain).toList
      if list.isEmpty then List(fieldChain.symbol.name)
      else list

    if fields.isEmpty then report.errorAndAbort("Field chain cannot be empty")
    else
      '{ (model: M, newValue: V) =>
        ${
          copyLoop(
            obj = '{ model }.asTerm,
            fieldName = fields.head,
            tail = fields.tail.toList,
            value = '{ newValue }.asTerm,
          ).asExpr.asInstanceOf[Expr[M]]
        }
      }
    end if
  end setExpr

  val typeSymbol = TypeRepr.of[M].typeSymbol
  if !typeSymbol.isClassDef || !typeSymbol.flags.is(Flags.Case) then
    report.errorAndAbort(s"Lens can only be created for case class fields, got ${typeSymbol.name}")

  def make(fieldChain: Term) =
    '{
      new Lens[M, V]:
        def get(m: M): V = $path(m)
        def set(m: M, v: V): M =
          ${
            setExpr(fieldChain)
          }(m, v)
    }

  @tailrec
  def findLambda(term: Term): Term =
    term match
      case Lambda(_, _)        => term
      case Inlined(_, _, expr) => findLambda(expr)
      case e                   => report.errorAndAbort(s"Expected a lambda or inlined lambda, got ${e.show}")
  end findLambda

  findLambda(path.asTerm) match
    case Lambda(valDefs, fieldChain) =>
      fieldChain match
        case _: Select => make(fieldChain)
        case e =>
          report.errorAndAbort(s"Only simple field accessors are supported, e.g., _.field got ${e.show}")
    case e =>
      report.errorAndAbort(
        s"Only simple field accessors are supported, e.g., _.field got ${e.show(using Printer.TreeStructure)}"
      )
  end match
end lensForImpl
