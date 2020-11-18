package io.srcmaxim.blog;

import software.amazon.awscdk.core.App;

public class BlogApp {

    public static void main(final String[] args) {
        var app = new App();

        new BlogApiStack(app, "BlogApiStack");
        new BlogPipelineStack(app, "BlogPipelineStack", null);

        app.synth();
    }

}
