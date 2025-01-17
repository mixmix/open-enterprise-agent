package io.iohk.atala.pollux.credentialschema.controller

import io.iohk.atala.api.http.RequestContext
import io.iohk.atala.api.http.model.{CollectionStats, Pagination}
import io.iohk.atala.pollux.credentialschema.http.{VerificationPolicy, VerificationPolicyPage}
import sttp.model.Uri
import io.iohk.atala.api.util.PaginationUtils

case class VerificationPolicyPageRequestLogic(
    ctx: RequestContext,
    pagination: Pagination,
    items: List[VerificationPolicy],
    stats: CollectionStats
) {
  def composeNextUri(uri: Uri): Option[Uri] = PaginationUtils.composeNextUri(uri, items, pagination, stats)

  def composePreviousUri(uri: Uri): Option[Uri] = PaginationUtils.composePreviousUri(uri, items, pagination, stats)

  def result: VerificationPolicyPage = {
    val self = ctx.request.uri.toString
    val pageOf = ctx.request.uri.copy(querySegments = Seq.empty).toString
    val next = composeNextUri(ctx.request.uri).map(_.toString)
    val previous = composePreviousUri(ctx.request.uri).map(_.toString)
    val baseUri = ctx.request.uri.copy(querySegments = Seq.empty)

    val pageResult = VerificationPolicyPage(
      self = self,
      kind = "VerificationPolicyPage",
      pageOf = pageOf,
      next = next,
      previous = previous,
      contents = items.map(item => item.withBaseUri(baseUri))
    )

    pageResult
  }
}
