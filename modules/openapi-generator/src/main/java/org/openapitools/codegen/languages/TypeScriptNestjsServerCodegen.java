package org.openapitools.codegen.languages;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenConfig;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.CodegenSecurity;
import org.openapitools.codegen.DefaultCodegen;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.api.TemplatingExecutor;
import org.openapitools.codegen.meta.GeneratorMetadata;
import org.openapitools.codegen.meta.Stability;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.CamelizeOption;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.openapitools.codegen.utils.CamelizeOption.LOWERCASE_FIRST_CHAR;
import static org.openapitools.codegen.utils.CamelizeOption.LOWERCASE_FIRST_LETTER;
import static org.openapitools.codegen.utils.CamelizeOption.UPPERCASE_FIRST_CHAR;
import static org.openapitools.codegen.utils.StringUtils.camelize;
import static org.openapitools.codegen.utils.StringUtils.dashize;
import static org.openapitools.codegen.utils.StringUtils.underscore;

public class TypeScriptNestjsServerCodegen extends DefaultCodegen implements CodegenConfig {
    private final Logger LOGGER = LoggerFactory.getLogger(TypeScriptNestjsServerCodegen.class);

    // Opciones CLI específicas
    public static final String NEST_VERSION = "nestVersion";
    public static final String CONTROLLER_SUFFIX = "controllerSuffix";
    public static final String SERVICE_SUFFIX = "serviceSuffix";
    public static final String USE_VALIDATIONS = "useValidations";
    public static final String USE_SCOPEGUARD = "useScopeGuard";
    public static final String GENERATE_SERVICE_INTERFACE = "generateServiceInterface";
    public static final String NPM_REPOSITORY = "npmRepository";
    public static final String FILE_NAMING = "fileNaming";
    public static final String STRING_ENUMS = "stringEnums";
    public static final String USE_SINGLE_REQUEST_PARAMETER = "useSingleRequestParameter";

    // Valores por defecto
    protected String nestVersion = "8.0.0";
    protected String controllerSuffix = "Controller";
    protected String serviceSuffix = "Service";
    protected boolean useValidations = true;
    protected boolean useScopeGuard = true;
    protected boolean generateServiceInterface = true;
    protected String fileNaming = "camelCase";
    protected Boolean stringEnums = false;
    protected String modelSuffix = "";

