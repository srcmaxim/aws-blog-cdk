package io.srcmaxim.blog;

import software.amazon.awscdk.core.CfnOutput;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.apigatewayv2.AddRoutesOptions;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.HttpStage;
import software.amazon.awscdk.services.apigatewayv2.PayloadFormatVersion;
import software.amazon.awscdk.services.apigatewayv2.integrations.LambdaProxyIntegration;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.codedeploy.AutoRollbackConfig;
import software.amazon.awscdk.services.codedeploy.LambdaDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.LambdaDeploymentGroup;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.DockerImageCode;
import software.amazon.awscdk.services.lambda.DockerImageFunction;
import software.amazon.awscdk.services.lambda.EcrImageCodeProps;

import java.util.List;
import java.util.Map;

import static software.amazon.awscdk.services.apigatewayv2.HttpMethod.*;

public class BlogApiStack extends Stack {

    HttpApi httpApi;

    public BlogApiStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        //----- HTTP API with Lambda Function and Lambda Deployment Group -----//
        httpApi = HttpApi.Builder.create(this, "BlogHttpGateway")
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

        var lambdaRepository = Repository.fromRepositoryName(this, "LambdaRepositoryArn", "blog-lambda");

        var function = DockerImageFunction.Builder.create(this, "BlogFunction")
                .code(DockerImageCode.fromEcr(lambdaRepository, EcrImageCodeProps.builder().tag("10").build()))
                .timeout(Duration.seconds(15))
                .memorySize(128)
                .build();

        var alias = Alias.Builder.create(this, "BlogAlias")
                .aliasName("Current")
                .version(function.getCurrentVersion())
                .build();

        var lambdaProxyIntegration = LambdaProxyIntegration.Builder.create()
                .handler(alias)
                .payloadFormatVersion(PayloadFormatVersion.VERSION_2_0)
                .build();

        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/health")
                .methods(List.of(GET))
                .integration(lambdaProxyIntegration)
                .build());

        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/meta")
                .methods(List.of(GET))
                .integration(lambdaProxyIntegration)
                .build());

        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/error")
                .methods(List.of(GET))
                .integration(lambdaProxyIntegration)
                .build());

        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/posts")
                .methods(List.of(GET, POST, PUT))
                .integration(lambdaProxyIntegration)
                .build());

        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/posts/{id}")
                .methods(List.of(GET, PUT, DELETE))
                .integration(lambdaProxyIntegration)
                .build());

        var metricOptions = MetricOptions.builder()
                .label("5XX")
                .dimensions(Map.of("ApiId", httpApi.getHttpApiId()))
                .statistic("Sum")
                .period(Duration.minutes(1))
                .build();

        var failureAlarm = Alarm.Builder.create(this, "ApiGateway5XXAlarm")
                .metric(httpApi.metric("5XX", metricOptions))
                .threshold(1)
                .evaluationPeriods(1)
                .build();

        LambdaDeploymentGroup.Builder.create(this, "DeploymentGroup")
                .alias(alias)
                .deploymentConfig(LambdaDeploymentConfig.CANARY_10_PERCENT_10_MINUTES)
                .alarms(List.of(failureAlarm))
                .autoRollback(AutoRollbackConfig.builder().deploymentInAlarm(true).build())
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
