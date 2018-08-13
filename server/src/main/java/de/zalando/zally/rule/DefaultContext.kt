package de.zalando.zally.rule

import com.fasterxml.jackson.core.JsonPointer
import de.zalando.zally.rule.ContentParseResult.NotApplicable
import de.zalando.zally.rule.ContentParseResult.ParsedWithErrors
import de.zalando.zally.rule.ContentParseResult.Success
import de.zalando.zally.rule.api.Context
import de.zalando.zally.rule.api.Violation
import de.zalando.zally.util.ast.JsonPointers
import de.zalando.zally.util.ast.MethodCallRecorder
import de.zalando.zally.util.ast.ReverseAst
import io.swagger.models.Info
import io.swagger.models.Swagger
import io.swagger.models.auth.OAuth2Definition
import io.swagger.parser.SwaggerParser
import io.swagger.parser.util.SwaggerDeserializationResult
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.converter.SwaggerConverter
import io.swagger.v3.parser.core.models.ParseOptions
import io.swagger.v3.parser.core.models.SwaggerParseResult
import io.swagger.v3.parser.util.ResolverFully
import org.slf4j.LoggerFactory

class DefaultContext(
    override val source: String,
    openApi: OpenAPI,
    swagger: Swagger? = null
) : Context {
    private val recorder = MethodCallRecorder(openApi).skipMethods(*extensionNames)
    private val openApiAst = ReverseAst.fromObject(openApi).withExtensionMethodNames(*extensionNames).build()
    private val swaggerAst = swagger?.let { ReverseAst.fromObject(it).withExtensionMethodNames(*extensionNames).build() }

    override val api = recorder.proxy
    override fun isOpenAPI3(): Boolean = this.swaggerAst == null

    /**
     * Convenience method for filtering and iterating over the paths in order to create Violations.
     * @param pathFilter a filter selecting the paths to validate
     * @param action the action to perform on filtered items
     * @return a list of Violations and/or nulls where no violations are necessary
     */
    override fun validatePaths(
        pathFilter: (Map.Entry<String, PathItem>) -> Boolean,
        action: (Map.Entry<String, PathItem>) -> List<Violation?>
    ): List<Violation> = api.paths
        .orEmpty()
        .filter(pathFilter)
        .flatMap(action)
        .filterNotNull()

    /**
     * Convenience method for filtering and iterating over the operations in order to create Violations.
     * @param pathFilter a filter selecting the paths to validate
     * @param operationFilter a filter selecting the operations to validate
     * @param action the action to perform on filtered items
     * @return a list of Violations and/or nulls where no violations are necessary
     */
    override fun validateOperations(
        pathFilter: (Map.Entry<String, PathItem>) -> Boolean,
        operationFilter: (Map.Entry<HttpMethod, Operation>) -> Boolean,
        action: (Map.Entry<HttpMethod, Operation>) -> List<Violation?>
    ): List<Violation> = validatePaths(pathFilter) { (_, path) ->
        path.readOperationsMap()
            .orEmpty()
            .filter(operationFilter)
            .flatMap(action)
            .filterNotNull()
    }

    /**
     * Creates a List of one Violation with a pointer to the OpenAPI or Swagger model node specified,
     * defaulting to the last recorded location.
     * @param description the description of the Violation
     * @param value the OpenAPI or Swagger model node
     * @return the new Violation
     */
    override fun violations(description: String, value: Any): List<Violation> =
        listOf(violation(description, value))

    /**
     * Creates a List of one Violation with the specified pointer, defaulting to the last recorded location.
     * @param description the description of the Violation
     * @param pointer an existing pointer or null
     * @return the new Violation
     */
    override fun violations(description: String, pointer: JsonPointer?): List<Violation> =
        listOf(violation(description, pointer))

    /**
     * Creates a Violation with a pointer to the OpenAPI or Swagger model node specified,
     * defaulting to the last recorded location.
     * @param description the description of the Violation
     * @param value the OpenAPI or Swagger model node
     * @return the new Violation
     */
    override fun violation(description: String, value: Any): Violation =
        violation(description, pointerForValue(value))

    /**
     * Creates a Violation with the specified pointer, defaulting to the last recorded location.
     * @param description the description of the Violation
     * @param pointer an existing pointer or null
     * @return the new Violation
     */
    override fun violation(description: String, pointer: JsonPointer?): Violation =
        Violation(description, pointer ?: recorder.pointer)

    /**
     * Check whether a location should be ignored by a specific rule.
     * @param pointer the location to check
     * @param ruleId the rule id to check
     * @return true if the location should be ignored for this rule
     */
    override fun isIgnored(pointer: JsonPointer, ruleId: String): Boolean =
        swaggerAst?.isIgnored(pointer, ruleId) ?: openApiAst.isIgnored(pointer, ruleId)

    private fun pointerForValue(value: Any): JsonPointer? = if (swaggerAst != null) {
        val swaggerPointer = swaggerAst.getPointer(value)
        if (swaggerPointer != null)
            swaggerPointer
        else {
            // Attempt to convert an OpenAPI pointer to a Swagger pointer.
            val openApiPointer = openApiAst.getPointer(value)
            JsonPointers.convertPointer(openApiPointer) ?: openApiPointer
        }
    } else {
        openApiAst.getPointer(value)
    }

    companion object {

        private val log = LoggerFactory.getLogger(DefaultContext::class.java)
        private val extensionNames = arrayOf("getVendorExtensions", "getExtensions")

        fun createOpenApiContext(content: String): ContentParseResult<Context> =
            parseOpenApi(content)
                .flatMap { resolveOpenApi(it) }
                .map { DefaultContext(content, it.openAPI) }

        fun createSwaggerContext(content: String): ContentParseResult<Context> =
            parseSwagger(content).flatMap { parseResult ->
                convertSwaggerToOpenAPI(parseResult)
                    .flatMap { resolveSwagger(it) }
                    .map { convertResult ->
                        DefaultContext(content, convertResult.openAPI, parseResult.swagger)
                    }
            }

        private fun parseOpenApi(content: String): ContentParseResult<SwaggerParseResult> {
            val parseOptions = ParseOptions()
            parseOptions.isResolve = true
            // parseOptions.isResolveFully = true // https://github.com/swagger-api/swagger-parser/issues/682
            val parseResult = OpenAPIV3Parser().readContents(content, null, parseOptions)
            return if (parseResult.openAPI === null) {
                if (parseResult.messages.isEmpty() || parseResult.messages.contains("attribute openapi is missing")) {
                    NotApplicable()
                } else {
                    ParsedWithErrors(parseResult.messages.filterNotNull().map(::errorToViolation))
                }
            } else {
                Success(parseResult)
            }
        }

        private fun resolveOpenApi(parseResult: SwaggerParseResult): ContentParseResult<SwaggerParseResult> {
            try {
                ResolverFully(true).resolveFully(parseResult.openAPI) // workaround for NPE bug in swagger-parser
            } catch (e: NullPointerException) {
                log.warn("Failed to fully resolve OpenAPI schema.", e)
            }
            return Success(parseResult)
        }

        private fun parseSwagger(content: String): ContentParseResult<SwaggerDeserializationResult> {
            val parseResult = SwaggerParser().readWithInfo(content, true)
            val didParse = parseResult !== null
            val swaggerIsMissing = parseResult.messages.contains("attribute swagger is missing")
            return if (!didParse || swaggerIsMissing) {
                if (parseResult.messages.isEmpty() || swaggerIsMissing) {
                    NotApplicable()
                } else {
                    ParsedWithErrors(parseResult.messages.mapNotNull(::errorToViolation))
                }
            } else {
                Success(parseResult)
            }
        }

        /**
         * This serves two goals:
         * - Fixing the parsed Swagger object before automatic conversion to OpenAPI
         * - Detecting cases where a violation should be automatically returned in the result.
         */
        private fun preConvertChecks(swaggerDeserializationResult: SwaggerDeserializationResult): List<Violation> {
            val swagger = swaggerDeserializationResult.swagger
            val violations = mutableListOf<Violation>()

            // INFO
            if (swagger.info === null) {
                swagger.info = Info()
            }

            // OAUTH2 security definitions
            swagger.securityDefinitions.orEmpty().values
                .filter { it.type == "oauth2" }
                .map { it as OAuth2Definition }
                .forEach {
                    if (it.flow == null) {
                        it.flow = ""
                    }
                    if (it.scopes == null) {
                        it.scopes = LinkedHashMap()
                    }
                }

            return violations
        }

        private fun convertSwaggerToOpenAPI(parseResult: SwaggerDeserializationResult): ContentParseResult<SwaggerParseResult> {
            val preConvertViolations = preConvertChecks(parseResult)
            if (preConvertViolations.isNotEmpty()) {
                return ParsedWithErrors(preConvertViolations)
            }

            val convertResult = try {
                SwaggerConverter().convert(parseResult)
            } catch (t: Throwable) {
                log.warn("Unable to convert specification from 'Swagger 2' to 'OpenAPI 3'. Error not covered by pre-checks.", t)
                val violation = Violation("Unable to parse specification", JsonPointers.root)
                return ParsedWithErrors(listOf(violation))
            }
            return if (convertResult.openAPI === null) {
                if (convertResult.messages.orEmpty().isNotEmpty()) {
                    ParsedWithErrors(convertResult.messages.mapNotNull(::errorToViolation))
                } else {
                    log.warn("Unable to convert specification from 'Swagger 2' to 'OpenAPI 3'. No error specified, but 'openAPI' is null.")
                    val violation = Violation("Unable to parse specification", JsonPointers.root)
                    ParsedWithErrors(listOf(violation))
                }
            } else {
                Success(convertResult)
            }
        }

        private fun resolveSwagger(convertResult: SwaggerParseResult): ContentParseResult<SwaggerParseResult> {
            try {
                ResolverFully(true).resolveFully(convertResult.openAPI)
            } catch (e: NullPointerException) {
                log.warn("Failed to fully resolve Swagger schema.", e)
            }
            return Success(convertResult)
        }

        private fun errorToViolation(error: String): Violation =
            Violation(error, errorToJsonPointer(error))

        private val attributeIsMissingRegEx = Regex("attribute [^ ]* is missing")

        private fun errorToJsonPointer(error: String): JsonPointer {
            if (error.matches(attributeIsMissingRegEx)) {
                val words = error.split(' ')
                if (words.size > 1) {
                    val pathInError = words[1]
                    val pathParts = pathInError.split('.')
                    val pathWithoutLast = pathParts.take(pathParts.size - 1).joinToString("/")
                    return JsonPointer.compile("/$pathWithoutLast")
                }
            }
            return JsonPointers.root
        }
    }
}
