package graphql.nadel.engine;

import graphql.GraphQLError;
import graphql.Scalars;
import graphql.SerializationError;
import graphql.TypeMismatchError;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionPath;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.nadel.ServiceExecutionResult;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.normalized.NormalizedQueryFromAst;
import graphql.nadel.result.ElapsedTime;
import graphql.nadel.result.ExecutionResultNode;
import graphql.nadel.result.LeafExecutionResultNode;
import graphql.nadel.result.ListExecutionResultNode;
import graphql.nadel.result.NonNullableFieldWasNullError;
import graphql.nadel.result.ObjectExecutionResultNode;
import graphql.nadel.result.RootExecutionResultNode;
import graphql.nadel.util.ErrorUtil;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertTrue;
import static graphql.nadel.result.LeafExecutionResultNode.newLeafExecutionResultNode;
import static graphql.nadel.result.ObjectExecutionResultNode.newObjectExecutionResultNode;
import static graphql.schema.GraphQLTypeUtil.isList;

public class ServiceResultToResultNodes {


    private static final Logger log = LoggerFactory.getLogger(ServiceResultToResultNodes.class);


    public RootExecutionResultNode resultToResultNode(ExecutionContext executionContext,
                                                      ExecutionStepInfo executionStepInfo,
                                                      List<MergedField> mergedFields,
                                                      ServiceExecutionResult serviceExecutionResult,
                                                      ElapsedTime elapsedTimeForServiceCall,
                                                      NormalizedQueryFromAst normalizedQueryFromAst
    ) {
        long startTime = System.currentTimeMillis();

        List<GraphQLError> errors = ErrorUtil.createGraphQlErrorsFromRawErrors(serviceExecutionResult.getErrors());
        RootExecutionResultNode rootNode = RootExecutionResultNode.newRootExecutionResultNode().errors(errors).elapsedTime(elapsedTimeForServiceCall).build();
        NadelContext nadelContext = executionContext.getContext();

        RootExecutionResultNode rootExecutionResultNode = fetchTopLevelFields(rootNode, executionContext, serviceExecutionResult, elapsedTimeForServiceCall, normalizedQueryFromAst);
        long elapsedTime = System.currentTimeMillis() - startTime;
        log.debug("ServiceResultToResultNodes time: {} ms, executionId: {}", elapsedTime, executionContext.getExecutionId());
        return rootExecutionResultNode;
    }


    private RootExecutionResultNode fetchTopLevelFields(RootExecutionResultNode rootNode,
                                                        ExecutionContext executionContext,
                                                        ServiceExecutionResult serviceExecutionResult,
                                                        ElapsedTime elapsedTime,
                                                        NormalizedQueryFromAst normalizedQueryFromAst) {
        List<NormalizedQueryField> topLevelFields = normalizedQueryFromAst.getTopLevelFields();

        ExecutionPath rootPath = ExecutionPath.rootPath();
        Object source = serviceExecutionResult.getData();

        List<ExecutionResultNode> children = new ArrayList<>(topLevelFields.size());
        for (NormalizedQueryField topLevelField : topLevelFields) {
            ExecutionPath path = rootPath.segment(topLevelField.getResultKey());
            List<String> fieldIds = normalizedQueryFromAst.getFieldIds(topLevelField);

            ExecutionResultNode executionResultNode = fetchAndAnalyzeField(executionContext, source, topLevelField, normalizedQueryFromAst, path, fieldIds, elapsedTime);
            children.add(executionResultNode);
        }
        return (RootExecutionResultNode) rootNode.withNewChildren(children);
    }


    private ExecutionResultNode fetchAndAnalyzeField(ExecutionContext context,
                                                     Object source,
                                                     NormalizedQueryField normalizedQueryField,
                                                     NormalizedQueryFromAst normalizedQueryFromAst,
                                                     ExecutionPath executionPath,
                                                     List<String> fieldIds,
                                                     ElapsedTime elapsedTime) {
        Object fetchedValue = fetchValue(source, normalizedQueryField.getResultKey());
        return analyseValue(context, fetchedValue, normalizedQueryField, normalizedQueryFromAst, executionPath, fieldIds, elapsedTime);
    }