    public TypeScriptNestjsServerCodegen() {
        super();

        generatorMetadata = GeneratorMetadata.newBuilder(generatorMetadata)
                .stability(Stability.EXPERIMENTAL)
                .build();
        outputFolder = "generated-code/typescript-nestjs-server";
        embeddedTemplateDir = templateDir = "typescript-nestjs-server";

        // Registrar plantillas para modelos, controladores y servicios
        modelTemplateFiles.put("model.mustache", ".ts");
        apiTemplateFiles.put("controller.mustache", "Controller.ts");

        supportingFiles.add(new SupportingFile("module.mustache", "", "api.module.ts"));
        supportingFiles.add(new SupportingFile("package.mustache", "", "package.json"));
        supportingFiles.add(new SupportingFile("tsconfig.json.mustache", "", "tsconfig.json"));
        supportingFiles.add(new SupportingFile("index.mustache", "", "index.ts"));
        supportingFiles.add(new SupportingFile("scopes.decorator.mustache", "decorators", "scopes.decorator.ts"));
        supportingFiles.add(new SupportingFile("scope.guard.mustache", "guards", "scope.guard.ts"));
        supportingFiles.add(new SupportingFile("snakeToCamel.pipe.mustache", "pipes", "SnakeToCamelPipe.ts"));
        supportingFiles.add(new SupportingFile("camelToSnake.pipe.mustache", "pipes", "CamelToSnakePipe.ts"));
        supportingFiles.add(new SupportingFile("customValidation.pipe.mustache", "pipes", "CustomValidationPipe.ts"));
        supportingFiles.add(new SupportingFile("npmignore.mustache", "", ".npmignore"));
        supportingFiles.add(new SupportingFile("provider.tokens.mustache", "constants", "provider.tokens.ts"));

        apiPackage = "controllers";
        modelPackage = "models";

        cliOptions.add(new CliOption(NEST_VERSION, "Version of NestJS to generate code for", nestVersion));
        cliOptions.add(new CliOption(CONTROLLER_SUFFIX, "Suffix for generated controllers", controllerSuffix));
        cliOptions.add(new CliOption(SERVICE_SUFFIX, "Suffix for generated services", serviceSuffix));
        cliOptions.add(CliOption.newBoolean(USE_VALIDATIONS, "Include model validations using class-validator", useValidations));
        cliOptions.add(CliOption.newBoolean(USE_SCOPEGUARD, "Use ScopeGuard decorators based on security definitions", useScopeGuard));
        cliOptions.add(CliOption.newBoolean(GENERATE_SERVICE_INTERFACE, "Generate service interface (contract) to be implemented externally", generateServiceInterface));
        cliOptions.add(new CliOption(NPM_REPOSITORY, "Set the URL of your private npm repository in package.json"));
        cliOptions.add(new CliOption(FILE_NAMING, "Naming convention for the output files: 'camelCase', 'kebab-case'.", fileNaming));
        cliOptions.add(new CliOption(STRING_ENUMS, "Generate string enums instead of objects for enum values.", String.valueOf(stringEnums)));
        cliOptions.add(new CliOption(USE_SINGLE_REQUEST_PARAMETER, "Generate functions with a single argument containing all API endpoint parameters instead of one argument per parameter.", "false"));

        reservedWords.addAll(Arrays.asList("from", "headers", "request", "response"));
    }

    private String getStringProperty(String key, String defaultValue) {
        return additionalProperties.containsKey(key)
                ? additionalProperties.get(key).toString()
                : defaultValue;
    }

    private boolean getBooleanProperty(String key, boolean defaultValue) {
        return additionalProperties.containsKey(key)
                ? Boolean.parseBoolean(additionalProperties.get(key).toString())
                : defaultValue;
    }

    private String getControllerAnnotation(CodegenParameter param) {
        String annotation = "";
        String pipes = getPipesForParam(param);
        if (param.isBodyParam) {
            annotation = "@Body() ";
        } else if (param.isPathParam) {
            annotation = "@Param('" + camelize(param.paramName, LOWERCASE_FIRST_CHAR) + "'" + pipes + ") ";
        } else if (param.isQueryParam) {
            annotation = "@Query('" + camelize(param.paramName, LOWERCASE_FIRST_CHAR) + "'" + pipes + ") ";
        } else if (param.isHeaderParam) {
            annotation = "@Headers('" + camelize(param.paramName, LOWERCASE_FIRST_CHAR) + "'" + pipes + ") ";
        }
        return annotation;
    }

    private void buildSignaturesForOperation(CodegenOperation op) {
        if (op.allParams != null && !op.allParams.isEmpty()) {
            StringBuilder controllerSignature = new StringBuilder();
            StringBuilder serviceSignature = new StringBuilder();
            StringBuilder argList = new StringBuilder();
            int paramCount = op.allParams.size();
            for (int i = 0; i < paramCount; i++) {
                CodegenParameter param = op.allParams.get(i);
                String annotation = getControllerAnnotation(param);
                controllerSignature.append(annotation)
                        .append(param.paramName)
                        .append(": ")
                        .append(param.dataType);
                serviceSignature.append(param.paramName)
                        .append(": ")
                        .append(param.dataType);
                argList.append(param.paramName);
                if (i < paramCount - 1) {
                    controllerSignature.append(", ");
                    serviceSignature.append(", ");
                    argList.append(", ");
                }
            }
            op.vendorExtensions.put("controllerSignatureList", controllerSignature.toString());
            op.vendorExtensions.put("serviceSignatureList", serviceSignature.toString());
            op.vendorExtensions.put("argList", argList.toString());
        } else {
            op.vendorExtensions.put("controllerSignatureList", "");
            op.vendorExtensions.put("serviceSignatureList", "");
            op.vendorExtensions.put("argList", "");
        }
    }

