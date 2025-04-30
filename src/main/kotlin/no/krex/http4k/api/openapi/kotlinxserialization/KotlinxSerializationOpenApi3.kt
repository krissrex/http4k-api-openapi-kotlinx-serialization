package no.krex.http4k.api.openapi.kotlinxserialization

import io.swagger.v3.core.util.Json31
import io.swagger.v3.oas.models.Components
import kotlinx.serialization.KSerializer
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.JsonSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityRequirement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import org.http4k.asString
import org.http4k.contract.ContractRenderer
import org.http4k.contract.ContractRoute
import org.http4k.contract.PathSegments
import org.http4k.contract.Tag
import org.http4k.contract.WebCallback
import org.http4k.contract.openapi.operationId
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.ContentNegotiation
import org.http4k.lens.Header
import org.http4k.lens.Meta
import org.http4k.lens.ParamMeta
import org.http4k.lens.httpBodyRoot
import org.http4k.security.Security

class KotlinxSerializationOpenApi3(
  val json: Json
) : ContractRenderer {

  override fun description(
    contractRoot: PathSegments,
    security: Security?,
    routes: List<ContractRoute>,
    tags: Set<Tag>,
    webhooks: Map<String, List<WebCallback>>
  ): Response {

    val schema = OpenAPI(SpecVersion.V31).apply {
      openapi = "3.1.0"
      components = Components().apply {
//        schemas = TODO()
//        securitySchemes = TODO()
      }

//      info = TODO()
//      this.tags = TODO()
//      this.webhooks = TODO()
//      servers = TODO()
    }

    val paths = routes.map { route ->
      val path = route.describeFor(contractRoot)
      val method = route.method
      val meta = route.meta

      val pathSpec = PathItem().apply {
        this.summary = meta.summary
        this.description = meta.description
      }

      val operationSpec = Operation()
      operationSpec.summary = meta.summary
      operationSpec.description = meta.description
      operationSpec.parameters = meta.requestParams.map { param ->
        val paramMeta: ParamMeta = param.meta.paramMeta
        val paramSpec = Parameter()

        when (paramMeta) {
          is ParamMeta.ArrayParam -> TODO()
          is ParamMeta.EnumParam<*> -> TODO()
          is ParamMeta.ObjectParam -> TODO()
          else -> {
            paramSpec.`in` = param.meta.location
            paramSpec.name = param.meta.name
            paramSpec.required = param.meta.required
            paramSpec.description = param.meta.description
            // TODO deprecated. Maybe from metadata
          }
        }

        paramSpec
      }

      operationSpec.responses = ApiResponses()
      meta.responses.forEach { response ->
        operationSpec.responses.addApiResponse(
                response.message.status.code.toString(),
                ApiResponse().also { response ->
                  //todo
                  // content.addMediaType()
                    response.content = Content().also { content ->
                        // Todo make json schema from kotlinx serializer descriptor
                    }
                },
        )
      }

      operationSpec.requestBody = RequestBody().apply {
        this.required = meta.requests.isNotEmpty() // TODO probably wrong
        this.description = meta.requests.mapNotNull { it.description }.joinToString("\n")



        this.content = Content().also { content ->
          meta.requests.forEach { req ->
            val contentType = Header.CONTENT_TYPE(req.message)?.withNoDirectives()?.value ?: "TODO"

            content.addMediaType(
                contentType,
                MediaType().also { mediaType ->
                  mediaType.schema = JsonSchema().also { jsonSchema ->
                    //jsonSchema.discriminator
                    //jsonSchema.anyOf = mutableListOf(JsonSchema())
                    jsonSchema.description = req.description
                    jsonSchema.type = "object"
                    jsonSchema.`$schema` = "https://spec.openapis.org/oas/3.1/dialect/base"
                    jsonSchema.required = mutableListOf()
                    jsonSchema.properties = mutableMapOf()
                    // if pointing to #/components: jsonSchema.`$ref`
                    // If a Map<String, String>: jsonSchema.additionalProperties = mapOf("type" to "string")
                  }
                  // Example must be set after schema.
                  val serializer: KSerializer<Any> =
                      json.serializersModule.serializer(req.example!!::class.java)
                  mediaType.example = json.encodeToJsonElement(serializer, req.example!!)
                },
            )
          }


        }
      }

      operationSpec.security = listOfNotNull(meta.security ?: security).map { security ->
        SecurityRequirement().apply {
          // Add a name that matches #security and a list of scopes if oauth2 or openIdConnect
          // Todo use a SecurityRender-like mechanism.
          addList("todo")
        }
      }
      operationSpec.operationId = operationId(meta, method, route.describeFor(contractRoot))

      when (method) {
        Method.GET -> pathSpec.get = operationSpec
        Method.POST -> pathSpec.post = operationSpec
        Method.PUT -> pathSpec.put = operationSpec
        Method.DELETE -> pathSpec.delete = operationSpec
        Method.OPTIONS -> pathSpec.options = operationSpec
        Method.TRACE -> pathSpec.trace = operationSpec
        Method.PATCH -> pathSpec.patch = operationSpec
        Method.PURGE -> {} // ?
        Method.HEAD -> pathSpec.head = operationSpec
      }

      when (method) {
        Method.GET,
        Method.HEAD -> {
        }

        else -> operationSpec.requestBody = RequestBody().also { bodySpec ->
          meta.body //todo
          bodySpec.`$ref` = TODO()
        }
      }

      // http4k tags by contractRoot.toString if tags are empty.
      meta.tags.forEach { tag ->
        // TODO clean duplicate tags
        val tagSpec = io.swagger.v3.oas.models.tags.Tag().apply {
          this.name = tag.name
          this.description = tag.description
        }
        schema.addTagsItem(tagSpec)
        operationSpec.addTagsItem(tagSpec.name)
      }

      schema.path(path, pathSpec)
      pathSpec
    }


    return Response(Status.OK).with(bodyLens of schema)
  }

  val bodyLens: BiDiBodyLens<OpenAPI> =
      httpBodyRoot(
          metas = listOf(Meta(true, "body", ParamMeta.ObjectParam, "body", "", emptyMap())),
          acceptedContentType = ContentType.APPLICATION_JSON,
          contentNegotiation = ContentNegotiation.None,
      )
          .map({ it.payload.asString() }, { Body(it) })
          .map({ Json31.mapper().readValue(it, OpenAPI::class.java) }, { Json31.pretty(it) })
          .toLens()
}
