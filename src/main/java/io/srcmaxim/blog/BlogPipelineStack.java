package io.srcmaxim.blog;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.SecretValue;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.BuildSpec;
import software.amazon.awscdk.services.codebuild.ComputeType;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codebuild.PipelineProject;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CloudFormationCreateUpdateStackAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubTrigger;

import java.util.List;

public class BlogPipelineStack extends Stack {

    public BlogPipelineStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public BlogPipelineStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        var lambdaSourceOutput = Artifact.artifact("LAMBDA_SOURCE");
        var cdkSourceOutput = Artifact.artifact("CDK_SOURCE");

        var lambdaBuildOutput = Artifact.artifact("LAMBDA_BUILD");
        var cdkBuildOutput = Artifact.artifact("CDK_BUILD");

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

        pipeline.addStage(StageOptions.builder()
                .stageName("LambdaBuild")
                .actions(List.of(
                        CodeBuildAction.Builder.create()
                                .actionName("LambdaBuild")
                                .project(PipelineProject.Builder.create(this, "LambdaBuildProject")
                                        .environment(BuildEnvironment.builder()
                                                .buildImage(LinuxBuildImage.STANDARD_4_0)
                                                .computeType(ComputeType.MEDIUM)
                                                .build())
                                        .buildSpec(BuildSpec.fromSourceFilename("buildspec-lambda.json"))
                                        .fileSystemLocations(List.of())
                                        .build())
                                .input(lambdaSourceOutput)
                                .outputs(List.of(lambdaBuildOutput))
                                .build()
                )).build());

        pipeline.addStage(StageOptions.builder()
                .stageName("CdkBuild")
                .actions(List.of(
                        CodeBuildAction.Builder.create()
                                .actionName("CdkBuild")
                                .project(PipelineProject.Builder.create(this, "CdkBuildProject")
                                        .environment(BuildEnvironment.builder()
                                                .buildImage(LinuxBuildImage.STANDARD_4_0)
                                                .computeType(ComputeType.SMALL)
                                                .build())
                                        .buildSpec(BuildSpec.fromSourceFilename("buildspec-cdk.json"))
                                        .build())
                                .input(cdkSourceOutput)
                                .extraInputs(List.of(lambdaBuildOutput))
                                .outputs(List.of(cdkBuildOutput))
                                .build()
                )).build());

        pipeline.addStage(StageOptions.builder()
                .stageName("Deploy")
                .actions(List.of(
                        CloudFormationCreateUpdateStackAction.Builder.create()
                                .actionName("BlogApiStackDeploy")
                                .templatePath(cdkBuildOutput.atPath("BlogApiStack.template.json"))
                                .stackName("BlogApiStack")
                                .adminPermissions(true)
                                .extraInputs(List.of(lambdaBuildOutput))
                                .build()
                )).build());
    }

}
