package m2

import java.io.{FileOutputStream, PrintStream}

import grammar2.M2Parser
import lang_m2.Ast0._
import lang_m2._
import org.antlr.v4.runtime.tree.ParseTree
import org.scalatest.FunSuite

class TypeCheckerTest extends FunSuite {
  val moduleParser = new Util {
    override def whatToParse: (M2Parser) => ParseTree = { parser => parser.module() }
  }

  def dotypeCheck(src: String) = {
    val (ast0, sourceMap) = moduleParser.parse[Module](src)
    println(ast0)

    val typeChecker = new TypeChecker()
    val typeCheckerResult = typeChecker.transform(ast0, sourceMap)
    typeCheckerResult match {
      case TypeCheckSuccess(ast1) =>
        println(ast1)
        val out = new PrintStream(System.out)
        new IrGen(out).gen(ast1)
      case TypeCheckFail(at, error) =>
        throw new Exception(s"at ${at.fname}:${at.line}:${at.line} -> \n\t$error")
    }
  }

  test("type def test") {
    dotypeCheck(
      """
        |type Unit = llvm { void }
        |type Boolean = llvm { i1 }
        |type Int = llvm { i32 }
        |type Float = llvm { f32 }
        |type String = llvm { i8* }
        |type Vec3 = (x: Float, y: Float, z: Float)
        |type Fd = (self handle: Int)
        |type FnPtr = (name: String, ptr: () -> Unit)
      """.stripMargin)
  }

  test("simple test") {
    dotypeCheck(
      """
        |type Unit = llvm { void }
        |type Boolean = llvm { i1 }
        |type Float = llvm { float }
        |type Int = llvm { i32 }
        |
        |def +: (self: Int, other: Int) -> Int = llvm {
        |  %1 = add nsw i32 %other, %self
        |  ret i32 %1
        |}
        |def *: (self: Int, other: Int) -> Int = llvm {
        |  %1 = mul nsw i32 %other, %self
        |  ret i32 %1
        |}
        |def twice = \self: Int -> self + self
        |
        |type Vec3 = (x: Int, y: Int, z: Int)
        |
        |def + = \self: Vec3, other: Vec3 ->
        |  Vec3(self.x + other.x, self.y + other.y, self.z + other.z)
        |
        |def main = {
        |  val a = 1 + 2.twice * 3
        |  val v1 = Vec3(1, 2, 1)
        |  val v2 = Vec3(3, 2, 1)
        |  val v3 = v1 + v2
        |  a + v3.x
        |}: Int
      """.stripMargin)
  }

  test("a store and access test") {
    dotypeCheck(
      """
        |type Int = llvm { i32 }
        |type Vec3 = (x: Int, y: Int, z: Int)
        |
        |def +: (self: Int, other: Int) -> Int = llvm {
        |  %1 = add nsw i32 %other, %self
        |  ret i32 %1
        |}
        |
        |def main = {
        |  var a = Vec3(1, 1, 1)
        |  a.x = a.y + a.z
        |  a.x
        |}: Int
      """.stripMargin)
  }

  test("float test") {
    dotypeCheck(
      """
        |type Unit = llvm { void }
        |type Float = llvm { float }
        |type Int = llvm { i32 }
        |
        |def +: (self: Float, other: Float) -> Float = llvm {
        |  %1 = fadd float %other, %self
        |  ret float %1
        |}
        |def *: (self: Float, other: Float) -> Float = llvm {
        |  %1 = fmul float %other, %self
        |  ret float %1
        |}
        |
        |def toInt: (self: Float) -> Int = llvm {
        |  %1 = fptosi float %self to i32
        |  ret i32 %1
        |}
        |
        |def main = {
        | val pi = 3.14
        | val a = pi * pi + 2.1
        |
        | a.toInt
        |}: Int
      """.stripMargin)
  }

  test("strings test (Hello, world)") {
    dotypeCheck(
      """
        |type Unit = llvm { void }
        |type String = llvm { i8* }
        |
        |def print: (self: String) -> Unit = llvm  {
        |  %1 = call i32 @puts(i8* %self)
        |  ret void
        |}
        |
        |def main = \ ->
        |  'こんにちは、世界!'.print
      """.stripMargin)
  }

