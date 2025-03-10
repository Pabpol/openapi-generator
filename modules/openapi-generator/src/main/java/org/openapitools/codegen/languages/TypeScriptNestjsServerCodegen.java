package org.openapitools.codegen.languages;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import org.openapitools.codegen.*;
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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.openapitools.codegen.languages.TypeScriptNestjsClientCodegen.SERVICE_FILE_SUFFIX;
import static org.openapitools.codegen.utils.CamelizeOption.LOWERCASE_FIRST_CHAR;
import static org.openapitools.codegen.utils.CamelizeOption.LOWERCASE_FIRST_LETTER;
import static org.openapitools.codegen.utils.StringUtils.*;

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

        // Configurar metadatos y carpeta de salida
        generatorMetadata = GeneratorMetadata.newBuilder(generatorMetadata)
                .stability(Stability.EXPERIMENTAL)
                .build();
        outputFolder = "generated-code/typescript-nestjs-server";
        embeddedTemplateDir = templateDir = "typescript-nestjs-server";

        // Registrar plantillas para modelos, controladores y servicios
        modelTemplateFiles.put("model.mustache", ".ts");
        apiTemplateFiles.put("controller.mustache", "Controller.ts");

        // Módulo principal que agrupa controladores y servicios
        supportingFiles.add(new SupportingFile("module.mustache", "", "api.module.ts"));

        // Archivos de soporte para configurar el proyecto
        supportingFiles.add(new SupportingFile("package.mustache", "", "package.json"));
        supportingFiles.add(new SupportingFile("tsconfig.json.mustache", "", "tsconfig.json"));
        supportingFiles.add(new SupportingFile("index.mustache", "", "index.ts"));
//        supportingFiles.add(new SupportingFile("variables.mustache", getIndexDirectory(), "variables.ts"));
//        supportingFiles.add(new SupportingFile("gitignore", "", ".gitignore"));
//        supportingFiles.add(new SupportingFile("README.mustache", getIndexDirectory(), "README.md"));
        // Archivos de seguridad
        supportingFiles.add(new SupportingFile("scopes.decorator.mustache", "decorators", "scopes.decorator.ts"));
        supportingFiles.add(new SupportingFile("scope.guard.mustache", "guards", "scope.guard.ts"));
        // Archivo de arranque
