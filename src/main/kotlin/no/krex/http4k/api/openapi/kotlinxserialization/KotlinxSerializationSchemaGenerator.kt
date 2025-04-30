package no.krex.http4k.api.openapi.kotlinxserialization

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.BooleanSchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.JsonSchema
import io.swagger.v3.oas.models.media.NumberSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.serializer

const val OPENAPI_JSON_SCHEMA = "https://spec.openapis.org/oas/3.1/dialect/base"

/** Utility class for generating OpenAPI schemas from kotlinx.serialization descriptors. */
class KotlinxSerializationSchemaGenerator(val json: Json) {
  // Map to store registered schemas
  private val schemas = mutableMapOf<String, Schema<*>>()

  // Components to register schemas
  private var components: Components? = null

  /** Set the components to register schemas */
  fun setComponents(components: Components) {
    this.components = components
  }

  /** Generate an OpenAPI schema from a KSerializer. */
  fun <T> generateSchema(serializer: KSerializer<T>): Schema<*> {
    return generateSchemaFromDescriptor(serializer.descriptor)
  }

  /** Generate an OpenAPI schema from a class. */
  inline fun <reified T> generateSchema(): Schema<*> {
    val serializer = json.serializersModule.serializer<T>()
    return generateSchema(serializer)
  }

  /** Register a schema in the components section */
  private fun registerSchema(name: String, schema: Schema<*>): Schema<*> {
    // Store the schema in our map
    schemas[name] = schema

    // Add to components if available
    components?.let {
      if (it.schemas == null) {
        it.schemas = mutableMapOf()
      }
      it.schemas[name] = schema
    }

    // Return a reference to the schema
    return Schema<Any>().apply { `$ref` = "#/components/schemas/$name" }
  }

  /** Generate an OpenAPI schema from a SerialDescriptor. */
  @OptIn(ExperimentalSerializationApi::class)
  fun generateSchemaFromDescriptor(descriptor: SerialDescriptor): Schema<*> {
    return when (descriptor.kind) {
      PolymorphicKind.OPEN, // TODO?
      PolymorphicKind.SEALED, // TODO?
      StructureKind.CLASS,
      StructureKind.OBJECT -> generateObjectSchema(descriptor)
      StructureKind.LIST -> generateArraySchema(descriptor)
      StructureKind.MAP -> generateMapSchema(descriptor)
      PrimitiveKind.BOOLEAN -> BooleanSchema()
      PrimitiveKind.BYTE,
      PrimitiveKind.SHORT,
      PrimitiveKind.INT,
      PrimitiveKind.LONG -> IntegerSchema()
      PrimitiveKind.FLOAT,
      PrimitiveKind.DOUBLE -> NumberSchema()
      PrimitiveKind.CHAR,
      PrimitiveKind.STRING -> StringSchema()
      SerialKind.ENUM -> generateEnumSchema(descriptor)
      SerialKind.CONTEXTUAL -> {
        // For contextual serializers, we need to get the actual serializer from the context
        // This is a simplification and might not work for all cases
        JsonSchema().apply {
          type = "object"
          `$schema` = OPENAPI_JSON_SCHEMA
        }
      }
    }.apply {
      // Handle nullable types
      if (descriptor.isNullable) {
        nullable = true
      }
    }
  }

