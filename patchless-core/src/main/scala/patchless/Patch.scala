package patchless

import scala.annotation.tailrec

import shapeless.labelled.{FieldType, field}
import shapeless.ops.hlist.{Mapper, Zip}
import shapeless._
import scala.language.experimental.macros
import scala.language.dynamics

abstract class Patch[T] extends (T => T) {
  type Updates <: HList
  def updates: Updates
}

object Patch extends Dynamic {

  type Aux[T, U <: HList] = Patch[T] { type Updates = U }

  def applyDynamicNamed[T](method: String)(rec: Any*): Patch[T] = macro PatchMacros.mkPatchImpl[T]

  def ofUpdates[T, U <: HList](up: U)(implicit
    patchable: Patchable.Aux[T, U]
  ): Aux[T, U] = new Patch[T] {
    type Updates = U
    val updates: U = up
    def apply(t: T): T = patchable(t, up)
  }

  def diff[T, L <: HList, Z <: HList, U <: HList](a: T, b: T)(implicit
    gen: LabelledGeneric.Aux[T, L],
    zip: Zip.Aux[L :: L :: HNil, Z],
    patchable: Patchable.Aux[T, U],
    mapper: Mapper.Aux[differences.type, Z, U]
  ): Aux[T, U] = ofUpdates[T, U](mapper(zip(gen.to(a) :: gen.to(b) :: HNil)))


  object differences extends Poly1 {
    implicit def cases[K <: Symbol, V] = at[(FieldType[K, V], FieldType[K, V])] {
      case (a, b) => if(a == b)
          field[K](None: Option[V])
        else
          field[K](Some(b): Option[V])
    }
  }

  abstract class Updates[T, U <: HList] {
    type Updates = U
  }

  implicit class ConcretePatchOps[T, U <: HList](val patch: Patch[T])(implicit
    patchable: Patchable.Aux[T, U]
  ) {
    def patchUpdates: U = patch.updates.asInstanceOf[U]
  }
}

// Macros to support patch Apply syntax
class PatchMacros(ctx: scala.reflect.macros.whitebox.Context) extends RecordMacros(ctx) {
  import c.universe._
  def mkPatchImpl[T: WeakTypeTag](method: c.Expr[String])(rec: Tree*): Tree = {
    val T = weakTypeOf[T]
    val patchable = c.inferImplicitValue(
      appliedType(weakTypeOf[Patchable[_]].typeConstructor, T),
      silent = false
    )
    val typ = patchable.tpe.typeArgs.lastOption.getOrElse {
      c.abort(c.enclosingPosition, s"No Updates type found for $T")
    }

    val args = rec.map {
      case q"(${Literal(Constant(name: String))}, $arg)" =>
        name -> arg
    }.toMap

    val result = c.typecheck(mkUpdates(T, typ, args))
    q"_root_.patchless.Patch.ofUpdates[$T, $typ]($result)"
  }

  @tailrec
  final def mkUpdates(T: Type, typ: Type, args: Map[String, Tree], current: List[Tree] = Nil):Tree = {
    typ match {
      case TypeRef(_, _, List(TypeRef(_, _, List(f @ RefinedType(List(_, TypeRef(_, _, List(ConstantType(Constant(k: String))))), _), v)), tail)) =>
        args.get(k) match {
          case Some(tree) => mkUpdates(T, tail, args - k, c.typecheck(q"_root_.shapeless.labelled.field[$f].apply(Some($tree):$v)") :: current)
          case None => mkUpdates(T, tail, args, c.typecheck(q"_root_.shapeless.labelled.field[$f].apply(None:$v)") :: current)
        }
      case t if t <:< weakTypeOf[shapeless.HNil] =>
        args.headOption.foreach {
          case (name, arg) => c.abort(arg.pos, s"$T has no field $name")
        }
        current.foldLeft(q"_root_.shapeless.HNil":Tree) {
          (accum, next) => q"_root_.shapeless.::($next, $accum)"
        }
    }
  }
}