    private Object fetchValue(Object source, String key) {
        if (source == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) source;
        return map.get(key);
    }

    private ExecutionResultNode analyseValue(ExecutionContext executionContext,
                                             Object fetchedValue,
                                             NormalizedQueryField normalizedQueryField,
                                             NormalizedQueryFromAst normalizedQueryFromAst,
                                             ExecutionPath executionPath,
                                             List<String> fieldIds,
                                             ElapsedTime elapsedTime) {
        return analyzeFetchedValueImpl(executionContext, fetchedValue, normalizedQueryField, normalizedQueryFromAst, normalizedQueryField.getFieldDefinition().getType(), executionPath, fieldIds, elapsedTime);
    }

    private ExecutionResultNode analyzeFetchedValueImpl(ExecutionContext executionContext,
                                                        Object toAnalyze,
                                                        NormalizedQueryField normalizedQueryField,
                                                        NormalizedQueryFromAst normalizedQueryFromAst,
                                                        GraphQLOutputType curType,
                                                        ExecutionPath executionPath,
                                                        List<String> fieldIds,
                                                        ElapsedTime elapsedTime) {

        boolean isNonNull = GraphQLTypeUtil.isNonNull(curType);
        if (toAnalyze == null && isNonNull) {
            NonNullableFieldWasNullError nonNullableFieldWasNullError = new NonNullableFieldWasNullError((GraphQLNonNull) curType, executionPath);
            return createNullERNWithNullableError(normalizedQueryField, executionPath, fieldIds, elapsedTime, nonNullableFieldWasNullError);
        } else if (toAnalyze == null) {
            return createNullERN(normalizedQueryField, executionPath, fieldIds, elapsedTime);
        }

        curType = (GraphQLOutputType) GraphQLTypeUtil.unwrapNonNull(curType);
        if (isList(curType)) {
            return analyzeList(executionContext, toAnalyze, (GraphQLList) curType, normalizedQueryField, normalizedQueryFromAst, executionPath, fieldIds, elapsedTime);
        } else if (curType instanceof GraphQLScalarType) {
            return analyzeScalarValue(toAnalyze, (GraphQLScalarType) curType, normalizedQueryField, executionPath, fieldIds, elapsedTime);
        } else if (curType instanceof GraphQLEnumType) {
            return analyzeEnumValue(toAnalyze, (GraphQLEnumType) curType, normalizedQueryField, executionPath, fieldIds, elapsedTime);
        }


        GraphQLObjectType resolvedObjectType = resolveType(executionContext, toAnalyze, curType);
        return resolveObject(executionContext, normalizedQueryField, fieldIds, normalizedQueryFromAst, resolvedObjectType, toAnalyze, executionPath, elapsedTime);
    }

    private ObjectExecutionResultNode resolveObject(ExecutionContext context,
                                                    NormalizedQueryField normalizedField,
                                                    List<String> objectFieldIds,
                                                    NormalizedQueryFromAst normalizedQueryFromAst,
                                                    GraphQLObjectType resolvedType,
                                                    Object completedValue,
                                                    ExecutionPath executionPath,
                                                    ElapsedTime elapsedTime) {

        List<ExecutionResultNode> nodeChildren = new ArrayList<>(normalizedField.getChildren().size());
        for (NormalizedQueryField child : normalizedField.getChildren()) {
            if (child.getObjectType() == resolvedType) {
                ExecutionPath pathForChild = executionPath.segment(child.getResultKey());
                List<String> fieldIds = normalizedQueryFromAst.getFieldIds(child);
                ExecutionResultNode childNode = fetchAndAnalyzeField(context, completedValue, child, normalizedQueryFromAst, pathForChild, fieldIds, elapsedTime);
                nodeChildren.add(childNode);
            }
        }
        return newObjectExecutionResultNode()
                .executionPath(executionPath)
                .alias(normalizedField.getAlias())
                .fieldIds(objectFieldIds)
                .objectType(normalizedField.getObjectType())
                .fieldDefinition(normalizedField.getFieldDefinition())
                .completedValue(completedValue)
                .children(nodeChildren)
                .elapsedTime(elapsedTime)
                .build();

    }


