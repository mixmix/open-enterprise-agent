package io.iohk.atala.pollux.credentialschema

import io.iohk.atala.api.http.model.{CollectionStats, Order, PaginationInput}
import io.iohk.atala.api.http.{FailureResponse, InternalServerError, NotFound, RequestContext}
import io.iohk.atala.pollux.credentialschema.VerificationPolicyEndpoints.*
import io.iohk.atala.pollux.credentialschema.controller.{
  VerificationPolicyController,
  VerificationPolicyPageRequestLogic
}
import io.iohk.atala.pollux.credentialschema.http.{VerificationPolicy, VerificationPolicyInput, VerificationPolicyPage}
import sttp.tapir.redoc.RedocUIOptions
import sttp.tapir.redoc.bundle.RedocInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.{Task, URIO, ZIO, ZLayer}

import java.util.UUID

class VerificationPolicyServerEndpoints(
    controller: VerificationPolicyController
) {
  def throwableToInternalServerError(throwable: Throwable) =
    ZIO.fail[FailureResponse](InternalServerError(throwable.getMessage))

  // TODO: make the endpoint typed ZServerEndpoint[SchemaRegistryService, Any]
  val createVerificationPolicyServerEndpoint: ZServerEndpoint[Any, Any] =
    createVerificationPolicyEndpoint.zServerLogic { case (ctx: RequestContext, input: VerificationPolicyInput) =>
      controller.createVerificationPolicy(ctx, input)
    }

  val updateVerificationPolicyServerEndpoint: ZServerEndpoint[Any, Any] = {
    updateVerificationPolicyEndpoint.zServerLogic {
      case (
            ctx: RequestContext,
            id: UUID,
            nonce: Int,
            update: VerificationPolicyInput
          ) =>
        controller.updateVerificationPolicyById(ctx, id, nonce, update)
    }
  }

  val getVerificationPolicyByIdServerEndpoint: ZServerEndpoint[Any, Any] =
    getVerificationPolicyByIdEndpoint.zServerLogic { case (ctx: RequestContext, id: UUID) =>
      controller.getVerificationPolicyById(ctx, id)
    }

  val deleteVerificationPolicyByIdServerEndpoint: ZServerEndpoint[Any, Any] =
    deleteVerificationPolicyByIdEndpoint.zServerLogic { case (ctx: RequestContext, id: UUID, nonce: Int) =>
      controller.deleteVerificationPolicyById(ctx, id, nonce)
    }

  val lookupVerificationPoliciesByQueryServerEndpoint: ZServerEndpoint[Any, Any] =
    lookupVerificationPoliciesByQueryEndpoint.zServerLogic {
      case (
            ctx: RequestContext,
            filter: VerificationPolicy.Filter,
            paginationInput: PaginationInput,
            order: Option[Order]
          ) =>
        controller
          .lookupVerificationPolicies(
            ctx,
            filter,
            paginationInput.toPagination,
            order
          )
    }

  val all: List[ZServerEndpoint[Any, Any]] =
    List(
      createVerificationPolicyServerEndpoint,
      getVerificationPolicyByIdServerEndpoint,
      updateVerificationPolicyServerEndpoint,
      deleteVerificationPolicyByIdServerEndpoint,
      lookupVerificationPoliciesByQueryServerEndpoint
    )
}

object VerificationPolicyServerEndpoints {
  def all: URIO[VerificationPolicyController, List[ZServerEndpoint[Any, Any]]] = {
    for {
      controller <- ZIO.service[VerificationPolicyController]
      endpoints = new VerificationPolicyServerEndpoints(controller)
    } yield endpoints.all
  }
}
