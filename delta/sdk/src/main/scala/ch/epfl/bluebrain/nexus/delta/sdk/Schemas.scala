package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{SchemaCommand, SchemaEvent, SchemaRejection, SchemaState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * Operations pertaining to managing schemas.
  */
trait Schemas {

  /**
    * Creates a new schema where the id is either present on the payload or self generated.
    *
    * @param projectRef the project reference where the schema belongs
    * @param source     the schema payload
    */
  def create(projectRef: ProjectRef, source: Json)(implicit caller: Subject): IO[SchemaRejection, SchemaResource]

  /**
    * Creates a new schema with the expanded form of the passed id.
    *
    * @param id         the identifier that will be expanded to the Iri of the schema
    * @param projectRef the project reference where the schema belongs
    * @param source     the schema payload
    */
  def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource]

  /**
    * Updates an existing schema.
    *
    * @param id         the identifier that will be expanded to the Iri of the schema
    * @param projectRef the project reference where the schema belongs
    * @param rev        the current revision of the schema
    * @param source     the schema payload
    */
  def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      source: Json
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource]

  /**
    * Adds a tag to an existing schema.
    *
    * @param id         the identifier that will be expanded to the Iri of the schema
    * @param projectRef the project reference where the schema belongs
    * @param tag        the tag name
    * @param tagRev     the tag revision
    * @param rev        the current revision of the schema
    */
  def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: Label,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource]

  /**
    * Deprecates an existing schema.
    *
    * @param id         the identifier that will be expanded to the Iri of the schema
    * @param projectRef the project reference where the schema belongs
    * @param rev       the revision of the schema
    */
  def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource]

  /**
    * Fetches a schema.
    *
    * @param id         the identifier that will be expanded to the Iri of the schema
    * @param projectRef the project reference where the schema belongs
    * @return the schema in a Resource representation, None otherwise
    */
  def fetch(id: IdSegment, projectRef: ProjectRef): IO[SchemaRejection, Option[SchemaResource]]

  /**
    * Fetches a schema at a specific revision.
    *
    * @param id         the identifier that will be expanded to the Iri of the schema
    * @param projectRef the project reference where the schema belongs
    * @param rev       the schemas revision
    * @return the schema as a schema at the specified revision
    */
  def fetchAt(id: IdSegment, projectRef: ProjectRef, rev: Long): IO[SchemaRejection, Option[SchemaResource]]

  /**
    * Fetches a schema by tag.
    *
    * @param id         the identifier that will be expanded to the Iri of the schema
    * @param projectRef the project reference where the schema belongs
    * @param tag        the tag revision
    * @return the schema as a schema at the specified revision
    */
  def fetchBy(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: Label
  ): IO[SchemaRejection, Option[SchemaResource]] =
    fetch(id, projectRef).flatMap {
      case Some(schema) =>
        schema.value.tags.get(tag) match {
          case Some(rev) => fetchAt(id, projectRef, rev).leftMap(_ => TagNotFound(tag))
          case None      => IO.raiseError(TagNotFound(tag))
        }
      case None         => IO.pure(None)
    }

  /**
    * A non terminating stream of events for schemas. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset): Stream[Task, Envelope[SchemaEvent]]

}

object Schemas {

  /**
    * The schemas module type.
    */
  final val moduleType: String = "schema"

  private[delta] def next(state: SchemaState, event: SchemaEvent): SchemaState = {

    @SuppressWarnings(Array("OptionGet"))
    // It is fine to do it unsafely since we have already computed the graph on evaluation previously in order to validate the schema.
    def toGraphUnsafe(expanded: ExpandedJsonLd) =
      expanded.toGraph.toOption.get

    // format: off
    def created(e: SchemaCreated): SchemaState = state match {
      case Initial     => Current(e.id, e.project, e.source, e.compacted, e.expanded, toGraphUnsafe(e.expanded), e.rev, deprecated = false, Map.empty, e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: SchemaUpdated): SchemaState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, source = e.source, compacted = e.compacted, expanded = e.expanded, graph = toGraphUnsafe(e.expanded), updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: SchemaTagAdded): SchemaState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: SchemaDeprecated): SchemaState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }
    event match {
      case e: SchemaCreated    => created(e)
      case e: SchemaUpdated    => updated(e)
      case e: SchemaTagAdded   => tagAdded(e)
      case e: SchemaDeprecated => deprecated(e)
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  private[delta] def evaluate(state: SchemaState, cmd: SchemaCommand)(implicit
      clock: Clock[UIO] = IO.clock,
      rcr: RemoteContextResolution
  ): IO[SchemaRejection, SchemaEvent] = {

    def toGraph(id: Iri, expanded: ExpandedJsonLd) =
      IO.fromEither(expanded.toGraph.leftMap(err => InvalidJsonLdFormat(Some(id), err)))

    def validate(id: Iri, graph: Graph): IO[SchemaRejection, Unit] =
      for {
        report <- ShaclEngine(graph.model, reportDetails = true).leftMap(SchemaShaclEngineRejection(id, _))
        result <- if (report.isValid()) IO.unit else IO.raiseError(InvalidSchema(id, report))
      } yield result

    def create(c: CreateSchema) =
      state match {
        case Initial =>
          for {
            graph <- toGraph(c.id, c.expanded)
            _     <- validate(c.id, graph)
            t     <- IOUtils.instant
          } yield SchemaCreated(c.id, c.project, c.source, c.compacted, c.expanded, 1L, t, c.subject)

        case _ => IO.raiseError(SchemaAlreadyExists(c.id))
      }

    def update(c: UpdateSchema) =
      state match {
        case Initial                      =>
          IO.raiseError(SchemaNotFound(c.id))
        case s: Current if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(SchemaIsDeprecated(c.id))
        case s: Current                   =>
          for {
            graph <- toGraph(c.id, c.expanded)
            _     <- validate(c.id, graph)
            time  <- IOUtils.instant
          } yield SchemaUpdated(c.id, c.project, c.source, c.compacted, c.expanded, s.rev + 1, time, c.subject)

      }

    def tag(c: TagSchema) =
      state match {
        case Initial                                               =>
          IO.raiseError(SchemaNotFound(c.id))
        case s: Current if s.rev != c.rev                          =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated                            =>
          IO.raiseError(SchemaIsDeprecated(c.id))
        case s: Current if c.targetRev <= 0 || c.targetRev > s.rev =>
          IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
        case s: Current                                            =>
          IOUtils.instant.map(SchemaTagAdded(c.id, c.project, c.targetRev, c.tag, s.rev + 1, _, c.subject))

      }

    def deprecate(c: DeprecateSchema) =
      state match {
        case Initial                      =>
          IO.raiseError(SchemaNotFound(c.id))
        case s: Current if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(SchemaIsDeprecated(c.id))
        case s: Current                   =>
          IOUtils.instant.map(SchemaDeprecated(c.id, c.project, s.rev + 1, _, c.subject))
      }

    cmd match {
      case c: CreateSchema    => create(c)
      case c: UpdateSchema    => update(c)
      case c: TagSchema       => tag(c)
      case c: DeprecateSchema => deprecate(c)
    }
  }
}