    private ExecutionResultNode analyzeList(ExecutionContext executionContext,
                                            Object toAnalyze,
                                            GraphQLList curType,
                                            NormalizedQueryField normalizedQueryField,
                                            NormalizedQueryFromAst normalizedQueryFromAst,
                                            ExecutionPath executionPath,
                                            List<String> fieldIds,
                                            ElapsedTime elapsedTime) {

        if (toAnalyze instanceof List) {
            return createListImpl(executionContext, toAnalyze, (List<Object>) toAnalyze, curType, normalizedQueryField, normalizedQueryFromAst, executionPath, fieldIds, elapsedTime);
        } else {
            TypeMismatchError error = new TypeMismatchError(executionPath, curType);
            return LeafExecutionResultNode.newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .alias(normalizedQueryField.getAlias())
                    .fieldDefinition(normalizedQueryField.getFieldDefinition())
                    .objectType(normalizedQueryField.getObjectType())
                    .completedValue(null)
                    .fieldIds(fieldIds)
                    .elapsedTime(elapsedTime)
                    .addError(error)
                    .build();
        }
    }

    private LeafExecutionResultNode createNullERNWithNullableError(NormalizedQueryField normalizedQueryField,
                                                                   ExecutionPath executionPath,
                                                                   List<String> fieldIds,
                                                                   ElapsedTime elapsedTime,
                                                                   NonNullableFieldWasNullError nonNullableFieldWasNullError) {
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .alias(normalizedQueryField.getAlias())
                .fieldDefinition(normalizedQueryField.getFieldDefinition())
                .objectType(normalizedQueryField.getObjectType())
                .completedValue(null)
                .fieldIds(fieldIds)
                .elapsedTime(elapsedTime)
                .nonNullableFieldWasNullError(nonNullableFieldWasNullError)
                .build();
    }

    private LeafExecutionResultNode createNullERN(NormalizedQueryField normalizedQueryField,
                                                  ExecutionPath executionPath,
                                                  List<String> fieldIds,
                                                  ElapsedTime elapsedTime) {
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .alias(normalizedQueryField.getAlias())
                .fieldDefinition(normalizedQueryField.getFieldDefinition())
                .objectType(normalizedQueryField.getObjectType())
                .completedValue(null)
                .fieldIds(fieldIds)
                .elapsedTime(elapsedTime)
                .build();
    }

    private ExecutionResultNode createListImpl(ExecutionContext executionContext,
                                               Object fetchedValue,
                                               List<Object> iterableValues,
                                               GraphQLList currentType,
                                               NormalizedQueryField normalizedQueryField,
                                               NormalizedQueryFromAst normalizedQueryFromAst,
                                               ExecutionPath executionPath,
                                               List<String> fieldIds,
                                               ElapsedTime elapsedTime) {
        List<ExecutionResultNode> children = new ArrayList<>();
        int index = 0;
        for (Object item : iterableValues) {
            ExecutionPath indexedPath = executionPath.segment(index);
            children.add(analyzeFetchedValueImpl(executionContext, item, normalizedQueryField, normalizedQueryFromAst, (GraphQLOutputType) GraphQLTypeUtil.unwrapOne(currentType), indexedPath, fieldIds, elapsedTime));
            index++;
        }
        return ListExecutionResultNode.newListExecutionResultNode()
                .executionPath(executionPath)
                .alias(normalizedQueryField.getAlias())
                .fieldDefinition(normalizedQueryField.getFieldDefinition())
                .objectType(normalizedQueryField.getObjectType())
                .completedValue(fetchedValue)
                .fieldIds(fieldIds)
                .elapsedTime(elapsedTime)
                .children(children)
                .build();
    }


