package avrohugger
package format
package specific

import avrohugger.format.abstractions.Importer
import avrohugger.matchers.TypeMatcher
import avrohugger.types._
import avrohugger.stores.SchemaStore
import org.apache.avro.Schema.Type.RECORD
import org.apache.avro.{ Protocol, Schema }
import treehugger.forest._
import definitions._
import treehuggerDSL._

import scala.jdk.CollectionConverters._

object SpecificImporter extends Importer {

  def getImports(
    schemaOrProtocol: Either[Schema, Protocol],
    currentNamespace: Option[String],
    schemaStore:      SchemaStore,
    typeMatcher:      TypeMatcher
  ): Set[Import] = {

    println("1")
    val switchAnnotSymbol = RootClass.newClass("scala.annotation.switch")
    println("2")
    val switchImport = Set(IMPORT(switchAnnotSymbol))
    println("3")
    val topLevelSchemas = getTopLevelSchemas(schemaOrProtocol, schemaStore, typeMatcher)
    println("4")
    val recordSchemas = getRecordSchemas(topLevelSchemas)
    println("5")
    val enumSchemas = getEnumSchemas(topLevelSchemas)
    println("6")
    val userDefinedDeps = getUserDefinedImports(recordSchemas ++ enumSchemas, currentNamespace, typeMatcher)
    println("7")
    val shapelessDeps = getShapelessImports(recordSchemas, typeMatcher)
    println("8")
    val libraryDeps = shapelessDeps
    println("9")

    schemaOrProtocol match {
      case Left(schema) =>
        if (schema.getType == RECORD) libraryDeps ++ userDefinedDeps ++ switchImport
        else libraryDeps ++ userDefinedDeps ++ switchImport
      case Right(protocol) =>
        val messages = protocol.getMessages.asScala.toMap
        if (messages.isEmpty) libraryDeps ++ userDefinedDeps ++ switchImport // for ADT
        else switchImport // for RPC
    }
  }

  /** Shapeless representations of Coproduct are by default only active when `union` is defined with more than two types or three types
    * where one of them is nullable. Otherwise the default values require no special imports since they are codegen in terms of [[Option]]
    * and [[Either]]
    */
  private[this] def getShapelessImports(topLevelRecordSchemas: Set[Schema], typeMatcher: TypeMatcher): Set[Import] = {

    def determineShapelessCoproductImports(
      field:               Schema.Field,
      schema:              Schema,
      typeMatcher:         TypeMatcher,
      potentialRecursives: Set[String]
    ): Set[String] =
      if (potentialRecursives.contains(schema.getFullName)) Set.empty
      else
        schema.getType match {
          case Schema.Type.UNION =>
            coproductImportsForUnionType(field, schema, typeMatcher) ++
              schema
                .getTypes()
                .asScala
                .toList
                .flatMap(s => determineShapelessCoproductImports(field, s, typeMatcher, potentialRecursives + schema.getFullName))
          case Schema.Type.ARRAY =>
            determineShapelessCoproductImports(field, schema.getElementType(), typeMatcher, potentialRecursives + schema.getFullName)
          case Schema.Type.MAP =>
            determineShapelessCoproductImports(field, schema.getValueType(), typeMatcher, potentialRecursives + schema.getFullName)
          case Schema.Type.RECORD =>
            schema
              .getFields()
              .asScala
              .toList
              .flatMap { f =>
                determineShapelessCoproductImports(field, f.schema(), typeMatcher, potentialRecursives + schema.getFullName)
              }
              .toSet
          case _ =>
            Set.empty[String]
        }

    def determineShapelessTagImport(schema: Schema, typeMatcher: TypeMatcher, potentialRecursives: Set[String]): Set[String] =
      if (potentialRecursives.contains(schema.getFullName)) Set.empty
      else
        schema.getType match {
          case Schema.Type.UNION =>
            schema
              .getTypes()
              .asScala
              .toList
              .flatMap(s => determineShapelessTagImport(s, typeMatcher, potentialRecursives + schema.getFullName))
              .toSet
          case Schema.Type.ARRAY =>
            determineShapelessTagImport(schema.getElementType(), typeMatcher, potentialRecursives + schema.getFullName)
          case Schema.Type.MAP => determineShapelessTagImport(schema.getValueType(), typeMatcher, potentialRecursives + schema.getFullName)
          case Schema.Type.RECORD =>
            schema
              .getFields()
              .asScala
              .toList
              .flatMap { f =>
                determineShapelessTagImport(f.schema, typeMatcher, potentialRecursives + schema.getFullName)
              }
              .toSet
          case Schema.Type.BYTES => importsForBigDecimalTagged(schema)
          case _                 => Set.empty[String]
        }

    def importsForBigDecimalTagged(schemas: Schema*): Set[String] =
      schemas
        .find { schema =>
          schema.getType == Schema.Type.BYTES && LogicalType.foldLogicalTypes(schema = schema, default = false) { case Decimal(_, _) =>
            typeMatcher.avroScalaTypes.decimal match {
              case ScalaBigDecimal(_)              => false
              case ScalaBigDecimalWithPrecision(_) => true
            }
          }
        }
        .map(_ => Set("tag.@@"))
        .getOrElse(Set())

    def coproductImportsForUnionType(field: Schema.Field, unionSchema: Schema, typeMatcher: TypeMatcher): Set[String] = {
      val thresholdNonNullTypes = typeMatcher.avroScalaTypes.union match {
        case OptionalShapelessCoproduct     => 0 // if a union contains at least one type, then it will need :+:
        case OptionShapelessCoproduct       => 1
        case OptionEitherShapelessCoproduct => 2 // unions of one nullable type become Option, two become Either
      }
      val unionTypes        = unionSchema.getTypes().asScala.toList
      val unionNonNullTypes = unionTypes.filterNot(_.getType == Schema.Type.NULL)
      val unionContainsNull:    Boolean = unionNonNullTypes.length < unionTypes.length
      val isShapelessCoproduct: Boolean = unionNonNullTypes.length > thresholdNonNullTypes
      val hasDefaultValue:      Boolean = !unionContainsNull
      if (isShapelessCoproduct && hasDefaultValue)
        Set(":+:", "CNil", "Coproduct")
      else if (isShapelessCoproduct)
        Set(":+:", "CNil")
      else
        Set.empty[String]
    }

    def shapelessImport(x: Set[String]): Set[Import] =
      x.headOption.map(_ => Set(IMPORT(RootClass.newClass(s"shapeless.{${x.mkString(", ")}}")))).getOrElse(Set())

    val shapelessCopSymbols: Set[String] =
      for {
        topLevelRecordSchema <- topLevelRecordSchemas
        field                <- topLevelRecordSchema.getFields().asScala
        symbol               <- determineShapelessCoproductImports(field, field.schema(), typeMatcher, Set.empty[String])
      } yield symbol
    val shapelessTag: Set[String] =
      for {
        topLevelRecordSchema <- topLevelRecordSchemas
        field                <- topLevelRecordSchema.getFields().asScala
        symbol               <- determineShapelessTagImport(field.schema(), typeMatcher, Set.empty[String])
      } yield symbol

    shapelessImport(shapelessCopSymbols) ++ shapelessImport(shapelessTag)
  }

}
