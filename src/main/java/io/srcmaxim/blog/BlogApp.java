package io.srcmaxim.blog;

import software.amazon.awscdk.core.App;

public class BlogApp {

    public static void main(final String[] args) {
        var app = new App();

        var blogApiStack = new BlogApiStack(app, "BlogApiStack");
        var lambdaCode = blogApiStack.getLambdaCode();
        new BlogPipelineStack(app, "BlogPipelineStack", null, lambdaCode);

        app.synth();
    }

}