    private GraphQLObjectType resolveType(ExecutionContext executionContext, Object source, GraphQLType curType) {
        if (curType instanceof GraphQLObjectType) {
            return (GraphQLObjectType) curType;
        }
        NadelContext nadelContext = executionContext.getContext();
        String underscoreTypeNameAlias = nadelContext.getUnderscoreTypeNameAlias();

        assertTrue(source instanceof Map, () -> "The Nadel result object MUST be a Map");

        Map<String, Object> sourceMap = (Map<String, Object>) source;
        assertTrue(sourceMap.containsKey(underscoreTypeNameAlias), () -> "The Nadel result object for interfaces and unions MUST have an aliased __typename in them");

        Object typeName = sourceMap.get(underscoreTypeNameAlias);
        assertNotNull(typeName, () -> "The Nadel result object for interfaces and unions MUST have an aliased__typename with a non null value in them");

        GraphQLObjectType objectType = executionContext.getGraphQLSchema().getObjectType(typeName.toString());
        assertNotNull(objectType, () -> String.format("There must be an underlying graphql object type called '%s'", typeName));
        return objectType;


    }


    private ExecutionResultNode analyzeScalarValue(Object toAnalyze,
                                                   GraphQLScalarType scalarType,
                                                   NormalizedQueryField normalizedQueryField,
                                                   ExecutionPath executionPath,
                                                   List<String> fieldIds,
                                                   ElapsedTime elapsedTime) {
        Object serialized;
        try {
            serialized = serializeScalarValue(toAnalyze, scalarType);
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(executionPath, e);
            return newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .alias(normalizedQueryField.getAlias())
                    .fieldDefinition(normalizedQueryField.getFieldDefinition())
                    .objectType(normalizedQueryField.getObjectType())
                    .completedValue(null)
                    .fieldIds(fieldIds)
                    .elapsedTime(elapsedTime)
                    .addError(error)
                    .build();
        }

        // TODO: fix that: this should not be handled here
        //6.6.1 http://facebook.github.io/graphql/#sec-Field-entries
        if (serialized instanceof Double && ((Double) serialized).isNaN()) {
            return createNullERN(normalizedQueryField, executionPath, fieldIds, elapsedTime);
        }
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .alias(normalizedQueryField.getAlias())
                .fieldDefinition(normalizedQueryField.getFieldDefinition())
                .objectType(normalizedQueryField.getObjectType())
                .completedValue(serialized)
                .fieldIds(fieldIds)
                .elapsedTime(elapsedTime)
                .build();

    }

    protected Object serializeScalarValue(Object toAnalyze, GraphQLScalarType scalarType) throws CoercingSerializeException {
        if (scalarType == Scalars.GraphQLString) {
            if (toAnalyze instanceof String) {
                return toAnalyze;
            } else {
                throw new CoercingSerializeException("Unexpected value '" + toAnalyze + "'. String expected");
            }
        }
        return scalarType.getCoercing().serialize(toAnalyze);
    }

    private ExecutionResultNode analyzeEnumValue(Object toAnalyze,
                                                 GraphQLEnumType enumType,
                                                 NormalizedQueryField normalizedQueryField,
                                                 ExecutionPath executionPath,
                                                 List<String> fieldIds,
                                                 ElapsedTime elapsedTime) {
        Object serialized;
        try {
            serialized = enumType.serialize(toAnalyze);
        } catch (CoercingSerializeException e) {
            SerializationError error = new SerializationError(executionPath, e);
            return newLeafExecutionResultNode()
                    .executionPath(executionPath)
                    .alias(normalizedQueryField.getAlias())
                    .fieldDefinition(normalizedQueryField.getFieldDefinition())
                    .objectType(normalizedQueryField.getObjectType())
                    .completedValue(null)
                    .fieldIds(fieldIds)
                    .elapsedTime(elapsedTime)
                    .addError(error)
                    .build();
        }
        return newLeafExecutionResultNode()
                .executionPath(executionPath)
                .alias(normalizedQueryField.getAlias())
                .fieldDefinition(normalizedQueryField.getFieldDefinition())
                .objectType(normalizedQueryField.getObjectType())
                .completedValue(serialized)
                .fieldIds(fieldIds)
                .elapsedTime(elapsedTime)
                .build();
    }


}
