/**
  * Copyright 2014 Dropbox, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package djinni

import java.io.File

import djinni.ast.Record.RecordDeriving
import djinni.ast._
import djinni.generatorTools._
import djinni.meta._
import djinni.syntax.Error
import djinni.writer.IndentWriter

import scala.collection.mutable
import scala.collection.parallel.immutable

class ObjcGenerator(spec: Spec) extends BaseObjcGenerator(spec) {

  class ObjcRefs() {
    var body = mutable.TreeSet[String]()
    var header = mutable.TreeSet[String]()

    def find(ty: TypeRef) { find(ty.resolved) }
    def find(tm: MExpr) {
      tm.args.foreach(find)
      find(tm.base)
    }
    def find(m: Meta) = for(r <- marshal.references(m)) r match {
      case ImportRef(arg) => header.add("#import " + arg)
      case DeclRef(decl, _) => header.add(decl)
      case _ => throw new AssertionError("Unreachable")
    }
  }

  override def generateEnum(origin: String, ident: Ident, doc: Doc, e: Enum) {
    val refs = new ObjcRefs()

    refs.header.add("#import <Foundation/Foundation.h>")

    val self = marshal.typename(ident, e)
    writeHeaderFile(marshal.headerName(ident), origin, refs.header, w => {
      writeDoc(w, doc)
      w.wl(if(e.flags) {
        s"typedef NS_OPTIONS(NSUInteger, $self)"
      } else {
        if (spec.objcClosedEnums) {
          s"typedef NS_CLOSED_ENUM(NSInteger, $self)"
        } else {
          s"typedef NS_ENUM(NSInteger, $self)"
        }
      })
      w.bracedSemi {
        writeEnumOptionNone(w, e, self + idObjc.enum(_))
        writeEnumOptions(w, e, self + idObjc.enum(_))
        writeEnumOptionAll(w, e, self + idObjc.enum(_))
      }
    })
  }

  def bodyName(ident: String): String = idObjc.ty(ident) + "." + spec.objcppExt // Must be a Obj-C++ file in case the constants are not compile-time constant expressions

  def writeObjcConstMethDecl(c: Const, w: IndentWriter) {
    val label = "+"
    val nullability = marshal.nullability(c.ty.resolved).fold("")(" __" + _)
    val ret = marshal.fqFieldType(c.ty) + nullability
    val decl = s"$label ($ret)${idObjc.method(c.ident)}"
    writeAlignedObjcCall(w, decl, List(), ";", p => ("",""))
  }

  /**
    * Generate Interface
    */
  override def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface) {
    val refs = new ObjcRefs()
    i.methods.map(m => {
      if (!isCppOnly(m)) {
        m.params.map(p => refs.find(p.ty))
        m.ret.foreach(refs.find)
      }
    })
    i.consts.map(c => {
      refs.find(c.ty)
    })

    val self = marshal.typename(ident, i)

    refs.header.add("#import <Foundation/Foundation.h>")

    def writeObjcFuncDecl(method: Interface.Method, w: IndentWriter) {
      val label = if (method.static) "+" else "-"
      val ret = marshal.returnType(method.ret)
      val decl = s"$label ($ret)${idObjc.method(method.ident)}"
      writeAlignedObjcCall(w, decl, method.params, "", p => (idObjc.field(p.ident), s"(${marshal.paramType(p.ty)})${idObjc.local(p.ident)}"))
    }

    // Generate the header file for Interface
    writeHeaderFile(marshal.headerName(ident), origin, refs.header, w => {
      for (c <- i.consts if marshal.canBeConstVariable(c)) {
        writeDoc(w, c.doc)
        w.w(s"extern ")
        writeObjcConstVariableDecl(w, c, self)
        w.wl(s";")
      }
      w.wl
      writeDoc(w, doc)
      if (i.ext.objc) w.wl(s"@protocol $self") else w.wl(s"@interface $self : NSObject")
      for (m <- i.methods) {
        if (!isCppOnly(m)) {
          w.wl
          writeMethodDoc(w, m, idObjc.local)
          writeObjcFuncDecl(m, w)
          w.wl(";")
        }
      }
      for (c <- i.consts if !marshal.canBeConstVariable(c)) {
        w.wl
        writeDoc(w, c.doc)
        writeObjcConstMethDecl(c, w)
      }
      w.wl
      w.wl("@end")
    })

    // Generate the implementation file for Interface
    if (i.consts.nonEmpty) {
      refs.body.add("#import " + q(spec.objcIncludePrefix + marshal.headerName(ident)))
      writeSourceFile(bodyName(ident.name), origin, refs.body, w => {
        generateObjcConstants(w, i.consts, self, ObjcConstantType.ConstVariable)
      })
      // For constants implemented via Methods, we generate their definitions in the
      // corresponding ObjcCpp file (i.e.: `ClassName`+Private.mm)
    }
  }

  override def generateRecord(origin: String, ident: Ident, doc: Doc, params: Seq[TypeParam], r: Record) {
    val refs = new ObjcRefs()
    for (c <- r.consts)
      refs.find(c.ty)
    for (f <- r.fields)
      refs.find(f.ty)

    val objcName = ident.name + (if (r.ext.objc) "_base" else "")
    val noBaseSelf = marshal.typename(ident, r) // Used for constant names
    val self = marshal.typename(objcName, r)

    refs.header.add("#import <Foundation/Foundation.h>")
    refs.body.add("!#import " + q((if (r.ext.objc) spec.objcExtendedRecordIncludePrefix else spec.objcIncludePrefix) + marshal.headerName(ident)))

    if (r.ext.objc) {
      refs.header.add(s"@class $noBaseSelf;")
    }

    def checkMutable(tm: MExpr): Boolean = tm.base match {
      case MOptional => checkMutable(tm.args.head)
      case MString => true
      case MList => true
      case MSet => true
      case MMap => true
      case MBinary => true
      case _ => false
    }

    val firstInitializerArg = if(r.fields.isEmpty) "" else IdentStyle.camelUpper("with_" + r.fields.head.ident.name)

    // Generate the header file for record
    writeHeaderFile(marshal.headerName(objcName), origin, refs.header, w => {
      writeDoc(w, doc)
      w.wl(s"@interface $self : NSObject")

      def writeInitializer(sign: String, prefix: String) {
        val decl = s"$sign (nonnull instancetype)$prefix$firstInitializerArg"
        writeAlignedObjcCall(w, decl, r.fields, "", f => (idObjc.field(f.ident), s"(${marshal.paramType(f.ty)})${idObjc.local(f.ident)}"))
        w.wl(";")
      }

      writeInitializer("-", "init")
      if (!r.ext.objc) writeInitializer("+", IdentStyle.camelLower(objcName))

      for (c <- r.consts if !marshal.canBeConstVariable(c)) {
        w.wl
        writeDoc(w, c.doc)
        writeObjcConstMethDecl(c, w)
      }

      for (f <- r.fields) {
        w.wl
        writeDoc(w, f.doc)
        val nullability = marshal.nullability(f.ty.resolved).fold("")(", " + _)
        w.wl(s"@property (nonatomic, readonly${nullability}) ${marshal.fqFieldType(f.ty)} ${idObjc.field(f.ident)};")
      }
      if (r.derivingTypes.contains(RecordDeriving.Ord)) {
        w.wl
        w.wl(s"- (NSComparisonResult)compare:(nonnull $self *)other;")
      }
      w.wl
      w.wl("@end")
      // Constants come last in case one of them is of the record's type
      if (r.consts.nonEmpty) {
        w.wl
        for (c <- r.consts if marshal.canBeConstVariable(c)) {
          writeDoc(w, c.doc)
          w.w(s"extern ")
          writeObjcConstVariableDecl(w, c, noBaseSelf);
          w.wl(s";")
        }
      }
    })

    // Generate the implementation file for record
    writeSourceFile(bodyName(objcName), origin, refs.body, w => {
      if (r.consts.nonEmpty) generateObjcConstants(w, r.consts, noBaseSelf, ObjcConstantType.ConstVariable)

      w.wl
      w.wl(s"@implementation $self")
      w.wl
      // Constructor from all fields (not copying)
      val init = s"- (nonnull instancetype)init$firstInitializerArg"
      writeAlignedObjcCall(w, init, r.fields, "", f => (idObjc.field(f.ident), s"(${marshal.paramType(f.ty)})${idObjc.local(f.ident)}"))
      w.wl
      w.braced {
        w.w("if (self = [super init])").braced {
          for (f <- r.fields) {
            if (checkMutable(f.ty.resolved))
              w.wl(s"_${idObjc.field(f.ident)} = [${idObjc.local(f.ident)} copy];")
            else
              w.wl(s"_${idObjc.field(f.ident)} = ${idObjc.local(f.ident)};")
          }
        }
        w.wl("return self;")
      }
      w.wl

      // Convenience initializer
      if(!r.ext.objc) {
        val decl = s"+ (nonnull instancetype)${IdentStyle.camelLower(objcName)}$firstInitializerArg"
        writeAlignedObjcCall(w, decl, r.fields, "", f => (idObjc.field(f.ident), s"(${marshal.paramType(f.ty)})${idObjc.local(f.ident)}"))
        w.wl
        w.braced {
          val call = s"return [($self*)[self alloc] init$firstInitializerArg"
          writeAlignedObjcCall(w, call, r.fields, "", f => (idObjc.field(f.ident), s"${idObjc.local(f.ident)}"))
          w.wl("];")
        }
        w.wl
      }

      if (r.consts.nonEmpty) generateObjcConstants(w, r.consts, noBaseSelf, ObjcConstantType.ConstMethod)

      if (r.derivingTypes.contains(RecordDeriving.Eq)) {
        w.wl("- (BOOL)isEqual:(id)other")
        w.braced {
          w.w(s"if (![other isKindOfClass:[$self class]])").braced {
            w.wl("return NO;")
          }
          w.wl(s"$self *typedOther = ($self *)other;")
          val skipFirst = SkipFirst()
          w.w(s"return ").nestedN(2) {
            for (f <- r.fields) {
              skipFirst { w.wl(" &&") }
              f.ty.resolved.base match {
                case MBinary => w.w(s"[self.${idObjc.field(f.ident)} isEqualToData:typedOther.${idObjc.field(f.ident)}]")
                case MList => w.w(s"[self.${idObjc.field(f.ident)} isEqualToArray:typedOther.${idObjc.field(f.ident)}]")
                case MSet => w.w(s"[self.${idObjc.field(f.ident)} isEqualToSet:typedOther.${idObjc.field(f.ident)}]")
                case MMap => w.w(s"[self.${idObjc.field(f.ident)} isEqualToDictionary:typedOther.${idObjc.field(f.ident)}]")
                case MOptional =>
                  f.ty.resolved.args.head.base match {
                    case df: MDef if df.body == Enum =>
                      w.w(s"self.${idObjc.field(f.ident)} == typedOther.${idObjc.field(f.ident)}")
                    case _ =>
                      w.w(s"((self.${idObjc.field(f.ident)} == nil && typedOther.${idObjc.field(f.ident)} == nil) || ")
                      w.w(s"(self.${idObjc.field(f.ident)} != nil && [self.${idObjc.field(f.ident)} isEqual:typedOther.${idObjc.field(f.ident)}]))")
                  }
                case MString => w.w(s"[self.${idObjc.field(f.ident)} isEqualToString:typedOther.${idObjc.field(f.ident)}]")
                case MDate => w.w(s"[self.${idObjc.field(f.ident)} isEqualToDate:typedOther.${idObjc.field(f.ident)}]")
                case t: MPrimitive => w.w(s"self.${idObjc.field(f.ident)} == typedOther.${idObjc.field(f.ident)}")
                case df: MDef => df.body match {
                  case r: Record => w.w(s"[self.${idObjc.field(f.ident)} isEqual:typedOther.${idObjc.field(f.ident)}]")
                  case e: Enum => w.w(s"self.${idObjc.field(f.ident)} == typedOther.${idObjc.field(f.ident)}")
                  case _ => throw new AssertionError("Unreachable")
                }
                case e: MExtern => e.body match {
                  case r: Record => if(e.objc.pointer) {
                      w.w(s"[self.${idObjc.field(f.ident)} isEqual:typedOther.${idObjc.field(f.ident)}]")
                    } else {
                      w.w(s"self.${idObjc.field(f.ident)} == typedOther.${idObjc.field(f.ident)}")
                    }
                  case e: Enum => w.w(s"self.${idObjc.field(f.ident)} == typedOther.${idObjc.field(f.ident)}")
                  case _ => throw new AssertionError("Unreachable")
                }
                case _ => throw new AssertionError("Unreachable")
              }
            }
          }
          w.wl(";")
        }
        w.wl

        w.wl("- (NSUInteger)hash")
        w.braced {
          w.w(s"return ").nestedN(2) {
            w.w(s"NSStringFromClass([self class]).hash")
            for (f <- r.fields) {
              w.wl(" ^")
              f.ty.resolved.base match {
                case MOptional =>
                  f.ty.resolved.args.head.base match {
                    case df: MDef if df.body == Enum =>
                      w.w(s"(NSUInteger)self.${idObjc.field(f.ident)}")
                    case _ => w.w(s"self.${idObjc.field(f.ident)}.hash")
                  }
                case t: MPrimitive => w.w(s"(NSUInteger)self.${idObjc.field(f.ident)}")
                case df: MDef => df.body match {
                  case e: Enum => w.w(s"(NSUInteger)self.${idObjc.field(f.ident)}")
                  case _ => w.w(s"self.${idObjc.field(f.ident)}.hash")
                }
                case e: MExtern => e.body match {
                  case e: Enum => w.w(s"(NSUInteger)self.${idObjc.field(f.ident)}")
                  case r: Record => w.w("(" + e.objc.hash.format("self." + idObjc.field(f.ident)) + ")")
                  case _ => throw new AssertionError("Unreachable")
                }
                case _ => w.w(s"self.${idObjc.field(f.ident)}.hash")
              }
            }
          }
          w.wl(";")
        }
        w.wl
      }

      def generatePrimitiveOrder(ident: Ident, w: IndentWriter): Unit = {
        w.wl(s"if (self.${idObjc.field(ident)} < other.${idObjc.field(ident)}) {").nested {
          w.wl(s"tempResult = NSOrderedAscending;")
        }
        w.wl(s"} else if (self.${idObjc.field(ident)} > other.${idObjc.field(ident)}) {").nested {
          w.wl(s"tempResult = NSOrderedDescending;")
        }
        w.wl(s"} else {").nested {
          w.wl(s"tempResult = NSOrderedSame;")
        }
        w.wl("}")
      }
      if (r.derivingTypes.contains(RecordDeriving.Ord)) {
        w.wl(s"- (NSComparisonResult)compare:($self *)other")
        w.braced {
          w.wl("NSComparisonResult tempResult;")
          for (f <- r.fields) {
            f.ty.resolved.base match {
              case MString | MDate => w.wl(s"tempResult = [self.${idObjc.field(f.ident)} compare:other.${idObjc.field(f.ident)}];")
              case t: MPrimitive => generatePrimitiveOrder(f.ident, w)
              case df: MDef => df.body match {
                case r: Record => w.wl(s"tempResult = [self.${idObjc.field(f.ident)} compare:other.${idObjc.field(f.ident)}];")
                case e: Enum => generatePrimitiveOrder(f.ident, w)
                case _ => throw new AssertionError("Unreachable")
              }
              case e: MExtern => e.body match {
                case r: Record => if(e.objc.pointer) w.wl(s"tempResult = [self.${idObjc.field(f.ident)} compare:other.${idObjc.field(f.ident)}];") else generatePrimitiveOrder(f.ident, w)
                case e: Enum => generatePrimitiveOrder(f.ident, w)
                case _ => throw new AssertionError("Unreachable")
              }
              case _ => throw new AssertionError("Unreachable")
            }
            w.w("if (tempResult != NSOrderedSame)").braced {
              w.wl("return tempResult;")
            }
          }
          w.wl("return NSOrderedSame;")
        }
        w.wl
      }

      w.wl("- (NSString *)description")
      w.braced {
        w.w(s"return ").nestedN(2) {
          w.w("[NSString stringWithFormat:@\"<%@ %p")

          for (f <- r.fields) w.w(s" ${idObjc.field(f.ident)}:%@")
          w.w(">\", self.class, (void *)self")

          for (f <- r.fields) {
            w.w(", ")
            f.ty.resolved.base match {
              case MOptional => w.w(s"self.${idObjc.field(f.ident)}")
              case t: MPrimitive => w.w(s"@(self.${idObjc.field(f.ident)})")
              case df: MDef => df.body match {
                case e: Enum => w.w(s"@(self.${idObjc.field(f.ident)})")
                case _ => w.w(s"self.${idObjc.field(f.ident)}")
              }
              case e: MExtern =>
                if (e.objc.pointer) {
                  w.w(s"self.${idObjc.field(f.ident)}")
                } else {
                  w.w(s"@(self.${idObjc.field(f.ident)})")
                }
              case _ => w.w(s"self.${idObjc.field(f.ident)}")
            }
          }
        }
        w.wl("];")
      }
      w.wl

      w.wl("@end")
    })
  }

  def writeHeaderFile(fileName: String, origin: String, refs: Iterable[String], f: IndentWriter => Unit) =
    writeObjCFile(spec.objcHeaderOutFolder, fileName, origin, refs, f)

  def writeSourceFile(fileName: String, origin: String, refs: Iterable[String], f: IndentWriter => Unit) =
    writeObjCFile(spec.objcOutFolder, fileName, origin, refs, f)

  def writeObjCFile(folder: Option[File], fileName: String, origin: String, refs: Iterable[String], f: IndentWriter => Unit) {
    createFile(folder.get, fileName, (w: IndentWriter) => {
      w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
      w.wl("// This file generated by Djinni from " + origin)
      w.wl
      if (refs.nonEmpty) {
        // Ignore the ! in front of each line; used to put own headers to the top
        // according to Objective-C style guide
        refs.foreach(s => w.wl(if (s.charAt(0) == '!') s.substring(1) else s))
        w.wl
      }
      f(w)
    })
  }

}
