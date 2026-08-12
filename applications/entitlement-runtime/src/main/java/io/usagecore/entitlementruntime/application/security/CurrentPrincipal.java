package io.usagecore.entitlementruntime.application.security;

public interface CurrentPrincipal {

    AuthenticatedPrincipal require();
}
