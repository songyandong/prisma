package com.prisma.api.schema

import com.prisma.api.connector.Types.DataItemFilterCollection
import com.prisma.api.connector._
import com.prisma.api.mutations.BatchPayload
import com.prisma.api.resolver.DeferredTypes._
import com.prisma.api.resolver.{IdBasedConnection, IdBasedConnectionDefinition}
import com.prisma.api.schema.CustomScalarTypes.{DateTimeType, JsonType}
import com.prisma.gc_values._
import com.prisma.shared.models
import com.prisma.shared.models.{Field => _, _}
import com.prisma.util.coolArgs.GCAnyConverter
import sangria.schema.{Field => SangriaField, _}

import scala.concurrent.ExecutionContext

class ObjectTypeBuilder(
    project: models.Project,
    nodeInterface: Option[InterfaceType[ApiUserContext, PrismaNode]] = None,
    withRelations: Boolean = true,
    onlyId: Boolean = false
)(implicit ec: ExecutionContext) {
  val batchPayloadType: ObjectType[ApiUserContext, BatchPayload] = ObjectType(
    name = "BatchPayload",
    description = "",
    fieldsFn = () => {
      List(
        SangriaField(
          "count",
          fieldType = LongType,
          description = Some("The number of nodes that have been affected by the Batch operation."),
          resolve = (ctx: Context[ApiUserContext, BatchPayload]) => { ctx.value.count }
        )
      )
    }
  )

  val modelObjectTypes: Map[String, ObjectType[ApiUserContext, PrismaNode]] = project.models.map(model => (model.name, modelToObjectType(model))).toMap

  val modelConnectionTypes: Map[String, ObjectType[ApiUserContext, IdBasedConnection[PrismaNode]]] =
    project.models.map(model => (model.name, modelToConnectionType(model).connectionType)).toMap

  def modelToConnectionType(model: Model): IdBasedConnectionDefinition[ApiUserContext, IdBasedConnection[PrismaNode], PrismaNode] = {
    IdBasedConnection.definition[ApiUserContext, IdBasedConnection, PrismaNode](
      name = model.name,
      nodeType = modelObjectTypes(model.name),
      connectionFields = {

        List(
          SangriaField(
            "aggregate",
            aggregateTypeForModel(model),
            resolve = (ctx: Context[ApiUserContext, IdBasedConnection[PrismaNode]]) => ctx.value.parent.args.getOrElse(QueryArguments.empty)
          )
        )
      }
    )
  }

  def aggregateTypeForModel(model: models.Model): ObjectType[ApiUserContext, QueryArguments] = {
    ObjectType(
      name = s"Aggregate${model.name}",
      fields = List(
        SangriaField(
          "count",
          IntType,
          resolve = (ctx: Context[ApiUserContext, QueryArguments]) => CountManyModelDeferred(model, Some(ctx.value))
        )
      )
    )
  }

  protected def modelToObjectType(model: models.Model): ObjectType[ApiUserContext, PrismaNode] = {
    new ObjectType(
      name = model.name,
      description = None,
      fieldsFn = () => {
        model.fields
          .filter(_.isVisible)
          .filter(field =>
            field.isScalar match {
              case true  => true
              case false => withRelations
          })
          .map(mapClientField(model))
      },
      interfaces = {
        val idFieldHasRightType = model.idField.exists(f => f.typeIdentifier == TypeIdentifier.String || f.typeIdentifier == TypeIdentifier.GraphQLID)
        if (model.hasVisibleIdField && idFieldHasRightType) {
          nodeInterface.toList
        } else {
          List.empty
        }
      },
      instanceCheck = (value: Any, valClass: Class[_], tpe: ObjectType[ApiUserContext, _]) =>
        value match {
          case PrismaNode(_, _, Some(tpe.name)) => true
          case PrismaNode(_, _, Some(_))        => false
          case _                                => valClass.isAssignableFrom(value.getClass)
      },
      astDirectives = Vector.empty
    )
  }

  def mapClientField(model: models.Model)(field: models.Field): SangriaField[ApiUserContext, PrismaNode] = SangriaField(
    field.name,
    fieldType = mapToOutputType(Some(model), field),
    arguments = mapToListConnectionArguments(model, field),
    resolve = (ctx: Context[ApiUserContext, PrismaNode]) => mapToOutputResolve(model, field)(ctx),
    tags = List()
  )

  def mapToOutputType(model: Option[models.Model], field: models.Field): OutputType[Any] = {
    val outputType: OutputType[Any] = field match {
      case f: RelationField => resolveConnection(f)
      case f: ScalarField =>
        f.typeIdentifier match {
          case TypeIdentifier.String    => StringType
          case TypeIdentifier.Int       => IntType
          case TypeIdentifier.Float     => FloatType
          case TypeIdentifier.Boolean   => BooleanType
          case TypeIdentifier.GraphQLID => IDType
          case TypeIdentifier.DateTime  => DateTimeType
          case TypeIdentifier.Json      => JsonType
          case TypeIdentifier.Enum      => SchemaBuilderUtils.mapEnumFieldToInputType(field)
        }
    }

    if (field.isScalar && field.isList) {
      ListType(outputType)
    } else if (!field.isRequired) {
      OptionType(outputType)
    } else {
      outputType
    }
  }

  def resolveConnection(field: RelationField): OutputType[Any] = {
    field.isList match {
      case true  => ListType(modelObjectTypes(field.relatedModel_!.name))
      case false => modelObjectTypes(field.relatedModel_!.name)
    }
  }

  def mapToListConnectionArguments(model: models.Model, field: models.Field): List[Argument[Option[Any]]] = {
    field match {
      case f if f.isHidden               => List.empty
      case _: ScalarField                => List.empty
      case f: RelationField if f.isList  => mapToListConnectionArguments(f.relatedModel_!)
      case f: RelationField if !f.isList => mapToSingleConnectionArguments(f.relatedModel_!)
    }
  }

  def mapToListConnectionArguments(model: Model): List[Argument[Option[Any]]] = {
    import SangriaQueryArguments._
    val skipArgument = Argument("skip", OptionInputType(IntType))

    List(
      whereArgument(model, project),
      orderByArgument(model).asInstanceOf[Argument[Option[Any]]],
      skipArgument.asInstanceOf[Argument[Option[Any]]],
      IdBasedConnection.Args.After.asInstanceOf[Argument[Option[Any]]],
      IdBasedConnection.Args.Before.asInstanceOf[Argument[Option[Any]]],
      IdBasedConnection.Args.First.asInstanceOf[Argument[Option[Any]]],
      IdBasedConnection.Args.Last.asInstanceOf[Argument[Option[Any]]]
    )
  }

  def mapToUniqueArguments(model: models.Model): List[Argument[_]] = {
    import com.prisma.util.coolSangria.FromInputImplicit.DefaultScalaResultMarshaller

    model.scalarNonListFields
      .filter(_.isUnique)
      .map(field => Argument(field.name, SchemaBuilderUtils.mapToOptionalInputType(field)))
  }

  def mapToSingleConnectionArguments(model: Model): List[Argument[Option[Any]]] = {
    import SangriaQueryArguments._

    List(whereArgument(model, project))
  }

  def generateFilterElement(input: Map[String, Any], model: Model, isSubscriptionFilter: Boolean = false): DataItemFilterCollection = {
    val filterArguments = new FilterArguments(model, isSubscriptionFilter)

    input
      .map {
        case (key, value) =>
          val FieldFilterTuple(field, filter) = filterArguments.lookup(key)
          lazy val asRelationField            = field.get.asInstanceOf[RelationField]
          lazy val asScalarField              = field.get.asInstanceOf[ScalarField]
          value match {
            case value: Map[_, _] =>
              val typedValue = value.asInstanceOf[Map[String, Any]]
              if (List("AND", "OR", "NOT").contains(key) || (isSubscriptionFilter && key == "node")) {
                generateFilterElement(typedValue, model, isSubscriptionFilter)
              } else {
                // this must be a relation filter
                TransitiveRelationFilter(
                  project.schema,
                  asRelationField,
                  model,
                  asRelationField.relatedModel_!,
                  asRelationField.relation,
                  filter.name,
                  generateFilterElement(typedValue, asRelationField.relatedModel_!, isSubscriptionFilter)
                )
              }

            case value: Seq[Any] if value.nonEmpty && value.head.isInstanceOf[Map[_, _]] =>
              FilterElement(key, value.asInstanceOf[Seq[Map[String, Any]]].map(x => generateFilterElement(x, model, isSubscriptionFilter)), None, filter.name)

            //-------- non recursive

            case value: Seq[Any] if field.isDefined && field.get.isScalar =>
              val converter = GCAnyConverter(field.get.typeIdentifier, isList = false)
              FinalValueFilter(key, ListGCValue(value.map(x => converter.toGCValue(x).get).toVector), asScalarField, filter.name)

            case Some(filterValue) if field.isDefined && field.get.isScalar =>
              val converter = GCAnyConverter(field.get.typeIdentifier, isList = false)
              FinalValueFilter(key, converter.toGCValue(filterValue).get, asScalarField, filter.name)

            case Some(filterValue) if field.isDefined && field.get.isRelation =>
              FinalRelationFilter(project.schema, key, filterValue, field.get.asInstanceOf[RelationField], filter.name)

            case valueNew if field.isDefined && field.get.isRelation =>
              FinalRelationFilter(project.schema, key, valueNew, field.get.asInstanceOf[RelationField], filter.name)

            case valueNew if field.isDefined && field.get.isScalar =>
              val converter = GCAnyConverter(field.get.typeIdentifier, isList = false)
              FinalValueFilter(key, converter.toGCValue(valueNew).get, asScalarField, filter.name)

            case _ =>
              FilterElement(key, value, field, filter.name)
          }
      }
      .toList
      .asInstanceOf[DataItemFilterCollection]
  }

  def extractQueryArgumentsFromContext(model: Model, ctx: Context[ApiUserContext, Unit]): Option[QueryArguments] = {
    extractQueryArgumentsFromContext(model, ctx, isSubscriptionFilter = false)
  }

  def extractQueryArgumentsFromContextForSubscription(model: Model, ctx: Context[_, Unit]): Option[QueryArguments] = {
    extractQueryArgumentsFromContext(model, ctx, isSubscriptionFilter = true)
  }

  private def extractQueryArgumentsFromContext(model: Model, ctx: Context[_, Unit], isSubscriptionFilter: Boolean): Option[QueryArguments] = {
    val rawFilterOpt: Option[Map[String, Any]] = ctx.argOpt[Map[String, Any]]("where")
    val filterOpt                              = rawFilterOpt.map(generateFilterElement(_, model, isSubscriptionFilter))
    val skipOpt                                = ctx.argOpt[Int]("skip")
    val orderByOpt                             = ctx.argOpt[OrderBy]("orderBy")
    val afterOpt                               = ctx.argOpt[String](IdBasedConnection.Args.After.name)
    val beforeOpt                              = ctx.argOpt[String](IdBasedConnection.Args.Before.name)
    val firstOpt                               = ctx.argOpt[Int](IdBasedConnection.Args.First.name)
    val lastOpt                                = ctx.argOpt[Int](IdBasedConnection.Args.Last.name)

    Some(SangriaQueryArguments.createSimpleQueryArguments(skipOpt, afterOpt, firstOpt, beforeOpt, lastOpt, filterOpt, orderByOpt))
  }

  def mapToOutputResolve[C <: ApiUserContext](model: models.Model, field: models.Field)(
      ctx: Context[C, PrismaNode]): sangria.schema.Action[ApiUserContext, _] = {

    val item: PrismaNode = unwrapDataItemFromContext(ctx)

    field match {
      case f: ScalarField if f.isList =>
        ScalarListDeferred(model, f, item.id)

      case f: ScalarField if !f.isList =>
        GCValueExtractor.fromGCValue(item.data.map(field.name))

      case f: RelationField if f.isList =>
        val arguments = extractQueryArgumentsFromContext(f.relatedModel_!, ctx.asInstanceOf[Context[ApiUserContext, Unit]])
        DeferredValue(ToManyDeferred(f, item.id, arguments)).map(_.toNodes)

      case f: RelationField if !f.isList =>
        val arguments = extractQueryArgumentsFromContext(f.relatedModel_!, ctx.asInstanceOf[Context[ApiUserContext, Unit]])
        ToOneDeferred(f, item.id, arguments)
    }
  }

  def unwrapDataItemFromContext[C <: ApiUserContext](ctx: Context[C, PrismaNode]) = {
    // note: ctx.value is sometimes of type Some[DataItem] at runtime even though the type is DataItem
    //metacounts of relations being required or not is one cause see RequiredRelationMetaQueriesSpec
    // todo: figure out why and fix issue at source
    ctx.value.asInstanceOf[Any] match {
      case Some(x: PrismaNode) => x
      case x: PrismaNode       => x
      case None                => throw new Exception("Resolved DataItem was None. This is unexpected - please investigate why and fix.")
    }
  }
}
