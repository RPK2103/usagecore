package io.usagecore.usagepipeline.application.security;

public interface CurrentPrincipal {

    AuthenticatedPrincipal require();
}
