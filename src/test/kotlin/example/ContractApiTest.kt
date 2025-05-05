package example

import com.jayway.jsonpath.Criteria
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.Filter
import com.jayway.jsonpath.JsonPath
import io.swagger.v3.oas.models.Components
import java.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import no.krex.http4k.api.openapi.kotlinxserialization.KotlinxSerializationOpenApi3
import org.http4k.contract.contract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.ContentType.Companion.APPLICATION_XML
import org.http4k.core.ContentType.Companion.TEXT_PLAIN
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.KotlinxSerialization
import org.http4k.lens.Cookies
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.boolean
import org.http4k.lens.contentType
import org.http4k.lens.int
import org.http4k.lens.string
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.security.ApiKeySecurity
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull

class ContractApiTest {

  companion object {
    /** The unit under test */
    lateinit var schemaString: String
    lateinit var schema: DocumentContext

    @JvmStatic
    @BeforeAll
    fun setUp() {
      val server = routes("/" bind contractRoute)
      val schemaResponse = server(Request(Method.GET, "/openapi.json"))
      schemaString = schemaResponse.bodyString()
      schema = JsonPath.parse(schemaString)
    }
  }

  @Test
  fun `should generate openApi schema for http4k api`() {
    // Selfie.expectSelfie(schema.bodyString()).toMatchDisk_TODO()
    println(schemaString)
  }

  @Test
  fun `should make components for pet, cat and dog`() {
    // Verify the components schemas
    assertNotNull(schema.read<Components>("$.components.schemas.Pet"))
    assertNotNull(schema.read<Components>("$.components.schemas.Cat"))
    assertNotNull(schema.read<Components>("$.components.schemas.Dog"))
  }

  @Test
  fun `should either contain pet or anyof cat and dog in response`() {
    // Get the schema for the polymorphic route response
    val responseSchema =
        schema.read<Map<*, *>>(
            "$.paths['/polymorphic'].get.responses['200'].content['application/json'].schema")

    // Fixme clean up this assertion. Not sure if it works.
    // Assert that the response schema contains either a $ref to Pet or oneOf with Cat and Dog
    assertTrue(
        responseSchema.containsKey("\$ref") || // Direct Pet reference
            (responseSchema.containsKey("oneOf") && // oneOf with Cat and Dog
                (responseSchema["oneOf"] as List<*>)
                    .map { (it as Map<*, *>)["\$ref"] }
                    .containsAll(listOf("#/components/schemas/Cat", "#/components/schemas/Dog"))),
    )
  }

  @Test
  fun `should add parameters for path params`() {
    // Verify the paths
    assertNotNull(
        schema
            .read<List<*>>(
                "$.paths['/path/{pathPart}/subPath/then/{secondPathPart}'].post.parameters[?]",
                Filter.filter(Criteria.where("name").eq("pathPart").and("in").eq("path")),
            )
            .first(),
    )
    assertNotNull(
        schema
            .read<List<*>>(
                "$.paths['/path/{pathPart}/subPath/then/{secondPathPart}'].post.parameters[?]",
                Filter.filter(Criteria.where("name").eq("secondPathPart").and("in").eq("path")),
            )
            .first(),
    )
  }

  @Test
  fun `should add parameters for query params`() {
    // Verify the queries path exists
    assertNotNull(schema.read<Any>("$.paths['/queries'].get"))

    // Verify the query parameters
    assertNotNull(
        schema.read<Any>(
            "$.paths['/queries'].get.parameters[?]",
            Filter.filter(Criteria.where("name").eq("name").and("in").eq("query")),
        ),
    )
    assertNotNull(
        schema.read<Any>(
            "$.paths['/queries'].get.parameters[?]",
            Filter.filter(Criteria.where("name").eq("id").and("in").eq("query")),
        ),
    )
    assertNotNull(
        schema.read<Any>(
            "$.paths['/queries'].get.parameters[?]",
            Filter.filter(Criteria.where("name").eq("defaulted").and("in").eq("query")),
        ),
    )
    assertNotNull(
        schema.read<Any>(
            "$.paths['/queries'].get.parameters[?]",
            Filter.filter(Criteria.where("name").eq("optional").and("in").eq("query")),
        ),
    )
  }

