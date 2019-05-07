package graphql.nadel.engine;

import graphql.GraphQLError;
import graphql.Internal;
import graphql.execution.Async;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ExecutionStepInfoFactory;
import graphql.execution.MergedField;
import graphql.execution.nextgen.ExecutionStrategy;
import graphql.execution.nextgen.FieldSubSelection;
import graphql.execution.nextgen.result.ExecutionResultNode;
import graphql.execution.nextgen.result.RootExecutionResultNode;
import graphql.language.Argument;
import graphql.language.Field;
import graphql.nadel.FieldInfo;
import graphql.nadel.FieldInfos;
import graphql.nadel.Operation;
import graphql.nadel.Service;
import graphql.nadel.ServiceExecutionHooks;
import graphql.nadel.engine.tracking.FieldTracking;
import graphql.nadel.instrumentation.NadelInstrumentation;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static graphql.Assert.assertNotEmpty;
import static graphql.nadel.engine.ArtificialFieldUtils.removeArtificialFields;
import static graphql.util.FpKit.map;
import static java.lang.String.format;
import static java.util.Collections.singletonList;

@Internal
public class NadelExecutionStrategy {

    private final Logger log = LoggerFactory.getLogger(ExecutionStrategy.class);

    private final ExecutionStepInfoFactory executionStepInfoFactory = new ExecutionStepInfoFactory();
    private final ServiceResultNodesToOverallResult serviceResultNodesToOverallResult = new ServiceResultNodesToOverallResult();
    private final OverallQueryTransformer queryTransformer = new OverallQueryTransformer();

    private final List<Service> services;
    private final FieldInfos fieldInfos;
    private final GraphQLSchema overallSchema;
    private final NadelInstrumentation instrumentation;
    private final ServiceExecutor serviceExecutor;
    private final HydrationInputResolver hydrationInputResolver;
    private final ServiceExecutionHooks serviceExecutionHooks;

    public NadelExecutionStrategy(List<Service> services,
                                  FieldInfos fieldInfos,
                                  GraphQLSchema overallSchema,
                                  NadelInstrumentation instrumentation,
                                  ServiceExecutionHooks serviceExecutionHooks) {
        this.overallSchema = overallSchema;
        this.instrumentation = instrumentation;
        assertNotEmpty(services);
        this.services = services;
        this.fieldInfos = fieldInfos;
        this.serviceExecutionHooks = serviceExecutionHooks;
        this.serviceExecutor = new ServiceExecutor(overallSchema, instrumentation);
        this.hydrationInputResolver = new HydrationInputResolver(services, fieldInfos, overallSchema, instrumentation, serviceExecutor);
    }

    public CompletableFuture<RootExecutionResultNode> execute(ExecutionContext executionContext, FieldSubSelection fieldSubSelection) {
        ExecutionStepInfo rootExecutionStepInfo = fieldSubSelection.getExecutionStepInfo();

        List<ServiceCall> serviceCalls = prepareServiceExecution(executionContext, fieldSubSelection, rootExecutionStepInfo);

        FieldTracking fieldTracking = new FieldTracking(instrumentation, executionContext);

        Operation operation = Operation.fromAst(executionContext.getOperationDefinition().getOperation());

        List<CompletableFuture<RootExecutionResultNode>> resultNodes = new ArrayList<>();
        for (ServiceCall serviceCall : serviceCalls) {
            Service service = serviceCall.service;
            String operationName = buildOperationName(service, executionContext);
            ExecutionStepInfo stepInfo = serviceCall.stepInfo;
            MergedField mergedField = stepInfo.getField();

            //
            // take the original query and transform it into the underlying query needed for that top level field
            //
            QueryTransformationResult queryTransformerResult = queryTransformer.transformMergedFields(executionContext, operationName, operation, singletonList(mergedField));

            //
            // say they are dispatched
            fieldTracking.fieldsDispatched(singletonList(stepInfo));
            //
            // now call put to the service with the new query
            Object serviceContext = serviceCall.serviceContext;
            CompletableFuture<RootExecutionResultNode> serviceResult = serviceExecutor
                    .execute(executionContext, queryTransformerResult, service, operation, serviceContext)
                    .thenApply(rootResultNode -> serviceExecutionHooks.postServiceResult(service, serviceContext, overallSchema, rootResultNode))
                    .thenApply(resultNode -> (RootExecutionResultNode) serviceResultNodesToOverallResult.convert(resultNode, overallSchema, rootExecutionStepInfo, queryTransformerResult.getTransformationByResultField()));

            //
            // and then they are done call back on field tracking that they have completed (modulo hydrated ones).  This is per service call
            serviceResult.whenComplete(fieldTracking::fieldsCompleted);

            resultNodes.add(serviceResult);
        }

        CompletableFuture<RootExecutionResultNode> rootResult = mergeTrees(resultNodes);
        return rootResult
                .thenCompose(
                        //
                        // all the nodes that are hydrated need to make new service calls to get their eventual value
                        //
                        rootExecutionResultNode -> hydrationInputResolver.resolveAllHydrationInputs(executionContext, fieldTracking, rootExecutionResultNode)
                                //
                                .thenApply(resultNode -> removeArtificialFields(getNadelContext(executionContext), resultNode))
                                .thenApply(RootExecutionResultNode.class::cast))
                .whenComplete(this::possiblyLogException);
    }


