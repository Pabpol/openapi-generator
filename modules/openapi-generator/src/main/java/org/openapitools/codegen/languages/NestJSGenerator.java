package org.openapitools.codegen.languages;

import org.openapitools.codegen.*;
import org.openapitools.codegen.languages.features.BeanValidationFeatures;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;

import java.util.*;

import static org.openapitools.codegen.utils.StringUtils.camelize;

public class NestJSGenerator extends DefaultCodegen implements CodegenConfig, BeanValidationFeatures {

    private boolean useBeanValidation = true;

    public NestJSGenerator() {
        super();
        outputFolder = "generated-code/nestjs-server";
        embeddedTemplateDir = templateDir = "nestjs-server";
        apiPackage = "src/controllers";
        modelPackage = "src/dto";
        additionalProperties.put("servicePackage", "src/services");
        supportedLibraries.put("nestjs", "NestJS Server Generator");

        // Definir los archivos generados
        apiTemplateFiles.put("controller.mustache", ".ts");
        apiTemplateFiles.put("service.mustache", ".ts");
        modelTemplateFiles.put("dto.mustache", ".ts");
        supportingFiles.add(new SupportingFile("package.mustache", "", "package.json"));
        supportingFiles.add(new SupportingFile("tsconfig.mustache", "", "tsconfig.json"));
    }

    @Override
    public String getName() {
        return "nestjs-server";
    }

    @Override
    public String toApiName(String name) {
        if (name.length() == 0) {
            return "DefaultController";
        }
        return camelize(name) + "Controller";
    }

    @Override
    public String toApiFilename(String name) {
        return toApiName(name);
    }

    public String toServiceName(String name) {
        if (name == null || name.isEmpty()) {
            return "DefaultService";
        }
        return "I" + camelize(name) + "Service";
    }

    public String toServiceFilename(String name) {
        return toServiceName(name);
    }

    @Override
    public String apiFilename(String templateName, String tag) {
        String result = super.apiFilename(templateName, tag);

        if (templateName.equals("service.mustache")) {
            result = result.replace("controllers", "services");
        }
        return result;
    }

    @Override
    public void setUseBeanValidation(boolean useBeanValidation) {
        this.useBeanValidation = useBeanValidation;
    }

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        OperationMap objectMap = objs.getOperations();
        List<CodegenOperation> operations = objectMap.getOperation();

        for (CodegenOperation operation : operations) {
            operation.httpMethod = operation.httpMethod.toLowerCase(Locale.ROOT);

            for (CodegenResponse response : operation.responses) {
                if ("0".equals(response.code)) {
                    response.code = "default";
                }
            }
        }
        return objs;
    }
}
