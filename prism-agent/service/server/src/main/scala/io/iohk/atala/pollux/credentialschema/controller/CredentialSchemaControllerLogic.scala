package io.iohk.atala.pollux.credentialschema.controller

import io.iohk.atala.api.http.RequestContext
import io.iohk.atala.api.http.model.{CollectionStats, Pagination}
import io.iohk.atala.pollux.credentialschema.http.CredentialSchemaPageResponse
import sttp.model.Uri
import sttp.model.Uri.QuerySegment
import sttp.model.Uri.QuerySegment.KeyValue

import scala.util.Try
import io.iohk.atala.api.util.PaginationUtils

case class CredentialSchemaControllerLogic(
    ctx: RequestContext,
    pagination: Pagination,
    page: CredentialSchemaPageResponse,
    stats: CollectionStats
) {

  def composeNextUri(uri: Uri): Option[Uri] =
    PaginationUtils.composeNextUri(uri, page.contents, pagination, stats)

  def composePreviousUri(uri: Uri): Option[Uri] =
    PaginationUtils.composePreviousUri(uri, page.contents, pagination, stats)

  def result: CredentialSchemaPageResponse = {
    val self = ctx.request.uri.toString
    val pageOf = ctx.request.uri.copy(querySegments = Seq.empty).toString
    val next = composeNextUri(ctx.request.uri).map(_.toString)
    val previous = composePreviousUri(ctx.request.uri).map(_.toString)

    val pageResult = page.copy(
      self = self,
      pageOf = pageOf,
      next = next,
      previous = previous,
      contents = page.contents.map(item =>
        item.withBaseUri(
          ctx.request.uri.copy(querySegments = Seq.empty)
        )
      )
    )

    pageResult
  }
}
