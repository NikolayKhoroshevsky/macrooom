package macrooom

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object ObjectMapper {

  def convert_impl[A: c.WeakTypeTag, B: c.WeakTypeTag](c: Context)(source: c.Expr[A]): c.Expr[B] = {
    import c.universe._
    val srcSymbol = c.weakTypeOf[A].typeSymbol
    if (!srcSymbol.isClass) c.abort(c.enclosingPosition, s"${srcSymbol.name} is not a class")

    val distSymbol = c.weakTypeOf[B].typeSymbol
    if (!distSymbol.isClass) c.abort(c.enclosingPosition, s"${distSymbol.name} is not a class")

    if (distSymbol.isJava) {
      // In the case of Java, use accessors to copy values
      val sets = getSetters[B](c)().flatMap { setter =>
        convertToGetterTermName[A](c)(setter.name.decodedName.toString) map { getterTermName =>
          q"${setter.name.toTermName}(${source}.${getterTermName})"
        }
      }
      c.Expr[B](q"""
        new ${distSymbol}() {
          ..$sets
        }
      """)
    } else {
      // In the case of Scala, use the constructor to copy values
      val params = getConstructorParams[B](c)().map { param =>
        val getterTermName = convertToGetterTermName[A](c)(param.name.decodedName.toString).getOrElse {
          c.abort(c.enclosingPosition, s"${srcSymbol.name} does not have enough method to create ${distSymbol.name}")
        }
        q"${param.name.toTermName} = ${source}.${getterTermName}"
      }
      c.Expr[B](q"""
        new ${distSymbol}(..$params)
      """)
    }
  }

  def convertTo[A, B](source: A): B = macro ObjectMapper.convert_impl[A, B]

  private def convertToGetterTermName[A: c.WeakTypeTag](c: Context)(name: String) = {
    import c.universe._
    val rawName = if (name.startsWith("set")) Character.toLowerCase(name.charAt(3)) + name.substring(4) else name
    val getterName = if (c.weakTypeOf[A].typeSymbol.isJava) "get" + rawName.capitalize else rawName

    val decls = weakTypeTag[A].tpe.decls
    // if target class does not have field or method, return None
    if (decls.exists(d => d.isPublic && d.name.decodedName.toString == getterName)) {
      Some(TermName(getterName))
    } else {
      None
    }
  }

  private def getSetters[A: c.WeakTypeTag](c: Context)() = {
    import c.universe._
    val decls = weakTypeTag[A].tpe.decls
    decls.collect {
      case m: MethodSymbol if m.name.decodedName.toString.matches("^set[A-Z].+") &&
        m.paramLists.head.length == 1 && m.isPublic => m
    }
  }

  private def getConstructorParams[A: c.WeakTypeTag](c: Context)() = {
    import c.universe._
    val decls = weakTypeTag[A].tpe.decls
    val ctor = decls.collectFirst { case m: MethodSymbol if m.isPrimaryConstructor => m }.getOrElse {
      c.abort(c.enclosingPosition, s"Not found constructor")
    }
    ctor.paramLists.head
  }
}
