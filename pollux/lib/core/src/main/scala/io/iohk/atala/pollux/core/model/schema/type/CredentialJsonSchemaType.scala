package io.iohk.atala.pollux.core.model.schema.`type`

import io.iohk.atala.pollux.core.model.schema.Schema
import io.iohk.atala.pollux.core.model.schema.validator.JsonSchemaError
import io.iohk.atala.pollux.core.model.schema.validator.JsonSchemaValidatorImpl
import zio.*
import zio.json.*

object CredentialJsonSchemaType extends CredentialSchemaType {
  val VC_JSON_SCHEMA_URI = "https://w3c-ccg.github.io/vc-json-schemas/schema/2.0/schema.json"

  override val `type`: String = VC_JSON_SCHEMA_URI

  override def validate(schema: Schema): IO[JsonSchemaError, Unit] =
    for {
      _ <- JsonSchemaValidatorImpl.from(schema)
    } yield ()
}
