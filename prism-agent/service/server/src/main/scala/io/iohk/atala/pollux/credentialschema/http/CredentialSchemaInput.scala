package io.iohk.atala.pollux.credentialschema.http

import io.iohk.atala.pollux.core.model.CredentialSchema.Input
import io.iohk.atala.pollux.credentialschema.http.CredentialSchemaResponse.annotations
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.{description, encodedExample, validate, validateEach}
import sttp.tapir.json.zio.schemaForZioJsonValue
import zio.json.*
import zio.json.ast.Json
import sttp.tapir.Validator.*
import io.iohk.atala.api.http.*

case class CredentialSchemaInput(
    @description(annotations.name.description)
    @encodedExample(annotations.name.example)
    @validate(nonEmptyString)
    name: String,
    @description(annotations.version.description)
    @encodedExample(annotations.version.example)
    @validate(pattern(SemVerRegex))
    version: String,
    @description(annotations.description.description)
    @encodedExample(annotations.description.example)
    @validateEach(nonEmptyString)
    description: Option[String],
    @description(annotations.`type`.description)
    @encodedExample(annotations.`type`.example)
    `type`: String,
    @description(annotations.schema.description)
    @encodedExample(annotations.schema.example.toJson)
    schema: zio.json.ast.Json,
    @description(annotations.tags.description)
    @encodedExample(annotations.tags.example)
    tags: Seq[String],
    @description(annotations.author.description)
    @encodedExample(annotations.author.example)
    @validate(pattern(DIDRefRegex))
    author: String
)
object CredentialSchemaInput {
  def toDomain(in: CredentialSchemaInput): Input =
    Input(
      name = in.name,
      version = in.version,
      tags = in.tags,
      description = in.description.getOrElse(""),
      `type` = in.`type`,
      schema = in.schema,
      author = in.author,
      authored = None
    )
  given encoder: JsonEncoder[CredentialSchemaInput] =
    DeriveJsonEncoder.gen[CredentialSchemaInput]
  given decoder: JsonDecoder[CredentialSchemaInput] =
    DeriveJsonDecoder.gen[CredentialSchemaInput]
  given schema: Schema[CredentialSchemaInput] = Schema.derived
}
