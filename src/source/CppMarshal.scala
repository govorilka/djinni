package djinni

import djinni.ast._
import djinni.generatorTools._
import djinni.meta._

class CppMarshal(spec: Spec) extends Marshal(spec) {
  // The scopeSymbols parameter accepted by many of these functions describes a Seq of
  // symbols/names that are declared in the current scope. The TypeRef or MExpr expression
  // will be fully qualified if it clashes with any of these symbols, even if full qualification
  // has not been requested.

  override def typename(tm: MExpr): String = toCppType(tm, None, Seq())
  def typename(tm: MExpr, scopeSymbols: Seq[String]): String = toCppType(tm, None, scopeSymbols)
  def typename(ty: TypeRef, scopeSymbols: Seq[String]): String = typename(ty.resolved, scopeSymbols)
  def typename(name: String, ty: TypeDef): String = ty match {
    case e: Enum => idCpp.enumType(name)
    case i: Interface => idCpp.interfaceType(name)
    case r: Record => idCpp.ty(name)
    case p: PrivateInterface => p.typename
  }

  override def fqTypename(tm: MExpr): String = toCppType(tm, Some(spec.cppNamespace), Seq())
  def fqTypename(name: String, ty: TypeDef): String = ty match {
    case e: Enum => withNs(Some(spec.cppNamespace), idCpp.enumType(name))
    case i: Interface => withNs(Some(spec.cppNamespace), idCpp.interfaceType(name))
    case r: Record => withNs(Some(spec.cppNamespace), idCpp.ty(name))
    case p: PrivateInterface => p.typename
  }

  def paramType(tm: MExpr, scopeSymbols: Seq[String]): String = toCppParamType(tm, None, scopeSymbols)
  def paramType(ty: TypeRef, scopeSymbols: Seq[String]): String = paramType(ty.resolved, scopeSymbols)
  override def paramType(tm: MExpr): String = toCppParamType(tm)
  override def fqParamType(tm: MExpr): String = toCppParamType(tm, Some(spec.cppNamespace))

  def returnType(ret: Option[TypeRef], scopeSymbols: Seq[String]): String = {
    ret.fold("void")(toCppType(_, None, scopeSymbols))
  }
  override def returnType(ret: Option[TypeRef]): String = ret.fold("void")(toCppType(_, None))
  override def fqReturnType(ret: Option[TypeRef]): String = {
    ret.fold("void")(toCppType(_, Some(spec.cppNamespace)))
  }

  def fieldType(tm: MExpr, scopeSymbols: Seq[String]): String = typename(tm, scopeSymbols)
  def fieldType(ty: TypeRef, scopeSymbols: Seq[String]): String = fieldType(ty.resolved, scopeSymbols)
  override def fieldType(tm: MExpr): String = typename(tm)
  override def fqFieldType(tm: MExpr): String = fqTypename(tm)

  override def toCpp(tm: MExpr, expr: String): String = throw new AssertionError("cpp to cpp conversion")
  override def fromCpp(tm: MExpr, expr: String): String = throw new AssertionError("cpp to cpp conversion")

  def hppReferences(m: Meta, exclude: String, forwardDeclareOnly: Boolean): Seq[SymbolReference] = m match {
    case p: MPrimitive => p.idlName match {
      case "i8" | "i16" | "i32" | "i64" => List(CppIncludeRef("<cstdint>"))
      case _ => List()
    }
    case MString => List(CppIncludeRef("<string>"))
    case MDate => List(CppIncludeRef("<chrono>"))
    case MBinary => List(CppIncludeRef("<vector>"), CppIncludeRef("<cstdint>"))
    case MOptional => List(CppIncludeRef(spec.cppOptionalHeader))
    case MList => List(CppIncludeRef("<vector>"))
    case MSet => List(CppIncludeRef("<unordered_set>"))
    case MMap => List(CppIncludeRef("<unordered_map>"))
    case d: MDef => d.body match {
      case r: Record =>
        if (d.name != exclude) {
          if (forwardDeclareOnly) {
            List(DeclRef(s"struct ${typename(d.name, d.body)};", Some(spec.cppNamespace)))
          } else {
            List(CppIncludeRef(include(d.name, d.body)))
          }
        } else {
          List()
        }
      case e: Enum =>
        if (d.name != exclude) {
          if (forwardDeclareOnly) {
            val underlyingType = if(e.flags) " : unsigned" else ""
            List(DeclRef(s"enum class ${typename(d.name, d.body)}${underlyingType};", Some(spec.cppNamespace)))
          } else {
            List(CppIncludeRef(include(d.name, d.body)))
          }
        } else {
          List()
        }
      case i: Interface =>
        val base = if (d.name != exclude) {
          List(CppIncludeRef("<memory>"), DeclRef(s"class ${typename(d.name, d.body)};", Some(spec.cppNamespace)))
        } else {
          List(CppIncludeRef("<memory>"))
        }
        spec.cppNnHeader match {
          case Some(nnHdr) => CppIncludeRef(nnHdr) :: base
          case _ => base
        }
      case p: PrivateInterface =>
        List(CppIncludeRef(s"<${p.header}>"))
    }
    case e: MExtern => e.body match {
      // Do not forward declare extern types, they might be in arbitrary namespaces.
      // This isn't a problem as extern types cannot cause dependency cycles with types being generated here
      case i: Interface => List(CppIncludeRef("<memory>"), CppIncludeRef(e.cpp.header))
      case _ => List(CppIncludeRef(e.cpp.header))
    }
    case p: MParam => List()
  }

