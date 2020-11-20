package io.srcmaxim.blog;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.SecretValue;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.codebuild.*;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubTrigger;
import software.amazon.awscdk.services.iam.PolicyStatement;

import java.util.List;

public class BlogPipelineStack extends Stack {

    public BlogPipelineStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        var lambdaSourceOutput = Artifact.artifact("LAMBDA_SOURCE");
        var cdkSourceOutput = Artifact.artifact("CDK_SOURCE");

        var lambdaBuildOutput = Artifact.artifact("LAMBDA_BUILD");

        var pipeline = Pipeline.Builder.create(this, "BlogPipeline")
                .build();

        pipeline.addStage(StageOptions.builder()
                .stageName("Source")
                .actions(List.of(
                        GitHubSourceAction.Builder.create()
                                .actionName("GitHubLambdaSource")
                                .output(lambdaSourceOutput)
                                .oauthToken(SecretValue.secretsManager("GITHUB_TOKEN"))
                                .owner("srcmaxim")
                                .repo("aws-blog-lambda")
                                .branch("master")
                                .trigger(GitHubTrigger.POLL)
                                .build(),
                        GitHubSourceAction.Builder.create()
                                .actionName("GitHubCdkSource")
                                .output(cdkSourceOutput)
                                .oauthToken(SecretValue.secretsManager("GITHUB_TOKEN"))
                                .owner("srcmaxim")
                                .repo("aws-blog-cdk")
                                .branch("master")
                                .trigger(GitHubTrigger.POLL)
                                .build()
                )).build());

        var quarkusBuildImage = LinuxBuildImage.fromDockerRegistry("quay.io/quarkus/centos-quarkus-maven:20.2.0-java11");
        var lambdaBuildProject = PipelineProject.Builder.create(this, "LambdaBuildProject")
                .environment(BuildEnvironment.builder()
                        .buildImage(quarkusBuildImage)
                        .computeType(ComputeType.MEDIUM)
                        .build())
                .buildSpec(BuildSpec.fromSourceFilename("buildspec-quarkus.yml"))
                .cache(Cache.local(LocalCacheMode.DOCKER_LAYER, LocalCacheMode.CUSTOM))
                .build();
        pipeline.addStage(StageOptions.builder()
                .stageName("LambdaBuild")
                .actions(List.of(
                        CodeBuildAction.Builder.create()
                                .actionName("LambdaBuild")
                                .project(lambdaBuildProject)
                                .input(lambdaSourceOutput)
                                .outputs(List.of(lambdaBuildOutput))
                                .build()
                )).build());

        var cdkDeployProject = PipelineProject.Builder.create(this, "CdkDeployProject")
                .environment(BuildEnvironment.builder()
                        .buildImage(LinuxBuildImage.STANDARD_4_0)
                        .computeType(ComputeType.SMALL)
                        .build())
                .buildSpec(BuildSpec.fromSourceFilename("buildspec.yml"))
                .build();

        var policyAllowAll = PolicyStatement.Builder.create()
                .actions(List.of(
                        "s3:*",
                        "dynamodb:*",
                        "cloudformation:*",
                        "codedeploy:*",
                        "lambda:*",
                        "iam:*"
                ))
                .resources(List.of("*"))
                .build();

        cdkDeployProject.addToRolePolicy(policyAllowAll);

        pipeline.addStage(StageOptions.builder()
                .stageName("Deploy")
                .actions(List.of(
                        CodeBuildAction.Builder.create()
                                .actionName("CdkDeploy")
                                .project(cdkDeployProject)
                                .input(cdkSourceOutput)
                                .extraInputs(List.of(lambdaBuildOutput))
                                .build()
                )).build());
    }

}
