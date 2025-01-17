package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewJsonLdSourceDecoder.{toValue, ElasticSearchViewFields}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, permissions, ElasticSearchViewRejection, ElasticSearchViewType, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe._
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

/**
  * Decoder for [[ElasticSearchViewValue]] which maps some fields to string, before decoding to get around lack of
  * support for @json in json ld library.
  */
//TODO remove when support for @json is added in json-ld library
class ElasticSearchViewJsonLdSourceDecoder private (
    decoder: JsonLdSourceResolvingDecoder[ElasticSearchViewRejection, ElasticSearchViewFields]
) {

  def apply(project: Project, source: Json)(implicit
      caller: Caller
  ): IO[ElasticSearchViewRejection, (Iri, ElasticSearchViewValue)] =
    decoder(project, mapJsonToString(source)).map { case (iri, fields) =>
      iri -> toValue(fields)
    }

  def apply(project: Project, iri: Iri, source: Json)(implicit
      caller: Caller
  ): IO[ElasticSearchViewRejection, ElasticSearchViewValue] =
    decoder(
      project,
      iri,
      mapJsonToString(source)
    ).map(toValue)

  private def mapJsonToString(json: Json): Json = json
    .mapAllKeys("mapping", _.noSpaces.asJson)
    .mapAllKeys("settings", _.noSpaces.asJson)
}

object ElasticSearchViewJsonLdSourceDecoder {

  sealed private trait ElasticSearchViewFields extends Product with Serializable {
    def tpe: ElasticSearchViewType
  }

  private object ElasticSearchViewFields {

    // Describe the legacy payload using deprecated fields to keep retro-compatibility
    final case class LegacyIndexingElasticSearchViewFields(
        resourceSchemas: Set[Iri] = Set.empty,
        resourceTypes: Set[Iri] = Set.empty,
        resourceTag: Option[TagLabel] = None,
        sourceAsText: Boolean = false,
        includeMetadata: Boolean = false,
        includeDeprecated: Boolean = false,
        mapping: JsonObject,
        settings: Option[JsonObject] = None,
        permission: Permission = permissions.query
    ) extends ElasticSearchViewFields {
      override val tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
    }

    final case class IndexingElasticSearchViewFields(
        resourceTag: Option[TagLabel] = None,
        pipeline: Option[List[PipeDef]] = None,
        mapping: JsonObject,
        settings: Option[JsonObject] = None,
        context: Option[ContextObject] = None,
        permission: Permission = permissions.query
    ) extends ElasticSearchViewFields {
      override val tpe: ElasticSearchViewType = ElasticSearchViewType.ElasticSearch
    }

    final case class AggregateElasticSearchViewFields(
        views: NonEmptySet[ViewRef]
    ) extends ElasticSearchViewFields {
      override val tpe: ElasticSearchViewType = ElasticSearchViewType.AggregateElasticSearch
    }

    implicit final val elasticSearchViewFieldsJsonLdDecoder: JsonLdDecoder[ElasticSearchViewFields] = {
      val legacyFieldsJsonLdDecoder    = deriveJsonLdDecoder[LegacyIndexingElasticSearchViewFields]
      val indexingFieldsJsonLdDecoder  = deriveJsonLdDecoder[IndexingElasticSearchViewFields]
      val aggregateFieldsJsonLdDecoder = deriveJsonLdDecoder[AggregateElasticSearchViewFields]
      val pipeline                     = nxv + "pipeline"
      (cursor: ExpandedJsonLdCursor) =>
        cursor.getTypes.flatMap { types =>
          (
            types.contains(ElasticSearchViewType.ElasticSearch.tpe),
            types.contains(ElasticSearchViewType.AggregateElasticSearch.tpe)
          ) match {
            case (true, true)   =>
              Left(
                ParsingFailure(
                  s"The payload can't contain both '${ElasticSearchViewType.ElasticSearch}' and '${ElasticSearchViewType.AggregateElasticSearch}' types.'"
                )
              )
            case (true, false)  =>
              //TODO implement strict json-ld decoding
              if (cursor.downField(pipeline).succeeded)
                indexingFieldsJsonLdDecoder(cursor)
              else
                legacyFieldsJsonLdDecoder(cursor)
            case (false, true)  =>
              aggregateFieldsJsonLdDecoder(cursor)
            case (false, false) =>
              Left(
                ParsingFailure(
                  s"The payload has to contain '${ElasticSearchViewType.ElasticSearch}' or '${ElasticSearchViewType.AggregateElasticSearch}' types.'"
                )
              )
          }
        }
    }

  }

  import ElasticSearchViewFields._

  private def toValue(fields: ElasticSearchViewFields): ElasticSearchViewValue = fields match {
    case i: LegacyIndexingElasticSearchViewFields =>
      // Translate legacy fields into a pipeline
      val pipeline = List(
        i.resourceSchemas.nonEmpty -> FilterBySchema(i.resourceSchemas),
        i.resourceTypes.nonEmpty   -> FilterByType(i.resourceTypes),
        !i.includeDeprecated       -> FilterDeprecated(),
        !i.includeMetadata         -> DiscardMetadata(),
        true                       -> DefaultLabelPredicates(),
        i.sourceAsText             -> SourceAsText()
      ).mapFilter { case (b, p) => Option.when(b)(p) }

      IndexingElasticSearchViewValue(
        resourceTag = i.resourceTag,
        pipeline,
        mapping = Some(i.mapping),
        settings = i.settings,
        context = None,
        permission = i.permission
      )
    case i: IndexingElasticSearchViewFields       =>
      IndexingElasticSearchViewValue(
        resourceTag = i.resourceTag,
        // If there is no pipeline defined, we use a default one to keep the historic behaviour
        pipeline = i.pipeline.getOrElse(IndexingElasticSearchViewValue.defaultPipeline),
        mapping = Some(i.mapping),
        settings = i.settings,
        context = i.context,
        permission = i.permission
      )
    case a: AggregateElasticSearchViewFields      =>
      AggregateElasticSearchViewValue(a.views)
  }

  def apply(uuidF: UUIDF, contextResolution: ResolverContextResolution)(implicit api: JsonLdApi) =
    new ElasticSearchViewJsonLdSourceDecoder(
      new JsonLdSourceResolvingDecoder[ElasticSearchViewRejection, ElasticSearchViewFields](
        contexts.elasticsearch,
        contextResolution,
        uuidF
      )
    )
}
