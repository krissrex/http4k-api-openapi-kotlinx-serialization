package example

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import no.krex.http4k.api.openapi.kotlinxserialization.KotlinxSerializationOpenApi3
import org.http4k.contract.contract
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.KotlinxSerialization
import org.http4k.lens.Query
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.security.ApiKeySecurity
import org.junit.jupiter.api.Test

class ContractApiTest {

  @Test
  fun `should generate openApi schema for http4k api`() {
    // Given

    val server = routes("/" bind contractRoute)

    // When
    val schema = server(Request(Method.GET, "/openapi.json"))

    // Then
    //Selfie.expectSelfie(schema.bodyString()).toMatchDisk_TODO()
    println(schema.bodyString())
  }

  val contractRoute: RoutingHttpHandler = contract {
//    renderer = OpenApi3(ApiInfo("My great API", "v1.0"), KotlinxSerialization)
    renderer = KotlinxSerializationOpenApi3(json)
    descriptionPath = "/openapi.json"
    security = ApiKeySecurity(Query.required("api_key"), { it.isNotEmpty() })

    routes +=
        "polymorphic" meta {
          receiving(MyRequest.bodyLens to MyRequest("Example name"))
          returning(
              Status.OK,
              KotlinxSerialization.autoBody<Cat>().toLens() to
                  Cat("Fluffy", Cat.HuntingSkill.ADVENTUROUS),
          )
          returning(
              Status.OK,
              KotlinxSerialization.autoBody<Dog>().toLens() to
                  Dog("Rover", 5),
          )

        } bindContract Method.GET to { req: Request -> Response(Status.OK) }
  }
}

val json = Json { }

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