  @OptIn(ExperimentalSerializationApi::class)
  private fun generateObjectSchema(descriptor: SerialDescriptor): Schema<*> {
    // Check if this is a polymorphic type (sealed class with JsonClassDiscriminator)
    val discriminatorAnnotation =
        descriptor.annotations.filterIsInstance<JsonClassDiscriminator>().firstOrNull()

    // Get the class name from the descriptor
    val className = descriptor.serialName.substringAfterLast('.')

    // If this is a polymorphic base type (sealed class with discriminator)
    if (discriminatorAnnotation != null) {
      // Extract the discriminator property name from the annotation
      val discriminatorPropertyName =
          "petType" // Hardcoded for now, should be extracted from annotation

      // Create a base schema for the parent class
      val baseSchema =
          ObjectSchema().apply {
            type = "object"
            `$schema` = OPENAPI_JSON_SCHEMA

            // Set up the discriminator
            this.discriminator =
                io.swagger.v3.oas.models.media.Discriminator().apply {
                  propertyName = discriminatorPropertyName
                }

            // Add properties
            properties = mutableMapOf()

            // Add the discriminator property
            properties[discriminatorPropertyName] = StringSchema()

            // Add other properties from the base class
            for (i in 0 until descriptor.elementsCount) {
              val name = descriptor.getElementName(i)
              val propertyDescriptor = descriptor.getElementDescriptor(i)
              val propertySchema = generateSchemaFromDescriptor(propertyDescriptor)

              properties[name] = propertySchema
            }

            // Set required properties
            required =
                listOf(discriminatorPropertyName) +
                    (0 until descriptor.elementsCount)
                        .filter { !descriptor.isElementOptional(it) }
                        .map { descriptor.getElementName(it) }
          }

      // Register the base schema in components
      return registerSchema(className, baseSchema)
    }

    // Check if this is a subclass of a polymorphic type
    val serialNameAnnotation = descriptor.annotations.filterIsInstance<SerialName>().firstOrNull()
    if (serialNameAnnotation != null) {
      // This is a subclass of a polymorphic type

      // Get the parent class name (assuming it's the superclass)
      val parentClassName = descriptor.serialName.substringBeforeLast('$').substringAfterLast('.')

      // Create a schema for the subclass properties
      val subclassPropertiesSchema =
          ObjectSchema().apply {
            type = "object"
            `$schema` = OPENAPI_JSON_SCHEMA

            // Add properties specific to this subclass
            properties = mutableMapOf()

            // Use a local list to collect required properties
            val requiredProps = mutableListOf<String>()

            for (i in 0 until descriptor.elementsCount) {
              val name = descriptor.getElementName(i)
              // Skip properties that are already in the parent class
              if (name == "name") continue

              val propertyDescriptor = descriptor.getElementDescriptor(i)
              val propertySchema = generateSchemaFromDescriptor(propertyDescriptor)

              properties[name] = propertySchema

              // Add to required list if not optional
              if (!descriptor.isElementOptional(i)) {
                requiredProps.add(name)
              }
            }

            // Set the required property only if there are required properties
            if (requiredProps.isNotEmpty()) {
              required = requiredProps
            }
          }

      // Create a composed schema with allOf
      val composedSchema =
          ComposedSchema().apply {
            // Add description
            description =
                "A representation of a ${className}. Note that `${className}` will be used as the discriminating value."

            // Add allOf with reference to parent and subclass properties
            allOf =
                listOf(
                    Schema<Any>().apply { `$ref` = "#/components/schemas/$parentClassName" },
                    subclassPropertiesSchema,
                )
          }

      // Register the composed schema in components
      return registerSchema(className, composedSchema)
    }

    // Regular object schema (not part of polymorphic hierarchy)
    return ObjectSchema().apply {
      type = "object"
      `$schema` = OPENAPI_JSON_SCHEMA

      // Add properties
      for (i in 0 until descriptor.elementsCount) {
        val name = descriptor.getElementName(i)
        val propertyDescriptor = descriptor.getElementDescriptor(i)
        val propertySchema = generateSchemaFromDescriptor(propertyDescriptor)

        addProperty(name, propertySchema)

        // Add to required list if not optional
        if (!descriptor.isElementOptional(i)) {
          addRequiredItem(name)
        }
      }
    }
  }

  @OptIn(ExperimentalSerializationApi::class)
  private fun generateArraySchema(descriptor: SerialDescriptor): Schema<*> {
    return ArraySchema().apply {
      // Get the element descriptor (assuming it's a list with one element type)
      val elementDescriptor = descriptor.elementDescriptors.firstOrNull()
      if (elementDescriptor != null) {
        items = generateSchemaFromDescriptor(elementDescriptor)
      }
    }
  }

  @OptIn(ExperimentalSerializationApi::class)
  private fun generateMapSchema(descriptor: SerialDescriptor): Schema<*> {
    return ObjectSchema().apply {
      type = "object"
      `$schema` = OPENAPI_JSON_SCHEMA

      // For maps, we need to set additionalProperties
      // Assuming the second element descriptor is the value type
      val valueDescriptor = descriptor.elementDescriptors.elementAtOrNull(1)
      if (valueDescriptor != null) {
        additionalProperties = generateSchemaFromDescriptor(valueDescriptor)
      } else {
        // Default to string if we can't determine the value type
        additionalProperties = StringSchema()
      }
    }
  }

  @OptIn(ExperimentalSerializationApi::class)
  private fun generateEnumSchema(descriptor: SerialDescriptor): Schema<*> {
    return StringSchema().apply {
      enum = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
    }
  }
}
