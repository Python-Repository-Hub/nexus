package ch.epfl.bluebrain.nexus.delta.sdk.views

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewData.IndexingData
import io.circe.Json
import monix.bio.IO

object IndexingDataGen {

  def fromDataResource(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources),
      tags: Map[TagLabel, Long] = Map.empty,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  )(implicit resolution: RemoteContextResolution, baseUri: BaseUri): IO[RdfError, IndexingData] = {
    IndexingData(
      Resources.eventExchangeValue(
        ResourceGen.sourceToResourceF(id, project, source, schema, tags, rev, subject, deprecated, am, base)
      )
    )
  }

}
