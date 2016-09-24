package lang_m2

import lang_m2.Ast0._
import lang_m2.Ast1.FnPointer

import scala.collection.mutable
import TypeCheckerUtil._

class TypeChecker {
  var annoVars = 0
  var anonFns = 0

  def nextAnonVar = {
    annoVars += 1
    s"anon$annoVars"
  }

  def nextAnonFn = {
    annoVars += 1
    s"anonFn$annoVars"
  }

  sealed trait FnKind
  case object Anon extends FnKind
  case object Self extends FnKind
  case object NonSelf extends FnKind

  def evalBlockExpression(scope: Scope,
                          forInit: Boolean,
                          typeAdvice: Option[TypeHint],
                          expression: BlockExpression): InferedExp =
    expression match {
      case v: Val =>
        val initExp = evalBlockExpression(scope, forInit = true, v.typeHint, v.init)
        v.typeHint.map { th => assertTypeEquals(v.init, th, initExp.th) }

        val classifier = initExp.th match {
          case FnTypeHint(seq, _) => seq.headOption.map(_.typeHint)
          case _ => None
        }

        scope.addVar(v, v.name, initExp.th, v.mutable, LocalSymbol)

        val lowType = scope.toLow(initExp.th)
        val infExp = InferedExp(thUnit,
          Seq(Ast1.Var(v.name, lowType))
            ++ initExp.stats
            :+ Ast1.Store(Ast1.lLocal(v.name), Seq(), lowType, initExp.init.get),
          None)

        infExp
      case block@Block(args, seq) =>
        val th = typeAdvice.map {
          case fth: FnTypeHint => Some(fth)
          case _ => None
        }.getOrElse(None)

        val fnName = nextAnonFn
        val infFn = evalFunction(scope, RawFn("", Fn(fnName, th, block, None)), kind = Anon)
        InferedExp(infFn.th, Seq(), Some(Ast1.lGlobal(fnName)))
      case call@ApplyCall(self) =>
        val inferedSelf = evalBlockExpression(scope, forInit = true, None, self)
        val callableFn = scope.findSelfFn("apply", inferedSelf.th, inferCallback = fn => evalFunction(scope, fn, kind = Self))
          .getOrElse(throw new CompileEx(call, CE.NoFnToCall("apply")))


        val infArgs = Seq(inferedSelf)
        val literal = lowLiteral(GlobalSymbol, s"${callableFn._package}apply_for_${inferedSelf.th.name}")
        val lowCall = Ast1.Call(literal, scope.toLow(callableFn.th).asInstanceOf[FnPointer], infArgs.map(_.init.get))

        val (stats, init) =
          if (forInit) (infArgs.flatMap(_.stats), Some(lowCall))
          else (infArgs.flatMap(_.stats) :+ lowCall, None)

        InferedExp(callableFn.th.ret, stats, init)
      case call@GetCall(self, args) =>
        val inferedSelf = evalBlockExpression(scope, forInit = true, None, self)
        val callableFn = scope.findSelfFn("get", inferedSelf.th, inferCallback = fn => evalFunction(scope, fn, kind = Self))
          .getOrElse(throw new CompileEx(call, CE.NoFnToCall("get")))

        val inferedLastArgs = callableFn.th.seq.drop(1).zip(args).map {
          case (argTh, argExpr) => evalBlockExpression(scope, forInit = true, Some(argTh.typeHint), argExpr)
        }

        inferedLastArgs.zip(callableFn.th.seq.drop(1)).foreach {
          case (infArg, argTh) => if (infArg.th != argTh.typeHint) throw new CompileEx(self, CE.NoFnToCall("get"))
        }

        val infArgs = inferedSelf +: inferedLastArgs
        val literal = lowLiteral(GlobalSymbol, s"${callableFn._package}get_for_${inferedSelf.th.name}")

        val lowCall = Ast1.Call(literal, scope.toLow(callableFn.th).asInstanceOf[FnPointer], infArgs.map(_.init.get))
        val (stats, init) =
          if (forInit) (infArgs.flatMap(_.stats), Some(lowCall))
          else (infArgs.flatMap(_.stats) :+ lowCall, None)

        InferedExp(callableFn.th.ret, stats, init)
      case call@SelfCall(fnName, self, args) =>
        val inferedSelf = evalBlockExpression(scope, forInit = true, None, self)
        val callableFn = scope.findSelfFn(fnName, inferedSelf.th, inferCallback = fn => evalFunction(scope, fn, kind = Self))

        callableFn.map { callableFn =>
          val inferedLastArgs = callableFn.th.seq.drop(1).zip(args).map {
            case (argTh, argExpr) => evalBlockExpression(scope, forInit = true, Some(argTh.typeHint), argExpr)
          }

          inferedLastArgs.zip(callableFn.th.seq.drop(1)).foreach {
            case (infArg, argTh) => if (infArg.th != argTh.typeHint) throw new CompileEx(self, CE.NoFnToCall("get"))
          }

          val infArgs = inferedSelf +: inferedLastArgs
          val literal = lowLiteral(GlobalSymbol, s"${callableFn._package}${fnName}_for_${inferedSelf.th.name}")

          val lowCall = Ast1.Call(literal, scope.toLow(callableFn.th).asInstanceOf[FnPointer], infArgs.map(_.init.get))
          val (stats, init) =
            if (forInit) (infArgs.flatMap(_.stats), Some(lowCall))
            else (infArgs.flatMap(_.stats) :+ lowCall, None)

          InferedExp(callableFn.th.ret, stats, init)
        }.getOrElse {
          scope.resolveType(inferedSelf.th) match {
            case ft: FactorType =>
              val fieldExists = ft.fields.find(_.name == fnName).isDefined
              if (!fieldExists) throw new CompileEx(call, CE.NoFnToCall(fnName))

              if (args.isEmpty)
                evalBlockExpression(scope, forInit, typeAdvice, ApplyCall(Prop(self, lId(fnName))))
              else
                evalBlockExpression(scope, forInit, typeAdvice, GetCall(Prop(self, lId(fnName)), args))
            case _ => throw new CompileEx(call, CE.NoFnToCall(fnName))
          }
        }
      case self@Call(fnName, args) =>
        var found: Option[(SymbolLocation, (String, FnTypeHint))] = None
        scope.findVar(fnName).map { si =>
          if (si.th.isInstanceOf[FnTypeHint])
            found = Some((si.location, ("", si.th.asInstanceOf[FnTypeHint])))
        }
        scope.findFn(fnName, inferCallback = fn => evalFunction(scope, fn, kind = NonSelf)).map { callableFn =>
          found = Some((GlobalSymbol, (callableFn._package, callableFn.th)))
        }

        found.map { case (location, (_package, fnTh)) =>
          val infArgs = fnTh.seq.zip(args).map {
            case (argTh, argExpr) => evalBlockExpression(scope, forInit = true, Some(argTh.typeHint), argExpr)
          }

          infArgs.zip(fnTh.seq).foreach {
            case (infArg, argTh) => if (infArg.th != argTh.typeHint) throw new CompileEx(self, CE.NoFnToCall(fnName))
          }

          val literal = lowLiteral(location, s"${_package}$fnName")

          val lowCall = Ast1.Call(literal, scope.toLow(fnTh).asInstanceOf[FnPointer], infArgs.map(_.init.get))
          val (stats, init) =
            if (forInit) (infArgs.flatMap(_.stats), Some(lowCall))
            else (infArgs.flatMap(_.stats) :+ lowCall, None)

          InferedExp(fnTh.ret, stats, init)
        }.getOrElse {
          if (args.isEmpty)
            evalBlockExpression(scope, forInit, typeAdvice, ApplyCall(lId(fnName)))
          else
            evalBlockExpression(scope, forInit, typeAdvice, GetCall(lId(fnName), args))
        }
      case lInt(value) =>
        val infTh = typeAdvice.map {
          case sth: ScalarTypeHint =>
            scope.resolveType(sth) match {
              case ScalarType(name, ll) =>
                if (ll.matches("i\\d+") && ll != "i1") sth
                else thInt
              case _ => thInt
            }
          case _ => thInt
        }.getOrElse(thInt)

        InferedExp(infTh, Seq(), Some(Ast1.lInt(value)))
      case lFloat(value) =>
        val infTh = typeAdvice.map {
          case sth: ScalarTypeHint =>
            scope.resolveType(sth) match {
              case ScalarType(name, ll) =>
                if (Seq("half", "float", "double", "fp128", "x86_fp80", "ppc_fp128").contains(ll)) sth
                else thFloat
              case _ => thFloat
            }
          case _ => thFloat
        }.getOrElse(thFloat)

        InferedExp(infTh, Seq(), Some(Ast1.lFloat(value)))
      case lBoolean(value) =>
        InferedExp(thBool, Seq(), Some(Ast1.lInt(value)))
      case lString(value) =>
        InferedExp(thString, Seq(), Some(Ast1.lString(s"${nextAnonVar}", HexUtil.singleByteNullTerminated(value.getBytes))))
      case self@lId(varName) =>
        val vi = scope.findVar(varName).getOrElse(throw new CompileEx(self, CE.VarNotFound(varName)))

        val literal = lowLiteral(vi.location, varName)

        InferedExp(vi.th, Seq(), Some(literal))
      case Prop(from, prop) =>
        val infFrom = evalBlockExpression(scope, forInit = true, None, from)

        val realField = infFrom.th match {
          case sth: ScalarTypeHint =>
            scope.resolveType(sth) match {
              case factor: FactorType =>
                factor.fields.find(field => field.name == prop.value)
              case scalar: ScalarType => None
            }
          case fn: FnTypeHint => None
        }

        if (realField.isDefined) {
          val field = realField.get
          val lowFromType = scope.toLow(infFrom.th)
          InferedExp(field.typeHint, Seq(), Some(Ast1.Access(infFrom.init.get, lowFromType, field.name)))
        } else {
          // Fixme: no cheats, bro. Test that needed function exists
          evalBlockExpression(scope, forInit, typeAdvice, SelfCall(prop.value, from, Seq()))
        }
      case Store(to, what) =>
        // FIXME: validate fields and ret
        val whatExp = evalBlockExpression(scope, forInit = true, Some(thBool), what)
        val varName = to.head.value
        val _var = scope.findVar(varName).getOrElse(throw new CompileEx(to.head, CE.VarNotFound(varName)))

        if (!_var.isMutable)
          throw new CompileEx(to.head, CE.ReassignToVal())

        val srcType = scope.resolveType(_var.th)

        to.drop(1).foldLeft[Type](srcType) {
          case (res, prop) =>
            res match {
              case FactorType(name, fields) =>
                fields.find(f => f.name == prop.value) match {
                  case Some(field) => scope.resolveType(field.typeHint)
                  case None => throw new CompileEx(prop, CE.PropNotFound(prop.value, name))
                }
              case _type@_ => throw new CompileEx(prop, CE.PropNotFound(prop.value, _type.name))
            }
        }

        val lowVarType = scope.toLow(_var.th)
        val lowTo = lowLiteral(_var.location, varName)
        val lowStore = Ast1.Store(lowTo, to.drop(1).map(_.value), lowVarType, whatExp.init.get)

        InferedExp(thUnit, whatExp.stats :+ lowStore, None)
      case self@Cond(ifCond, _then, _else) =>
        val anonVarName = nextAnonVar

        def retMapper(scope: Scope, last: Option[InferedExp]): InferedExp =
          last.map { last =>
            val lastStatInit =
              if (last.init.isDefined && last.init.get.isInstanceOf[Ast1.Stat]) Seq(last.init.get.asInstanceOf[Ast1.Stat])
              else Seq()

            if (last.th == thUnit) InferedExp(thUnit, last.stats ++ lastStatInit, None)
            else {
              if (forInit) {
                val lowType = scope.toLow(last.th)
                InferedExp(last.th, last.stats :+ Ast1.Store(Ast1.lLocal(anonVarName), Seq(), lowType, last.init.get), None)
              }
              else InferedExp(last.th, last.stats ++ lastStatInit, None)
            }
          }.getOrElse(InferedExp(thUnit, Seq(), None))

        val condExp = evalBlockExpression(scope, forInit = true, Some(thBool), ifCond)
        assertTypeEquals(ifCond, thBool, condExp.th)

        val (ifTh, ifBranch) = evalBlock2(scope, typeAdvice, _then.seq, retMapper)
        val (elseTh, elseBranch) = _else.map { _else =>
          evalBlock2(scope, typeAdvice, _else.seq, retMapper)
        }.getOrElse(thUnit, Ast1.Block(Seq()))

        val lowIf = Ast1.Cond(condExp.init.get, ifBranch.seq, elseBranch.seq)

        if (forInit) {
          if (ifTh != elseTh) throw new CompileEx(self, CE.BranchTypesNotEqual())
          val lowType = scope.toLow(ifTh)
          InferedExp(ifTh, Seq(Ast1.Var(anonVarName, lowType)) :+ lowIf, Some(Ast1.lLocal(anonVarName)))
        }
        else InferedExp(ifTh, Seq(lowIf), None)

      case BoolAnd(left, right) =>
        val leftInf = evalBlockExpression(scope, forInit = true, Some(thBool), left)
        assertTypeEquals(left, thBool, leftInf.th)
        val rightInf = evalBlockExpression(scope, forInit = true, Some(thBool), right)
        assertTypeEquals(left, thBool, rightInf.th)

        if (forInit)
          InferedExp(thBool, Seq(), Some(Ast1.BoolAnd(leftInf.init.get, rightInf.init.get)))
        else
          InferedExp(thBool, Seq(Ast1.BoolAnd(leftInf.init.get, rightInf.init.get)), None)
      case BoolOr(left, right) =>
        //FIXME: deduplicate code
        val leftInf = evalBlockExpression(scope, forInit = true, Some(thBool), left)
        assertTypeEquals(left, thBool, leftInf.th)
        val rightInf = evalBlockExpression(scope, forInit = true, Some(thBool), right)
        assertTypeEquals(left, thBool, rightInf.th)

        if (forInit)
          InferedExp(thBool, Seq(), Some(Ast1.BoolOr(leftInf.init.get, rightInf.init.get)))
        else
          InferedExp(thBool, Seq(Ast1.BoolOr(leftInf.init.get, rightInf.init.get)), None)
      case While(cond, _then) =>
        val condExp = evalBlockExpression(scope, forInit = true, Some(thBool), cond)

        assertTypeEquals(cond, thBool, condExp.th)

        val (th, block) = evalBlock2(scope, typeAdvice, _then.seq, retMapper = {
          case (scope, infExp) =>
            if (infExp.isDefined) infExp.get
            else InferedExp(thUnit, Seq(), None)
        })

        InferedExp(thUnit, Seq(Ast1.While(condExp.init.get, block.seq)), None)
    }

