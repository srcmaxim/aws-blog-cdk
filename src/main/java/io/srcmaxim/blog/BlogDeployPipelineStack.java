package io.srcmaxim.blog;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.codedeploy.AutoRollbackConfig;
import software.amazon.awscdk.services.codedeploy.LambdaDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.LambdaDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.EcrSourceAction;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.DockerImageCode;
import software.amazon.awscdk.services.lambda.DockerImageFunction;

import java.util.List;
import java.util.Map;

public class BlogDeployPipelineStack extends Stack {

    public BlogDeployPipelineStack(final Construct scope, final String id, final StackProps props, HttpApi httpApi) {
        super(scope, id, props);

        var lambdaRepository = Repository.fromRepositoryName(this, "LambdaRepository", "blog-lambda");

        var pipeline = Pipeline.Builder.create(this, "BlogDeployPipeline")
                .build();

        pipeline.addStage(StageOptions.builder()
                .stageName("Source")
                .actions(List.of(
                        EcrSourceAction.Builder.create()
                                .actionName("EcrLambdaSource")
                                .imageTag("10")
                                .repository(lambdaRepository)
                                .build())
                ).build());

        var function = DockerImageFunction.Builder.create(this, "BlogFunction")
                .code(DockerImageCode.fromEcr(lambdaRepository))
                .timeout(Duration.seconds(15))
                .memorySize(128)
                .build();

        var alias = Alias.Builder.create(this, "BlogAlias")
                .aliasName("Current")
                .version(function.getCurrentVersion())
                .build();

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
    }

}
