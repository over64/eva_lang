package m3.parse

import m3.typecheck.Equations

import scala.collection.mutable

case class AstInfo(fname: String, line: Int, col: Int, lineEnd: Int, colEnd: Int) {
  override def toString: String = s"$fname.abra:$line:$col"
}

object Ast0 {
  sealed trait ParseNode {
    val meta: mutable.HashMap[String, Any] = new mutable.HashMap()

    def setLocation(loc: AstInfo): Unit = meta.put("source.location", loc)
    def getLocation = meta.get("source.location")

    def location: AstInfo = meta.getOrElse("source.location", {
      val x = 1 // so weird
      throw new RuntimeException("no location")
    }).asInstanceOf[AstInfo]

    def getTypeHintOpt[T <: TypeHint]: Option[T] = meta.get("typecheck.typeHint").map(m => m.asInstanceOf[T])

    def getTypeHint[T <: TypeHint]: T = getTypeHintOpt.getOrElse {
      throw new RuntimeException("no typeHint")
    }

    def setTypeHint(th: TypeHint): Unit = meta.put("typecheck.typeHint", th)
  }

  sealed trait Expression extends ParseNode
  sealed trait FnBody
  sealed trait Level1Declaration extends ParseNode
  sealed trait Literal extends Expression {
    val value: String
  }

  case class lNone() extends Literal {
    override val value: String = "none"
  }
  case class lInt(value: String) extends Literal
  case class lFloat(value: String) extends Literal
  case class lBoolean(value: String) extends Literal
  case class lString(value: String) extends Literal
  case class lId(value: String) extends Literal
  case class llVm(code: String) extends ParseNode with FnBody

  sealed trait TypeDecl extends Level1Declaration {
    val name: String
    val params: Seq[GenericTh]

    def setBuiltinIntegral =
      meta.put("builtin.integral", true)
    def isBuiltinIntegral =
      meta.contains("builtin.integral")

    def setBuiltinArray(len: Option[Long]) = {
      meta.put("builtin.array", true)
      len.foreach { l => meta.put("builtin.array.len", l) }
    }

    def isBuiltinArray(): Boolean =
      meta.contains("builtin.array")

    def getBuiltinArrayLen(): Option[Long] =
      meta.get("builtin.array.len").map(_.asInstanceOf[Long])
  }

  case class ScalarDecl(ref: Boolean, params: Seq[GenericTh], name: String, llType: String) extends TypeDecl
  case class FieldDecl(isSelf: Boolean, name: String, th: TypeHint) extends ParseNode
  case class StructDecl(params: Seq[GenericTh], name: String, fields: Seq[FieldDecl]) extends TypeDecl
  case class UnionDecl(params: Seq[GenericTh], name: String, variants: Seq[TypeHint]) extends TypeDecl

  sealed trait TypeHint extends ParseNode
  case class ScalarTh(params: Seq[TypeHint], name: String, mod: Seq[String]) extends TypeHint {
    override def toString: String = s"${if (mod.isEmpty) "" else mod.mkString("", ".", ".")}$name${if (params.isEmpty) "" else params.mkString("[", ", ", "]")}"
  }
  case class FieldTh(name: String, typeHint: TypeHint) extends ParseNode {
    override def toString: String = s"$name: $typeHint"
  }
  case class StructTh(seq: Seq[FieldTh]) extends TypeHint {
    override def toString: String = seq.mkString("(", ", ", ")")
  }
  case class UnionTh(seq: Seq[TypeHint]) extends TypeHint {
    override def toString: String = seq.map {
      case unionTh: UnionTh => "(" + unionTh + ")"
      case other => other.toString
    }.mkString(" | ")
  }

  case class Bound(selfDef: String, args: Seq[TypeHint], ret: TypeHint)

  case class GenericTh(var typeName: String, var isAnon: Boolean = false) extends TypeHint {
    override def toString: String = typeName
  }
  case object AnyTh extends TypeHint {
    override def toString: String = "_"
  }

  sealed trait ClosureType {
    val th: TypeHint
  }
  case class CLocal(th: TypeHint) extends ClosureType
  case class CParam(th: TypeHint) extends ClosureType
  case class FnTh(closure: Seq[ClosureType], args: Seq[TypeHint], ret: TypeHint) extends TypeHint {
    override def toString: String =
      args.mkString("(", ", ", ")") + " -> " + ret
  }

  case class Prop(from: Expression, props: Seq[lId]) extends Expression
  case class Tuple(seq: Seq[Expression]) extends Expression
  case class Cons(sth: ScalarTh, args: Seq[Expression]) extends Expression
  case class SelfCall(fnName: String, self: Expression, args: Seq[Expression]) extends Expression
  case class Call(expr: Expression, args: Seq[Expression]) extends Expression

  case class AbraCode(seq: Seq[Expression]) extends FnBody
  case class Lambda(args: Seq[Arg], body: FnBody) extends Expression

  sealed trait AndOr {
    val left: Expression
    val right: Expression
  }
  case class And(left: Expression, right: Expression) extends Expression with AndOr
  case class Or(left: Expression, right: Expression) extends Expression with AndOr
  case class If(cond: Expression, _do: Seq[Expression], _else: Seq[Expression]) extends Expression
  case class Is(vName: Option[lId], typeRef: TypeHint, _do: Seq[Expression]) extends ParseNode
  case class Unless(expr: Expression, is: Seq[Is]) extends Expression
  case class While(cond: Expression, _do: Seq[Expression]) extends Expression
  case class Store(var th: TypeHint, to: Seq[lId], what: Expression) extends Expression {
    def getDeclTh[T <: TypeHint]: T = meta.get("typecheck.store.declTypeHint").map(m => m.asInstanceOf[T]).get

    def setDeclTh(th: TypeHint): Unit = meta.put("typecheck.store.declTypeHint", th)
  }
  case class Ret(what: Option[Expression]) extends Expression
  case class Break() extends Expression
  case class Continue() extends Expression
  case class Arg(name: String, var typeHint: TypeHint) extends ParseNode
  case class Def(name: String, lambda: Lambda, var retTh: TypeHint) extends Level1Declaration {
    def getEquationsOpt: Option[Equations] = meta.get("typecheck.equations").map(m => m.asInstanceOf[Equations])

    def getEquations: Equations = getEquationsOpt.getOrElse {
      val x = 1
      throw new RuntimeException("no equations")
    }

    def setEquations(eq: Equations): Unit = meta.put("typecheck.equations", eq)
  }
  case class ImportEntry(modName: String, path: String, withTypes: Seq[String]) extends ParseNode
  case class Import(seq: Seq[ImportEntry]) extends ParseNode
  case class Module(pkg: String,
                    imports: Import,
                    lowCode: Seq[llVm],
                    types: Map[String, TypeDecl],
                    defs: Map[String, Def],
                    selfDefs: Map[String, Seq[Def]]) extends ParseNode
}