    @SuppressWarnings("unused")
    private <T> void possiblyLogException(T result, Throwable exception) {
        if (exception != null) {
            exception.printStackTrace();
        }
    }

    private CompletableFuture<RootExecutionResultNode> mergeTrees(List<CompletableFuture<RootExecutionResultNode>> resultNodes) {
        return Async.each(resultNodes).thenApply(rootNodes -> {
            List<ExecutionResultNode> mergedChildren = new ArrayList<>();
            List<GraphQLError> errors = new ArrayList<>();
            map(rootNodes, RootExecutionResultNode::getChildren).forEach(mergedChildren::addAll);
            map(rootNodes, RootExecutionResultNode::getErrors).forEach(errors::addAll);
            return new RootExecutionResultNode(mergedChildren, errors);
        });
    }

    public static class ServiceCall {

        public ServiceCall(Service service, Object serviceContext, ExecutionStepInfo stepInfo) {
            this.service = service;
            this.serviceContext = serviceContext;
            this.stepInfo = stepInfo;
        }

        Service service;
        Object serviceContext;
        ExecutionStepInfo stepInfo;
    }

    private List<ServiceCall> prepareServiceExecution(ExecutionContext context, FieldSubSelection fieldSubSelection, ExecutionStepInfo rootExecutionStepInfo) {
        List<ServiceCall> result = new ArrayList<>();
        for (MergedField mergedField : fieldSubSelection.getMergedSelectionSet().getSubFieldsList()) {
            ExecutionStepInfo newExecutionStepInfo = executionStepInfoFactory.newExecutionStepInfoForSubField(context, mergedField, rootExecutionStepInfo);
            Service service = getServiceForFieldDefinition(newExecutionStepInfo.getFieldDefinition());
            Object serviceContext = serviceExecutionHooks.createServiceContext(service, newExecutionStepInfo);
            List<Argument> newArguments = serviceExecutionHooks.modifyArguments(service, serviceContext, newExecutionStepInfo, mergedField.getArguments());
            if (newArguments != null) {
                newExecutionStepInfo = changeFieldArguments(newExecutionStepInfo, newArguments);
            }
            result.add(new ServiceCall(service, serviceContext, newExecutionStepInfo));
        }
        return result;
    }

    private ExecutionStepInfo changeFieldArguments(ExecutionStepInfo executionStepInfo, List<Argument> newArguments) {
        MergedField mergedField = executionStepInfo.getField();
        List<Field> fields = mergedField.getFields();
        List<Field> newFields = new ArrayList<>();
        for (Field field : fields) {
            newFields.add(field.transform(builder -> builder.arguments(newArguments)));
        }

        MergedField newMergedField = mergedField.transform(builder -> builder.fields(newFields));
        return executionStepInfo.transform(builder -> builder.field(newMergedField));
    }

    private Service getServiceForFieldDefinition(GraphQLFieldDefinition fieldDefinition) {
        FieldInfo info = fieldInfos.getInfo(fieldDefinition);
        return info.getService();
    }

    private String buildOperationName(Service service, ExecutionContext executionContext) {
        // to help with downstream debugging we put our name and their name in the operation
        NadelContext nadelContext = (NadelContext) executionContext.getContext();
        if (nadelContext.getOriginalOperationName() != null) {
            return format("nadel_2_%s_%s", service.getName(), nadelContext.getOriginalOperationName());
        } else {
            return format("nadel_2_%s", service.getName());
        }
    }

    private NadelContext getNadelContext(ExecutionContext executionContext) {
        return (NadelContext) executionContext.getContext();
    }

}


