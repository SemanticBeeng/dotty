package dotty.tools.dottydoc
package model

import comment._
import dotty.tools.dotc
import dotc.core.Types._
import dotc.core.Contexts.Context
import dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.SymDenotations._
import dotty.tools.dotc.core.Names.TypeName
import dotc.core.{ Flags => DottyFlags }
import dotc.ast.Trees._


object factories {
  import dotty.tools.dotc.ast.tpd._
  import DottyFlags._

  type TypeTree = dotty.tools.dotc.ast.Trees.Tree[Type]

  def flags(t: Tree)(implicit ctx: Context): List[String] =
    (t.symbol.flags & SourceModifierFlags)
      .flagStrings.toList
      .filter(_ != "<trait>")
      .filter(_ != "interface")

  private def pathList(tpe: Type): List[String] = tpe match {
    case t: ThisType =>
      pathList(t.tref)
    case t: NamedType if t.prefix == NoPrefix  && t.name.toString == "<root>" =>
      Nil
    case t: NamedType if t.prefix == NoPrefix =>
      t.name.toString :: Nil
    case t: NamedType =>
      pathList(t.prefix) :+ t.name.toString
  }

  def path(t: Tree)(implicit ctx: Context): List[String] = {
    val ref =
      if (t.symbol.isTerm) t.symbol.termRef
      else t.symbol.typeRef

    pathList(ref)
  }

  private val product = """Product[1-9][0-9]*""".r

  private def cleanTitle(title: String): String = title match {
    // matches Entity.this.Something
    case x if x matches "[^\\[]+\\.this\\..+" => x.split("\\.").last
    // Matches Entity[P, ...]
    case x if x matches "[^\\[]+\\[[^\\]]+\\]" =>
      val Array(tpe, params) = x.dropRight(1).split("\\[")
      s"""$tpe[${params.split(",").map(x => cleanTitle(x.trim)).mkString(", ")}]"""
    case _ => title
  }

  private def cleanQuery(query: String): String = query match {
    case x if x matches "[^\\[]+\\[[^\\]]+\\]" => x.takeWhile(_ != '[')
    case _ => query
  }

  def returnType(t: TypeTree)(implicit ctx: Context): Reference = {
    def typeRef(name: String, params: List[MaterializableLink]) =
      TypeReference(name, UnsetLink(Text(name), name), params)

    def expandTpe(t: Type, params: List[MaterializableLink] = Nil): Reference = t match {
      case ref @ RefinedType(parent, rn) => {
        val paramName = ref.refinedInfo match {
          case ta: TypeAlias if ta.alias.isInstanceOf[NamedType] =>
            ta.alias.asInstanceOf[NamedType].name.decode.toString
          case _ =>
            rn.decode.toString.split("\\$").last
        }
        val param = UnsetLink(Text(paramName), paramName)
        expandTpe(parent, param :: params)
      }
      case TypeRef(_, name) =>
        typeRef(name.decode.toString, params)
      case OrType(left, right) =>
        OrTypeReference(expandTpe(left), expandTpe(right))
      case AndType(left, right) =>
        AndTypeReference(expandTpe(left), expandTpe(right))
      case AnnotatedType(tpe, _) =>
        expandTpe(tpe)
      case ExprType(tpe) =>
        expandTpe(tpe)
      case c: ConstantType =>
        ConstantReference(c.show)
      case tt: ThisType =>
        expandTpe(tt.underlying)
    }

    expandTpe(t.tpe)
  }

  def typeParams(t: Tree)(implicit ctx: Context): List[String] = t match {
    case t: DefDef =>
      def variance(s: Symbol) =
        if (s is Covariant) "+"
        else if (s is Contravariant) "-"
        else ""
      t.tparams.map(p => variance(p.symbol) + p.show)
    case t: TypeDef if t.rhs.isInstanceOf[Template] =>
      // Get the names from the constructor method `DefDef`
      typeParams(t.rhs.asInstanceOf[Template].constr)
  }

  def paramLists(t: DefDef)(implicit ctx: Context): List[List[NamedReference]] = {
    def getParams(xs: List[ValDef]): List[NamedReference] =
      xs.map(vd => NamedReference(vd.name.decode.toString, returnType(vd.tpt)))

    t.vparamss.map(getParams)
  }

  def superTypes(t: Tree)(implicit ctx: Context): List[MaterializableLink] = t.symbol.denot match {
    case cd: ClassDenotation =>
      def isJavaLangObject(prefix: Type): Boolean =
        prefix match {
          case TypeRef(ThisType(TypeRef(NoPrefix, outerName)), innerName) =>
            outerName.toString == "lang" && innerName.toString == "Object"
          case _ => false
        }

      def isProductWithArity(prefix: Type): Boolean = prefix match {
        case TypeRef(TermRef(TermRef(NoPrefix, root), scala), prod) =>
          root.toString == "_root_" &&
          scala.toString == "scala" &&
          product.findFirstIn(prod.toString).isDefined
        case _ => false
      }

      cd.classParents.collect {
        case t: TypeRef if !isJavaLangObject(t) && !isProductWithArity(t) =>
          UnsetLink(Text(t.name.toString), pathList(t).mkString("."))
      }
    case _ => Nil
  }
}
