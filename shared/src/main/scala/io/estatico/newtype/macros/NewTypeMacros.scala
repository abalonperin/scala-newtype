package io.estatico.newtype.macros

import io.estatico.newtype.Coercible
import scala.reflect.ClassTag
import scala.reflect.macros.blackbox

//noinspection TypeAnnotation
@macrocompat.bundle
private[macros] class NewTypeMacros(val c: blackbox.Context)
  extends NewTypeCompatMacros {

  import c.universe._

  def newtypeAnnotation(annottees: Tree*): Tree =
    runAnnotation(subtype = false, annottees)

  def newsubtypeAnnotation(annottees: Tree*): Tree =
    runAnnotation(subtype = true, annottees)

  def runAnnotation(subtype: Boolean, annottees: Seq[Tree]): Tree = {
    val (name, result) = annottees match {
      case List(clsDef: ClassDef) =>
        (clsDef.name, runClass(clsDef, subtype))
      case List(clsDef: ClassDef, modDef: ModuleDef) =>
        (clsDef.name, runClassWithObj(clsDef, modDef, subtype))
      case _ =>
        fail(s"Unsupported @$macroName definition")
    }
    if (debug) println(s"Expanded @$macroName $name:\n" ++ show(result))
    if (debugRaw) println(s"Expanded @$macroName $name (raw):\n" + showRaw(result))
    result
  }

  // Support Flag values which are not available in Scala 2.10
  implicit final class FlagSupportOps(val repr: Flag.type) {
    def CASEACCESSOR = scala.reflect.internal.Flags.CASEACCESSOR.toLong.asInstanceOf[FlagSet]
    def PARAMACCESSOR = scala.reflect.internal.Flags.PARAMACCESSOR.toLong.asInstanceOf[FlagSet]
  }

  val CoercibleCls = typeOf[Coercible[Nothing, Nothing]].typeSymbol
  val CoercibleObj = CoercibleCls.companion
  val ClassTagCls = typeOf[ClassTag[Nothing]].typeSymbol
  val ClassTagObj = ClassTagCls.companion
  val ObjectCls = typeOf[Object].typeSymbol

  // We need to know if the newtype is defined in an object so we can report
  // an error message if methods are defined on it (otherwise, the user will
  // get a cryptic error of 'value class may not be a member of another class'
  // due to our generated extension methods.
  val isDefinedInObject = c.internal.enclosingOwner.isModuleClass

  val macroName: Tree = {
    c.prefix.tree match {
      case Apply(Select(New(name), _), _) => name
      case _ => c.abort(c.enclosingPosition, "Unexpected macro application")
    }
  }

  val (debug, debugRaw) = c.prefix.tree match {
    case q"new ${`macroName`}(..$args)" =>
      (
        args.collectFirst { case q"debug = true" => }.isDefined,
        args.collectFirst { case q"debugRaw = true" => }.isDefined
      )
    case _ => (false, false)
  }

  def fail(msg: String) = c.abort(c.enclosingPosition, msg)

  def runClass(clsDef: ClassDef, subtype: Boolean) = {
    runClassWithObj(clsDef, q"object ${clsDef.name.toTermName}".asInstanceOf[ModuleDef], subtype)
  }

  def runClassWithObj(clsDef: ClassDef, modDef: ModuleDef, subtype: Boolean) = {
    val valDef = extractConstructorValDef(getConstructor(clsDef.impl.body))
    // Converts [F[_], A] to [F, A]; needed for applying the defined type params.
    val tparamNames: List[TypeName] = clsDef.tparams.map(_.name)
    // Type params with variance removed for building methods.
    val tparamsNoVar: List[TypeDef] = clsDef.tparams.map(td =>
      TypeDef(Modifiers(Flag.PARAM), td.name, td.tparams, td.rhs)
    )
    val tparamsWild = tparamsNoVar.map {
      case TypeDef(mods, _, args, tree) => TypeDef(mods, typeNames.WILDCARD, args, tree)
    }
    // Ensure we're not trying to inherit from anything.
    validateParents(clsDef.impl.parents)
    // Build the type and object definitions.
    generateNewType(clsDef, modDef, valDef, tparamsNoVar, tparamNames, tparamsWild, subtype)
  }

  def mkBaseTypeDef(clsDef: ClassDef, reprType: Tree, subtype: Boolean) = {
    val refinementName = TypeName(clsDef.name.decodedName.toString + "$newtype")
    (clsDef.tparams, subtype) match {
      case (_, false)      =>  q"type Base             = { type $refinementName } "
      case (Nil, true)     =>  q"type Base             = $reprType"
      case (tparams, true) =>  q"type Base[..$tparams] = $reprType"
    }
  }

  def mkTypeTypeDef(clsDef: ClassDef, tparamsNames: List[TypeName], subtype: Boolean) =
    (clsDef.tparams, subtype) match {
      case (Nil, false) =>     q"type Type             <: Base with Tag"
      case (tparams, false) => q"type Type[..$tparams] <: Base with Tag[..$tparamsNames]"
      case (Nil, true)  =>     q"type Type             <: Base with Tag"
      case (tparams, true) =>  q"type Type[..$tparams] <: Base[..$tparamsNames] with Tag[..$tparamsNames]"
    }

  def generateNewType(
    clsDef: ClassDef, modDef: ModuleDef, valDef: ValDef,
    tparamsNoVar: List[TypeDef], tparamNames: List[TypeName], tparamsWild: List[TypeDef],
    subtype: Boolean
  ): Tree = {
    val q"object $objName extends { ..$objEarlyDefs } with ..$objParents { $objSelf => ..$objDefs }" = modDef
    val typeName = clsDef.name
    val clsName = clsDef.name.decodedName
    val reprType = valDef.tpt
    val typesTraitName = TypeName(clsName.toString + '$' + "Types")
    val tparams = clsDef.tparams
    val classTagName = TermName(clsName + "$classTag")
    val companionExtraDefs =
      generateClassTag(classTagName, valDef, tparamsNoVar, tparamNames, subtype) ::
        maybeGenerateApplyMethod(clsDef, valDef, tparamsNoVar, tparamNames) :::
        maybeGenerateOpsDef(clsDef, valDef, tparamsNoVar, tparamNames) :::
        generateCoercibleInstances(tparamsNoVar, tparamNames, tparamsWild) :::
        generateDerivingMethods(tparamsNoVar, tparamNames, tparamsWild)

    val newtypeObjParents = objParents :+ tq"$typesTraitName"
    val newtypeObjDef = q"""
      object $objName extends { ..$objEarlyDefs } with ..$newtypeObjParents { $objSelf =>
        ..$objDefs
        ..$companionExtraDefs
      }
    """

    // Note that we use an abstract type alias
    // `type Type <: Base with Tag` and not `type Type = ...` to prevent
    // scalac automatically expanding the type alias.
    // Also, Scala 2.10 doesn't support objects having abstract type members, so we have to
    // use some indirection by defining the abstract type in a trait then having
    // the companion object extend the trait.
    // See https://github.com/scala/bug/issues/10750

    val baseTypeDef = mkBaseTypeDef(clsDef, reprType, subtype)
    val typeTypeDef = mkTypeTypeDef(clsDef, tparamNames, subtype)

    if (tparams.isEmpty) {
      q"""
        type $typeName = $objName.Type
        trait $typesTraitName {
          type Repr = $reprType
          $baseTypeDef
          trait Tag
          ${mkTypeTypeDef(clsDef, tparamNames, subtype)}
        }
        $newtypeObjDef
      """
    } else {
      q"""
        type $typeName[..$tparams] = ${typeName.toTermName}.Type[..$tparamNames]
        trait $typesTraitName {
          type Repr[..$tparams] = $reprType
          $baseTypeDef
          trait Tag[..$tparams]
          $typeTypeDef
        }
        $newtypeObjDef
      """
    }
  }

  def maybeGenerateApplyMethod(
    clsDef: ClassDef, valDef: ValDef, tparamsNoVar: List[TypeDef], tparamNames: List[TypeName]
  ): List[Tree] = {
    if (!clsDef.mods.hasFlag(Flag.CASE)) Nil else List(
      if (tparamsNoVar.isEmpty) {
        q"def apply(${valDef.name}: ${valDef.tpt}): Type = ${valDef.name}.asInstanceOf[Type]"
      } else {
        q"""
          def apply[..$tparamsNoVar](${valDef.name}: ${valDef.tpt}): Type[..$tparamNames] =
            ${valDef.name}.asInstanceOf[Type[..$tparamNames]]
        """
      }
    )
  }

  // We should expose the constructor argument as an extension method only if
  // it was defined as a public param.
  def shouldGenerateValMethod(clsDef: ClassDef, valDef: ValDef): Boolean = {
    clsDef.impl.body.collectFirst {
      case vd: ValDef
        if (vd.mods.hasFlag(Flag.CASEACCESSOR) || vd.mods.hasFlag(Flag.PARAMACCESSOR))
          && !vd.mods.hasFlag(Flag.PRIVATE)
          && vd.name == valDef.name => ()
    }.isDefined
  }

  def maybeGenerateValMethod(
    clsDef: ClassDef, valDef: ValDef
  ): Option[Tree] = {
    if (!shouldGenerateValMethod(clsDef, valDef)) {
      None
    } else if (!isDefinedInObject) {
      c.abort(valDef.pos, s"""
        |Fields can only be defined for newtypes defined in an object
        |Consider defining as: private val ${valDef.name.decodedName}
      """.trim.stripMargin)
    } else {
      Some(q"def ${valDef.name}: ${valDef.tpt} = $$this$$.asInstanceOf[${valDef.tpt}]")
    }
  }

  def maybeGenerateOpsDef(
    clsDef: ClassDef, valDef: ValDef, tparamsNoVar: List[TypeDef], tparamNames: List[TypeName]
  ): List[Tree] = {
    val extensionMethods =
      maybeGenerateValMethod(clsDef, valDef) ++ getInstanceMethods(clsDef)

    if (extensionMethods.isEmpty) {
      Nil
    } else {
      // Note that we generate the implicit class for extension methods and the
      // implicit def to convert `this` used in the Ops to our newtype value.
      if (clsDef.tparams.isEmpty) {
        List(
          q"""
              implicit final class Ops$$newtype(val $$this$$: Type) extends $opsClsParent {
                ..$extensionMethods
              }
            """,
          q"implicit def opsThis(x: Ops$$newtype): Type = x.$$this$$"
        )
      } else {
        List(
          q"""
              implicit final class Ops$$newtype[..${clsDef.tparams}](
                val $$this$$: Type[..$tparamNames]
              ) extends $opsClsParent {
                ..$extensionMethods
              }
            """,
          q"""
              implicit def opsThis[..$tparamsNoVar](
                x: Ops$$newtype[..$tparamNames]
              ): Type[..$tparamNames] = x.$$this$$
            """
        )
      }
    }
  }

  def generateDerivingMethods(
    tparamsNoVar: List[TypeDef], tparamNames: List[TypeName], tparamsWild: List[TypeDef]
  ): List[Tree] = {
    if (tparamsNoVar.isEmpty) {
      List(q"def deriving[T[_]](implicit ev: T[Repr]): T[Type] = ev.asInstanceOf[T[Type]]")
    } else {
      List(
        q"""
          def deriving[T[_], ..$tparamsNoVar](
            implicit ev: T[Repr[..$tparamNames]]
          ): T[Type[..$tparamNames]] = ev.asInstanceOf[T[Type[..$tparamNames]]]
        """,
        q"""
          def derivingK[T[_[..$tparamsWild]]](implicit ev: T[Repr]): T[Type] =
            ev.asInstanceOf[T[Type]]
        """
      )

    }
  }

  def generateCoercibleInstances(
    tparamsNoVar: List[TypeDef], tparamNames: List[TypeName], tparamsWild: List[TypeDef]
  ): List[Tree] = {
    if (tparamsNoVar.isEmpty) List(
      q"@inline implicit def unsafeWrap: $CoercibleCls[Repr, Type] = $CoercibleObj.instance",
      q"@inline implicit def unsafeUnwrap: $CoercibleCls[Type, Repr] = $CoercibleObj.instance",
      q"@inline implicit def unsafeWrapM[M[_]]: $CoercibleCls[M[Repr], M[Type]] = $CoercibleObj.instance",
      q"@inline implicit def unsafeUnwrapM[M[_]]: $CoercibleCls[M[Type], M[Repr]] = $CoercibleObj.instance",
      // Avoid ClassCastException with Array types by prohibiting Array coercing.
      q"@inline implicit def cannotWrapArrayAmbiguous1: $CoercibleCls[Array[Repr], Array[Type]] = $CoercibleObj.instance",
      q"@inline implicit def cannotWrapArrayAmbiguous2: $CoercibleCls[Array[Repr], Array[Type]] = $CoercibleObj.instance",
      q"@inline implicit def cannotUnwrapArrayAmbiguous1: $CoercibleCls[Array[Type], Array[Repr]] = $CoercibleObj.instance",
      q"@inline implicit def cannotUnwrapArrayAmbiguous2: $CoercibleCls[Array[Type], Array[Repr]] = $CoercibleObj.instance"
    ) else List(
      q"@inline implicit def unsafeWrap[..$tparamsNoVar]: $CoercibleCls[Repr[..$tparamNames], Type[..$tparamNames]] = $CoercibleObj.instance",
      q"@inline implicit def unsafeUnwrap[..$tparamsNoVar]: $CoercibleCls[Type[..$tparamNames], Repr[..$tparamNames]] = $CoercibleObj.instance",
      q"@inline implicit def unsafeWrapM[M[_], ..$tparamsNoVar]: $CoercibleCls[M[Repr[..$tparamNames]], M[Type[..$tparamNames]]] = $CoercibleObj.instance",
      q"@inline implicit def unsafeUnwrapM[M[_], ..$tparamsNoVar]: $CoercibleCls[M[Type[..$tparamNames]], M[Repr[..$tparamNames]]] = $CoercibleObj.instance",
      q"@inline implicit def unsafeWrapK[T[_[..$tparamsNoVar]]]: $CoercibleCls[T[Repr], T[Type]] = $CoercibleObj.instance",
      q"@inline implicit def unsafeUnwrapK[T[_[..$tparamsNoVar]]]: $CoercibleCls[T[Type], T[Repr]] = $CoercibleObj.instance",
      // Avoid ClassCastException with Array types by prohibiting Array coercing.
      q"@inline implicit def cannotWrapArrayAmbiguous1[..$tparamsNoVar]: $CoercibleCls[Array[Repr[..$tparamNames]], Array[Type[..$tparamNames]]] = $CoercibleObj.instance",
      q"@inline implicit def cannotWrapArrayAmbiguous2[..$tparamsNoVar]: $CoercibleCls[Array[Repr[..$tparamNames]], Array[Type[..$tparamNames]]] = $CoercibleObj.instance",
      q"@inline implicit def cannotUnwrapArrayAmbiguous1[..$tparamsNoVar]: $CoercibleCls[Array[Type[..$tparamNames]], Array[Repr[..$tparamNames]]] = $CoercibleObj.instance",
      q"@inline implicit def cannotUnwrapArrayAmbiguous2[..$tparamsNoVar]: $CoercibleCls[Array[Type[..$tparamNames]], Array[Repr[..$tparamNames]]] = $CoercibleObj.instance"
    )
  }

  def getConstructor(body: List[Tree]): DefDef = body.collectFirst {
    case dd: DefDef if dd.name == termNames.CONSTRUCTOR => dd
  }.getOrElse(fail("Failed to locate constructor"))

  def extractConstructorValDef(ctor: DefDef): ValDef = ctor.vparamss match {
    case List(List(vd)) => vd
    case _ => fail("Unsupported constructor, must have exactly one argument")
  }

  def getInstanceMethods(clsDef: ClassDef): List[DefDef] = {
    val res = clsDef.impl.body.flatMap {
      case vd: ValDef =>
        if (vd.mods.hasFlag(Flag.CASEACCESSOR) || vd.mods.hasFlag(Flag.PARAMACCESSOR)) Nil
        else c.abort(vd.pos, "val definitions not supported, use def instead")
      case dd: DefDef =>
        if (dd.name == termNames.CONSTRUCTOR) Nil else List(dd)
      case x =>
        c.abort(x.pos, s"illegal definition in newtype: $x")
    }
    if (res.nonEmpty && !isDefinedInObject) {
      c.abort(res.head.pos, "Methods can only be defined for newtypes defined in an object")
    }
    res
  }

  def validateParents(parents: List[Tree]): Unit = {
    val ignoredExtends = List(tq"scala.Product", tq"scala.Serializable", tq"scala.AnyRef")
    val unsupported = parents.filterNot(t => ignoredExtends.exists(t.equalsStructure))
    if (unsupported.nonEmpty) {
      fail(s"newtypes do not support inheritance; illegal supertypes: ${unsupported.mkString(", ")}")
    }
  }

  // The erasure of opaque newtypes is always Object.
  def generateClassTag(
    name: TermName, valDef: ValDef, tparamsNoVar: List[TypeDef], tparamNames: List[TypeName],
    subtype: Boolean
  ): Tree = {
    def mkClassTag(t: Tree) = q"implicitly[$ClassTagCls[$t]]"
    def objClassTag = mkClassTag(tq"$ObjectCls")
    (tparamsNoVar, subtype) match {
      case (Nil, false) =>
        q"implicit def $name: $ClassTagCls[Type] = $objClassTag.asInstanceOf[$ClassTagCls[Type]]"
      case (ts, false) =>
        q"""implicit def $name[..$ts]: $ClassTagCls[Type[..$tparamNames]] =
              $objClassTag.asInstanceOf[$ClassTagCls[Type[..$tparamNames]]]"""
      case (Nil, true) =>
        q"""implicit def $name(implicit ct: $ClassTagCls[${valDef.tpt}]): $ClassTagCls[Type] =
              ct.asInstanceOf[$ClassTagCls[Type]]"""
      case (ts, true) =>
        q"""implicit def $name[..$ts](
              implicit ct: $ClassTagCls[${valDef.tpt}]
            ): $ClassTagCls[Type[..$tparamNames]] =
              ct.asInstanceOf[$ClassTagCls[Type[..$tparamNames]]]"""
    }
  }
}
