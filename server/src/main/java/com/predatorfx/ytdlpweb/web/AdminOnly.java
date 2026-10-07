package com.predatorfx.ytdlpweb.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Every handler in this controller needs an admin session; the team login is not enough. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminOnly {
}
