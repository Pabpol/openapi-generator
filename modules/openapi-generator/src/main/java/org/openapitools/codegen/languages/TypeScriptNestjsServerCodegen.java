package org.openapitools.codegen.languages;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.servers.Server;
import org.openapitools.codegen.*;
import org.openapitools.codegen.api.TemplatingExecutor;
import org.openapitools.codegen.meta.GeneratorMetadata;
import org.openapitools.codegen.meta.Stability;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.OperationsMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.openapitools.codegen.languages.TypeScriptNestjsClientCodegen.SERVICE_FILE_SUFFIX;
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
        supportingFiles.add(new SupportingFile("index.mustache", getIndexDirectory(), "index.ts"));
        supportingFiles.add(new SupportingFile("variables.mustache", getIndexDirectory(), "variables.ts"));
        supportingFiles.add(new SupportingFile("gitignore", "", ".gitignore"));
        supportingFiles.add(new SupportingFile("README.mustache", getIndexDirectory(), "README.md"));
        // Archivos de seguridad
        supportingFiles.add(new SupportingFile("scopes.decorator.mustache", "decorators", "scopes.decorator.ts"));
        supportingFiles.add(new SupportingFile("scope.guard.mustache", "guards", "scope.guard.ts"));
        // Archivo de arranque
        supportingFiles.add(new SupportingFile("main.mustache", "src", "main.ts"));

        // Definir paquetes internos
        apiPackage = "apis";
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
    public void processOpts() {
        super.processOpts();
        typeMapping.put("string", "string");

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

        if (operationsMap != null && operationsMap.getOperations() != null) {
            for (CodegenOperation op : operationsMap.getOperations().getOperation()) {
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
                            controllerAnnotation = "@Param('" + param.paramName + "') ";
                        } else if (param.isQueryParam) {
                            controllerAnnotation = "@Query('" + param.paramName + "') ";
                        } else if (param.isHeaderParam) {
                            controllerAnnotation = "@Headers('" + param.paramName + "') ";
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

    // Agrupa las operaciones por tag. Si no hay tag, se agrupa en "Default"
    protected Map<String, List<CodegenOperation>> groupOperationsByTag(OperationsMap operationsMap) {
        Map<String, List<CodegenOperation>> groupedOps = new LinkedHashMap<>();
        if (operationsMap == null || operationsMap.getOperations() == null) {
            return groupedOps;
        }
        for (CodegenOperation op : operationsMap.getOperations().getOperation()) {
            String tag = (op.tags != null && !op.tags.isEmpty()) ? op.tags.get(0).getName() : "Default";
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
            String rendered = renderTemplate("service.interface.mustache", templateData);
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
//            String controllerFileName = convertUsingFileNamingConvention(controllerName);
//            String serviceFileName = convertUsingFileNamingConvention(serviceName);

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
