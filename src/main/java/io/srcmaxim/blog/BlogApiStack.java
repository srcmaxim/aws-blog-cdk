package io.srcmaxim.blog;

import software.amazon.awscdk.core.CfnOutput;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.apigatewayv2.AddRoutesOptions;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.HttpStage;
import software.amazon.awscdk.services.apigatewayv2.LambdaProxyIntegration;
import software.amazon.awscdk.services.apigatewayv2.PayloadFormatVersion;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.codedeploy.LambdaDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.LambdaDeploymentGroup;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.ProjectionType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.CfnParametersCode;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;

import java.util.List;
import java.util.Map;

import static software.amazon.awscdk.services.apigatewayv2.HttpMethod.DELETE;
import static software.amazon.awscdk.services.apigatewayv2.HttpMethod.GET;
import static software.amazon.awscdk.services.apigatewayv2.HttpMethod.POST;
import static software.amazon.awscdk.services.apigatewayv2.HttpMethod.PUT;

public class BlogApiStack extends Stack {

    public BlogApiStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public BlogApiStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        //----- HTTP API with Lambda Function and Lambda Deployment Group -----//
        var httpApi = HttpApi.Builder.create(this, "BlogHttpGateway")
                .apiName("BlogHttpApi")
                .build();

        var devStage = HttpStage.Builder.create(this, "DevStage")
                .autoDeploy(true)
                .httpApi(httpApi)
                .stageName("dev")
                .build();

        var prodStage = HttpStage.Builder.create(this, "ProdStage")
                .httpApi(httpApi)
                .stageName("prod")
                .build();

        var function = Function.Builder.create(this, "BlogFunction")
                .runtime(Runtime.PROVIDED)
                .code(Code.fromAsset("function.zip"))
                .handler("not.used.in.provided.runtime")
                .environment(Map.of(
                        "DISABLE_SIGNAL_HANDLERS", "true"
                ))
                .timeout(Duration.seconds(15))
                .memorySize(128)
                .build();

        var alias = Alias.Builder.create(this, "BlogAlias")
                .aliasName("Current")
                .version(function.getCurrentVersion())
                .build();

        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/posts")
                .methods(List.of(GET, POST, PUT))
                .integration(LambdaProxyIntegration.Builder.create()
                        .handler(alias)
                        .payloadFormatVersion(PayloadFormatVersion.VERSION_2_0)
                        .build())
                .build());

        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/posts/{id}")
                .methods(List.of(GET, PUT, DELETE))
                .integration(LambdaProxyIntegration.Builder.create()
                        .handler(alias)
                        .payloadFormatVersion(PayloadFormatVersion.VERSION_2_0)
                        .build())
                .build());

        var failureAlarm = Alarm.Builder.create(this, "Alarm5XXError")
                .metric(alias.metric("5XXError", MetricOptions.builder()
                        .dimensions(Map.of("ApiName",  httpApi.getHttpApiName()))
                        .statistic("Sum")
                        .period(Duration.minutes(1))
                        .build()))
                .threshold(1)
                .evaluationPeriods(1)
                .build();

        LambdaDeploymentGroup.Builder.create(this, "DeploymentGroup")
                .alias(alias)
                .deploymentConfig(LambdaDeploymentConfig.CANARY_10_PERCENT_10_MINUTES)
                .alarms(List.of(failureAlarm))
                .build();

        //----- DynamoDB Table -----//
        var pk = Attribute.builder().name("PK").type(AttributeType.STRING).build();
        var sk = Attribute.builder().name("SK").type(AttributeType.STRING).build();

        var table = Table.Builder
                .create(this, "BlogTable")
                .tableName("Blog")
                .partitionKey(pk)
                .sortKey(sk)
                .readCapacity(1)
                .writeCapacity(1)
                .build();

        var gsi1Pk = Attribute.builder().name("GSI1PK").type(AttributeType.STRING).build();
        var gsi1Sk = Attribute.builder().name("GSI1SK").type(AttributeType.STRING).build();

        table.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("GSI1")
                .partitionKey(gsi1Pk)
                .sortKey(gsi1Sk)
                .projectionType(ProjectionType.ALL)
                .readCapacity(1)
                .writeCapacity(1)
                .build());

        table.grantReadWriteData(function);

        //----- CloudFormation Outputs -----//
        CfnOutput.Builder.create(this, "BlogDynamoDbArn")
                .exportName("BlogDynamoDbArn")
                .value(table.getTableArn())
                .build();

        CfnOutput.Builder.create(this, "BlogHttpGatewayUrl")
                .exportName("BlogHttpGatewayUrl")
                .value(httpApi.getUrl())
                .build();
    }

}