    private List<Map<String, Object>> generateEnumVars(List<Object> enumValues, String type) {
        List<Map<String, Object>> enumVars = new ArrayList<>();
        for (int i = 0; i < enumValues.size(); i++) {
            Object enumValue = enumValues.get(i);
            Map<String, Object> ev = new HashMap<>();
            ev.put("name", toEnumVarName(enumValue.toString(), type));
            ev.put("value", Boolean.TRUE.equals(stringEnums)
                    ? "\"" + enumValue.toString() + "\""
                    : enumValue.toString());
            ev.put("last", i == enumValues.size() - 1);
            enumVars.add(ev);
        }
        return enumVars;
    }

    private Optional<Map<String, String>> createImportForType(String type) {
        if (type == null) return Optional.empty();
        String typeName = type;
        if (typeName.endsWith("[]")) {
            typeName = typeName.substring(0, typeName.length() - 2);
        }
        if (needToImport(typeName)) {
            Map<String, String> imp = new HashMap<>();
            imp.put("classname", typeName);
            imp.put("importPath", "../models");
            imp.put("filename", toModelFilename(typeName));
            return Optional.of(imp);
        }
        return Optional.empty();
    }

    private String getPipesForParam(CodegenParameter param) {
        List<String> pipes = new ArrayList<>();

        if (param.isPathParam && param.dataFormat != null && param.dataFormat.equalsIgnoreCase("uuid")) {
            pipes.add("new ParseUUIDPipe()");
            registerPipeImport("ParseUUIDPipe", "@nestjs/common");
        }
        if (param.isQueryParam && "number".equalsIgnoreCase(param.dataType)) {
            pipes.add("new ParseIntPipe()");
            registerPipeImport("ParseIntPipe", "@nestjs/common");

        }
        if (param.vendorExtensions.containsKey("customPipes")) {
            Object custom = param.vendorExtensions.get("customPipes");
            if (custom instanceof List<?>) {
                for (Object o : (List<?>) custom) {
                    if (o != null) {
                        pipes.add(o.toString());
                    }
                }
            }
        }
        if (pipes.isEmpty()) {
            return "";
        }
        return ", " + String.join(", ", pipes);
    }