  def cppReferences(m: Meta, exclude: String, forwardDeclareOnly: Boolean): Seq[SymbolReference] = {
    // Only need to provide full definitions for cpp if forward decls were used in header
    if (!forwardDeclareOnly) {
      List()
    } else {
      m match {
        case d: MDef => d.body match {
          case r: Record =>
            if (d.name != exclude) {
              List(CppIncludeRef(include(d.name, d.body)))
            } else {
              List()
            }
          case e: Enum =>
            if (d.name != exclude) {
              List(CppIncludeRef(include(d.name, d.body)))
            } else {
              List()
            }
          case _ => List()
        }
        case _ => List()
      }
    }
  }

  def headerName(ident: String, ty: TypeDef): String = {
    val name = typename(ident, ty)
    spec.cppFileIdentStyle (name) + "." + spec.cppHeaderExt
  }

  def include(ident: String, ty: TypeDef): String = {
    ty match {
      case p: PrivateInterface => p.header
      case r: Record => 
        val prefix = if (r.ext.cpp) spec.cppExtendedRecordIncludePrefix else spec.cppIncludePrefix
        q(prefix + headerName(ident, ty))
      case _ =>
        q(spec.cppIncludePrefix + headerName(ident, ty))  
    }
  }

  private def toCppType(ty: TypeRef, namespace: Option[String] = None, scopeSymbols: Seq[String] = Seq()): String =
    toCppType(ty.resolved, namespace, scopeSymbols)

  private def toCppType(tm: MExpr, namespace: Option[String], scopeSymbols: Seq[String]): String = {
    def withNamespace(name: String): String = {
      // If an unqualified symbol needs to have its namespace added, this code assumes that the
      // namespace is the one that's defined for generated types (spec.cppNamespace).
      // This seems like a safe assumption for the C++ generator as it doesn't make much use of
      // other global names, but might need to be refined to cover other types in the future.
      val ns = namespace match {
        case Some(ns) => Some(ns)
        case None => if (scopeSymbols.contains(name)) Some(spec.cppNamespace) else None
      }
      withNs(ns, name)
    }
    def base(m: Meta): String = m match {
      case p: MPrimitive => p.cName
      case MString => if (spec.cppUseWideStrings) "std::wstring" else "std::string"
      case MDate => "std::chrono::system_clock::time_point"
      case MBinary => "std::vector<uint8_t>"
      case MOptional => spec.cppOptionalTemplate
      case MList => "std::vector"
      case MSet => "std::unordered_set"
      case MMap => "std::unordered_map"
      case d: MDef =>
        d.body match {
          case e: Enum => withNamespace(idCpp.enumType(d.name))
          case r: Record => withNamespace(idCpp.ty(d.name))
          case i: Interface => s"std::shared_ptr<${withNamespace(idCpp.interfaceType(d.name))}>"
          case p: PrivateInterface => p.typename
        }
      case e: MExtern => e.body match {
        case i: Interface => s"std::shared_ptr<${e.cpp.typename}>"
        case _ => e.cpp.typename
      }
      case p: MParam => idCpp.typeParam(p.name)
    }
    def expr(tm: MExpr): String = {
      spec.cppNnType match {
        case Some(nnType) => {
          // if we're using non-nullable pointers for interfaces, then special-case
          // both optional and non-optional interface types
          val args = if (tm.args.isEmpty) "" else tm.args.map(expr).mkString("<", ", ", ">")
          tm.base match {
            case d: MDef =>
              d.body match {
                case i: Interface => s"${nnType}<${withNamespace(idCpp.interfaceType(d.name))}>"
                case _ => base(tm.base) + args
              }
            case MOptional =>
              tm.args.head.base match {
                case d: MDef =>
                  d.body match {
                    case i: Interface => s"std::shared_ptr<${withNamespace(idCpp.interfaceType(d.name))}>"
                    case _ => base(tm.base) + args
                  }
                case _ => base(tm.base) + args
              }
            case _ => base(tm.base) + args
          }
        }
      case None =>
        if (isOptionalInterface(tm)) {
          // otherwise, interfaces are always plain old shared_ptr
          expr(tm.args.head)
        } else {
          val args = if (tm.args.isEmpty) "" else tm.args.map(expr).mkString("<", ", ", ">")
          base(tm.base) + args
        }
      }
    }
    expr(tm)
  }

  def byValue(tm: MExpr): Boolean = tm.base match {
    case p: MPrimitive => true
    case d: MDef => byValue(d.body)
    case e: MExtern => e.body match {
      case i: Interface => false
      case e: Enum => true
      case r: Record => e.cpp.byValue
      case p: PrivateInterface => false
    }
    case MOptional => byValue(tm.args.head)
    case _ => false
  }

  def byValue(ty: TypeDef): Boolean = ty match {
    case i: Interface => false
    case r: Record => false
    case e: Enum => true
    case p: PrivateInterface => false 
  }

  // this can be used in c++ generation to know whether a const& should be applied to the parameter or not
  private def toCppParamType(tm: MExpr, namespace: Option[String] = None, scopeSymbols: Seq[String] = Seq()): String = {
    val cppType = toCppType(tm, namespace, scopeSymbols)
    val refType = "const " + cppType + " &"
    val valueType = cppType
    if(byValue(tm)) valueType else refType
  }
}