  @Test
  fun `should `() {
    // Verify the simple path exists
    assertNotNull(schema.read<Any>("$.paths['/simple'].get"))

    // Verify the person route with query parameters
    assertNotNull(schema.read<Any>("$.paths['/person'].get"))
    assertNotNull(
        schema.read<Any>(
            "$.paths['/person'].get.parameters[?]",
            Filter.filter(Criteria.where("name").eq("name").and("in").eq("query")),
        ),
    )
    assertNotNull(
        schema.read<Any>(
            "$.paths['/person'].get.parameters[?]",
            Filter.filter(Criteria.where("name").eq("id").and("in").eq("query")),
        ),
    )
    // assertNotNull(schema.read<Any>("$.paths['/team'].put"))
    // assertNotNull(schema.read<Any>("$.paths['/resource/{resourceId}'].delete"))
    // assertNotNull(schema.read<Any>("$.paths['/data-types'].get"))
    // assertNotNull(schema.read<Any>("$.paths['/optional-data'].get"))
  }
}

val contractRoute: RoutingHttpHandler = contract {
  //    renderer = OpenApi3(ApiInfo("My great API", "v1.0"), KotlinxSerialization)
  renderer = KotlinxSerializationOpenApi3(json)
  descriptionPath = "/openapi.json"
  security = ApiKeySecurity(Query.required("api_key"), { it.isNotEmpty() })

  // Simple route
  routes +=
      "simple" meta
          {
            summary = "Simple route"
            receiving(MyRequest.bodyLens to MyRequest("Example name"))
            returning(
                Status.OK,
                MyRequest.bodyLens to MyRequest("Simple response"),
            )
          } bindContract
          Method.POST to
          { req: Request ->
            Response(Status.OK)
          }

  // Sealed class polymorphic
  routes +=
      "polymorphic" meta
          {
            // fixme: Will this have two examples? Or is the example attached to the schema?

            // The contract will not store the lens and its type, only the response where the lens
            // has been applied with the example.
            // Only the example is kept and can generate the json schema.
            // We can not actually get back the Pet serializer or know which serializer was used in
            // the lens.
            // We can only get back the finished json from the lens, or ask Json for the serializer
            // of the example classes.
            // Information is thus lost, by applying the lens and not keeping it nor the
            // serializer/mapping available.
            // One option is to create a Request lens that returns a [ResponseWithContext] and keep
            // the extra serializer info inside the context.
            returning(
                Status.OK,
                KotlinxSerialization.autoBody<Pet>().toLens() to
                    Cat("Fluffy", Cat.HuntingSkill.ADVENTUROUS),
            )
            returning(
                Status.OK,
                KotlinxSerialization.autoBody<Pet>().toLens() to Dog("Rover", 5),
            )
          } bindContract
          Method.GET to
          { req: Request ->
            Response(Status.OK)
          }

  // TODO open polymorphic

  // Different media types route
  routes +=
      "mediaTypes" meta
          {
            summary = "Endpoint returning different media types"

            returning("text" to Response(Status.OK).contentType(TEXT_PLAIN).body("text"))
            returning(
                "json" to
                    Response(Status.OK)
                        .contentType(APPLICATION_JSON)
                        .body("""{"hello": "world"}"""))
            returning(
                "xml" to
                    Response(Status.OK)
                        .contentType(APPLICATION_XML)
                        .body("<greeting>Hello XML</greeting>"))
          } bindContract
          Method.GET to
          { req: Request ->
            Response(Status.OK)
          }

  routes +=
      "path" / Path.of("pathPart") / "subPath" / "then" / Path.of("secondPathPart") meta
          {
            summary = "Post with two url paths"
            receiving(MyRequest.bodyLens to MyRequest("Example name"))
          } bindContract
          Method.POST to
          { _, _, _, _ ->
            { req: Request -> Response(Status.OK) }
          }

  // Route with query parameters
  routes +=
      "queries" meta
          {
            summary = "Get person by name and id"
            queries += Query.string().required("name")
            queries += Query.int().required("id")
            queries += Query.int().defaulted("defaulted", 123)
            queries += Query.boolean().optional("optional")
          } bindContract
          Method.GET to
          { req: Request ->
            Response(Status.OK)
          }

  routes +=
      "headerAndCookie" meta
          {
            summary = "Reading from headers"
            headers += Query.string().required("name")
            cookies += Cookies.required("myCookie")
          } bindContract
          Method.PUT to
          { req: Request ->
            Response(Status.OK)
          }

  // Route with DELETE method returning different status codes
  routes +=
      "resource" / Path.of("resourceId") meta
          {
            summary = "Delete a resource by ID"
            returning(Status.OK)
            returning(Status.NOT_FOUND)
            returning(Status.FORBIDDEN)
          } bindContract
          Method.DELETE to
          { resourceId ->
            { req: Request -> Response(Status.OK) }
          }

  // Route returning a collection of DataTypes
  routes +=
      "datatypes" meta
          {
            summary = "Get various data types"
            returning(
                Status.OK,
                DataTypesListLens.bodyLens to
                    DataTypesList(
                        items =
                            listOf(
                                DataTypes(
                                    stringValue = "Example String",
                                    intValue = 42,
                                    longValue = 1234567890L,
                                    doubleValue = 3.14159,
                                    booleanValue = true,
                                    floatValue = 2.71828f,
                                    instantValue = Instant.parse("2023-01-01T00:00:00Z"),
                                ),
                                DataTypes(
                                    stringValue = "Another String",
                                    intValue = 100,
                                    longValue = 9876543210L,
                                    doubleValue = 1.61803,
                                    booleanValue = false,
                                    floatValue = 1.41421f,
                                    instantValue = Instant.parse("2024-01-01T14:00:00Z"),
                                ),
                            ),
                    ),
            )
          } bindContract
          Method.GET to
          { req: Request ->
            Response(Status.OK)
          }

  // Route returning OptionalData
  routes +=
      "optionalData" meta
          {
            summary = "Get data with optional fields"
            returning(
                Status.OK,
                OptionalDataLens.bodyLens to
                    OptionalData(
                        requiredField = "This field is required",
                        requiredNullable = null,
                        optionalString = "This is optional",
                        optionalInt = 42,
                        optionalList = listOf("Item 1", "Item 2"),
                    ),
            )
            returning(
                Status.OK,
                OptionalDataLens.bodyLens to
                    OptionalData(
                        requiredField = "Only required field",
                        requiredNullable = null,
                    ),
            )
          } bindContract
          Method.GET to
          { req: Request ->
            Response(Status.OK)
          }
}

