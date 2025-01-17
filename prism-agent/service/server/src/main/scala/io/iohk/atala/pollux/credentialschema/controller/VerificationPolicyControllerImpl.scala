package io.iohk.atala.pollux.credentialschema.controller

import io.iohk.atala.api.http.*
import io.iohk.atala.api.http.model.{CollectionStats, Order, Pagination}
import io.iohk.atala.pollux.core.model
import io.iohk.atala.pollux.core.model.CredentialSchemaAndTrustedIssuersConstraint
import io.iohk.atala.pollux.core.model.error.VerificationPolicyError
import io.iohk.atala.pollux.core.model.error.VerificationPolicyError.*
import io.iohk.atala.pollux.core.service.VerificationPolicyService
import io.iohk.atala.pollux.credentialschema.http.VerificationPolicy.*
import io.iohk.atala.pollux.credentialschema.http.{VerificationPolicy, VerificationPolicyInput, VerificationPolicyPage}
import io.iohk.atala.shared.models.WalletAccessContext
import zio.*
import zio.ZIO.*

import java.util.UUID

class VerificationPolicyControllerImpl(service: VerificationPolicyService) extends VerificationPolicyController {

  def verificationPolicyError2FailureResponse(
      vpe: VerificationPolicyError
  ): ErrorResponse = {
    vpe match {
      case RepositoryError(cause) =>
        ErrorResponse.internalServerError(detail = Option(cause.getMessage))
      case NotFoundError(id) =>
        ErrorResponse.notFound(detail = Option(s"VerificationPolicy is not found by $id"))
      case UnexpectedError(cause) =>
        ErrorResponse.internalServerError(detail = Option(cause.getMessage))
    }
  }
  override def createVerificationPolicy(
      ctx: RequestContext,
      in: VerificationPolicyInput
  ): ZIO[WalletAccessContext, ErrorResponse, VerificationPolicy] = {
    val constraints = in.constraints
      .map(c =>
        CredentialSchemaAndTrustedIssuersConstraint(
          c.schemaId,
          c.trustedIssuers
        )
      )

    for {
      createdVerificationPolicy <- service.create(
        in.name,
        in.description,
        constraints
      )
    } yield createdVerificationPolicy
      .toSchema()
      .withBaseUri(ctx.request.uri)

  } mapError (e => verificationPolicyError2FailureResponse(e))

  override def getVerificationPolicyById(
      ctx: RequestContext,
      id: UUID
  ): ZIO[WalletAccessContext, ErrorResponse, VerificationPolicy] = {
    service.get(id).flatMap {
      case Some(vp) => succeed(vp.toSchema().withUri(ctx.request.uri))
      case None     => fail(NotFoundError(id))
    }
  }.mapError(e => verificationPolicyError2FailureResponse(e))

  override def updateVerificationPolicyById(
      ctx: RequestContext,
      id: UUID,
      nonce: Int,
      update: VerificationPolicyInput
  ): ZIO[WalletAccessContext, ErrorResponse, VerificationPolicy] = {
    val updatedZIO = for {
      constraints <- zio.ZIO.succeed(
        update.constraints.toVector // TODO: refactor to Seq
          .map(c =>
            CredentialSchemaAndTrustedIssuersConstraint(
              c.schemaId,
              c.trustedIssuers
            )
          )
      )
      vp <- model.VerificationPolicy.make(
        update.name,
        update.description,
        constraints,
        nonce = nonce + 1
      )
      updated <- service.update(id, nonce, vp)
    } yield updated

    updatedZIO
      .flatMap {
        case Some(vp) => succeed(vp.toSchema().withUri(ctx.request.uri))
        case None     => fail(NotFoundError(id))
      }
      .mapError(e => verificationPolicyError2FailureResponse(e))
  }

  override def deleteVerificationPolicyById(
      ctx: RequestContext,
      id: UUID
  ): ZIO[WalletAccessContext, ErrorResponse, Unit] = {
    service
      .delete(id)
      .flatMap {
        case Some(_) => succeed(())
        case None    => fail(NotFoundError(id))
      }
      .mapError(e => verificationPolicyError2FailureResponse(e))
  }

  override def lookupVerificationPolicies(
      ctx: RequestContext,
      filter: VerificationPolicy.Filter,
      pagination: Pagination,
      order: Option[Order]
  ): ZIO[WalletAccessContext, ErrorResponse, VerificationPolicyPage] = {
    for {
      filteredDomainRecords <- service
        .lookup(filter.name, Some(pagination.offset), Some(pagination.limit))
        .mapError(verificationPolicyError2FailureResponse)
      filteredCount <- service
        .filteredCount(filter.name)
        .mapError(verificationPolicyError2FailureResponse)
      baseUri = ctx.request.uri.copy(querySegments = Seq.empty)
      filteredRecords = filteredDomainRecords.map(
        _.toSchema().withBaseUri(baseUri)
      )
      totalCount <- service
        .totalCount()
        .mapError(verificationPolicyError2FailureResponse)
      response = VerificationPolicyPageRequestLogic(
        ctx,
        pagination,
        filteredRecords,
        CollectionStats(totalCount, filteredCount)
      ).result
    } yield response
  }
}

object VerificationPolicyControllerImpl {
  val layer: URLayer[VerificationPolicyService, VerificationPolicyController] =
    ZLayer.fromFunction(VerificationPolicyControllerImpl(_))
}
