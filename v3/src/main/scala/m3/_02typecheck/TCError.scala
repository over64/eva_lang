package m3._02typecheck

import m3.{Ast0, AstInfo}
import m3.Ast0.{GenericTh, TypeHint}

import scala.collection.immutable.ArraySeq
import scala.collection.mutable.Buffer

sealed trait TypeCheckError extends Exception

object TCE {
  case class FieldNameNotUnique(location: AstInfo) extends TypeCheckError
  case class TypeHintRequired(location: AstInfo) extends TypeCheckError
  case class RetTypeHintRequired(location: AstInfo) extends TypeCheckError
  case class TypeHintUnexpected(location: AstInfo) extends TypeCheckError
  case class NoSuchModulePath(location: AstInfo) extends TypeCheckError {
    override def toString: String = s"$location No such module path"
  }
  case class NoSuchType(location: AstInfo, mod: Seq[String], name: String) extends TypeCheckError {
    override def toString: String = s"$location No such type with name ${mod.mkString(".")}.$name"
  }
  case class NoSuchParameter(location: AstInfo, gth: GenericTh) extends TypeCheckError

  case class NoSuchVar(location: AstInfo) extends TypeCheckError
  case class VarAlreadyDeclared(location: AstInfo, varName: String) extends TypeCheckError
  case class NoSuchSymbol(location: AstInfo, name: String) extends TypeCheckError {
    override def toString: String = s"$location No local var, argument or function with name $name"
  }
  case class NoSuchField(location: AstInfo, th: TypeHint, fieldName: String) extends TypeCheckError {
    override def toString: String = s"$location No such field $fieldName on type $th"
  }

  case class NoSuchCallable(location: AstInfo, name: String) extends TypeCheckError {
    override def toString: String = s"$location No such function with name $name"
  }
  case class ExpressionNotCallable(location: AstInfo) extends TypeCheckError
  case class ParamsCountMismatch(location: AstInfo) extends TypeCheckError {
    override def toString: String = s"$location Generic type params count mismatch"
  }

  case class ArgsCountMismatch(location: Seq[AstInfo], expected: Int, has: Int) extends TypeCheckError {
    override def toString: String = s"Arg count mismatch: expected $expected has $has\n${location.map(l => s"at $l").mkString("\n")}"
  }

  case class LambdaWithNativeCode(location: AstInfo) extends TypeCheckError

  case class TypeMismatch(location: Seq[AstInfo], expected: TypeHint, has: TypeHint) extends TypeCheckError {
    override def toString: String =
      s"Type mismatch: expected $expected has $has\n${location.map(l => s"at $l").mkString("\n")}"
  }
  case class RetTypeMismatch(location: AstInfo, expected: TypeHint, has: TypeHint) extends TypeCheckError {

  }
  case class DoubleSelfDefDeclaration(location: AstInfo, selfTh: TypeHint) extends TypeCheckError
  case class StructTypeRequired(location: AstInfo) extends TypeCheckError
  case class UnionMembersNotUnique(location: AstInfo) extends TypeCheckError
  case class IntegerLiteralOutOfRange(location: AstInfo, th: Option[TypeHint]) extends TypeCheckError
  case class FloatingLiteralOutOfRange(location: AstInfo, th: Option[TypeHint]) extends TypeCheckError
  case class ExpectedIntegerType(location: AstInfo, otherTh: TypeHint) extends TypeCheckError
  case class ArraySizeExpected(location: Seq[AstInfo], thArraySizes: Ast0.UnionTh, has: TypeHint) extends TypeCheckError
  case class BuiltinTypeRedeclare(location: AstInfo, name: String) extends TypeCheckError
  case class NoWhileForBreakOrContinue(location: AstInfo) extends TypeCheckError
  case class ExpectedUnionType(location: AstInfo, has: TypeHint) extends TypeCheckError {
    override def toString: String = s"$location Expected union type but has $has"
  }
  case class UnlessExpectedOneOf(location: AstInfo, oneOf: ArraySeq[TypeHint], has: TypeHint) extends TypeCheckError
  case class CaseAlreadyCovered(location: AstInfo, forTh: TypeHint, coveredAt: AstInfo) extends TypeCheckError
  case class NoSuchDef(location: Seq[AstInfo], name: String) extends TypeCheckError {
    override def toString: String = s"No such def with name $name \n${location.map(l => s"at $l").mkString("\n")}"
  }

  case class NoSuchSelfDef(location: Seq[AstInfo], name: String, forType: TypeHint) extends TypeCheckError {
    override def toString: String = s"No such self def with name $name for type $forType \n${location.map(l => s"at $l").mkString("\n")}"
  }
  case class TypeAlreadyLocalDefined(location: AstInfo, name: String) extends TypeCheckError
}
