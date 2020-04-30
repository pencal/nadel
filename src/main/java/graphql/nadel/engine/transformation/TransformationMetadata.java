package graphql.nadel.engine.transformation;

import graphql.GraphQLError;
import graphql.Internal;
import graphql.nadel.normalized.NormalizedQueryField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Internal
public class TransformationMetadata {

    private final List<NormalizedFieldAndError> removedFields = new ArrayList<>();

    private final Map<String, List<FieldMetadata>> metadataByFieldId = new LinkedHashMap<>();

    public static class NormalizedFieldAndError {
        private final NormalizedQueryField normalizedField;
        private final GraphQLError error;

        public NormalizedFieldAndError(NormalizedQueryField normalizedField, GraphQLError error) {
            this.normalizedField = normalizedField;
            this.error = error;
        }

        public NormalizedQueryField getNormalizedField() {
            return normalizedField;
        }

        public GraphQLError getError() {
            return error;
        }
    }

    public void add(List<NormalizedQueryField> fields, GraphQLError error) {
        for (NormalizedQueryField field : fields) {
            removedFields.add(new NormalizedFieldAndError(field, error));
        }
    }

    public List<NormalizedFieldAndError> getRemovedFieldsForParent(NormalizedQueryField parent) {
        List<NormalizedFieldAndError> result = new ArrayList<>();
        for (NormalizedFieldAndError fieldAndError : removedFields) {
            if (fieldAndError.normalizedField.getParent() == parent) {
                result.add(fieldAndError);
            }
        }
        return result;
    }

    public Map<String, List<FieldMetadata>> getMetadataByFieldId() {
        return metadataByFieldId;
    }

}

