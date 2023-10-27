package org.kie.trustyai.service.validators;

import java.util.Objects;

import org.kie.trustyai.service.data.DataSource;
import org.kie.trustyai.service.data.metadata.Metadata;
import org.kie.trustyai.service.payloads.BaseMetricRequest;
import org.kie.trustyai.service.payloads.PayloadConverter;
import org.kie.trustyai.service.payloads.service.SchemaItem;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

@ApplicationScoped
public class BaseMetricRequestValidator implements ConstraintValidator<ValidBaseMetricRequest, BaseMetricRequest> {
    @Inject
    Instance<DataSource> dataSource;

    @Override
    public void initialize(ValidBaseMetricRequest constraintAnnotation) {
        ConstraintValidator.super.initialize(constraintAnnotation);
    }

    @Override
    public boolean isValid(BaseMetricRequest request, ConstraintValidatorContext context) {
        final String modelId = request.getModelId();
        if (!dataSource.get().hasMetadata(modelId)) {
            context.buildConstraintViolationWithTemplate("No metadadata found for model=" + modelId).addConstraintViolation();
            return false;
        } else {
            final Metadata metadata = dataSource.get().getMetadata(modelId);
            final String outcomeName = request.getOutcomeName();
            // Outcome name is not present
            if (!metadata.getOutputSchema().getItems().containsKey(outcomeName)) {
                context.buildConstraintViolationWithTemplate("No outcome found with name=" + outcomeName).addConstraintViolation();
                return false;
            }
            final String protectedAttribute = request.getProtectedAttribute();
            if (!metadata.getInputSchema().getItems().containsKey(protectedAttribute)) {
                context.buildConstraintViolationWithTemplate("No protected attribute found with name=" + protectedAttribute).addConstraintViolation();
                return false;
            }
            // Outcome name guaranteed to exist
            final SchemaItem outcomeSchema = metadata.getOutputSchema().getItems().get(outcomeName);
            if (!PayloadConverter.checkValueType(outcomeSchema.getType(), request.getFavorableOutcome())) {
                context.buildConstraintViolationWithTemplate(
                        "Invalid type for outcome. Got '" + request.getFavorableOutcome() + "', expected object compatible with '" + outcomeSchema.getType().toString() + "'").addConstraintViolation();
                return false;
            }
            // Protected attribute guaranteed to exist
            final SchemaItem protectedAttrSchema = metadata.getInputSchema().getItems().get(protectedAttribute);
            if (!PayloadConverter.checkValueType(protectedAttrSchema.getType(), request.getPrivilegedAttribute())) {
                context.buildConstraintViolationWithTemplate(
                        "Invalid type for privileged attribute. Got '" + request.getPrivilegedAttribute() + "', expected object compatible with ''" + protectedAttrSchema.getType().toString() + "'")
                        .addConstraintViolation();
                return false;
            }
            if (!PayloadConverter.checkValueType(protectedAttrSchema.getType(), request.getUnprivilegedAttribute())) {
                context.buildConstraintViolationWithTemplate(
                        "Invalid type for unprivileged attribute. Got '" + request.getUnprivilegedAttribute() + "', expected object compatible with ''" + protectedAttrSchema.getType().toString()
                                + "'")
                        .addConstraintViolation();
                return false;
            }
            if (Objects.nonNull(request.getBatchSize()) && request.getBatchSize() <= 0) {
                context.buildConstraintViolationWithTemplate(
                        "Request batch size must be bigger than 0.")
                        .addConstraintViolation();
                return false;
            }
        }
        return true;
    }
}