//        supportingFiles.add(new SupportingFile("main.mustache", "src", "main.ts"));

        // Definir paquetes internos
        apiPackage = "controllers";
        modelPackage = "models";

        // Registrar opciones CLI
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

    @Override
    public String escapeReservedWord(String name) {
        if ("response".equals(name)) {
            return "_" + name;
        }
        return super.escapeReservedWord(name);
    }

    // Método auxiliar para obtener el directorio de índice
    protected String getIndexDirectory() {
        return "src";
    }

    private String convertUsingFileNamingConvention(String originalName) {
        String name = this.removeModelPrefixSuffix(originalName);
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
        // sanitize name
        name = sanitizeName(name);

        // replace - with _ e.g. created-at => created_at
        name = name.replaceAll("-", "_");

        // if it's all upper case, do nothing
        if (name.matches("^[A-Z_]*$"))
            return name;

        // camelize the variable name
        // pet_id => PetId
        name = camelize(name, LOWERCASE_FIRST_LETTER);

        // for reserved word or word starting with number, append _
        if (isReservedWord(name) || name.matches("^\\d.*"))
            name = escapeReservedWord(name);

        return name;
    }
    @Override
    public String toModelName(String name) {
        if (name == null || name.isEmpty()) {
            return "DefaultModel";
        }
        // Si no contiene guiones bajos ni guiones, asumimos que ya está en formato PascalCase
        if (!name.contains("_") && !name.contains("-") && !name.contains(" ")) {
            return name;
        }
        // Separamos el nombre y capitalizamos cada parte
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
            // Obtener el schema de los elementos del array
            Schema<?> items = ModelUtils.getSchemaItems(p);
            // Obtener el tipo del elemento (se aplican las transformaciones necesarias, por ejemplo, sufijos)
            String innerType = getTypeDeclaration(items);
            // En TypeScript se suele usar la notación de corchetes para arrays
            return innerType + "[]";
        } else if (ModelUtils.isMapSchema(p)) {
            // Si fuera un map, se puede devolver una notación de objeto
            Schema<?> inner = ModelUtils.getAdditionalProperties(p);
            String innerType = getTypeDeclaration(inner);
            return "{ [key: string]: " + innerType + " }";
        }
        return super.getTypeDeclaration(p);
    }
    @Override
    public boolean needToImport(String type) {
        // Lista de tipos que no deben importarse (primitivos en TypeScript)
        if ("string".equals(type) || "array".equals(type) || "boolean".equals(type) || "number".equals(type) || "any".equals(type) ) {
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


        if (additionalProperties.containsKey(NEST_VERSION)) {
            nestVersion = additionalProperties.get(NEST_VERSION).toString();
        }
        additionalProperties.put(NEST_VERSION, nestVersion);

        if (additionalProperties.containsKey(CONTROLLER_SUFFIX)) {
            controllerSuffix = additionalProperties.get(CONTROLLER_SUFFIX).toString();
        }
        additionalProperties.put(CONTROLLER_SUFFIX, controllerSuffix);

        if (additionalProperties.containsKey(SERVICE_SUFFIX)) {
            serviceSuffix = additionalProperties.get(SERVICE_SUFFIX).toString();
        }
        additionalProperties.put(SERVICE_SUFFIX, serviceSuffix);

        if (additionalProperties.containsKey(USE_VALIDATIONS)) {
            useValidations = Boolean.parseBoolean(additionalProperties.get(USE_VALIDATIONS).toString());
        }
        additionalProperties.put(USE_VALIDATIONS, useValidations);

        if (additionalProperties.containsKey(USE_SCOPEGUARD)) {
            useScopeGuard = Boolean.parseBoolean(additionalProperties.get(USE_SCOPEGUARD).toString());
        }
        additionalProperties.put(USE_SCOPEGUARD, useScopeGuard);

        if (additionalProperties.containsKey(GENERATE_SERVICE_INTERFACE)) {
            generateServiceInterface = Boolean.parseBoolean(additionalProperties.get(GENERATE_SERVICE_INTERFACE).toString());
        }
        additionalProperties.put(GENERATE_SERVICE_INTERFACE, generateServiceInterface);

        if (additionalProperties.containsKey(FILE_NAMING)) {
            fileNaming = additionalProperties.get(FILE_NAMING).toString();
        }
        additionalProperties.put(FILE_NAMING, fileNaming);

        if (additionalProperties.containsKey(STRING_ENUMS)) {
            stringEnums = Boolean.parseBoolean(additionalProperties.get(STRING_ENUMS).toString());
        }
        if (openAPI != null && openAPI.getInfo() != null) {
            String title = openAPI.getInfo().getTitle();
            String version = openAPI.getInfo().getVersion();
            String projectName = title.toLowerCase().replaceAll("[^a-z0-9]+", "-");
            projectName = projectName.replaceAll("-$", "");
            additionalProperties.put("projectName", projectName);
            additionalProperties.put("projectVersion", version);
        }
        additionalProperties.put(STRING_ENUMS, stringEnums);

        // Inyectar propiedades globales para las plantillas
        additionalProperties.put("useValidations", useValidations);
        additionalProperties.put("useScopeGuard", useScopeGuard);
        additionalProperties.put("controllerSuffix", controllerSuffix);
        additionalProperties.put("serviceSuffix", serviceSuffix);
        additionalProperties.put("nestVersion", nestVersion);
        additionalProperties.put("generateServiceInterface", generateServiceInterface);
        additionalProperties.put("fileNaming", fileNaming);
        additionalProperties.put("stringEnums", stringEnums);
    }

    // Post-procesamiento de operaciones: se procesan los detalles de cada operación
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        OperationsMap operationsMap = super.postProcessOperationsWithModels(objs, allModels);
        boolean requiresScopeGuardGlobal = false;

        if (operationsMap != null && operationsMap.getImports() != null) {
            for (Map<String, String> imp : operationsMap.getImports()) {
                String originalClassname = imp.get("classname");
                if (originalClassname != null) {
                    // Si el nombre contiene el prefijo "Models.", lo removemos
                    if (originalClassname.startsWith("Models.")) {
                        originalClassname = originalClassname.substring("Models.".length());
                    }
                    // Convertimos a PascalCase (por ejemplo, de "getGuestById205Response" a "GetGuestById205Response")
                    String transformedClassname = toModelName(originalClassname);
                    imp.put("classname", transformedClassname);
                }
            }
        }

        if (operationsMap != null && operationsMap.getOperations() != null) {
            for (CodegenOperation op : operationsMap.getOperations().getOperation()) {
                if (op.path != null){
                    Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                    Matcher matcher = pattern.matcher(op.path);
                    StringBuffer sb = new StringBuffer();
                    while (matcher.find()){
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
                if (op.returnType == null || op.returnType.isEmpty()) {
                    op.vendorExtensions.put("returnType", "any");
                } else {
                    op.vendorExtensions.put("returnType", op.returnType);
                }
                if (op.authMethods != null && !op.authMethods.isEmpty()) {
                    requiresScopeGuardGlobal = true;
                    // Tomamos el primer método de autenticación
                    CodegenSecurity sec = op.authMethods.get(0);
                    if (sec.scopes != null && !sec.scopes.isEmpty()) {
                        List<String> scopeValues = new ArrayList<>();
                        for (Map<String, Object> scopeMap : sec.scopes) {
                            Object scope = scopeMap.get("scope");
                            if (scope != null) {
                                // Envolver cada scope entre comillas simples
                                scopeValues.add("'" + scope.toString() + "'");
                            }
                        }
                        // Unir los scopes en formato array: ['scope1', 'scope2']
                        String scopesFormatted = "[" + String.join(", ", scopeValues) + "]";
                        op.vendorExtensions.put("scopes", scopesFormatted);
                    }
                    op.vendorExtensions.put("requiresScopeGuard", true);
                } else {
                    op.vendorExtensions.put("requiresScopeGuard", false);

                }

                if (op.allParams != null && !op.allParams.isEmpty()) {
                    StringBuilder controllerSignatureBuilder = new StringBuilder();
                    StringBuilder serviceSignatureBuilder = new StringBuilder();
                    StringBuilder argBuilder = new StringBuilder();
                    int paramCount = op.allParams.size();
                    for (int i = 0; i < paramCount; i++) {
                        CodegenParameter param = op.allParams.get(i);
                        String controllerAnnotation = "";
                        if (param.isBodyParam) {
                            controllerAnnotation = "@Body() ";
                        } else if (param.isPathParam) {
                            controllerAnnotation = "@Param('" + camelize(param.paramName, LOWERCASE_FIRST_CHAR) + "') ";
                        } else if (param.isQueryParam) {
                            controllerAnnotation = "@Query('" + camelize(param.paramName, LOWERCASE_FIRST_CHAR) + "') ";
                        } else if (param.isHeaderParam) {
                            controllerAnnotation = "@Headers('" + camelize(param.paramName, LOWERCASE_FIRST_CHAR) + "') ";
                        }
                        // Firma para el controller: incluye decoradores
                        controllerSignatureBuilder.append(controllerAnnotation)
                                .append(param.paramName)
                                .append(": ")
                                .append(param.dataType);
                        // Firma para el servicio: solo nombre y tipo, sin decoradores
                        serviceSignatureBuilder.append(param.paramName)
                                .append(": ")
                                .append(param.dataType);
                        // Lista de argumentos (solo nombres)
                        argBuilder.append(param.paramName);
                        if (i < paramCount - 1) {
                            controllerSignatureBuilder.append(", ");
                            serviceSignatureBuilder.append(", ");
                            argBuilder.append(", ");
                        }
                    }
                    op.vendorExtensions.put("controllerSignatureList", controllerSignatureBuilder.toString());
                    op.vendorExtensions.put("serviceSignatureList", serviceSignatureBuilder.toString());
                    op.vendorExtensions.put("argList", argBuilder.toString());
                } else {
                    op.vendorExtensions.put("controllerSignatureList", "");
                    op.vendorExtensions.put("serviceSignatureList", "");
                    op.vendorExtensions.put("argList", "");
                }
            }
        }
        // Agrupar operaciones por tag y generar contratos de servicio para cada grupo
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
                    // Filtrar los tipos que no queremos importar (por ejemplo, primitivos o genéricos no generados)
                    if ("List".equals(imp) || "number".equals(imp) || "Map".equalsIgnoreCase(imp)  || "object".equals(imp) || "DateTime".equalsIgnoreCase(imp) || "Date".equalsIgnoreCase(imp) || "any".equalsIgnoreCase(imp)) {
                        continue;
                    }
                    Map<String, String> entry = new HashMap<>();
                    if ("UUID".equals(imp)) {
                        entry.put("classname", "UUID");
                        // Aquí definimos que UUID se importe desde 'crypto'
                        entry.put("importPath", "crypto");
                    } else {
                        // Para otros tipos, aplicamos la transformación a PascalCase
                        String transformed = toModelName(imp); // Ej: "GuestDto_companion" → "GuestDtoCompanion"
                        entry.put("classname", transformed);
                        entry.put("filename", toModelFilename(transformed));
                        entry.put("importPath", "../models");
                    }
                    tsImports.add(entry);
                }
                model.vendorExtensions.put("tsImports", tsImports);

                for (CodegenProperty prop : model.allVars) {
                    // Si el formato es 'base64', marcar para que se incluya la validación @IsBase64()
                    if (prop.getDataFormat() != null && prop.getDataFormat().equalsIgnoreCase("base64")) {
                        prop.vendorExtensions.put("isBase64", true);
                    }
                    if (prop.isEnum) {
                        modelHasEnum = true;
                    }
                    // Si el nombre viene en snake_case (p.ej. "customer_id"), se puede transformar a camelCase
                    // Aquí puedes aplicar tu propia lógica o utilizar una función helper
                    prop.name = toVarName(prop.baseName);
                }
                model.vendorExtensions.put("hasEnums", modelHasEnum);
            }
        }
        return models;
    }
    @Override
    public CodegenModel fromModel(String name, Schema schema) {
        // Llama al método base para procesar el modelo según la lógica existente
        CodegenModel model = super.fromModel(name, schema);

        // Actualiza el nombre del modelo final (classname) con tu transformación deseada.
        // Por ejemplo, si el modelo se llama "GuestDto_companion", lo transforma a "GuestDtoCompanion".
        model.classname = toModelName(model.schemaName);
        // Agregar lógica para enums
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            model.isEnum = true;
            List<Object> enumValues = schema.getEnum();
            List<Map<String, Object>> enumVars = new ArrayList<>();
            for (int i = 0; i < enumValues.size(); i++) {
                Object enumValue = enumValues.get(i);
                Map<String, Object> ev = new HashMap<>();
                // Convierte el valor en un nombre de variable TS válido.
                // Por ejemplo: "available" -> "AVAILABLE" o "Available"
                ev.put("name", toEnumVarName(enumValue.toString(), schema.getType()));
                // Si se usan string enums, envolver el valor entre comillas.
                ev.put("value", (Boolean.TRUE.equals(stringEnums) ? "\"" + enumValue.toString() + "\"" : enumValue.toString()));
                ev.put("last", i == enumValues.size() - 1);
                enumVars.add(ev);
            }
            Map<String, Object> allowableValues = new HashMap<>();
            allowableValues.put("enumVars", enumVars);
            model.allowableValues = allowableValues;
        }
        // Recorre todas las propiedades (allVars) para actualizar su dataType si es necesario
        for (CodegenProperty prop : model.allVars) {
            // Si la propiedad hace referencia a otro modelo y su dataType contiene guiones bajos,
            // se transforma usando toModelName para obtener el nombre final deseado.
            if (prop.dataType != null && prop.dataType.contains("_")) {
                String transformedDataType = toModelName(prop.dataType);
                prop.dataType = transformedDataType;
                // Opcional: también actualizar datatypeWithEnum si aplica
                prop.datatypeWithEnum = transformedDataType;
            }
        }

        return model;
    }
    @Override
    public CodegenProperty fromProperty(String name, Schema p) {
        CodegenProperty prop = super.fromProperty(name, p);
        if (p.getEnum() != null && !p.getEnum().isEmpty()) {
            prop.isEnum = true;
            // Obtiene los valores del enum
            List<Object> enumValues = p.getEnum();
            List<Map<String, Object>> enumVars = new ArrayList<>();
            for (int i = 0; i < enumValues.size(); i++) {
                Object enumValue = enumValues.get(i);
                Map<String, Object> ev = new HashMap<>();
                // Genera un nombre válido para el valor del enum
                ev.put("name", toEnumVarName(enumValue.toString(), p.getType()));
                // Si se usa stringEnums, se envuelve el valor entre comillas
                ev.put("value", (Boolean.TRUE.equals(stringEnums) ? "\"" + enumValue.toString() + "\"" : enumValue.toString()));
                ev.put("last", i == enumValues.size() - 1);
                enumVars.add(ev);
            }
            // Se asigna la lista de variables generada a allowableValues
            Map<String, Object> allowableValues = new HashMap<>();
            allowableValues.put("enumVars", enumVars);
            prop.allowableValues = allowableValues;
            // Se asigna un nombre para el enum inline, por ejemplo: "StatusEnum" si la propiedad se llama "status"
            prop.enumName = toModelName(prop.name) + "Enum";
            // Esto hará que en el template se utilice el enumName en lugar de la propiedad primitiva
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
        if (prop.isEnum) {
            // Si allowableValues tiene la clave "values", se genera "enumVars"
            if (prop.allowableValues != null && prop.allowableValues.containsKey("values")) {
                List<Object> enumValues = (List<Object>) prop.allowableValues.get("values");
                List<Map<String, Object>> enumVars = new ArrayList<>();
                for (int i = 0; i < enumValues.size(); i++) {
                    Object enumValue = enumValues.get(i);
                    Map<String, Object> ev = new HashMap<>();
                    ev.put("name", toEnumVarName(enumValue.toString(), prop.dataType));
                    ev.put("value", (Boolean.TRUE.equals(stringEnums)
                            ? "\"" + enumValue.toString() + "\""
                            : enumValue.toString()));
                    ev.put("last", i == enumValues.size() - 1);
                    enumVars.add(ev);
                }
                // Reemplazar o agregar la clave "enumVars" en allowableValues
                prop.allowableValues.put("enumVars", enumVars);
            }
        }
    }


    // Agrupa las operaciones por tag. Si no hay tag, se agrupa en "Default"
    protected Map<String, List<CodegenOperation>> groupOperationsByTag(OperationsMap operationsMap) {
        Map<String, List<CodegenOperation>> groupedOps = new LinkedHashMap<>();
        if (operationsMap == null || operationsMap.getOperations() == null) {
            return groupedOps;
        }
        for (CodegenOperation op : operationsMap.getOperations().getOperation()) {
            String tag = (op.tags != null && !op.tags.isEmpty()) ? op.tags.get(0).getName() : "Default";
            tag = toModelName(tag);
            if (!groupedOps.containsKey(tag)) {
                groupedOps.put(tag, new ArrayList<>());
            }
            groupedOps.get(tag).add(op);
        }
        return groupedOps;
    }

    // Genera un contrato de servicio (clase abstracta) por cada grupo de operaciones (tag)
    protected void generateServiceContracts(Map<String, List<CodegenOperation>> operationsByTag) {
        for (Map.Entry<String, List<CodegenOperation>> entry : operationsByTag.entrySet()) {
            String tag = entry.getKey();
            List<CodegenOperation> ops = entry.getValue();
            Map<String, Object> templateData = new HashMap<>();
            // Si el tag no es "Default", usa el tag para el nombre del servicio; de lo contrario, usa "DefaultService".
            String serviceName;
            if ("Default".equalsIgnoreCase(tag)) {
                serviceName = "DefaultServiceApiServiceInterface";
            } else {
                serviceName = capitalize(tag) + "ApiServiceInterface";
            }
            templateData.put("serviceName", serviceName);
            templateData.put("tag", tag);
            templateData.put("operations", ops);

            Set<Map<String, String>> importSet = new HashSet<>();
            for (CodegenOperation op :ops){
                if (op.returnType != null){
                    String typeName = op.returnType;
                    if (typeName.endsWith("[]")){
                        typeName = typeName.substring(0, typeName.length() - 2);
                    }
                    if (needToImport(typeName)){
                        Map<String, String> imp = new HashMap<>();
                        imp.put("classname", typeName);
                        imp.put("importPath", "../models");
                        imp.put("filename", toModelFilename(typeName));
                        importSet.add(imp);
                    }

                }
                if (op.allParams != null ){
                    for (CodegenParameter param : op.allParams){
                        if (param.dataType != null) {
                            String paramType = param.dataType;
                            if (paramType.endsWith("[]")){
                                paramType = paramType.substring(0, paramType.length() - 2);
                            }
                            if (needToImport(paramType)){
                                Map<String, String> imp = new HashMap<>();
                                imp.put("classname", paramType);
                                imp.put("importPath", "../models");
                                imp.put("filename", toModelFilename(paramType));
                                importSet.add(imp);
                            }
                        }
                    }
                }
            }
            templateData.put("imports", new ArrayList<>(importSet));
            String serviceTemplatePath = this.templateDir + "service.interface.mustache";
            String rendered = renderTemplate(serviceTemplatePath, templateData);
            String outputDir = outputFolder + File.separator + "services";
            File dir = new File(outputDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String outputFilename = outputDir + File.separator + serviceName + ".ts";
            writeToFile(rendered, outputFilename);
            LOGGER.info("Generated service contract for tag '{}': {}", tag, outputFilename);
        }
    }


    // Utiliza el motor de plantillas para renderizar un template dado su nombre y datos
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
            // Si TemplatingExecutor tiene otros métodos obligatorios, impĺementalos aquí.
        };
        try {
            return getTemplatingEngine().compileTemplate(executor, templateData, templateName);
        } catch (IOException e) {
            throw new RuntimeException("Error rendering template " + templateName, e);
        }
    }


    // Método auxiliar para escribir el contenido en un archivo
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
                Process process = Runtime.getRuntime().exec(new String[] { prettierPath, "--write", file.getAbsolutePath() });
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
        // Construye la ruta completa usando el directorio de templates (embeddedTemplateDir)
        String fullPath = embeddedTemplateDir + "/" + templateFile;
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(fullPath);
        if (is == null) {
            throw new IOException("Template file not found: " + fullPath);
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

        // Por cada grupo (tag) se asume que se genera un controlador y un servicio
        for (String tag : operationsByTag.keySet()) {
            // Por ejemplo, el nombre del servicio se deriva del tag (capitalizado)
            String serviceName = capitalize(tag);
            String controllerName = serviceName; // O puede tener un prefijo o sufijo diferente

            // Genera los nombres de archivo (convierte a la convención deseada)

            Map<String, Object> ctrl = new java.util.HashMap<>();
            ctrl.put("controllerName", controllerName);
            ctrl.put("controllerSuffix", "Api" + additionalProperties.get(CONTROLLER_SUFFIX)); // por ejemplo, "Controller"
            ctrl.put("controllerFileName", controllerName + "Api" + additionalProperties.get(CONTROLLER_SUFFIX)); // o el sufijo que uses para archivos de controlador
            controllers.add(ctrl);

            Map<String, Object> serv = new java.util.HashMap<>();
            serv.put("serviceName", serviceName);
            serv.put("serviceSuffix", "Api" + additionalProperties.get(SERVICE_SUFFIX) + "Interface"); // por ejemplo, "ApiServiceInterface"
            serv.put("serviceFileName", serviceName + "Api" + additionalProperties.get(SERVICE_SUFFIX) + "Interface"); // o el sufijo correspondiente
            services.add(serv);
        }
        additionalProperties.put("controllers", controllers);
        additionalProperties.put("services", services);
    }


}
