package com.predatorfx.ytdlpweb.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Reachable without any login (the login endpoints themselves, the health check). */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {

    /** Still answers a device or IP the admin has blocked — only the health check. */
    boolean evenWhenBlocked() default false;
}