  def evalBlock2(scope: Scope,
                 typeAdvice: Option[TypeHint],
                 expressions: Seq[BlockExpression],
                 retMapper: (Scope, Option[InferedExp]) => InferedExp): (TypeHint, Ast1.Block) = {
    val childScope = scope.mkChild()
    val stats = expressions.dropRight(1).flatMap { blockExpr =>
      evalBlockExpression(childScope, forInit = false, None, blockExpr).stats
    }

    val lastInfExp = expressions.lastOption.map { blockExpr =>
      evalBlockExpression(childScope, forInit = true, None, blockExpr)
    }

    val lastExp = retMapper(scope, lastInfExp)
    val lowBlock = Ast1.Block(stats ++ lastExp.stats)

    (lastExp.th, lowBlock)
  }

  def genVoid(infExp: InferedExp): InferedExp = {
    val lastStats = infExp.init match {
      case None => Seq()
      case Some(init) => if (init.isInstanceOf[Ast1.Stat]) Seq(init.asInstanceOf[Ast1.Stat]) else Seq()
    }
    InferedExp(thUnit, infExp.stats ++ lastStats ++ Seq(Ast1.RetVoid()), None)
  }

  def evalFunction(scope: Scope, rawFn: RawFn, kind: FnKind): InferedFn = {
    val fn = rawFn.fn
    val infArgs = inferFnArgs(fn)
    val firstArgTh = infArgs.headOption.map(_.typeHint)


    val expectedRet: Option[TypeHint] = fn.typeHint match {
      case Some(FnTypeHint(_, retTh)) =>
        fn.retTypeHint match {
          case Some(rth) =>
            if (rth != retTh)
              throw new CompileEx(rth, CE.ExprTypeMismatch(retTh, rth))
          case _ =>
        }
        Some(retTh)
      case None =>
        fn.retTypeHint match {
          case th@Some(_) => th
          case None => None
        }
    }

    val (inferedRet, lowBody): (TypeHint, Ast1.FnBody) = fn.body match {
      case LlInline(value) => // ret должен быть указан явно
        expectedRet.map { ret =>
          (ret, Ast1.IrInline(value))
        }.getOrElse(throw new CompileEx(fn, CE.NeedExplicitTypeDefinition()))
      case self@Block(args, seq) =>
        val childScope = scope.mkChild()

        infArgs.foreach { arg =>
          childScope.addVar(arg, arg.name, arg.typeHint, isMutable = false, ParamSymbol)
        }

        val (th, body) = evalBlock2(childScope, expectedRet, seq, retMapper = {
          case (scope, Some(infExp)) =>
            if (infExp.th == thUnit)
              genVoid(infExp)
            else if (expectedRet.isDefined && expectedRet.get == thUnit)
              genVoid(infExp)
            else {
              val lowType = scope.toLow(infExp.th)
              val seq = infExp.th match {
                case th: ScalarTypeHint =>
                  scope.resolveType(th) match {
                    case fact: FactorType =>
                      Seq(Ast1.Store(Ast1.lParam("ret"), Seq(), lowType, infExp.init.get), Ast1.RetVoid())
                    case _ => Seq(Ast1.Ret(scope.toLow(th), infExp.init.get))
                  }
                case th: FnTypeHint => Seq(Ast1.Ret(scope.toLow(th), infExp.init.get))
              }

              InferedExp(infExp.th, infExp.stats ++ seq, None)
            }
          case (scope, None) =>
            InferedExp(thUnit, Seq(Ast1.RetVoid()), None)
        })

        expectedRet.map { eth =>
          assertTypeEquals(self, eth, th)
        }

        (th, body)
    }

    val lowFnName = kind match {
      case Self => s"${rawFn._package}${fn.name}_for_${infArgs.head.typeHint.name}"
      case NonSelf => if (fn.name == "main") fn.name else s"${rawFn._package}${fn.name}"
      case Anon => s"${fn.name}"
    }

    val th = FnTypeHint(infArgs, inferedRet)
    val lowSignature = Ast1.FnPointer(
      args = infArgs.map {
        arg => Ast1.Field(arg.name, scope.toLow(arg.typeHint))
      },
      ret = scope.toLow(inferedRet))

    val lowFn = Ast1.Fn(lowFnName, lowSignature, lowBody)
    val infFn = InferedFn(rawFn._package, th, lowFn)

    kind match {
      case Self => scope.inferSelfFn(fn.name, infArgs.head.typeHint, infFn)
      case NonSelf => scope.inferFn(fn.name, infFn)
      case Anon => scope.inferAnonFn(fn.name, infFn)
    }

    infFn
  }

