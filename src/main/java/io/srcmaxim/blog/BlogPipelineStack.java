package io.srcmaxim.blog;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.SecretValue;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.BuildSpec;
import software.amazon.awscdk.services.codebuild.Cache;
import software.amazon.awscdk.services.codebuild.ComputeType;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codebuild.LocalCacheMode;
import software.amazon.awscdk.services.codebuild.PipelineProject;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubTrigger;
import software.amazon.awscdk.services.ecr.Repository;

import java.util.List;

public class BlogPipelineStack extends Stack {

    public BlogPipelineStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        var lambdaSourceOutput = Artifact.artifact("LAMBDA_SOURCE");

        var lambdaRepository = Repository.fromRepositoryName(this, "LambdaRepository", "blog-lambda");

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
                                .build()
                )).build());

        var lambdaBuildProject = PipelineProject.Builder.create(this, "LambdaBuildProject")
                .environment(BuildEnvironment.builder()
                        .buildImage(LinuxBuildImage.STANDARD_4_0)
                        .computeType(ComputeType.MEDIUM)
                        .privileged(true)
                        .build())
                .buildSpec(BuildSpec.fromSourceFilename("buildspec.yml"))
                .cache(Cache.local(LocalCacheMode.DOCKER_LAYER))
                .build();

        lambdaRepository.grantPullPush(lambdaBuildProject);

        pipeline.addStage(StageOptions.builder()
                .stageName("LambdaBuild")
                .actions(List.of(
                        CodeBuildAction.Builder.create()
                                .actionName("LambdaBuild")
                                .project(lambdaBuildProject)
                                .input(lambdaSourceOutput)
                                .build()
                )).build());
    }

}
