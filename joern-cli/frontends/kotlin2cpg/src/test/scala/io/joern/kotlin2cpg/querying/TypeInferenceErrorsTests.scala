package io.joern.kotlin2cpg.querying

import io.joern.kotlin2cpg.types.Constants
import io.joern.kotlin2cpg.Kt2CpgTestContext
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.semanticcpg.language._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class TypeInferenceErrorsTests extends AnyFreeSpec with Matchers {

  "CPG for code with QE of receiver for which the type cannot be inferred" - {
    lazy val cpg = Kt2CpgTestContext.buildCpg("""
        |package mypkg
        |
        |fun main() {
        |//    val foo = listOf(1, 2, 3) // purposely commented-out for the test to make sense
        |    val bar = foo.flatMap { x ->
        |        listOf(x, x + 1)
        |    }
        |    println(bar) // prints `[1, 2, 2, 3, 3, 4]`
        |}
        |""".stripMargin)

    "should contain a CALL node with an MFN starting with a placeholder type" in {
      val List(c) = cpg.call.drop(1).take(1).l
      c.methodFullName shouldBe Constants.kotlinAny + ".flatMap:ANY(ANY)"
    }
  }

  "CPG for code with simple stdlib fns" - {
    lazy val cpg = Kt2CpgTestContext.buildCpg("""
        |package mypkg
        |
        |import kotlin.collections.HashMap
        |
        |fun bar(): HashMap<String,String> {
        |    val x = HashMap<String, String>()
        |    return x
        |}
        |
        |fun foo(): Boolean {
        |    val c = bar()
        |    return false
        |}
        |""".stripMargin)

    // TODO: remove this test case as soon as there's a custom type descriptor renderer
    // this silly case is only necessary because the Kotlin compiler API writers decided,
    // for whatever reason, to not provide any way of rendering specific types without these
    // substrings in them
    "should not contain METHOD nodes with comments the substring `/*` them" in {
      val mfns = cpg.method.fullName.l
      mfns.filter { fn =>
        fn.contains("/*")
      } shouldBe Seq()
    }
  }

  "CPG for code with stdlib mutable list of items of class without type info available" - {
    lazy val cpg = Kt2CpgTestContext.buildCpg("""
        |package mypkg
        |
        |import com.intellij.ide.util.projectWizard.importSources.DetectedProjectRoot
        |
        |fun foo(x: DetectedProjectRoot): Boolean {
        |    val list = mutableListOf<DetectedProjectRoot>()
        |    list.add(1, x)
        |    println(list)
        |}
        |""".stripMargin)

    "should not contain METHOD nodes with comments the substring `ERROR` in them" in {
      val mfns = cpg.method.fullName.l
      mfns.filter { fn =>
        fn.contains("ERROR") || fn.contains("???")
      } shouldBe Seq()
    }

    "should contain a CALL node with a placeholder MFN in it" in {
      val List(c) = cpg.call.code("mutableListOf.*").l
      c.methodFullName shouldBe "kotlin.collections.mutableListOf:kotlin.collections.MutableList()"
    }
  }

  "CPG for code with QE expression without type info" - {
    lazy val cpg = Kt2CpgTestContext.buildCpg("""
        |package mypkg
        |
        |object AClass {
        |    fun getList(): List<Int?> {
        |        return listOf(1, 2, 3, 4, 5, null)
        |    }
        |}
        |
        |fun main() {
        |    val foo =
        |            AClass.getList()
        |                    .mapNotNull { x -> x }
        |                    .filter { x -> x < 4 }
        |    println(foo) // prints `[1, 2, 3]`
        |
        |    val bar = NoSuchClass.getList()
        |                    .mapNotNull { x -> x }
        |                    .filter { x -> x < 4 }
        |    println(bar)
        |
        |}
        |""".stripMargin)

    "should contain a CALL node with the correct MFN set when type info is available" in {
      val List(c) = cpg.call.methodFullName(Operators.assignment).where(_.argument(1).code("foo")).argument(2).isCall.l
      c.methodFullName shouldBe "kotlin.collections.Iterable.filter:kotlin.collections.List((kotlin.Int)->kotlin.Boolean)"
    }

    "should contain a CALL node with the correct MFN set when type info is not available" in {
      val List(c) = cpg.call.methodFullName(Operators.assignment).where(_.argument(1).code("bar")).argument(2).isCall.l
      c.methodFullName shouldBe "kotlin.Any.filter:ANY(ANY)"
    }
  }

  "CPG for code with extension fn defined on unresolvable type " - {
    lazy val cpg = Kt2CpgTestContext.buildCpg("""
        |package mypkg
        |
        |import com.intellij.openapi.editor.*
        |
        |// `Editor` is the unresolvable type
        |fun Editor.getFileSize(countNewLines: Boolean = false): Int {
        |  val len = document.textLength
        |  val doc = document.charsSequence
        |  return if (countNewLines || len == 0 || doc[len - 1] != '\n') len else len - 1
        |}
        |
        |""".stripMargin)

    "should contain a METHOD node with a MFN property starting with `kotlin.Any`" in {
      val List(m) = cpg.method.fullName(".*getFileSize.*").l
      m.fullName shouldBe "kotlin.Any.getFileSize:kotlin.Int(kotlin.Boolean)"
    }

    // TODO: add more tests
  }

  "CPG for code with extension fn defined on resolvable type with unresolvable subtypes" - {
    lazy val cpg = Kt2CpgTestContext.buildCpg("""
        |package mypkg
        |
        |import com.intellij.openapi.editor.*
        |
        |// `Editor` and `IntArrayList` are unresolvable types
        |fun MutableMap<Editor, IntArrayList>.clone(): MutableMap<Editor, IntArrayList> {
        |  val clone = HashMap<Editor, IntArrayList>(size)
        |  for ((editor, offsets) in this) {
        |    clone[editor] = offsets.clone()
        |  }
        |  return clone
        |}
        |""".stripMargin)

    "should contain a METHOD node with a MFN property that replaced the unresolvable types with `kotlin.Any`" in {
      val List(m) = cpg.method.fullName("kotlin.*clone.*").take(1).l
      m.fullName shouldBe "kotlin.collections.MutableMap.clone:kotlin.collections.MutableMap()"
      // TODO: fix return type
    }

    // TODO: add more tests
  }

  "CPG for code with `containsKey` call on collection of elements without corresponding imported class" - {
    lazy val cpg = Kt2CpgTestContext.buildCpg("""
        |package mypkg
        |
        |import io.no.SuchPackage
        |
        |fun render(results: Map<SuchPackage, IntList>) {
        |  var foo = mutableMapOf<SuchPackage, Array<Int>>()
        |  for (aKey in foo.keys.toList()) {
        |    if (!results.containsKey(aKey))
        |      println("DO!")
        |  }
        |}
        |
        |""".stripMargin)

    "should not contain any MFNs with `ERROR` in them" in {
      cpg.method.fullName(".*ERROR.*").fullName.l shouldBe List()
    }

    "should contain a METHOD node for `containsKey` with `kotlin.Any` as a replacement for failing type inference" in {
      val List(m) = cpg.method.fullName(".*containsKey.*").l
      m.fullName shouldBe "kotlin.collections.Map.containsKey:kotlin.Boolean(kotlin.Any)"
    }
  }
}