val json = Json {}

@Serializable
data class MyRequest(val name: String) {
  companion object {
    val bodyLens = KotlinxSerialization.autoBody<MyRequest>().toLens()
  }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("petType")
sealed class Pet {
  abstract val name: String
}

@Serializable
@SerialName("Cat")
data class Cat(
    override val name: String,
    val huntingSkill: HuntingSkill,
) : Pet() {
  @Serializable
  enum class HuntingSkill {
    CLUELESS,
    LAZY,
    ADVENTUROUS,
    AGGRESSIVE
  }
}

@Serializable
@SerialName("Dog")
data class Dog(override val name: String, val packSize: Int) : Pet()

// Class with nested objects
@Serializable
data class Address(val street: String, val city: String, val zipCode: String, val country: String)

@Serializable
data class Person(val id: Int, val name: String, val address: Address, val email: String)

// Class with collections
@Serializable
data class Team(
    val name: String,
    val members: List<String>,
    val roles: Map<String, String>,
    val scores: List<Int>
)

// Wrapper class for a list of DataTypes
@Serializable data class DataTypesList(val items: List<DataTypes>)

// Class with different primitive data types
@Serializable
data class DataTypes(
    val stringValue: String,
    val intValue: Int,
    val longValue: Long,
    val doubleValue: Double,
    val booleanValue: Boolean,
    val floatValue: Float,
    @Serializable(with = InstantSerializer::class) val instantValue: Instant
)

object InstantSerializer : KSerializer<Instant> {
  override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor("instant", PrimitiveKind.STRING)

  override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Instant) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Instant {
    return Instant.parse(decoder.decodeString())
  }
}

// Class with optional/nullable fields
@Serializable
data class OptionalData(
    val requiredField: String,
    val requiredNullable: String?,
    val optionalString: String? = null,
    val optionalInt: Int? = null,
    val optionalList: List<String>? = null
)

// Companion objects for lens creation
object PersonLens {
  val bodyLens = KotlinxSerialization.autoBody<Person>().toLens()
}

object TeamLens {
  val bodyLens = KotlinxSerialization.autoBody<Team>().toLens()
}

object DataTypesLens {
  val bodyLens = KotlinxSerialization.autoBody<DataTypes>().toLens()
}

object DataTypesListLens {
  val bodyLens = KotlinxSerialization.autoBody<DataTypesList>().toLens()
}

object OptionalDataLens {
  val bodyLens = KotlinxSerialization.autoBody<OptionalData>().toLens()
}
