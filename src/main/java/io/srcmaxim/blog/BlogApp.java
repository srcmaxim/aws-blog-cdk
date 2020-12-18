package io.srcmaxim.blog;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;

public class BlogApp {

    public static void main(final String[] args) {
        var app = new App();

        BlogPipelineStack blogPipelineStack = new BlogPipelineStack(app, "BlogPipelineStack", null);
        BlogApiStack blogApiStack = new BlogApiStack(app, "BlogApiStack", null);
        HttpApi httpApi = blogApiStack.httpApi;
        BlogDeployPipelineStack blogDeployPipelineStack = new BlogDeployPipelineStack(app, "BlogDeployPipelineStack", null, httpApi);

        app.synth();
    }

}
