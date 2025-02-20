package org.openapitools.codegen.languages;

import org.openapitools.codegen.*;
import org.openapitools.codegen.languages.features.BeanValidationFeatures;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
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
        return "I" + camelize(name) + "Service.ts";  // Asegura que termine en "Service.ts"
    }

    @Override
    public String apiFilename(String templateName, String tag) {
        String result = super.apiFilename(templateName, tag);

        if (templateName.equals("service.mustache")) {
            result = result.replace("controllers", "services");
            result = result.replace("Controller", "Service"); // Corregir el sufijo incorrecto
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

            // Debugging: Verificar si hay autenticación en los endpoints
            if (operation.authMethods != null && !operation.authMethods.isEmpty()) {
                operation.vendorExtensions.put("hasAuth", true);
            }
        }

        // Debugging: Verificar los modelos
        for (ModelMap model : allModels) {
            System.out.println("Procesando modelo: " + model.getModel().getClassname());
            System.out.println("Atributos:");
            model.getModel().getVars().forEach(var -> {
                System.out.println(" - " + var.getName() + " (" + var.getDataType() + ")");
            });
        }

        return objs;
    }

    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        List<ModelMap> models = objs.getModels();

        if (models == null || models.isEmpty()) {
            System.out.println("❌ ERROR: No se encontraron modelos en este `ModelsMap`.");
            return objs;
        }

        for (ModelMap model : models) {
            CodegenModel codegenModel = model.getModel();

            if (codegenModel == null) {
                System.out.println("❌ ERROR: Modelo nulo en `postProcessModels`.");
                continue;
            }

            System.out.println("🔍 Procesando modelo: " + codegenModel.classname);

            if (codegenModel.vars.isEmpty()) {
                System.out.println("⚠️  El modelo " + codegenModel.classname + " no tiene atributos.");
            } else {
                System.out.println("✅ Modelo " + codegenModel.classname + " con atributos:");
                for (CodegenProperty var : codegenModel.vars) {
                    System.out.println(" - " + var.name + " (" + var.dataType + ")");
                }
            }

            // Modifica el objeto sin afectar su referencia original
            model.put("classname", codegenModel.classname);
            model.put("vars", codegenModel.vars);
            model.put("description", codegenModel.description);
            System.out.println("📢 Enviando modelo a plantilla Mustache: " + model);
        }

        return objs;
    }







    @Override
    public void processOpts() {
        super.processOpts();

        // Definir DTOs
        modelTemplateFiles.put("dto.mustache", ".ts");
        apiTemplateFiles.put("controller.mustache", ".ts");
        apiTemplateFiles.put("service.mustache", ".ts");
    }
    @Override
    public String toModelFilename(String name) {
        return camelize(name) + "Dto";
    }
}
