package io.srcmaxim.blog;

import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.codebuild.*;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CloudFormationCreateUpdateStackAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubTrigger;
import software.amazon.awscdk.services.codepipeline.actions.S3DeployAction;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.CfnParametersCode;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketAccessControl;
import software.amazon.jsii.JsiiObject;

import java.util.List;

public class BlogPipelineStack extends Stack {

    public BlogPipelineStack(final Construct scope, final String id, final StackProps props, CfnParametersCode lambdaCode) {
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

        IBuildImage quarkusBuildImage = LinuxBuildImage.fromDockerRegistry("quay.io/quarkus/centos-quarkus-maven:20.2.0-java11");
        pipeline.addStage(StageOptions.builder()
                .stageName("LambdaBuild")
                .actions(List.of(
                        CodeBuildAction.Builder.create()
                                .actionName("LambdaBuild")
                                .project(PipelineProject.Builder.create(this, "LambdaBuildProject")
                                        .environment(BuildEnvironment.builder()
                                                .buildImage(quarkusBuildImage)
                                                .computeType(ComputeType.MEDIUM)
                                                .build())
                                        .buildSpec(BuildSpec.fromSourceFilename("buildspec-quarkus.yml"))
                                        .cache(Cache.local(LocalCacheMode.DOCKER_LAYER, LocalCacheMode.CUSTOM))
                                        .build())
                                .input(lambdaSourceOutput)
                                .outputs(List.of(lambdaBuildOutput))
                                .build()
                )).build());

        IRole cdkDeployRole = Role.Builder.create(this, "CdkDeployRole")
                .roleName(PhysicalName.GENERATE_IF_NEEDED)
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("AWSCloudFormationFullAccess")))
                .assumedBy(pipeline.getRole())
                .build();
        pipeline.addStage(StageOptions.builder()
                .stageName("Deploy")
                .actions(List.of(
                        CodeBuildAction.Builder.create()
                                .actionName("CdkDeploy")
                                .project(PipelineProject.Builder.create(this, "CdkBuildProject")
                                        .environment(BuildEnvironment.builder()
                                                .buildImage(LinuxBuildImage.STANDARD_4_0)
                                                .computeType(ComputeType.SMALL)
                                                .build())
                                        .buildSpec(BuildSpec.fromSourceFilename("buildspec.yml"))
                                        .build())
                                .input(cdkSourceOutput)
                                .extraInputs(List.of(lambdaBuildOutput))
                                .outputs(List.of(cdkBuildOutput))
                                .role(cdkDeployRole)
                                .build()
                )).build());
    }

}