  test("a conditions test") {
    dotypeCheck(
      """
        |type Unit = llvm { void }
        |type Boolean = llvm { i1 }
        |type Int = llvm { i32 }
        |
        |def >: (self: Int, other: Int) -> Boolean = llvm {
        |  %1 = icmp sgt i32 %self, %other
        |  ret i1 %1
        |}
        |
        |def foo = {
        |}: Unit
        |
        |def main = {
        |  val a = 11
        |  if a > 11 then foo() else 1
        |
        |  val b = if a > 11 then true else false
        |  b
        |}: Boolean
      """.stripMargin)
  }

  test("a while loop test") {
    dotypeCheck(
      """
        |type Unit = llvm { void }
        |type Boolean = llvm { i1 }
        |type Int = llvm { i32 }
        |
        |def +: (self: Int, other: Int) -> Int = llvm {
        |  %1 = add nsw i32 %other, %self
        |  ret i32 %1
        |}
        |def <: (self: Int, other: Int) -> Boolean = llvm {
        |  %1 = icmp slt i32 %self, %other
        |  ret i1 %1
        |}
        |
        |def main = {
        |  var a = 0
        |  while a < 100 { a = a + 1 }
        |  a
        |}: Int
      """.stripMargin)
  }

  test("fn pointer test") {
    dotypeCheck(
      """
        |type Unit = llvm { void }
        |type Int = llvm { i32 }
        |
        |def +: (self: Int, other: Int) -> Int = llvm {
        |  %1 = add nsw i32 %other, %self
        |  ret i32 %1
        |}
        |
        |def foo = { fn: (x: Int) -> Int, x: Int ->
        |  fn(x)
        |}: Int
        |
        |def main = {
        |  val fn = \i: Int -> i + 1
        |  val a = foo(\i -> i + 1, 1)
        |  val b = foo({ i -> i + 1 }, 2)
        |  val c = foo(fn, 3)
        |
        |  fn(0) + c + a + b
        |}: Int
      """.stripMargin)
  }

  test("a cheat arrays test") {
    dotypeCheck(
      """
        |type Unit = llvm { void }
        |type Boolean = llvm { i1 }
        |type Int = llvm { i32 }
        |type IntArray = llvm { i32* }
        |
        |def +: (self: Int, other: Int) -> Int = llvm {
        |  %1 = add nsw i32 %other, %self
        |  ret i32 %1
        |}
        |def *: (self: Int, other: Int) -> Int = llvm {
        |  %1 = mul nsw i32 %other, %self
        |  ret i32 %1
        |}
        |def <: (self: Int, other: Int) -> Boolean = llvm {
        |  %1 = icmp slt i32 %self, %other
        |  ret i1 %1
        |}
        |
        |def allocIntArray : (size: Int) -> IntArray = llvm {
        |  %1 = mul nsw i32 %size, 4
        |  %2 = call i8* @malloc(i32 %1)
        |  %3 = bitcast i8* %2 to i32*
        |  ret i32* %3
        |}
        |
        |def set: (self: IntArray, index: Int, value: Int) -> Unit = llvm {
        |  %1 = getelementptr i32, i32* %self, i32 %index
        |  store i32 %value, i32* %1
        |  ret void
        |}
        |
        |def apply: (self: IntArray, index: Int) -> Int = llvm {
        |  %1 = getelementptr i32, i32* %self, i32 %index
        |  %2 = load i32, i32* %1
        |  ret i32 %2
        |}
        |
        |def free : (self: IntArray) -> Unit = llvm {
        |  %1 = bitcast i32* %self to i8*
        |  call void @free(i8* %1)
        |  ret void
        |}
        |
        |def main = {
        |  val array = allocIntArray(10)
        |  var i = 0
        |  while i < 10 {
        |    array.set(i, i)
        |    i = i + 1
        |  }
        |  i = 0
        |  var sum = 0
        |  while i < 10 {
        |    sum = sum + array.apply(i)
        |    i = i + 1
        |  }
        |  array.free
        |  sum
        |}: Int
      """.stripMargin)
  }
}
