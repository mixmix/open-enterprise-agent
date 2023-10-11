package io.iohk.atala.iam.authentication.admin

import io.iohk.atala.agent.server.config.AppConfig
import zio.{URLayer, ZLayer}

final case class AdminConfig(token: String)

//TODO: after moving the classes to separated package, derive the adminConfig from the authenticationConfig
object AdminConfig {
  val layer: URLayer[AppConfig, AdminConfig] = ZLayer.fromFunction((conf: AppConfig) => conf.agent.authentication.admin)
}
