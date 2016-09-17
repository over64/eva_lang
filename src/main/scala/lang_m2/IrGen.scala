package lang_m2

import java.io.{OutputStream, PrintStream}

import Ast1._

import scala.collection.mutable

case class IrGen(val out: OutputStream) {
  implicit class Printer(out: OutputStream) {
    def println(line: String) = {
      out.write(line.getBytes)
      out.write(10) // \n
    }
  }

  var tmpVars = 0
  var labelId = 0

  def nextTmpVar(needPercent: Boolean = true): String = {
    tmpVars += 1
    if (needPercent)
      "%" + tmpVars
    else tmpVars.toString
  }

  def nextLabel: String = {
    labelId += 1
    s"label$labelId"
  }

  def evalGep(baseType: Type, fields: Seq[String]): (Type, Seq[Int]) = {
    fields.foldLeft((baseType, Seq[Int](0))) {
      case ((_type, seq), field) =>
        val (f, idx) =
          _type.asInstanceOf[Struct].fields.zipWithIndex.find {
            case (f, idx) => f.name == field
          }.head
        (f._type, seq ++ Seq(idx))
    }
  }

  // (resultName, resultType)
  def evalAccess(access: Access): (String, Type) = {
    val base = genInit(access.fromType, access.from, true)
    val (resType, indicies) = evalGep(access.fromType, Seq(access.prop))
    val res = nextTmpVar()

    out.println(s"\t$res = getelementptr ${access.fromType.name}, ${access.fromType.name}* $base, ${indicies.map { i => s"i32 $i" } mkString (", ")}")
    (res, resType)
  }

  def zalloc(varName: String, varType: Type): Unit = {
    val (_size, size, memsetDest) = (nextTmpVar(), nextTmpVar(), nextTmpVar())

    out.println(s"\t$varName = alloca ${varType.name}, align 4")
    out.println(s"\t${_size} = getelementptr ${varType.name}, ${varType.name}* null, i32 1")
    out.println(s"\t$size = ptrtoint ${varType.name}* ${_size} to i64")
    out.println(s"\t$memsetDest = bitcast ${varType.name}* $varName to i8*")
    out.println(s"\tcall void @llvm.memset.p0i8.i64(i8* $memsetDest, i8 0, i64 $size, i32 4, i1 false)")
  }

  def genInit(_type: Type, init: Init, needPtr: Boolean = false): String = init match {
    case lInt(value) =>
      if (needPtr) throw new Exception("not implemented in current ABI") else value
    case lFloat(value) =>
      if (needPtr) throw new Exception("not implemented in current ABI")
      else {
        if (_type.name == "float") {
          val d = (new java.lang.Float(value)).toDouble
          "0x" + java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(d))
        }
        else value
      }
    case lString(name, value) =>
      if (needPtr) throw new Exception("not implemented in current ABI")
      else {
        val tmp = nextTmpVar()
        out.println(s"\t$tmp = bitcast [${value.bytesLen} x i8]* @.$name to i8*")
        tmp
      }
    case lLocal(varName) =>
      if (needPtr) "%" + varName
      else {
        val tmp = nextTmpVar()
        out.println(s"\t$tmp = load ${_type.name}, ${_type.name}* %$varName")
        tmp
      }
    case lGlobal(value) =>
      if (needPtr) throw new Exception("not implemented in current ABI")
      else "@" + value
    case lParam(paramName) =>
      val isPtr = _type.isInstanceOf[Struct]
      (needPtr, isPtr) match {
        case (true, true) => "%" + paramName
        case (false, false) => "%" + paramName
        case (true, false) =>
          val tmp = nextTmpVar()
          zalloc(tmp, _type)
          out.println(s"\tstore ${_type.name} %$paramName, ${_type.name}* $tmp")
          tmp
        case (false, true) =>
          val tmp = nextTmpVar()
          out.println(s"\t$tmp = load ${_type.name}, ${_type.name}* %$paramName")
          tmp
      }
    case a: Access =>
      val (tmp, _type) = evalAccess(a)
      val next = nextTmpVar()
      out.println(s"\t$next = load ${_type.name}, ${_type.name}* $tmp")
      next
    case Call(id, _type, args) =>
      val toCall = id match {
        case lLocal(value) =>
          val tmp = nextTmpVar()
          out.println(s"\t$tmp = load ${_type.name}, ${_type.name}* %$value")
          tmp
        case lGlobal(value) => "@" + escapeFnName(value)
        case lParam(value) => "%" + value
      }

      _type.ret match {
        case struct@Struct(_name, fields) =>
          val newVar = nextTmpVar(needPercent = false)
          val newArgs = Seq(lLocal(newVar)) ++ args
          val newSignArgs = _type.realArgs

          zalloc("%" + newVar, struct)

          val calculatedArgs = newArgs.zip(newSignArgs).map {
            case (arg, argType) =>
              genInit(argType._type, arg, needPtr = argType._type.isInstanceOf[Struct])
          }

          val joinedArgs = calculatedArgs.zip(newSignArgs).map {
            case (arg, argType) =>
              s"${if (argType._type.isInstanceOf[Struct]) argType._type.name + "*" else argType._type.name} $arg"
          }.mkString(", ")

          out.println(s"\tcall ${_type.realRet.name} ${toCall}($joinedArgs)")
          if (needPtr) "%" + newVar
          else {
            val tmp = nextTmpVar()
            out.println(s"\t$tmp = load ${_type.ret.name}, ${_type.ret.name}* %$newVar")
            tmp
          }
        case _ =>
          val calculatedArgs = (args).zip(_type.args).map {
            case (arg, argType) =>
              genInit(argType._type, arg, needPtr = argType._type.isInstanceOf[Struct])
          }

          val joinedArgs = calculatedArgs.zip(_type.args).map {
            case (arg, argType) =>
              s"${if (argType._type.isInstanceOf[Struct]) argType._type.name + "*" else argType._type.name} $arg"
          }.mkString(", ")

          if (_type.ret == Scalar("void")) {
            out.println(s"\tcall ${_type.ret.name} ${toCall}($joinedArgs)")
            return null
          }

          val tmp = nextTmpVar()
          out.println(s"\t$tmp = call ${_type.ret.name} ${toCall}($joinedArgs)")
          if (needPtr) throw new Exception("not implemented in current ABI")
          else tmp
      }
    case BoolAnd(left, right) =>
      val (beginLabel, secondLabel, endLabel) = (nextLabel, nextLabel, nextLabel)
      out.println(s"\tbr label %$beginLabel")
      out.println(s"$beginLabel:")
      val leftRes = genInit(Scalar("i1"), left, needPtr = false)
      out.println(s"\tbr i1 $leftRes, label %$secondLabel, label %$endLabel")

      out.println(s"$secondLabel:")
      val rightRes = genInit(Scalar("i1"), right, needPtr = false)
      out.println(s"\tbr label %$endLabel")

      out.println(s"$endLabel:")
      val tmp1 = nextTmpVar()
      out.println(s"\t$tmp1 = phi i1 [false, %$beginLabel], [$rightRes, %$secondLabel]")

      if (needPtr) {
        val tmp2 = nextTmpVar()
        out.println(s"\t$tmp2 = alloca i1")
        out.println(s"\tstore i1 $tmp1, i1* $tmp2")
        tmp2
      } else tmp1

    case BoolOr(left, right) =>
      //FIXME: deduplicate code
      val (beginLabel, secondLabel, endLabel) = (nextLabel, nextLabel, nextLabel)
      out.println(s"\tbr label %$beginLabel")
      out.println(s"$beginLabel:")
      val leftRes = genInit(Scalar("i1"), left, needPtr = false)
      out.println(s"\tbr i1 $leftRes, label %$endLabel, label %$secondLabel")

      out.println(s"$secondLabel:")
      val rightRes = genInit(Scalar("i1"), right, needPtr = false)
      out.println(s"\tbr label %$endLabel")

      out.println(s"$endLabel:")
      val tmp1 = nextTmpVar()
      out.println(s"\t$tmp1 = phi i1 [true, %$beginLabel], [$rightRes, %$secondLabel]")

      if (needPtr) {
        val tmp2 = nextTmpVar()
        out.println(s"\t$tmp2 = alloca i1")
        out.println(s"\tstore i1 $tmp1, i1* $tmp2")
        tmp2
      } else tmp1
  }

  def genStat(seq: Seq[Stat]): Unit =
    seq.foreach {
      case v: Var =>
        zalloc(v.irName, v._type)
      case Store(to, fields, varType, init) =>
        val base = to match {
          case p: lParam => genInit(varType, p, true)
          case v: lId => genInit(varType, v, true)
        }

        val (resType, indicies) = evalGep(varType, fields)
        val forStore = genInit(resType, init, needPtr = false)
        val res = nextTmpVar()

        out.println(s"\t$res = getelementptr ${varType.name}, ${varType.name}* ${base}, ${indicies.map { i => s"i32 $i" } mkString (", ")}")
        out.println(s"\tstore ${resType.name} $forStore, ${resType.name}* $res")
      case call: Call =>
        genInit(call._type.ret, call)
      case boolAnd: BoolAnd =>
        genInit(Scalar("i1"), boolAnd, needPtr = false)
      case boolOr: BoolOr =>
        genInit(Scalar("i1"), boolOr, needPtr = false)
      case Cond(init, _if, _else) =>
        val condVar = genInit(Scalar("i1"), init, needPtr = false)
        val (ifLabel, elseLabel, endLabel) = (nextLabel, nextLabel, nextLabel)

        out.println(s"\tbr i1 $condVar, label %$ifLabel, label %$elseLabel")
        out.println(s"$ifLabel:")
        genStat(_if)
        out.println(s"\tbr label %$endLabel")
        out.println(s"$elseLabel:")
        genStat(_else)
        out.println(s"\tbr label %$endLabel")
        out.println(s"$endLabel:")
      case While(init, seq) =>
        val (beginLabel, bodyLabel, endLabel) = (nextLabel, nextLabel, nextLabel)

        out.println(s"\tbr label %$beginLabel")
        out.println(s"$beginLabel:")

        val condVar = genInit(Scalar("i1"), init, needPtr = false)
        out.println(s"\tbr i1 $condVar, label %$bodyLabel, label %$endLabel")
        out.println(s"$bodyLabel:")

        genStat(seq)

        out.println(s"\tbr label %$beginLabel")
        out.println(s"$endLabel:")

      case Ret(_type, init) =>
        val tmp = genInit(_type, init)
        out.println(s"\tret ${_type.name} $tmp")
      case RetVoid() =>
        out.println(s"\tret void")
    }

  def genFunction(fn: Fn): Unit = {
    val signature = fn._type
    out.println(s"define ${signature.realRet.name} @${escapeFnName(fn.name)}(${signature.irArgs.mkString(", ")}) {")

    fn.body match {
      case IrInline(ir) => out.println(s"$ir")
      case Block(seq) =>
        genStat(seq)
    }
    out.println("}")
  }

  case class StringConst(name: String, value: HexUtil.EncodeResult)

  def inferStringConsts(functions: Seq[Fn]): (Seq[StringConst], Seq[Fn]) = {
    var idSeq = 0
    val consts = mutable.ListBuffer[StringConst]()

    def mapInit(init: Init): Init = init match {
      case ls@lString(name, value) =>
        consts += StringConst(name, value)
        ls
      case some@_ => some
    }
    def mapStat(stat: Stat): Stat = stat match {
      case Store(toVar: lId, fields: Seq[String], varType: Type, init: Init) =>
        Store(toVar, fields, varType, mapInit(init))
      case Call(name, ptr, args: Seq[Init]) =>
        Call(name, ptr, args.map(arg => mapInit(arg)))
      case Cond(init: Init, _if: Seq[Stat], _else: Seq[Stat]) =>
        Cond(mapInit(init), _if.map(stat => mapStat(stat)), _else.map(stat => mapStat(stat)))
      case While(init: Init, seq: Seq[Stat]) =>
        While(mapInit(init), seq.map(stat => mapStat(stat)))
      case Ret(_type: Type, init: Init) =>
        Ret(_type, mapInit(init))
      case v: Var => v
      case rv: RetVoid => rv
      case boolAnd: BoolAnd => boolAnd
      case boolOr: BoolOr => boolOr
    }

    val mapped = functions.map { fn =>
      Fn(fn.name, fn._type, fn.body match {
        case ir: IrInline => ir
        case Block(seq) => Block(seq.map { stat => mapStat(stat) })
      })
    }
    (consts, mapped)
  }

  def gen(module: Module): Unit = {
    out.println("declare i32 @memcmp(i8*, i8*, i64)")
    out.println("declare void @llvm.memset.p0i8.i64(i8* nocapture, i8, i64, i32, i1)")

    module.structs.foreach { struct =>
      out.println(s"${struct.name} = type { ${struct.fields.map { f => f._type.name }.mkString(", ")} }")
    }
    val (consts, functions) = inferStringConsts(module.functions)

    consts.foreach { const =>
      out.println(s"@.${const.name} = constant [${const.value.bytesLen} x i8] ${"c\"" + const.value.str + "\""}, align 1")
    }
    functions.foreach { fn => tmpVars = 0; genFunction(fn) }
  }
}