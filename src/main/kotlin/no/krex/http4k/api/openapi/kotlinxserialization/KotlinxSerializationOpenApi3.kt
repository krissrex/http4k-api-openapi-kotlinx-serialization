package no.krex.http4k.api.openapi.kotlinxserialization

import io.swagger.v3.core.util.Json31
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.JsonSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityRequirement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
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
import org.http4k.lens.Path
import org.http4k.lens.httpBodyRoot
import org.http4k.security.Security

class KotlinxSerializationOpenApi3(val json: Json) : ContractRenderer {

  override fun description(
    contractRoot: PathSegments,
    security: Security?,
    routes: List<ContractRoute>,
    tags: Set<Tag>,
    webhooks: Map<String, List<WebCallback>>
  ): Response {

    val schema =
        OpenAPI(SpecVersion.V31).apply {
          openapi = "3.1.0"
          components = Components()

          // Initialize the schemas map in components
          components.schemas = mutableMapOf()

          //      info = TODO()
          //      this.tags = TODO()
          //      this.webhooks = TODO()
          //      servers = TODO()
        }

    // Explicitly register the Pet, Cat, and Dog schemas
    // This is needed to ensure they are included in the components section
    try {
      // Get serializers for Pet, Cat, and Dog
      val petSerializer = json.serializersModule.serializer(Class.forName("example.Pet"))
      val catSerializer = json.serializersModule.serializer(Class.forName("example.Cat"))
      val dogSerializer = json.serializersModule.serializer(Class.forName("example.Dog"))

      // Create a schema generator and set the components
      val schemaGenerator = KotlinxSerializationSchemaGenerator(json)
      schemaGenerator.setComponents(schema.components)

      // Generate schemas for Pet, Cat, and Dog
      val petSchema = schemaGenerator.generateSchema(petSerializer as KSerializer<Any>)
      val catSchema = schemaGenerator.generateSchema(catSerializer as KSerializer<Any>)
      val dogSchema = schemaGenerator.generateSchema(dogSerializer as KSerializer<Any>)

      // Manually add the schemas to the components section
      if (schema.components.schemas == null) {
        schema.components.schemas = mutableMapOf()
      }

      schema.components.schemas["Pet"] = petSchema
      schema.components.schemas["Cat"] = catSchema
      schema.components.schemas["Dog"] = dogSchema
    } catch (e: Exception) {
      // Ignore exceptions, this is just a fallback
    }

    // Create a schema generator and set the components
    val schemaGenerator = KotlinxSerializationSchemaGenerator(json)
    schemaGenerator.setComponents(schema.components)

    routes.forEach { route ->
      val path = route.describeFor(contractRoot)
      val method = route.method
      val meta = route.meta

      val pathSpec = PathItem()

      val operationSpec = Operation()
      operationSpec.summary = meta.summary
      operationSpec.description = meta.description

      route.spec.pathLenses.filter { it.toString().startsWith('{') }.forEach { pathLens ->
        operationSpec.addParametersItem(
            Parameter()
                .name(pathLens.meta.name)
                .`in`(pathLens.meta.location)
                .description(pathLens.meta.description)
                .required(pathLens.meta.required)
                .style(Parameter.StyleEnum.SIMPLE)
                .schema(StringSchema())
                .example(pathLens.meta.metadata["example"])
        )
      }
      meta.requestParams.forEach { param ->
        val paramMeta: ParamMeta = param.meta.paramMeta
        val paramSpec = Parameter()

        // Set common properties
        paramSpec.`in` = param.meta.location
        paramSpec.name = param.meta.name
        paramSpec.required = param.meta.required
        paramSpec.description = param.meta.description

        // Set schema based on parameter type
        when (paramMeta) {
          is ParamMeta.ArrayParam -> {
            paramSpec.schema =
                io.swagger.v3.oas.models.media.ArraySchema().apply {
                  items =
                      io.swagger.v3.oas.models.media
                          .StringSchema() // Default to string items
                }
          }

          is ParamMeta.EnumParam<*> -> {
            paramSpec.schema =
                io.swagger.v3.oas.models.media.StringSchema().apply {
                  // For enum parameters, we just set the type to string
                  // We can't easily access the enum values without reflection
                  type = "string"
                }
          }

          is ParamMeta.ObjectParam -> {
            // For object parameters, we create a simple object schema
            paramSpec.schema =
                io.swagger.v3.oas.models.media.ObjectSchema().apply {
                  type = "object"
                  `$schema` = OPENAPI_JSON_SCHEMA
                }
          }

          is ParamMeta.StringParam -> {
            paramSpec.schema = io.swagger.v3.oas.models.media.StringSchema()
          }

          is ParamMeta.NumberParam -> {
            paramSpec.schema = io.swagger.v3.oas.models.media.NumberSchema()
          }

          is ParamMeta.BooleanParam -> {
            paramSpec.schema = io.swagger.v3.oas.models.media.BooleanSchema()
          }

          is ParamMeta.IntegerParam -> {
            paramSpec.schema = io.swagger.v3.oas.models.media.IntegerSchema()
          }

          else -> {
            // Default to string for unknown types
            paramSpec.schema = io.swagger.v3.oas.models.media.StringSchema()
          }
        }

        operationSpec.addParametersItem(paramSpec)
      }

      operationSpec.responses = ApiResponses()

      // Group responses by status code and content type
      val responsesByStatusAndContentType =
          meta.responses.groupBy(
              { resp ->
                resp.message.status.code.toString() to
                    (Header.CONTENT_TYPE(resp.message)?.withNoDirectives()?.value
                      ?: "application/json")
              },
              { resp -> resp },
          )

      // Process each group
      responsesByStatusAndContentType.forEach { (statusAndContentType, responses) ->
        val (statusCode, contentType) = statusAndContentType

        // Create a response for this status code
        val apiResponse =
            ApiResponse().apply {
              // Use the description from the first response (or combine them if needed)
              description = responses.mapNotNull { it.description }.firstOrNull() ?: ""

              // Create content with the appropriate media type
              content =
                  Content().apply {
                    // If there's only one response, use its schema directly
                    if (responses.size == 1) {
                      val resp = responses.first()
                      addMediaType(
                          contentType,
                          MediaType().also { mediaType ->
                            // Use the schema generator to create a schema from the example
                            // object
                            if (resp.example != null) {
                              val example =
                                  resp.example!! // Use !! to make the type non-nullable
                              val serializer: KSerializer<Any> =
                                  json.serializersModule.serializer(example::class.java)
                              mediaType.schema = schemaGenerator.generateSchema(serializer)

                              // Set description if available
                              mediaType.schema.description = resp.description

                              // Example must be set after schema
                              mediaType.example = json.encodeToJsonElement(serializer, example)
                            } else {
                              // Fallback to a basic schema if no example is provided
                              mediaType.schema =
                                  JsonSchema().also { jsonSchema ->
                                    jsonSchema.description = resp.description
                                    jsonSchema.type = "object"
                                    jsonSchema.`$schema` = OPENAPI_JSON_SCHEMA
                                  }
                            }
                          },
                      )
                    } else {
                      // TODO Should group by content type. If several apply to the same content type, use oneOf.

                      // For polymorphic responses, reference the base class directly
                      addMediaType(
                          contentType,
                          MediaType().also { mediaType ->
                            // Process all examples to ensure schemas are registered
                            var baseClassName: String? = null

                            // First, process all examples to register their schemas
                            responses.forEach { resp ->
                              if (resp.example != null) {
                                val example = resp.example!!
                                val serializer: KSerializer<Any> =
                                    json.serializersModule.serializer(example::class.java)

                                // Generate the schema to ensure it's registered in components
                                schemaGenerator.generateSchema(serializer)

                                // For polymorphic types, we want to reference the base class
                                // Get the base class name (Pet in this case)
                                if (baseClassName == null) {
                                  baseClassName = example::class.java.superclass.simpleName

                                  // Also generate the schema for the base class to ensure it's
                                  // registered in components
                                  val baseClass = example::class.java.superclass
                                  println(baseClass.name)
                                  val baseSerializer: KSerializer<Any> =
                                      json.serializersModule.serializer(baseClass)
                                  schemaGenerator.generateSchema(baseSerializer)
                                }
                              }
                            }

                            // Create a reference to the base class schema
                            if (baseClassName != null) {
                              mediaType.schema =
                                  Schema<Any>().apply {
                                    `$ref` = "#/components/schemas/$baseClassName"
                                  }
                            }
                          },
                      )
                    }
                  }
            }

        // Add the response to the operation
        operationSpec.responses.addApiResponse(statusCode, apiResponse)
      }

      operationSpec.requestBody =
          RequestBody().apply {
            this.required = meta.requests.isNotEmpty() // TODO probably wrong
            this.description = meta.requests.mapNotNull { it.description }.joinToString("\n")

            this.content =
                Content().also { content ->
                  meta.requests.forEach { req ->
                    val contentType =
                        Header.CONTENT_TYPE(req.message)?.withNoDirectives()?.value
                          ?: "application/json"

                    content.addMediaType(
                        contentType,
                        MediaType().also { mediaType ->
                          // Use the schema generator to create a schema from the example object
                          if (req.example != null) {
                            val example = req.example!! // Use !! to make the type non-nullable
                            val serializer: KSerializer<Any> =
                                json.serializersModule.serializer(example::class.java)
                            mediaType.schema = schemaGenerator.generateSchema(serializer)

                            // Set description if available
                            mediaType.schema.description = req.description

                            // Example must be set after schema
                            mediaType.example = json.encodeToJsonElement(serializer, example)
                          } else {
                            // Fallback to a basic schema if no example is provided
                            mediaType.schema =
                                JsonSchema().also { jsonSchema ->
                                  jsonSchema.description = req.description
                                  jsonSchema.type = "object"
                                  jsonSchema.`$schema` = OPENAPI_JSON_SCHEMA
                                }
                          }
                        },
                    )
                  }
                }
          }

      operationSpec.security =
          listOfNotNull(meta.security ?: security).map { security ->
            SecurityRequirement().apply {
              // Add a name that matches #security and a list of scopes if oauth2 or
              // openIdConnect
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

        else -> {
          // Only add request body if there are requests defined
          if (meta.requests.isNotEmpty()) {
            operationSpec.requestBody =
                RequestBody().also { bodySpec ->
                  bodySpec.required = true
                  bodySpec.description =
                      meta.requests.mapNotNull { it.description }.joinToString("\n")

                  // Content is already set in the previous section, no need to set it again
                }
          }
        }
      }

      // http4k tags by contractRoot.toString if tags are empty.
      meta.tags.forEach { tag ->
        // TODO clean duplicate tags
        val tagSpec =
            io.swagger.v3.oas.models.tags.Tag().apply {
              this.name = tag.name
              this.description = tag.description
            }
        schema.addTagsItem(tagSpec)
        operationSpec.addTagsItem(tagSpec.name)
      }

      schema.path(path, pathSpec)
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