    private void registerPipeImport(String pipeName, String importPath) {
        // Obtén o crea la lista de importaciones para pipes en additionalProperties
        List<Map<String, String>> pipeImports = (List<Map<String, String>>) additionalProperties.get("pipeImports");
        if (pipeImports == null) {
            pipeImports = new ArrayList<>();
            additionalProperties.put("pipeImports", pipeImports);
        }
        // Agrega la importación solo si no se agregó previamente
        boolean exists = pipeImports.stream().anyMatch(entry -> pipeName.equals(entry.get("classname")));
        if (!exists) {
            Map<String, String> entry = new HashMap<>();
            entry.put("classname", pipeName);
            entry.put("importPath", importPath);
            pipeImports.add(entry);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Métodos sobrescritos y lógicos de generación
    // ────────────────────────────────────────────────────────────────

    @Override
    public String escapeReservedWord(String name) {
        if ("response".equals(name)) {
            return "_" + name;
        }
        return super.escapeReservedWord(name);
    }

    protected String getIndexDirectory() {
        return "src";
    }

    private String convertUsingFileNamingConvention(String originalName) {
        String name = removeModelPrefixSuffix(originalName);
        if ("kebab-case".equals(fileNaming)) {
            name = dashize(underscore(name));
        } else {
            name = camelize(name, LOWERCASE_FIRST_LETTER);
        }
        return name;
    }

    public String removeModelPrefixSuffix(String name) {
        String result = name;
        if (modelSuffix.length() > 0 && result.endsWith(modelSuffix)) {
            result = result.substring(0, result.length() - modelSuffix.length());
        }
        String prefix = capitalize(this.modelNamePrefix);
        String suffix = capitalize(this.modelNameSuffix);
        if (prefix.length() > 0 && result.startsWith(prefix)) {
            result = result.substring(prefix.length());
        }
        if (suffix.length() > 0 && result.endsWith(suffix)) {
            result = result.substring(0, result.length() - suffix.length());
        }
        return result;
    }

    @Override
    public String getName() {
        return "typescript-nestjs-server";
    }

    @Override
    public String getHelp() {
        return "Generates a NestJS server with support for model validations, scope guard security, and separated service interfaces.";
    }

    @Override
    public String toVarName(String name) {
        name = sanitizeName(name).replaceAll("-", "_");
        if (name.matches("^[A-Z_]*$"))
            return name;
        name = camelize(name, LOWERCASE_FIRST_LETTER);
        if (isReservedWord(name) || name.matches("^\\d.*"))
            name = escapeReservedWord(name);
        return name;
    }

    @Override
    public String toModelName(String name) {
        if (name == null || name.isEmpty()) {
            return "DefaultModel";
        }
        if (!name.contains("_") && !name.contains("-") && !name.contains(" ")) {
            return name;
        }
        String[] parts = name.split("[-_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(part.substring(0, 1).toUpperCase())
                        .append(part.substring(1));
            }
        }
        return sb.toString();
    }

    @Override
    public String getTypeDeclaration(Schema p) {
        if ("date-time".equals(p.getFormat())) {
            return "string";
        }
        if (ModelUtils.isArraySchema(p)) {
            Schema<?> items = ModelUtils.getSchemaItems(p);
            String innerType = getTypeDeclaration(items);
            return innerType + "[]";
        } else if (ModelUtils.isMapSchema(p)) {
            Schema<?> inner = ModelUtils.getAdditionalProperties(p);
            String innerType = getTypeDeclaration(inner);
            return "{ [key: string]: " + innerType + " }";
        }
        return super.getTypeDeclaration(p);
    }

    @Override
    public boolean needToImport(String type) {
        if ("string".equals(type) || "array".equals(type) || "boolean".equals(type)
                || "number".equals(type) || "any".equals(type) || "uuid".equalsIgnoreCase(type) || "integer".equalsIgnoreCase(type)) {
            return false;
        }
        return super.needToImport(type);
    }

    @Override
    public void processOpts() {
        super.processOpts();
        typeMapping.put("string", "string");
        typeMapping.put("number", "number");
        typeMapping.put("integer", "number");
        typeMapping.put("long", "number");
        typeMapping.put("double", "number");
        typeMapping.put("date", "string");
        typeMapping.put("date-time", "string");
        typeMapping.put("object", "any");
        typeMapping.put("UUID", "string");
        typeMapping.put("boolean", "boolean");

        nestVersion = getStringProperty(NEST_VERSION, nestVersion);
        additionalProperties.put(NEST_VERSION, nestVersion);

        controllerSuffix = getStringProperty(CONTROLLER_SUFFIX, controllerSuffix);
        additionalProperties.put(CONTROLLER_SUFFIX, controllerSuffix);

        serviceSuffix = getStringProperty(SERVICE_SUFFIX, serviceSuffix);
        additionalProperties.put(SERVICE_SUFFIX, serviceSuffix);

        useValidations = getBooleanProperty(USE_VALIDATIONS, useValidations);
        additionalProperties.put(USE_VALIDATIONS, useValidations);

        useScopeGuard = getBooleanProperty(USE_SCOPEGUARD, useScopeGuard);
        additionalProperties.put(USE_SCOPEGUARD, useScopeGuard);

        generateServiceInterface = getBooleanProperty(GENERATE_SERVICE_INTERFACE, generateServiceInterface);
        additionalProperties.put(GENERATE_SERVICE_INTERFACE, generateServiceInterface);

        fileNaming = getStringProperty(FILE_NAMING, fileNaming);
        additionalProperties.put(FILE_NAMING, fileNaming);

        if (additionalProperties.containsKey(STRING_ENUMS)) {
            stringEnums = getBooleanProperty(STRING_ENUMS, false);
        }
        if (openAPI != null && openAPI.getInfo() != null) {
            String title = openAPI.getInfo().getTitle();
            String version = openAPI.getInfo().getVersion();
            String projectName = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-$", "");
            additionalProperties.put("projectName", projectName);
            additionalProperties.put("projectVersion", version);
        }
        additionalProperties.put(STRING_ENUMS, stringEnums);

        additionalProperties.put("useValidations", useValidations);
        additionalProperties.put("useScopeGuard", useScopeGuard);
        additionalProperties.put("controllerSuffix", controllerSuffix);
        additionalProperties.put("serviceSuffix", serviceSuffix);
        additionalProperties.put("nestVersion", nestVersion);
        additionalProperties.put("generateServiceInterface", generateServiceInterface);
        additionalProperties.put("fileNaming", fileNaming);
        additionalProperties.put("stringEnums", stringEnums);
    }

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        OperationsMap operationsMap = super.postProcessOperationsWithModels(objs, allModels);
        boolean requiresScopeGuardGlobal = false;

        if (operationsMap != null && operationsMap.getImports() != null) {
            for (Map<String, String> imp : operationsMap.getImports()) {
                String originalClassname = imp.get("classname");
                if (originalClassname != null) {
                    if (originalClassname.startsWith("Models.")) {
                        originalClassname = originalClassname.substring("Models.".length());
                    }
                    String transformedClassname = toModelName(originalClassname);
                    imp.put("classname", transformedClassname);
                }
            }
        }

        if (operationsMap != null && operationsMap.getOperations() != null) {
            for (CodegenOperation op : operationsMap.getOperations().getOperation()) {
                additionalProperties.put("operationTokenName", op.baseName.replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase() + "_TOKEN");
                if (op.path != null) {
                    Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                    Matcher matcher = pattern.matcher(op.path);
                    StringBuffer sb = new StringBuffer();
                    while (matcher.find()) {
                        String inputParam = matcher.group(1);
                        String outputParam = camelize(inputParam, CamelizeOption.LOWERCASE_FIRST_CHAR);
                        matcher.appendReplacement(sb, ":" + outputParam);
                    }
                    matcher.appendTail(sb);
                    op.path = sb.toString();
                }
                op.httpMethod = capitalize(op.httpMethod.toLowerCase(Locale.ENGLISH));
                if (op.operationId == null || op.operationId.isEmpty()) {
                    op.operationId = "defaultMethod";
                }
                if (op.bodyParam != null && op.bodyParam.dataType != null) {
                    op.vendorExtensions.put("bodyType", op.bodyParam.dataType);
                } else {
                    op.vendorExtensions.put("bodyType", "any");
                }
                op.vendorExtensions.put("returnType", (op.returnType == null || op.returnType.isEmpty()) ? "any" : op.returnType);

                if (op.authMethods != null && !op.authMethods.isEmpty()) {
                    requiresScopeGuardGlobal = true;
                    CodegenSecurity sec = op.authMethods.get(0);
                    if (sec.scopes != null && !sec.scopes.isEmpty()) {
                        List<String> scopeValues = new ArrayList<>();
                        for (Map<String, Object> scopeMap : sec.scopes) {
                            Object scope = scopeMap.get("scope");
                            if (scope != null) {
                                scopeValues.add("'" + scope.toString() + "'");
                            }
                        }
                        op.vendorExtensions.put("scopes", "[" + String.join(", ", scopeValues) + "]");
                    }
                    op.vendorExtensions.put("requiresScopeGuard", true);
                } else {
                    op.vendorExtensions.put("requiresScopeGuard", false);
                }

                buildSignaturesForOperation(op);
            }
        }
        Map<String, List<CodegenOperation>> operationsByTag = groupOperationsByTag(operationsMap);
        generateServiceContracts(operationsByTag);
        generateModuleFile(operationsByTag);
        additionalProperties.put("requiresScopeGuardGlobal", requiresScopeGuardGlobal);
        return operationsMap;
    }

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> models = super.postProcessAllModels(objs);
        for (ModelsMap modelsMap : models.values()) {
            for (ModelMap modelMap : modelsMap.getModels()) {
                CodegenModel model = modelMap.getModel();
                List<Map<String, String>> tsImports = new ArrayList<>();
                boolean modelHasEnum = false;
                for (String imp : model.imports) {
                    if ("List".equals(imp) || "number".equals(imp) || "Map".equalsIgnoreCase(imp)
                            || "object".equals(imp) || "DateTime".equalsIgnoreCase(imp) || "Date".equalsIgnoreCase(imp)
                            || "any".equalsIgnoreCase(imp)) {
                        continue;
                    }
                    Map<String, String> entry = new HashMap<>();
                    String transformed = toModelName(imp);
                    entry.put("classname", transformed);
                    entry.put("filename", toModelFilename(transformed));
                    entry.put("importPath", "../models");

                    tsImports.add(entry);
                }
                model.vendorExtensions.put("tsImports", tsImports);

                for (CodegenProperty prop : model.allVars) {
                    if (prop.getDataFormat() != null && prop.getDataFormat().equalsIgnoreCase("base64")) {
                        prop.vendorExtensions.put("isBase64", true);
                    }
                    if (prop.isEnum) {
                        modelHasEnum = true;
                    }
                    prop.name = toVarName(prop.baseName);
                }
                model.vendorExtensions.put("hasEnums", modelHasEnum);
            }
        }
        return models;
    }

    @Override
    public CodegenModel fromModel(String name, Schema schema) {
        CodegenModel model = super.fromModel(name, schema);
        model.classname = toModelName(model.schemaName);
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            model.isEnum = true;
            model.allowableValues = new HashMap<>();
            model.allowableValues.put("enumVars", generateEnumVars(schema.getEnum(), schema.getType()));
        }
        for (CodegenProperty prop : model.allVars) {
            if (prop.dataType != null && prop.dataType.contains("_")) {
                String transformedDataType = toModelName(prop.dataType);
                prop.dataType = transformedDataType;
                prop.datatypeWithEnum = transformedDataType;
            }
        }
        return model;
    }

    @Override
    public CodegenProperty fromProperty(String name, Schema p) {
        CodegenProperty prop = super.fromProperty(name, p);
        if (prop.isNumber) {
            if (p.getMinimum() != null) {
                prop.minimum = p.getMinimum().toString();
            }
            if (p.getMaximum() != null) {
                prop.maximum = p.getMaximum().toString();
            }
        }
        if (p.getFormat() != null && p.getFormat().equals("byte")) {
            prop.dataType = "string";
            prop.baseType = "string";
            prop.vendorExtensions.put("isByteArray", true);
        }
        if (p.getEnum() != null && !p.getEnum().isEmpty()) {
            prop.isEnum = true;
            prop.allowableValues = new HashMap<>();
            prop.allowableValues.put("enumVars", generateEnumVars(p.getEnum(), p.getType()));
            prop.enumName = toModelName(prop.name) + "Enum";
            prop.datatypeWithEnum = prop.enumName;
        }
        return prop;
    }

    @Override
    public CodegenOperation fromOperation(String resourcePath, String httpMethod, Operation operation, List<Server> servers) {
        CodegenOperation op = super.fromOperation(resourcePath, httpMethod, operation, servers);
        if (op.operationId == null || op.operationId.isEmpty()) {
            op.operationId = toOperationId(operation.getOperationId());
        }
        op.httpMethod = capitalize(httpMethod.toLowerCase(Locale.ENGLISH));
        if (op.bodyParam != null && op.bodyParam.dataType != null) {
            op.vendorExtensions.put("bodyType", op.bodyParam.dataType);
        } else {
            op.vendorExtensions.put("bodyType", "any");
        }
        if (op.returnType == null || op.returnType.isEmpty()) {
            op.returnType = "any";
        }
        LOGGER.info("Processed operation: {} [{}]", op.operationId, op.httpMethod);
        return op;
    }

    @Override
    public void postProcessModelProperty(CodegenModel model, CodegenProperty prop) {
        super.postProcessModelProperty(model, prop);

        if (prop.isArray && prop.items != null && prop.items.isModel) {
            prop.items.dataType = camelize(prop.items.dataType, UPPERCASE_FIRST_CHAR);
        }

        if (prop.dataType != null && prop.dataType.contains("_")) {
            String transformedDataType = toModelName(prop.dataType);
            prop.dataType = transformedDataType;
            prop.datatypeWithEnum = transformedDataType;
        }
        if (prop.isDouble || prop.isFloat || prop.isInteger ||
                prop.isLong || prop.isShort || prop.isUnboundedInteger) {
            prop.isNumber = true;
        }
        if (prop.isDate || prop.isDateTime) {
            prop.isString = true;
        }
        if ("byte".equals(prop.getFormat())) {
            // Cambiar el tipo a string
            prop.dataType = "string";
            prop.baseType = "string";
            prop.isByteArray = false;
            prop.vendorExtensions.put("x-is-base64", true);

            // Eliminar importaciones que no existen
            if (model.imports != null) {
                model.imports.remove("ByteArray");
            }
        }
        if (prop.pattern != null && !prop.pattern.isEmpty()) {
            // Almacenar el patrón sin modificar para usarlo en @Matches
            prop.vendorExtensions.put("x-pattern-formatted", prop.pattern);
        }
        if (prop.isEnum && prop.allowableValues != null && prop.allowableValues.containsKey("values")) {
            List<Object> enumValues = (List<Object>) prop.allowableValues.get("values");
            prop.allowableValues.put("enumVars", generateEnumVars(enumValues, prop.dataType));
        }
    }

    protected Map<String, List<CodegenOperation>> groupOperationsByTag(OperationsMap operationsMap) {
        Map<String, List<CodegenOperation>> groupedOps = new LinkedHashMap<>();
        if (operationsMap == null || operationsMap.getOperations() == null) {
            return groupedOps;
        }
        for (CodegenOperation op : operationsMap.getOperations().getOperation()) {
            String tag = (op.tags != null && !op.tags.isEmpty()) ? op.tags.get(0).getName() : "Default";
            tag = toModelName(tag);
            groupedOps.computeIfAbsent(tag, k -> new ArrayList<>()).add(op);
        }
        return groupedOps;
    }

    protected void generateServiceContracts(Map<String, List<CodegenOperation>> operationsByTag) {
        for (Map.Entry<String, List<CodegenOperation>> entry : operationsByTag.entrySet()) {
            String tag = entry.getKey();
            List<CodegenOperation> ops = entry.getValue();
            Map<String, Object> templateData = new HashMap<>();
            String serviceName = "Default".equalsIgnoreCase(tag)
                    ? "DefaultServiceApiServiceInterface"
                    : capitalize(tag) + "ApiServiceInterface";
            templateData.put("serviceName", serviceName);
            templateData.put("tag", tag);
            templateData.put("operations", ops);

            Set<Map<String, String>> importSet = new HashSet<>();
            for (CodegenOperation op : ops) {
                createImportForType(op.returnType).ifPresent(importSet::add);
                if (op.allParams != null) {
                    for (CodegenParameter param : op.allParams) {
                        createImportForType(param.dataType).ifPresent(importSet::add);
                    }
                }
            }
            templateData.put("imports", new ArrayList<>(importSet));
            String serviceTemplatePath = this.templateDir + "/service.interface.mustache";
            String rendered = renderTemplate(serviceTemplatePath, templateData);
            String outputDir = outputFolder + File.separator + "services";
            new File(outputDir).mkdirs();
            String outputFilename = outputDir + File.separator + serviceName + ".ts";
            writeToFile(rendered, outputFilename);
            LOGGER.info("Generated service contract for tag '{}': {}", tag, outputFilename);
        }
    }

    protected String renderTemplate(String templateName, Map<String, Object> templateData) {
        TemplatingExecutor executor = new TemplatingExecutor() {
            @Override
            public String getFullTemplateContents(String templateFile) {
                try {
                    return TypeScriptNestjsServerCodegen.this.getFullTemplateContents(templateFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Path getFullTemplatePath(String templateFile) {
                return TypeScriptNestjsServerCodegen.this.getFullTemplatePath(templateFile);
            }
        };
        try {
            return getTemplatingEngine().compileTemplate(executor, templateData, templateName);
        } catch (IOException e) {
            throw new RuntimeException("Error rendering template " + templateName, e);
        }
    }

    protected void writeToFile(String content, String outputFilename) {
        try (FileWriter writer = new FileWriter(outputFilename)) {
            writer.write(content);
        } catch (Exception e) {
            LOGGER.error("Error writing file " + outputFilename, e);
        }
    }

    @Override
    public void postProcessFile(File file, String fileType) {
        super.postProcessFile(file, fileType);
        if (!file.getName().endsWith(".ts")) {
            return;
        }
        String prettierPath = System.getenv("PRETTIER_POST_PROCESS_FILE");
        if (prettierPath != null && !prettierPath.isEmpty()) {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{prettierPath, "--write", file.getAbsolutePath()});
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    LOGGER.error("Prettier returned error code {} for file: {}", exitCode, file.getAbsolutePath());
                }
            } catch (Exception e) {
                LOGGER.error("Error executing Prettier on file " + file.getAbsolutePath(), e);
            }
        }
    }

    public String getFullTemplateContents(String templateFile) throws IOException {
        String customTemplatePath = templateFile;
        File customTemplateFile = new File(customTemplatePath);

        InputStream is = null;
        if (customTemplateFile.exists()){
            is = new FileInputStream(customTemplateFile);
        }

        if (is == null) {
            String embeddedPath = embeddedTemplateDir + "/" + templateFile;
            is = this.getClass().getClassLoader().getResourceAsStream(embeddedPath);
            if (is == null){
                throw new IOException("Template file not found: " + templateFile);

            }
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        reader.close();
        return content.toString();
    }

    public Path getFullTemplatePath(String templateFile) {
        return new File(embeddedTemplateDir, templateFile).toPath();
    }

    private void generateModuleFile(Map<String, List<CodegenOperation>> operationsByTag) {
        List<Map<String, Object>> controllers = additionalProperties.containsKey("controllers")
                ? (List<Map<String, Object>>) additionalProperties.get("controllers")
                : new ArrayList<>();
        List<Map<String, Object>> services = additionalProperties.containsKey("services")
                ? (List<Map<String, Object>>) additionalProperties.get("services")
                : new ArrayList<>();

        for (String tag : operationsByTag.keySet()) {
            String serviceName = capitalize(tag);
            String controllerName = serviceName;
            Map<String, Object> ctrl = new HashMap<>();
            ctrl.put("controllerName", controllerName);
            ctrl.put("controllerSuffix", "Api" + additionalProperties.get(CONTROLLER_SUFFIX));
            ctrl.put("controllerFileName", controllerName + "Api" + additionalProperties.get(CONTROLLER_SUFFIX));
            controllers.add(ctrl);

            Map<String, Object> serv = new HashMap<>();
            serv.put("serviceName", serviceName);
            serv.put("serviceSuffix", "Api" + additionalProperties.get(SERVICE_SUFFIX) + "Interface");
            serv.put("serviceFileName", serviceName + "Api" + additionalProperties.get(SERVICE_SUFFIX) + "Interface");
            serv.put("serviceToken", serviceName.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase()+"_TOKEN");
            services.add(serv);
        }
        additionalProperties.put("controllers", controllers);
        additionalProperties.put("services", services);
    }
}