  def transform(_package: String, module: Module, sourceMap: SourceMap): TypeCheckResult = {
    try {
      val scope = {
        val types = module.types ++ Seq(ScalarType("Unit", "void"), ScalarType("Int", "i32"),
          ScalarType("Float", "float"), ScalarType("Boolean", "i1"), ScalarType("String", "i8*"))

        val typeMap = types.map { td => (td.name, TypeInfo(td, "", mutable.HashMap())) }.toMap
        val fnMap = mutable.HashMap[String, FnContainer]()

        val functions = module.functions ++ module.types.filter(_.isInstanceOf[FactorType]).flatMap { case ft: FactorType =>
          Seq(Macro.genConstructor(typeMap, ft), Macro.genEquals(typeMap, ft), Macro.genNotEquals(typeMap, ft))
        } :+ Macro.booleanNot()

        // FIXME: validate duplications
        functions.foreach { fn =>
          val firstArg = TypeCheckerUtil.inferFnArgs(fn).headOption

          firstArg match {
            case Some(FnTypeHintField(name, th)) if name == "self" =>
              typeMap.getOrElse(th.name, throw new CompileEx(th, CE.TypeNotFound(th.name)))
                .selfFunctions += (fn.name -> FnContainer(RawFn(_package, fn)))
            case _ => fnMap += (fn.name -> FnContainer(RawFn(_package, fn)))
          }
        }
        new Scope(new GlobalScope(typeMap, fnMap, imports = Map()))
      }

      val structs = module.types.filter(_.isInstanceOf[FactorType]).map { td =>
        val factorType = td.asInstanceOf[FactorType]
        scope.toLow(ScalarTypeHint(factorType.name)).asInstanceOf[Ast1.Struct]
      }

      val selfFunctions: Seq[Ast1.Fn] = scope.types.values.flatMap { ti =>
        ti.selfFunctions.values.map { selfFnCont =>
          selfFnCont.fnInfo match {
            case fn: RawFn => evalFunction(scope, fn, kind = Self).lowFn
            case HeaderFn(_, th) => null
            case InferedFn(_, _, lowFn) => lowFn
          }
        }
      }.toSeq


      val nonSelfFunctions = scope.functions.values.map(_.fnInfo).map {
        case fn: RawFn => evalFunction(scope, fn, kind = NonSelf).lowFn
        case InferedFn(_, th, lowFn) => lowFn
      }

      val anonFunctions = scope.anonFunctions.values.map(_.lowFn)

      TypeCheckSuccess(Ast1.Module(structs, selfFunctions ++ nonSelfFunctions ++ anonFunctions))
    }
    catch {
      case ex: CompileEx =>
        val at = sourceMap.find(ex.node).getOrElse(AstInfo("__no_source", 0, 0))
        TypeCheckFail(at, ex.error)
    }
  }
}
