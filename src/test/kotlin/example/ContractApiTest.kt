package example

import com.jayway.jsonpath.Criteria
import com.jayway.jsonpath.Filter
import com.jayway.jsonpath.JsonPath
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.parameters.Parameter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import no.krex.http4k.api.openapi.kotlinxserialization.KotlinxSerializationOpenApi3
import org.http4k.contract.contract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.KotlinxSerialization
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.security.ApiKeySecurity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.util.LinkedHashMap

class ContractApiTest {

  @Test
  fun `should generate openApi schema for http4k api`() {
    // Given

    val server = routes("/" bind contractRoute)

    // When
    val schemaResponse = server(Request(Method.GET, "/openapi.json"))

    // Then
    // Selfie.expectSelfie(schema.bodyString()).toMatchDisk_TODO()
    val schemaString = schemaResponse.bodyString()
    println(schemaString)

    val schema = JsonPath.parse(schemaString)

    // Verify the paths
    assertNotNull(
        schema.read<List<*>>("$.paths['/path/{pathPart}/subPath/then/{secondPathPart}'].post.parameters[?]",
            Filter.filter(Criteria.where("name").eq("pathPart").and("in").eq("path")))
            .first()
    )
    assertNotNull(
        schema.read<List<*>>("$.paths['/path/{pathPart}/subPath/then/{secondPathPart}'].post.parameters[?]",
            Filter.filter(Criteria.where("name").eq("secondPathPart").and("in").eq("path")))
            .first()
    )

    // Verify the components schemas
    assertNotNull(schema.read<Components>("$.components.schemas.Pet"))
    assertNotNull(schema.read<Components>("$.components.schemas.Cat"))
    assertNotNull(schema.read<Components>("$.components.schemas.Dog"))
  }

  val contractRoute: RoutingHttpHandler = contract {
    //    renderer = OpenApi3(ApiInfo("My great API", "v1.0"), KotlinxSerialization)
    renderer = KotlinxSerializationOpenApi3(json)
    descriptionPath = "/openapi.json"
    security = ApiKeySecurity(Query.required("api_key"), { it.isNotEmpty() })

    routes +=
        "polymorphic" meta
            {
              // todo get needs to read from query params instead of body
              receiving(MyRequest.bodyLens to MyRequest("Example name"))
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

    routes +=
        "path" / Path.of("pathPart") / "subPath" / "then" / Path.of("secondPathPart") meta
            {
              summary = "Post with two url paths"
              receiving(MyRequest.bodyLens to MyRequest("Example name"))
            } bindContract
            Method.POST to
            { _, _, _, _ ->
              { req: Request ->
                Response(Status.OK)
              }
            }
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
